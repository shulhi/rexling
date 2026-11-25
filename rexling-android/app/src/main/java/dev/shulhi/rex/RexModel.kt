package dev.shulhi.rex

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

internal val TerminalFontSizes=8f..20f
internal const val TerminalFontSizeStep=.5f

class RexModel @JvmOverloads constructor(application: Application, preferenceName: String="rex") : AndroidViewModel(application) {
    internal var activityVisible by mutableStateOf(false)
        private set
    internal var activitySources by mutableStateOf<List<ActivitySource>>(emptyList())
        private set
    internal fun showActivity(open: Boolean) {
        if(open) activeClient.cancelKeyRepeats()
        activityVisible=open;connections.values.forEach {it.activity(open)};updateActivity()
    }
    internal fun refreshActivity() {connections.values.forEach {it.activity(activityVisible)}}
    internal var attentionCount by mutableIntStateOf(0)
        private set
    internal var networkAvailable by mutableStateOf<Boolean?>(null)
        private set
    private var visibleActivityPanes: WatchedPanes?=null
    internal fun setVisibleActivityPanes(panes: WatchedPanes?) {
        if(visibleActivityPanes==panes) return
        visibleActivityPanes=panes
        updateActivity()
    }
    private fun updateActivity() {
        val visible=visibleActivityPanes
        val active=connections[activeServerId]
        if(visible!=null && active!=null) {
            val app=getApplication<Application>()
            val state=active.state.value
            val profile=activeServer
            val watched=ActivityFocusPolicy.watched(profile,state,visible,foreground && windowFocused && !activityVisible,active.navigationRequest.pending,
                app.getSystemService(android.os.PowerManager::class.java).isInteractive,app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            ActivityFocusPolicy.acknowledge(profile,state.activity,watched,active::isActivitySeen).takeIf {it.isNotEmpty()}?.let(active::markActivitySeen)
        }
        attentionCount=connections.values.sumOf {client->if(client.state.value.connected) ActivityFocusPolicy.unread(client.state.value.activity,client::isActivitySeen) else 0}
        activitySources=servers.mapNotNull {server->connections[server.id]?.state?.value?.let {state->
            if(state.phase in listOf("disconnected","preview")) null else ActivitySource(server,state.connected,state.activityLoading,
                if(state.connected) state.activityError else "Connection interrupted",state.activityPartial,state.activity,state.activity.filter {connections[server.id]?.isActivitySeen(it)==true}.map {it.key}.toSet())
        }}
    }
    internal fun markActivitySeen(target: ActivityTarget) {
        val client=connections[target.server.id] ?: return
        if(client.savedEndpoint!=target.server.endpoint) return
        val current=client.state.value.activity.firstOrNull {it.key==target.record.key && it.attentionVersion==target.record.attentionVersion} ?: return
        client.markActivitySeen(listOf(current));updateActivity()
    }
    internal fun markAllActivitySeen() {
        activitySources.filter {it.connected}.forEach {source->connections[source.server.id]?.markActivitySeen(source.items)};updateActivity()
    }
    internal fun openActivity(target: ActivityTarget) {
        val profile=servers.firstOrNull {it.id==target.server.id && it.endpoint==target.server.endpoint} ?: return
        val source=activitySources.firstOrNull {it.server.id==profile.id && it.connected} ?: return
        val record=source.items.firstOrNull {it.key==target.record.key} ?: return
        markActivitySeen(target);rememberLastSession();connectServer(profile.id);activeClient.openPane(record.session,record.block);showActivity(false)
    }
    private val preferences=application.getSharedPreferences(preferenceName,0)
    private var serverSwitchSerial=0L
    internal var pendingImageTarget: ImageTarget?=null
    internal val images=ImageUploads(application,viewModelScope)
    internal val downloads=FileDownloads(application,viewModelScope)
    internal fun imageTarget(): ImageTarget? {
        val client=activeClient;val state=client.state.value
        if(!foreground || !state.connected || !state.controlled || state.creating || client.navigationRequest.pending || state.selectedBlock.isBlank()) return null
        val program=state.programs[state.selectedBlock] ?: ProgramIdentity.Terminal
        return ImageTarget(activeServerId,activeServer.endpoint,state.selectedSession,state.selectedBlock,client.selectionVersion,serverSwitchSerial,program.kind,"${program.title} · ${activeServer.label}")
    }
    internal fun attachImage(uri: android.net.Uri,target: ImageTarget?,onCopied: ()->Unit={}): Boolean {
        if(target==null) {onCopied();images.notice="Take control of a pane before attaching a file.";return false}
        val profile=servers.firstOrNull {it.id==target.server && it.endpoint==target.endpoint}
        if(profile==null) {onCopied();images.notice="The original Rex server is no longer available.";return false}
        return images.start(uri,target,onCopied)
    }
    internal var experimentalCodexAttention by mutableStateOf(preferences.getBoolean(CodexAttentionPolicy.ENABLED,false))
        private set
    internal fun changeCodexAttention(enabled: Boolean) {
        experimentalCodexAttention=enabled;preferences.edit {putBoolean(CodexAttentionPolicy.ENABLED,enabled)}
        connections.values.forEach {it.refreshActivityPolicy()};updateActivity()
    }
    internal var backgroundAlerts by mutableStateOf(preferences.getBoolean(BackgroundAlerts.ENABLED,false))
        private set
    internal var notificationNotice by mutableStateOf("")
    private var pendingAlert: AlertDestination?=null
    private val alertsListener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==BackgroundAlerts.ENABLED) {
        viewModelScope.launch {backgroundAlerts=preferences.getBoolean(BackgroundAlerts.ENABLED,false)}
    }}
    internal fun setBackgroundAlerts(enabled: Boolean) {
        backgroundAlerts=enabled;preferences.edit {putBoolean(BackgroundAlerts.ENABLED,enabled)}
        BackgroundAlerts.reconcile(getApplication())
    }
    internal fun openAlert(target: AlertDestination) {
        val profile=servers.firstOrNull {it.id==target.serverId && it.endpoint==target.endpoint}
        if(profile==null) {notificationNotice="That server is no longer saved.";return}
        pendingAlert=target;connectServer(profile.id);tryOpenAlert()
    }
    private fun tryOpenAlert() {
        val target=pendingAlert ?: return
        val client=connections[target.serverId] ?: return
        val state=client.state.value
        if(!state.connected || !foreground) return
        pendingAlert=null
        if(state.sessions.none {it.id==target.session}) {notificationNotice="That session is no longer available.";return}
        client.markAttentionSeen(target.attentionKey,target.attentionVersion)
        client.openPane(target.session,target.block);showActivity(false)
    }
    internal val themes by lazy {ThemeCatalog.load(application)}
    private var darkTheme by mutableStateOf(preferences.getString("dark_theme","rexlink-default-dark").orEmpty())
    private var lightTheme by mutableStateOf(preferences.getString("light_theme","rexlink-default-light").orEmpty())
    internal var keepScreenAwake by mutableStateOf(runCatching {KeepScreenAwake.valueOf(preferences.getString("keep_screen_awake","SYSTEM")!!)}.getOrDefault(KeepScreenAwake.SYSTEM))
        private set
    internal fun changeKeepScreenAwake(value: KeepScreenAwake) {keepScreenAwake=value;preferences.edit {putString("keep_screen_awake",value.name)}}
    internal var immersiveStatusBar by mutableStateOf(preferences.getBoolean("immersive_status_bar",false))
        private set
    internal fun changeImmersiveStatusBar(value: Boolean) {
        immersiveStatusBar=value
        preferences.edit {putBoolean("immersive_status_bar",value)}
    }
    internal var fontSizes by mutableStateOf(TabWidthClass.entries.associateWith {profile->
        val key=fontSizeKey(profile)
        val previous=preferences.getInt(key,preferences.getInt("font_size",10))
        // SharedPreferences stores integers and floats as different types.
        preferences.getFloat("${key}_sp",previous.toFloat()).coerceIn(TerminalFontSizes)
    })
        private set
    private fun fontSizeKey(profile: TabWidthClass)="font_size_${profile.name.lowercase()}"

    internal fun changeFontSize(profile: TabWidthClass,size: Float) {
        val clamped=size.coerceIn(TerminalFontSizes)
        if(fontSizes[profile]==clamped) return
        fontSizes=fontSizes+(profile to clamped)
        preferences.edit {putFloat("${fontSizeKey(profile)}_sp",clamped)}
    }

    internal fun theme(dark: Boolean): AppTheme {
        val id=if(dark) darkTheme else lightTheme
        return ThemeCatalog.defaults.firstOrNull {it.id==id && it.terminal.dark==dark} ?: ThemeCatalog.resolve(themes,dark,id)
    }
    internal fun chooseTheme(id: String) {
        val theme=themes.firstOrNull {it.id==id} ?: return
        if(theme.terminal.dark) darkTheme=id else lightTheme=id
        preferences.edit {putString(if(theme.terminal.dark) "dark_theme" else "light_theme",id)}
    }
    var appearance by mutableStateOf(runCatching {Appearance.valueOf(preferences.getString("appearance","SYSTEM")!!)}.getOrDefault(Appearance.SYSTEM))
        private set
    internal var minimizeFloatingPanes by mutableStateOf(preferences.getBoolean("minimize_floating_panes",false))
        private set
    internal fun changeMinimizeFloatingPanes(value: Boolean) {
        minimizeFloatingPanes=value
        preferences.edit {putBoolean("minimize_floating_panes",value)}
    }
    internal var floatingDockPosition by mutableStateOf(androidx.compose.ui.geometry.Offset(
        preferences.getFloat("floating_dock_x",1f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 1f,
        preferences.getFloat("floating_dock_y",1f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 1f))
        private set
    internal fun moveFloatingDock(value: androidx.compose.ui.geometry.Offset) {
        floatingDockPosition=value
        preferences.edit {putFloat("floating_dock_x",value.x);putFloat("floating_dock_y",value.y)}
    }
    internal var controlItems by mutableStateOf(ControlShortcutConfig.restore(preferences.getString("control_shortcut_items",null)))
        private set
    internal fun saveControlShortcuts(text: String): String? = runCatching {
        val items=ControlShortcutConfig.parse(text)
        controlItems=items;preferences.edit {putString("control_shortcut_items",ControlShortcutConfig.editorText(items))}
    }.exceptionOrNull()?.message
    internal var toolbarKeys by mutableStateOf(ToolbarLayout.decode(preferences.getString("keyboard_toolbar",null)))
        private set
    internal fun changeToolbar(keys: List<ToolbarKey>) {
        toolbarKeys=ToolbarLayout.normalize(keys)
        preferences.edit {putString("keyboard_toolbar",ToolbarLayout.encode(toolbarKeys))}
    }
    internal var arrowJoystickEnabled by mutableStateOf(preferences.getBoolean("arrow_joystick_enabled",true))
        private set
    internal fun changeArrowJoystick(value: Boolean) {arrowJoystickEnabled=value;preferences.edit {putBoolean("arrow_joystick_enabled",value)}}
    // Earlier builds on this branch saved layouts by fold posture; their choices seed the width classes
    // they correspond to (folded is medium, unfolded landscape is wide).
    internal val tabLayouts=androidx.compose.runtime.mutableStateMapOf<TabWidthClass,TabBarLayout>().apply {
        val earlier=mapOf(TabWidthClass.MEDIUM to "tab_layout_folded",TabWidthClass.WIDE to "tab_layout_unfolded_landscape")
        TabWidthClass.entries.forEach {width->
            val fallback=TabBarLayout.decode(earlier[width]?.let {preferences.getString(it,null)},
                if(width==TabWidthClass.WIDE) TabBarLayout(style=TabRailStyle.TITLES) else TabBarLayout())
            put(width,TabBarLayout.decode(preferences.getString(width.key,null),fallback))
        }
    }
    internal fun tabLayout(width: TabWidthClass)=tabLayouts[width] ?: TabBarLayout()
    internal fun changeTabLayout(width: TabWidthClass,layout: TabBarLayout) {
        tabLayouts[width]=layout
        preferences.edit {putString(width.key,layout.encode())}
    }
    /** Full-screen large layouts inherit the previous choice until configured per orientation. */
    internal var paneLayouts by mutableStateOf(PaneOrientation.entries.associateWith {orientation->
        preferences.getBoolean(orientation.preferenceKey,preferences.getBoolean("multi_pane_unfolded",false))
    })
        private set
    internal fun changePaneLayout(orientation: PaneOrientation,multiple: Boolean) {
        paneLayouts=paneLayouts+(orientation to multiple)
        preferences.edit {putBoolean(orientation.preferenceKey,multiple)}
    }
    internal var toolbarWithKeyboardOnly by mutableStateOf(preferences.getBoolean("keyboard_toolbar_auto_hide",false))
        private set
    internal fun changeToolbarWithKeyboardOnly(value: Boolean) {toolbarWithKeyboardOnly=value;preferences.edit {putBoolean("keyboard_toolbar_auto_hide",value)}}
    internal var toolbarSystemColors by mutableStateOf(preferences.getBoolean("keyboard_toolbar_system_colors",false))
        private set
    internal fun changeToolbarSystemColors(value: Boolean) {toolbarSystemColors=value;preferences.edit {putBoolean("keyboard_toolbar_system_colors",value)}}
    internal var compactHeaderMode by mutableStateOf(
        preferences.getString("compact_header_mode",null)?.let {saved->CompactHeaderMode.entries.firstOrNull {it.name==saved}}
            ?: if(preferences.getBoolean("compact_header_with_keyboard",false)) CompactHeaderMode.WITH_KEYBOARD else CompactHeaderMode.NEVER)
        private set
    internal fun changeCompactHeaderMode(value: CompactHeaderMode) {
        compactHeaderMode=value
        preferences.edit {putString("compact_header_mode",value.name)}
    }
    internal var enterSubmits by mutableStateOf(preferences.getBoolean("keyboard_enter_submits",false))
        private set
    internal fun changeEnterSubmits(value: Boolean) {enterSubmits=value;preferences.edit {putBoolean("keyboard_enter_submits",value)}}
    internal var shortcutGesture by mutableStateOf(runCatching {ControlShortcutGesture.valueOf(preferences.getString("ctrl_shortcut_gesture","HOLD")!!)}.getOrDefault(ControlShortcutGesture.HOLD))
        private set
    internal fun changeShortcutGesture(value: ControlShortcutGesture) {shortcutGesture=value;preferences.edit {putString("ctrl_shortcut_gesture",value.name)}}
    internal var neovimShortcutLayout by mutableStateOf(runCatching {NeovimShortcutLayout.valueOf(preferences.getString("neovim_shortcut_layout","CIRCULAR")!!)}.getOrDefault(NeovimShortcutLayout.CIRCULAR))
        private set
    internal fun changeNeovimShortcutLayout(value: NeovimShortcutLayout) {neovimShortcutLayout=value;preferences.edit {putString("neovim_shortcut_layout",value.name)}}
    internal var neovimShortcutEffect by mutableStateOf(NeovimShortcutEffect.fromPreference(preferences.getString("neovim_shortcut_effect","FAN_OUT")))
        private set
    internal fun changeNeovimShortcutEffect(value: NeovimShortcutEffect) {neovimShortcutEffect=value;preferences.edit {putString("neovim_shortcut_effect",value.name)}}
    internal var neovimShortcutHaptics by mutableStateOf(preferences.getBoolean("neovim_shortcut_haptics",true))
        private set
    internal fun changeNeovimShortcutHaptics(value: Boolean) {neovimShortcutHaptics=value;preferences.edit {putBoolean("neovim_shortcut_haptics",value)}}
    internal var neovimShortcuts by mutableStateOf(NeovimShortcuts.decode(preferences.getString("neovim_shortcuts",null)))
        private set
    internal var neovimShortcutsEnabled by mutableStateOf(preferences.getBoolean("neovim_shortcuts_enabled",true))
        private set
    internal fun enableNeovimShortcuts(value: Boolean) {neovimShortcutsEnabled=value;preferences.edit {putBoolean("neovim_shortcuts_enabled",value)}}
    internal var neovimShortcutPosition by mutableStateOf(androidx.compose.ui.geometry.Offset(
        preferences.getFloat("neovim_shortcut_x",0f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 0f,
        preferences.getFloat("neovim_shortcut_y",1f).takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 1f))
        private set
    internal fun moveNeovimShortcut(value: androidx.compose.ui.geometry.Offset) {
        if(!value.x.isFinite() || !value.y.isFinite()) return
        neovimShortcutPosition=androidx.compose.ui.geometry.Offset(value.x.coerceIn(0f,1f),value.y.coerceIn(0f,1f))
        preferences.edit {putFloat("neovim_shortcut_x",neovimShortcutPosition.x);putFloat("neovim_shortcut_y",neovimShortcutPosition.y)}
    }
    internal fun saveNeovimShortcuts(text: String): String? = runCatching {
        val items=NeovimShortcuts.parse(text)
        neovimShortcuts=items;preferences.edit {putString("neovim_shortcuts",NeovimShortcuts.encode(items))}
    }.exceptionOrNull()?.message
    var servers by mutableStateOf(SavedServers.decode(preferences.getString("servers",null),preferences.getString("endpoint","").orEmpty()))
        private set
    var activeServerId by mutableStateOf(preferences.getString("active_server",null)?.takeIf {id->servers.any {it.id==id}} ?: servers.firstOrNull()?.id.orEmpty())
        private set
    var serverStatuses by mutableStateOf<Map<String,ServerStatus>>(emptyMap())
        private set
    internal var sessionTargets by mutableStateOf<List<SessionTarget>>(emptyList())
        private set
    internal var paneCatalogSources by mutableStateOf<List<PaneCatalogSource>>(emptyList())
        private set
    private var paneCatalogVisible=false
    internal var lastSessionKey by mutableStateOf(preferences.getString("last_global_session","").orEmpty())
        private set
    var closingSessionKey by mutableStateOf("")
        private set
    var sessionActionError by mutableStateOf("")
        private set
    internal var renamingSessionKey by mutableStateOf("")
        private set
    private var renameServerId=""
    private var renameSessionId=""
    private var renameLabel=""
    private var closingServerId=""
    private var closingSessionId=""
    private val connections=mutableMapOf<String,RexConnection>()
    private val observers=mutableMapOf<String,Job>()
    private var desired by mutableStateOf(preferences.getStringSet("connected_servers",emptySet()).orEmpty().filter {id->servers.any {it.id==id}}.toSet())
    private var palette: TerminalPalette?=null
    private var windowFocused=false
    internal fun windowFocusChanged(focused: Boolean) {windowFocused=focused;updateActivity()}
    private var foreground=true
    private var networkJob: Job?=null
    private val networkMonitor=NetworkMonitor(application) {change->viewModelScope.launch {
        val wasOffline=networkAvailable==false;networkAvailable=change.available
        if(!change.available || change.retry || wasOffline) {
            networkJob?.cancel();networkJob=viewModelScope.launch {
                if(change.available) delay(300)
                if(foreground) connections.filterKeys {it in desired}.values.forEach {it.networkChanged(change)}
            }
        }
    }}
    internal var activeClient by mutableStateOf(connection(activeServer))
        private set
    val activeServer: ServerProfile get()=servers.firstOrNull {it.id==activeServerId} ?: ServerProfile.Empty
    internal val activeConnectionRequested: Boolean get()=activeServerId in desired
    val connectedCount: Int get()=serverStatuses.values.count {it.connected}

    init {viewModelScope.launch {NetworkSettings.revision.drop(1).collect {connections.filterKeys {it in desired}.values.forEach {it.retryNow()}}};preferences.registerOnSharedPreferenceChangeListener(alertsListener);desired.toList().forEach {id->connectServer(id,select=false)}}

    private fun connection(profile: ServerProfile): RexConnection = connections.getOrPut(profile.id) {
        RexConnection(getApplication(),viewModelScope,profile.endpoint,preferences).also {client->
            client.visible=profile.id==activeServerId
            if(activityVisible) client.activity(true)
            palette?.let(client::updateTerminalTheme)
            if(!foreground) client.pause()
            observers[profile.id]=viewModelScope.launch {
                client.chromeState.collect {state->
                    val wasConnected=serverStatuses[profile.id]?.connected==true
                    val next=ServerStatus(state.phase,state.error)
                    if(serverStatuses[profile.id]!=next) serverStatuses=serverStatuses+(profile.id to next)

                    updateSessionTargets()
                    if(paneCatalogVisible) {
                        if(state.connected && !wasConnected) client.requestPaneCatalog(true)
                        updatePaneCatalog()
                    }
                    if(profile.id==pendingAlert?.serverId) tryOpenAlert()
                    updateActivity()
                    if(renamingSessionKey.isNotBlank() && profile.id==renameServerId) {
                        if(!state.creating && state.sessions.any {it.id==renameSessionId && it.label==renameLabel}) renamingSessionKey=""
                        else if(state.error.isNotBlank() || !state.connected || state.sessions.none {it.id==renameSessionId}) {
                            renamingSessionKey="";sessionActionError=state.error.ifBlank {if(state.connected) "That session is no longer available" else "Connection interrupted. Check the session name before retrying."}
                        }
                    }
                    if(closingSessionKey.isNotBlank() && profile.id==closingServerId) {
                        if(state.sessions.none {it.id==closingSessionId}) closingSessionKey=""
                        else if(state.error.isNotBlank() || !state.connected) {
                            sessionActionError=state.error.ifBlank {"Connection interrupted. Refresh the session list before trying again."}
                            closingSessionKey=""
                        }
                    }
                }
            }
        }
    }
    private fun updateSessionTargets() {
        val next=SessionCatalog.collect(servers,connections.mapValues {it.value.state.value})
        if(next!=sessionTargets) sessionTargets=next
    }
    private fun updatePaneCatalog() {
        paneCatalogSources=servers.mapNotNull {server->
            val state=connections[server.id]?.chromeState?.value ?: return@mapNotNull null
            if(state.connected) PaneCatalog.collect(server,state) else null
        }
    }
    internal fun showPaneCatalog(visible: Boolean) {
        if(paneCatalogVisible==visible) return
        paneCatalogVisible=visible
        if(visible) {
            updatePaneCatalog()
            refreshPaneCatalog()
        } else {
            connections.values.forEach {it.requestPaneCatalog(false)}
            paneCatalogSources=emptyList()
        }
    }
    internal fun refreshPaneCatalog() {
        connections.values.filter {it.chromeState.value.connected}.forEach {it.requestPaneCatalog(true)}
    }
    internal fun openCatalogPane(target: PaneCatalogTarget): Boolean {
        val client=connections[target.server.id] ?: return false
        val state=client.chromeState.value
        if(!state.connected || state.creating || client.savedEndpoint!=target.server.endpoint) return false
        if(paneCatalogSources.none {source->source.server==target.server && source.panes.any {it.key==target.key}}) return false
        val current=client.navigationRequest.session ?: state.selectedSession
        if(activeServerId==target.server.id && current!=target.session.id) rememberLastSession()
        connectServer(target.server.id)
        activeClient.openPane(target.session.id,target.pane.block)
        return true
    }
    private fun rememberLastSession() {
        val session=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
        if(session.isNotBlank()) {
            lastSessionKey=sessionKey(activeServerId,session)
            preferences.edit {putString("last_global_session",lastSessionKey)}
        }
    }
    internal fun selectSessionTarget(key: String) {
        val target=sessionTargets.firstOrNull {it.key==key} ?: return
        if(activeServerId==target.server.id) {
            val current=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
            if(current==target.session.id) return
            rememberLastSession()
        }
        connectServer(target.server.id)
        activeClient.selectSession(target.session.id)
    }
    internal fun renameSession(target: SessionTarget,label: String): Boolean {
        if(closingSessionKey.isNotBlank() || renamingSessionKey.isNotBlank()) return false
        sessionActionError=""
        sessionNameError(label)?.let {sessionActionError=it;return false}
        val current=sessionTargets.firstOrNull {it.key==target.key && it.server.endpoint==target.server.endpoint}
        if(current==null) {sessionActionError="That session is no longer available";return false}
        renameServerId=target.server.id;renameSessionId=target.session.id;renameLabel=label.trim();renamingSessionKey=target.key
        if(connections[target.server.id]?.renameSession(target.session.id,renameLabel)!=true) {
            renamingSessionKey="";sessionActionError="Wait for the server to finish its current operation, then try again.";return false
        }
        return true
    }
    internal fun closeSession(target: SessionTarget) {
        if(closingSessionKey.isNotBlank() || renamingSessionKey.isNotBlank()) return
        sessionActionError=""
        val current=sessionTargets.firstOrNull {it.key==target.key && it.server.endpoint==target.server.endpoint}
        if(current==null) {sessionActionError="That session is no longer available";return}
        closingSessionKey=target.key;closingServerId=target.server.id;closingSessionId=target.session.id
        if(connections[target.server.id]?.closeSession(target.session.id)!=true) {
            closingSessionKey="";sessionActionError="Wait for the server to finish its current operation, then try again."
        }
    }
    internal fun jumpLastSession() {
        if(sessionTargets.any {it.key==lastSessionKey}) selectSessionTarget(lastSessionKey)
        else activeClient.jumpLastSession()
    }
    internal fun refreshSessionDetails() {connections.values.filter {it.state.value.connected}.forEach {it.refreshSessionDetails()}}
    private fun persist() {updateSessionTargets();if(paneCatalogVisible) updatePaneCatalog();preferences.edit {
        putString("servers",SavedServers.encode(servers));putString("active_server",activeServerId)
        putStringSet("connected_servers",desired)
    };if(foreground && BackgroundAlerts.foreground.value) BackgroundAlerts.reconcile(getApplication())}
    fun connectServer(id: String,select: Boolean=true) {
        val profile=servers.firstOrNull {it.id==id} ?: return
        val client=connection(profile)
        if(select && activeServerId!=id) {
            rememberLastSession();serverSwitchSerial++
            activeClient.releaseControl();activeClient.visible=false
            client.visible=true;activeServerId=id;activeClient=client
        }
        desired=desired+id;persist()
        if(client.state.value.phase in listOf("disconnected","error","preview")) client.connect(profile.endpoint)
        else if(foreground && client.state.value.phase=="paused") client.resume()
    }
    /** The legacy URL form also adds a new host without disconnecting existing ones. */
    fun connect(endpoint: String): String? = runCatching {
        val normalized=ServerProfile.normalizeEndpoint(endpoint)
        val existing=servers.firstOrNull {it.endpoint==normalized}
        if(existing!=null) connectServer(existing.id)
        else saveServer(null,"",normalized)?.let {throw IllegalArgumentException(it)}
    }.exceptionOrNull()?.message

    internal fun forwardServers(): List<ForwardServer> {
        val knownSessions=sessionTargets
        return servers.map {server->
        val state=connections[server.id]?.state?.value
        ForwardServer(server,state?.selectedSession?.takeIf {it.isNotBlank()} ?: knownSessions.firstOrNull {it.server.id==server.id}?.session?.id.orEmpty(),serverStatuses[server.id]?.connected==true)
        }
    }
    fun saveServer(id: String?,label: String,endpoint: String,icon: ServerIcon?=null): String? = runCatching {
        val profile=ServerProfile.create(label,endpoint,id ?: java.util.UUID.randomUUID().toString(),icon ?: servers.firstOrNull {it.id==id}?.icon ?: ServerIcon.GLOBE)
        require(servers.none {it.id!=profile.id && it.endpoint==profile.endpoint}) {"That server is already saved"}
        val old=servers.firstOrNull {it.id==profile.id}
        if(old!=null && old.endpoint!=profile.endpoint) {
            PortForwards.forgetServer(getApplication(),old.id)
            images.forget(old.id)
            connections.remove(old.id)?.let {it.releaseControl();it.disconnect();it.close()}
            observers.remove(old.id)?.cancel();serverStatuses=serverStatuses-old.id
        }
        servers=if(old==null) servers+profile else servers.map {if(it.id==profile.id) profile else it}
        persist()
        val client=connection(profile)
        if(activeServerId==profile.id) {activeClient=client;client.visible=true}
        connectServer(profile.id)
        updateActivity()
    }.exceptionOrNull()?.message

    fun disconnectServer(id: String) {
        desired=desired-id;persist()
        connections[id]?.let {it.releaseControl();it.disconnect()}
    }
    fun forgetServer(id: String) {
        PortForwards.forgetServer(getApplication(),id)
        servers.firstOrNull {it.id==id}?.let {profile->NetworkSettings.forget(profile.endpoint,servers.filter {it.id!=id}.map {it.endpoint})}
        images.forget(id)
        if(activeServerId==id) serverSwitchSerial++
        disconnectServer(id)
        observers.remove(id)?.cancel();connections.remove(id)?.close()
        servers=servers.filterNot {it.id==id};serverStatuses=serverStatuses-id
        if(activeServerId==id) {
            activeServerId=servers.firstOrNull()?.id.orEmpty()
            activeClient=connection(activeServer);activeClient.visible=true
        }
        persist()
    }
    fun previewServer(id: String) {
        if(id!=activeServerId) return
        desired=desired-id;persist();activeClient.preview()
    }
    fun updateTerminalTheme(value: TerminalPalette) {palette=value;connections.values.forEach {it.updateTerminalTheme(value)}}
    fun changeAppearance(value: Appearance) {appearance=value;preferences.edit {putString("appearance",value.name)}}
    fun pause() {foreground=false;updateActivity();networkJob?.cancel();networkMonitor.stop();connections.values.forEach {it.pause()}}
    fun resume() {foreground=true;networkMonitor.start();connections.values.forEach {it.resume()};tryOpenAlert();BackgroundAlerts.reconcile(getApplication())}
    override fun onCleared() {images.close();preferences.unregisterOnSharedPreferenceChangeListener(alertsListener);networkJob?.cancel();networkMonitor.stop();observers.values.forEach {it.cancel()};connections.values.forEach {it.close()}}
}
