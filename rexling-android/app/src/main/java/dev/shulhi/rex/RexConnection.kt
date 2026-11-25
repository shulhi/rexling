package dev.shulhi.rex

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.core.content.edit

internal class RexConnection(application: Application, private val scope: CoroutineScope, val savedEndpoint: String, private val preferences: SharedPreferences = application.getSharedPreferences("rex",0)) {
    var lastSessionId by mutableStateOf(preferences.getString("last_session:$savedEndpoint","").orEmpty())
        private set
    var paneFrames by mutableStateOf<Map<String,TerminalScreen>>(emptyMap())
        private set
    private val clipboard=application.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    var hasSemanticPrompts by mutableStateOf(false)
        private set
    private val clipboardDelivery=TerminalClipboardDelivery {text->clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal",text))}
    private var tabMoveSerial=0L
    private var pendingTabOrder by mutableStateOf<PendingTabOrder?>(null)
    private var paneMoveSerial=0L
    private var pendingPaneOrder by mutableStateOf<PendingPaneOrder?>(null)
    fun orderedWindows(value: AppState): List<WindowItem> = pendingPaneOrder?.apply(value,pendingTabOrder?.apply(value) ?: value.windows) ?: pendingTabOrder?.apply(value) ?: value.windows
    fun editTerminal(session: String,window: String,id: String,type: String,label: String="",anchor: String="",split: PaneSplit=PaneSplit.RIGHT,focus: Boolean=false): Boolean {
        val current=state.value
        if(!visible || !foreground || !current.connected || current.creating || navigationRequest.pending || current.selectedSession!=session) return false
        val tab=current.windows.firstOrNull {it.id==window} ?: return false
        if(type=="rename_tab") {if(id!=window) return false} else if(tab.blocks.none {it.id==id}) return false
        return command(type,"session" to session,"window" to window,"id" to id,"label" to label.trim(),"anchor" to anchor,"direction" to split.name.lowercase(),"focus" to focus)
    }
    internal fun resizeFloating(session: String,window: String,pane: PanePlacement,bounds: PaneBounds): Boolean {
        val current=state.value
        if(!visible || !foreground || !current.connected || current.creating || navigationRequest.pending || current.selectedSession!=session) return false
        return command("resize_floating","session" to session,"window" to window,"id" to pane.id,"layerId" to pane.layerId,
            "bounds" to org.json.JSONArray(listOf(bounds.x,bounds.y,bounds.width,bounds.height)))
    }
    internal fun minimizeFloating(session: String,window: String,pane: PanePlacement): Boolean {
        val current=state.value
        if(!visible || !foreground || !current.connected || current.creating || navigationRequest.pending ||
            current.selectedSession!=session || pane.layerId.isBlank()) return false
        val tab=current.windows.firstOrNull {it.id==window} ?: return false
        if(tab.blocks.none {it.id==current.selectedBlock} ||
            tab.layout.none {it.id==pane.id && it.floating && !it.minimized && it.layerId==pane.layerId}) return false
        return command("minimize_floating","session" to session,"window" to window,"id" to pane.id,"layerId" to pane.layerId)
    }
    fun movePane(window: String,id: String,before: String?): Boolean {
        val current=state.value
        if(handle==0L || !visible || !foreground || !current.connected || current.creating || navigationRequest.pending) return false
        val old=orderedWindows(current).firstOrNull {it.id==window}?.blocks?.map {it.id} ?: return false
        val order=moveTabBefore(old,id,before) ?: return false
        if(order==old) return false
        pendingPaneOrder=PendingPaneOrder(current.selectedSession,window,++paneMoveSerial,order)
        return command("move_pane","session" to current.selectedSession,"window" to window,"id" to id,"before" to before.orEmpty(),"blocks" to org.json.JSONArray(old),"requestId" to paneMoveSerial)
    }
    internal fun dropPane(session: String,window: String,source: String,target: String,position: PaneDropPosition,tiled: List<String>) {
        val current=state.value
        if(handle==0L || !visible || !foreground || !current.connected || current.creating ||
            navigationRequest.pending || current.selectedSession!=session || source==target) return
        val tab=current.windows.firstOrNull {it.id==window} ?: return
        if(tab.zoomed.isNotBlank() || tab.layout.filterNot {it.floating}.map {it.id}!=tiled ||
            source !in tiled || target !in tiled || tab.blocks.none {it.id==current.selectedBlock}) return
        val layout=org.json.JSONArray(tiled)
        if(position==PaneDropPosition.CENTER) {
            command("move_pane","session" to session,"window" to window,"id" to source,"swapWith" to target,"tiled" to layout)
        } else {
            command("place_pane","session" to session,"window" to window,"id" to source,"anchor" to target,
                "position" to position.name.lowercase(),"tiled" to layout)
        }
    }
    fun moveTab(id: String,before: String?): Boolean {
        val current=state.value
        if(handle==0L || !visible || !foreground || !current.connected || current.creating || navigationRequest.pending) return false
        val old=orderedWindows(current).map {it.id}
        val order=moveTabBefore(old,id,before) ?: return false
        if(order==old) return false
        pendingTabOrder=PendingTabOrder(current.selectedSession,++tabMoveSerial,order)
        command("move_tab","session" to current.selectedSession,"id" to id,"before" to before.orEmpty(),"requestId" to tabMoveSerial)
        return true
    }
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState
    private val mutableChrome=MutableStateFlow(AppState())
    val chromeState: StateFlow<AppState> = mutableChrome
    private val mutableTerminal=MutableStateFlow<TerminalScreen?>(null)
    val terminal: StateFlow<TerminalScreen?> = mutableTerminal
    private val mutableDisplayLayout=MutableStateFlow(TerminalDisplayLayout())
    val displayLayout: StateFlow<TerminalDisplayLayout> = mutableDisplayLayout
    private data class DisplayFrame(val target: String,val screen: TerminalScreen,val token: String)
    private var queuedScreen: TerminalScreen?=null
    private var queuedTarget=""
    private var displayedToken: String?=null
    private var displayedTarget=""
    private var boundTarget=""
    private var boundView: java.lang.ref.WeakReference<TerminalView>?=null
    private val displayFrames by lazy {LatestDisplayFrame<DisplayFrame>(AndroidDisplayFrameClock()) {frame->
        queuedScreen=null;queuedTarget=""
        if(!closed && foreground && visible && !navigationRequest.pending && frame.target==frameTarget(state.value)) {
            android.os.Trace.beginSection("Rexlink.present.${frame.token}")
            try {
                displayedTarget=frame.target;displayedToken=frame.token
                if(boundTarget==frame.target) boundView?.get()?.let {view->if(view.isAttachedToWindow) view.presentFrame(frame.screen,frame.token)}
                mutableTerminal.value=frame.screen
                mutableDisplayLayout.value=TerminalDisplayLayout(frame.target,true,frame.screen.columns,frame.screen.rows,frame.screen.scroll.below>0)
            } finally {android.os.Trace.endSection()}
        }
    }}
    private fun frameTarget(value: AppState)="${value.endpoint}:${value.connectionEpoch}:${value.navigationId}:${value.selectedSession}:${value.selectedBlock}"
    private fun cancelDisplay() {displayFrames.clear();queuedScreen=null;queuedTarget="";boundView=null;boundTarget=""}
    private fun queueDisplay(next: AppState) {
        val target=frameTarget(next)
        if(next.screen==null || (displayedTarget.isNotEmpty() && displayedTarget!=target)) {
            cancelDisplay();displayedTarget="";mutableTerminal.value=null;mutableDisplayLayout.value=TerminalDisplayLayout(target=target)
        }
        if(target==displayedTarget && next.screen==mutableTerminal.value) {displayFrames.clear();queuedScreen=null;queuedTarget="";return}
        if(target==queuedTarget && next.screen==queuedScreen) return
        if(next.screen!=null && foreground && visible && !navigationRequest.pending) {
            queuedScreen=next.screen;queuedTarget=target
            displayFrames.offer(DisplayFrame(target,next.screen,"$handle.${next.revision}"))
        }
    }
    // Compose observes geometry/scroll affordances; live pixels are pushed to
    // the Android view. This snapshot is only for mounting/previews.
    fun screenForLayout(layout: TerminalDisplayLayout): TerminalScreen? =
        mutableTerminal.value.takeIf {layout.hasScreen && layout.target==displayedTarget}
    fun bindTerminalView(block: String,view: TerminalView): Boolean {
        val current=state.value
        val live=current.selectedBlock.ifBlank {if(current.phase=="preview") "preview" else ""}
        if(block!=live) return false
        view.onSemanticCopy={frame,col,row,output->
            val active=state.value
            if(!visible || !foreground || navigationRequest.pending || active.selectedBlock!=block) false
            else command("copy_semantic","session" to active.selectedSession,"block" to block,
                "navigationId" to active.navigationId,"connectionEpoch" to active.connectionEpoch,
                "columns" to frame.columns,"rows" to frame.rows,"scrollTotal" to frame.scroll.total,
                "scrollOffset" to frame.scroll.offset,"viewport" to frame.plainText(),"col" to col,"row" to row,"output" to output)
        }
        view.allowHiddenCursorInput=current.programs[block]?.supportsHiddenCursorInput==true
        view.onCursorTap={
            val active=state.value
            // Program identity must be live, not the last-known chrome cache.
            if(visible && foreground && active.connected && active.controlled && !active.creating && !navigationRequest.pending &&
                active.selectedBlock==block && active.programs[block]?.supportsHiddenCursorInput==true) view.keyboard()
        }
        boundTarget=frameTarget(current);boundView=java.lang.ref.WeakReference(view)
        val latest=mutableTerminal.value.takeIf {displayedTarget==boundTarget} ?: return false
        view.presentFrame(latest,displayedToken)
        return true
    }
    var screenDimensions by mutableStateOf<Pair<Int,Int>?>(null)
        private set
    var keyboardRequest by mutableStateOf("")
        private set
    var hasScreen by mutableStateOf(false)
        private set
    var controlModifier by mutableStateOf(false)
        private set
    var altModifier by mutableStateOf(false)
        private set
    private var shiftState by mutableStateOf(ShiftKeyState())
    val shiftModifier: Boolean get()=shiftState.active
    var navigationRequest by mutableStateOf(NavigationRequest())
        private set
    private val workspace=SavedWorkspace(preferences,savedEndpoint)
    private val presentation=SavedWorkspacePresentation(preferences,savedEndpoint)
    private val navigation=workspace.navigation
    private var restoreWorkspace=true
    private val activation=PaneActivation()
    private var navigationSerial = 0L
    private var keyEpoch by mutableStateOf(0L)
    fun cancelKeyRepeats() {keyEpoch++}
    internal val selectionVersion: Long get()=navigationSerial
    internal fun imageInputTarget(): String = "$navigationSerial:${state.value.connectionEpoch}:${state.value.selectedSession}:${state.value.selectedBlock}"
    fun keyTarget(): String = "$keyEpoch:$navigationSerial:${state.value.selectedSession}:${state.value.selectedBlock}"
    fun keyForTarget(name: String,target: String) {if(target==keyTarget()) key(name)}
    internal fun neovimShortcut(item: NeovimShortcut,target: String): Boolean {
        val current=state.value
        val input=NeovimShortcuts.input(current,item,target,keyTarget(),visible && foreground,navigationRequest.pending) ?: return false
        resetModifiers()
        if(!command("input","text" to input,"session" to current.selectedSession,"block" to current.selectedBlock,
                "navigationId" to current.navigationId,"connectionEpoch" to current.connectionEpoch)) return false
        keyboardRequest=""
        return true
    }
    fun activity(open: Boolean) = command("activity","open" to open)
    private var seen=runCatching {JSONObject(preferences.getString("activity_seen:$savedEndpoint","{}").orEmpty())}.getOrDefault(JSONObject())
    fun isActivitySeen(record: ActivityRecord)=record.attentionKey.isNotBlank() && seen.optString(record.attentionKey)==record.attentionVersion
    fun markAttentionSeen(key: String,version: String) {
        if(key.isBlank() || version.isBlank()) return
        seen.put(key,version);saveAttentionSeen()
    }
    fun markActivitySeen(records: List<ActivityRecord>) {
        for(record in records) if(record.attentionKey.isNotBlank() && record.attentionVersion.isNotBlank()) seen.put(record.attentionKey,record.attentionVersion)
        saveAttentionSeen()
    }
    private fun saveAttentionSeen() {
        if(seen.length()>2048) {val keys=seen.keys().asSequence().toList();keys.take(keys.size-2048).forEach {seen.remove(it)}}
        preferences.edit {putString("activity_seen:$savedEndpoint",seen.toString())};command("activity_seen","items" to seen)
    }
    fun retryNow() {if(foreground && savedEndpoint.isNotBlank()) {cancelKeyRepeats();resizeJob?.cancel();activation.resume();command("retry")}}
    fun networkChanged(change: NetworkChange) {
        if(foreground && (!change.available || change.reroute || !state.value.connected)) {cancelKeyRepeats();resizeJob?.cancel();command("network","online" to change.available,"force" to change.reroute)}
    }
    private var handle = 0L
    private var resizeJob: Job? = null
    private var lastSize = 0 to 0
    @Volatile private var foreground = true
    @Volatile private var closed = false
    @Volatile var visible = true
        set(value) {if(field!=value){field=value;if(!value) {pendingTabOrder=null;pendingPaneOrder=null;cancelDisplay();resetModifiers();cancelKeyRepeats();resizeJob?.cancel()} else {activation.navigate();queueDisplay(state.value)};command("visibility","visible" to value);if(value) sendMirror()}}
    private var pollJob: Job? = null

    init {
        try {
            handle = NativeBridge.create()
            check(handle != 0L) { "Could not start native terminal" }
            command("activity_seen","items" to seen)
            pollJob = scope.launch(Dispatchers.IO) {
                val decoder=TerminalUpdates()
                while (!closed) {
                    if (!foreground) { delay(500); continue }
                    val update=NativeBridge.awaitUpdate(handle,decoder.revision,250)
                    if(update<0) break
                    if(update>decoder.revision) {
                        try {
                            android.os.Trace.beginSection("Rexlet.decodeUpdate")
                            val decoded=try {decoder.decodeUpdate(NativeBridge.readUpdate(handle,decoder.revision))} finally {android.os.Trace.endSection()}
                            android.os.Trace.beginSection("Rexlink.ready.$handle.${decoder.revision}");android.os.Trace.endSection()
                            withContext(Dispatchers.Main.immediate) {
                                val next=when(decoded) {
                                    is TerminalUpdate.PaneFrames -> {
                                        android.os.Trace.beginSection("Rexlink.previewCache")
                                        try {paneFrames=updatePaneFrames(paneFrames,decoded.frames)} finally {android.os.Trace.endSection()}
                                        return@withContext
                                    }
                                    is TerminalUpdate.Full -> decoded.state
                                }
                                navigationRequest = navigationRequest.acknowledge(next)
                                pendingTabOrder=pendingTabOrder?.keepFor(next)
                                pendingPaneOrder=pendingPaneOrder?.keepFor(next)
                                clipboardDelivery.deliver(next,visible && foreground && !navigationRequest.pending)
                                val old=state.value
                                if(old.selectedSession!=next.selectedSession) presentation.flush()
                                if(old.connectionEpoch!=next.connectionEpoch) {
                                    activation.resume();cancelKeyRepeats();resetModifiers()
                                    navigationRequest=NavigationRequest();restoreWorkspace=true
                                }
                                val endedSession=next.staleSession.takeIf {it.isNotBlank() && it!=old.staleSession}
                                if(endedSession!=null) {
                                    workspace.forgetSession(endedSession)
                                    if(lastSessionId==endedSession) {
                                        lastSessionId=""
                                        preferences.edit {remove("last_session:${next.endpoint}")}
                                    }
                                    if(navigationRequest.session==endedSession ||
                                        (navigationRequest.session==null && next.selectedSession.isBlank())) {
                                        navigationRequest=NavigationRequest()
                                    }
                                }
                                android.os.Trace.beginSection("Rexlink.previewCache")
                                try {
                                    paneFrames=if(endedSession==null) updatePaneFrames(paneFrames,old,next) else emptyMap()
                                } finally {android.os.Trace.endSection()}
                                if(!restoreWorkspace && next.selectedSession.isNotBlank() && next.selectedSession!=state.value.selectedSession &&
                                    (navigationRequest.session==null || navigationRequest.session==next.selectedSession)) {
                                    val old=state.value.selectedSession
                                    if(old.isNotBlank() && !navigationRequest.pending) {
                                        lastSessionId=old
                                        preferences.edit {putString("last_session:${next.endpoint}",old)}
                                    }
                                    workspace.selectSession(next.selectedSession)
                                }
                                if(old.controlled!=next.controlled || old.selectedBlock!=next.selectedBlock || old.phase!=next.phase) cancelKeyRepeats()
                                if(!next.controlled || old.selectedBlock!=next.selectedBlock) resetModifiers()
                                publishActivityState(next)
                                queueDisplay(next)
                                hasSemanticPrompts=next.screen?.hasSemanticPrompt==true
                                hasScreen=next.screen!=null
                                screenDimensions=next.screen?.let {it.columns to it.rows}

                                openPreferredTerminal();rememberCurrentTerminal();claimActivePane()
                            }
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                navigationRequest = NavigationRequest()
                                setState(state.value.copy(error = "Could not read terminal state: ${error.message}"))
                            }
                        }
                    }

                }
            }
        } catch (error: Throwable) {
            setState(AppState(phase = "error", error = "Native terminal could not start: ${error.message}"))
        }
    }
    private var rawActivityState: AppState?=null
    private var policyInput: List<ActivityRecord>?=null
    private var policyOutput: List<ActivityRecord> = emptyList()
    private var policyEnabled=false
    private var policyForeground=false
    private var inferredBlocks: Set<String> = emptySet()
    internal fun refreshActivityPolicy() {policyInput=null;rawActivityState?.let {publishActivityState(state.value.copy(activity=it.activity,workingBlocks=it.workingBlocks))}}
    private fun publishActivityState(raw: AppState) {
        rawActivityState=raw
        val enabled=preferences.getBoolean(CodexAttentionPolicy.ENABLED,false)
        if(policyInput!==raw.activity || policyEnabled!=enabled || policyForeground!=foreground) {
            policyInput=raw.activity;policyEnabled=enabled;policyForeground=foreground
            policyOutput=CodexAttentionPolicy.apply(preferences,savedEndpoint,raw.activity,foreground)
            inferredBlocks=policyOutput.filter {it.state==ActivityState.BLOCKED && it.codexTitleAttention}.map {it.block}.toSet()
        }
        val value=if(policyOutput===raw.activity && inferredBlocks.isEmpty()) raw else raw.copy(activity=policyOutput,
            workingBlocks=if(inferredBlocks.isEmpty()) raw.workingBlocks else raw.workingBlocks-inferredBlocks)
        mutableState.value=value
        mutableChrome.value=presentation.chrome(value).copy(revision=0,screen=null,previewFrames=emptyMap())
    }
    private fun setState(next: AppState) {mutableState.value=next;mutableChrome.value=presentation.chrome(next).copy(revision=0,screen=null,previewFrames=emptyMap())}
    private fun command(type: String, vararg args: Pair<String, Any>): Boolean {
        if (handle == 0L) return false
        val request = JSONObject().put("type", type)
        args.forEach { (key, value) -> request.put(key, value) }
        val error = NativeBridge.command(handle, request.toString())
        if (error.isNotEmpty()) {
            navigationRequest = NavigationRequest()
            pendingTabOrder=null;pendingPaneOrder=null
            setState(state.value.copy(error=error))
            return false
        }
        return true
    }
    /** A pane shown beside the focused one in the multi-pane layout, and the grid that fits it. */
    internal data class MirrorPane(val block: String,val columns: Int,val rows: Int)
    private data class MirrorRequest(val session: String,val window: String,val panes: List<MirrorPane>)
    private var mirrorRequest=MirrorRequest("","",emptyList())
    internal fun mirror(session: String,window: String,panes: List<MirrorPane>) {
        val next=MirrorRequest(session,window,panes)
        if(next==mirrorRequest) return
        mirrorRequest=next
        sendMirror()
    }
    internal fun setMirrorObscured(obscured: Boolean) {
        command("mirror_obscured","obscured" to obscured)
    }
    /** Zooms a pane to fill its window, or restores the layout, as the desktop does; Rex tells every client. */
    internal fun zoom(block: String,zoom: Boolean) {
        val current=state.value
        if(!current.connected || current.selectedSession.isBlank() || block.isBlank()) return
        command("zoom","session" to current.selectedSession,"block" to block,"zoom" to zoom)
    }
    /** Moves a pane border in the multi-pane layout; Rex re-lays the window out and the panes follow. */
    internal fun resizeSplit(split: PaneSplitLine,ratio: Float) {
        val current=state.value
        if(!current.connected || current.selectedSession.isBlank()) return
        command("resize_split","session" to current.selectedSession,"direction" to if(split.horizontal) "horizontal" else "vertical",
            "before" to split.before,"after" to split.after,"ratio" to ratio.coerceIn(.1f,.9f).toDouble())
    }
    private fun sendMirror() {
        if(!visible) return
        val request=mirrorRequest
        val session=state.value.selectedSession
        if(request.session!=session) return
        command("mirror","session" to session,"window" to request.window,"panes" to org.json.JSONArray(request.panes.map {
            JSONObject().put("block",it.block).put("columns",it.columns).put("rows",it.rows)}))
    }
    private fun resetModifiers() {controlModifier=false;altModifier=false;shiftState=ShiftKeyState()}
    fun connect(server: String) {
        restoreWorkspace=true
        pendingTabOrder=null;pendingPaneOrder=null
        cancelDisplay();cancelKeyRepeats();resizeJob?.cancel();activation.navigate()
        resetModifiers()
        val value = server.trim()
        navigationRequest = NavigationRequest()
        keyboardRequest=""
        paneFrames=emptyMap()
        lastSessionId=preferences.getString("last_session:$value","").orEmpty()
        preferences.edit { putString("endpoint", value) }
        command("connect", "server" to value)
    }
    fun disconnect() { pendingTabOrder=null;pendingPaneOrder=null;cancelDisplay();cancelKeyRepeats();resetModifiers(); navigationRequest = NavigationRequest(); command("disconnect") }
    fun preview() = command("preview")
    fun selectSession(id: String) {
        presentation.flush()
        if(id.isBlank()) return
        workspace.selectSession(id)
        if(!state.value.connected) {navigationRequest=NavigationRequest();restoreWorkspace=true;return}
        restoreWorkspace=false
        if (id == (navigationRequest.session ?: state.value.selectedSession)) return
        pendingTabOrder=null;pendingPaneOrder=null
        val old=navigationRequest.session ?: state.value.selectedSession
        if(old.isNotBlank() && old!=id) {
            lastSessionId=old
            preferences.edit { putString("last_session:${state.value.endpoint}",old) }
        }
        cancelDisplay();cancelKeyRepeats();resizeJob?.cancel();activation.navigate()
        navigationRequest = NavigationRequest(session = id, id = ++navigationSerial)
        controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""
        command("session", "id" to id, "navigationId" to navigationRequest.id)
    }
    fun selectBlock(id: String) = selectBlock(id,null)
    internal fun selectBlock(id: String,grid: Pair<Int,Int>?,keepTiled: Boolean=false,restoreMinimized: Boolean=false) {
        val current = state.value
        if (navigationRequest.session != null && navigationRequest.session != current.selectedSession) return
        if (id == navigationRequest.block || (id == current.selectedBlock && !navigationRequest.pending)) return
        if (current.windows.none { window -> window.blocks.any { it.id == id } }) return
        cancelDisplay();cancelKeyRepeats();resizeJob?.cancel();activation.navigate()
        navigationRequest = NavigationRequest(block = id, id = ++navigationSerial)
        controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""
        restoreWorkspace=false
        workspace.remember(current.selectedSession,current.windows,id)
        if(grid!=null) lastSize=grid
        command("block", "id" to id, "navigationId" to navigationRequest.id,"keepTiled" to keepTiled,
            "restoreMinimized" to restoreMinimized)
    }
    fun openPane(session: String,block: String) {
        presentation.flush()
        val current=state.value
        if(!visible || !current.connected || current.creating) return
        if(current.selectedSession.isNotBlank() && current.selectedSession!=session) {
            lastSessionId=current.selectedSession;preferences.edit {putString("last_session:${current.endpoint}",lastSessionId)}
        }
        cancelDisplay();cancelKeyRepeats();resizeJob?.cancel();activation.navigate();resetModifiers();keyboardRequest=""
        restoreWorkspace=false;workspace.request(session,block)
        navigationRequest=NavigationRequest(session=session,block=block,id=++navigationSerial)
        command("session","id" to session,"block" to block,"navigationId" to navigationRequest.id,"restoreMinimized" to true)
    }
    fun tabPrograms(window: WindowItem): List<ProgramIdentity> = window.blocks.map {chromeState.value.programs[it.id] ?: ProgramIdentity.Loading}
    fun tabPaneIndex(window: WindowItem): Int = window.blocks.indexOfFirst {it.id==navigation.pane(state.value.selectedSession,window)}.coerceAtLeast(0)
    fun selectWindow(id: String) {
        val current = state.value
        current.windows.firstOrNull { it.id == id }?.let { window ->
            val target=navigation.pane(current.selectedSession,window.copy(blocks=window.visiblePanes()))
            val next=if(preferences.getBoolean("minimize_floating_panes",false) && window.floatingPanes().any {it.id==target})
                floatingNavigation.returnPane(current.selectedSession,window) ?: target else target
            next?.let(::selectBlock)
        }
    }
    fun rememberCurrentTerminal() {
        val current=state.value
        if(current.connected && !current.creating && !restoreWorkspace && !navigationRequest.pending && current.selectedBlock.isNotBlank())
            workspace.remember(current.selectedSession,current.windows,current.selectedBlock)
    }
    fun openPreferredTerminal() {
        val current=state.value
        if(!current.connected || current.creating || navigationRequest.pending) return
        if(!restoreWorkspace && current.selectedSession.isNotBlank() && current.selectedBlock.isNotBlank()) return
        val target=workspace.target(current)
        restoreWorkspace=false
        if(target!=null) {
            if(target.session!=current.selectedSession) selectSession(target.session)
            else target.block?.let(::selectBlock)
        }
    }
    val floatingNavigation=FloatingPaneNavigation()
    fun swipePane(step: Int) {
        val current = state.value
        val windows=orderedWindows(current).map {window->
            val visible=window.visiblePanes()
            val panes=if(preferences.getBoolean("minimize_floating_panes",false)) window.tiledPanes().ifEmpty {visible} else visible
            window.copy(blocks=panes)
        }
        if (current.connected && !current.creating) navigation.neighbour(windows, navigationRequest.block ?: current.selectedBlock, step)?.let(::selectBlock)
    }
    fun swipeSession(step: Int) {
        val current = state.value
        if (current.connected && !current.creating) navigation.neighbourSession(current.sessions,
            navigationRequest.session ?: current.selectedSession, step)?.let(::selectSession)
    }
    fun updateTerminalTheme(palette: TerminalPalette) {
        fun hex(color: Int) = "#%06x".format(java.util.Locale.ROOT, color and 0xffffff)
        command("theme", "args" to JSONObject()
            .put("scheme", if(palette.dark) "dark" else "light")
            .put("background", hex(palette.background)).put("foreground", hex(palette.foreground))
            .put("cursor", hex(palette.accent))
            .put("palette", org.json.JSONArray(palette.ansi.mapIndexed { index, color -> "$index=${hex(color)}" })))
    }
    fun jumpLastSession() { if(state.value.sessions.any { it.id==lastSessionId }) selectSession(lastSessionId) }
    fun refreshSessionDetails() { if(state.value.connected) command("sessions_info") }
    internal fun requestPaneCatalog(open: Boolean) {command("pane_catalog","open" to open)}
    fun prefetchPanes(ids: List<String>) {
        if(state.value.connected && !state.value.creating) command("peek","session" to state.value.selectedSession,"ids" to org.json.JSONArray(ids))
    }
    fun renameSession(id: String,label: String): Boolean {
        val current=state.value
        if(!current.connected || current.creating || current.sessions.none {it.id==id} || sessionNameError(label)!=null) return false
        command("rename_session","id" to id,"label" to label.trim());return true
    }
    fun closeSession(id: String): Boolean {
        val current=state.value
        if(!current.connected || current.creating || navigationRequest.pending || current.sessions.none {it.id==id}) return false
        command("close_session","id" to id);return true
    }
    fun createSession(label: String): Boolean {
        if(!state.value.connected || state.value.creating || navigationRequest.pending) return false
        activation.navigate();resizeJob?.cancel();cancelKeyRepeats()
        return command("new_session","label" to label)
    }
    fun createTab() {
        val current = state.value
        if (current.connected && !current.creating && !navigationRequest.pending && current.selectedSession.isNotBlank()) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();command("new_tab", "session" to current.selectedSession)}
    }
    fun createPane(split: PaneSplit=PaneSplit.RIGHT,anchor: String=state.value.selectedBlock,keepTiled: Boolean=false) {
        val current = state.value
        if (current.connected && !current.creating && !navigationRequest.pending && current.selectedSession.isNotBlank() && current.windows.any {w->w.blocks.any {it.id==anchor}}) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();command("new_pane", "session" to current.selectedSession, "anchor" to anchor, "direction" to split.direction,"keepTiled" to keepTiled)}
    }
    fun closePane(session: String,id: String) {
        val current=state.value
        if(current.connected && !current.creating && !navigationRequest.pending && session==current.selectedSession) {
            if(id==current.selectedBlock) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();controlModifier=false;altModifier=false;shiftState=ShiftKeyState();keyboardRequest=""}
            command("close_pane","session" to session,"id" to id)
        }
    }
    fun closeTab(session: String,id: String,blocks: List<String>) {
        val current=state.value
        if(current.connected && !current.creating && !navigationRequest.pending && session==current.selectedSession) {
            if(current.selectedBlock in blocks) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();controlModifier=false;altModifier=false;shiftState=ShiftKeyState();keyboardRequest=""}
            command("close_tab","session" to session,"id" to id,"blocks" to org.json.JSONArray(blocks))
        }
    }
    fun refresh() = command("refresh")
    fun claimActivePane() {
        val current=state.value
        if(!restoreWorkspace && activation.shouldTake(current,navigationRequest,visible && foreground,lastSize)) sendTake(current)
    }
    private fun sendTake(current: AppState) = command("take","session" to current.selectedSession,"block" to current.selectedBlock,
        "navigationId" to current.navigationId,"columns" to lastSize.first,"rows" to lastSize.second)
    fun takeControl(showKeyboard: Boolean=true) {
        val current=state.value
        if(visible && current.connected && !current.creating && !navigationRequest.pending && current.selectedBlock.isNotBlank()) {
            if(showKeyboard) keyboardRequest=current.selectedBlock
            sendTake(current)
        }
    }
    fun keyboardShown() {keyboardRequest=""}
    fun click(col: Int,row: Int,cw: Int,ch: Int) {
        flushImeComposition()
        val current=state.value
        if(visible && foreground && current.connected && current.controlled && !current.creating && !navigationRequest.pending && current.screen?.mouseTracking==true)
            command("click","session" to current.selectedSession,"block" to current.selectedBlock,"navigationId" to current.navigationId,
                "connectionEpoch" to current.connectionEpoch,"col" to col,"row" to row,"cw" to cw,"ch" to ch)
    }
    fun wheel(lines: Int,col: Int,row: Int,cw: Int,ch: Int) {
        val current=state.value
        if(visible && current.connected && !current.creating && !navigationRequest.pending && current.screen!=null)
            command("wheel","session" to current.selectedSession,"block" to current.selectedBlock,
                "navigationId" to current.navigationId,"connectionEpoch" to current.connectionEpoch,
                "lines" to lines,"col" to col,"row" to row,"cw" to cw,"ch" to ch,"columns" to lastSize.first,"rows" to lastSize.second)
    }
    fun releaseControl() { activation.release();resizeJob?.cancel();cancelKeyRepeats();controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""; if(state.value.selectedBlock.isNotBlank()) command("release") }
    fun pause() {
        presentation.flush()
        pendingTabOrder=null;pendingPaneOrder=null
        cancelDisplay();cancelKeyRepeats()
        navigationRequest = NavigationRequest()
        foreground = false; resizeJob?.cancel(); controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""
        setState(state.value.copy(controlled = false, phase = if (state.value.connected) "paused" else state.value.phase))
        command("pause")
    }
    fun resume() { policyInput=null;if(!foreground) {activation.resume();restoreWorkspace=true};foreground = true; command("resume") }
    fun toggleControl() { controlModifier = !controlModifier }
    fun toggleAlt() { altModifier = !altModifier }
    fun toggleShift() {shiftState=ShiftKeyState(latched=!shiftState.active)}
    fun pressShift() {shiftState=shiftState.press()}
    fun releaseShift(cancelled: Boolean) {shiftState=shiftState.release(cancelled)}
    fun controlShortcut(keys: String,target: String=keyTarget()) {
        if(target!=keyTarget() || !visible || !state.value.controlled || state.value.creating || navigationRequest.pending) return
        val input=runCatching {ControlShortcutConfig.decode(keys)}.getOrNull() ?: return
        resetModifiers();type(input)
    }
    private fun flushImeComposition() {
        val view=boundView?.get() ?: return
        val control=controlModifier;val alt=altModifier;val shift=shiftState
        resetModifiers()
        try {view.flushComposingText()} finally {controlModifier=control;altModifier=alt;shiftState=shift}
    }
    fun type(text: String) {
        if (!visible || !state.value.controlled || state.value.creating || navigationRequest.pending || text.isEmpty()) return
        boundView?.get()?.keyboardPan?.followCursor()
        flushImeComposition()
        val input = TerminalInput.modified(text, controlModifier, altModifier, shiftModifier)
        controlModifier = false; altModifier = false;shiftState=shiftState.consume();keyboardRequest=""
        command("input", "text" to input,"session" to state.value.selectedSession,"block" to state.value.selectedBlock,"navigationId" to state.value.navigationId,"connectionEpoch" to state.value.connectionEpoch)
    }
    fun scrollPrompt(direction: Int) {
        val current=state.value
        if(visible && foreground && !navigationRequest.pending && current.screen!=null) command("scroll_prompt",
            "session" to current.selectedSession,"block" to current.selectedBlock,"navigationId" to current.navigationId,
            "connectionEpoch" to current.connectionEpoch,"direction" to direction.coerceIn(-1,1))
    }
    fun scroll(lines: Int) {if(!navigationRequest.pending && state.value.screen!=null) command("scroll","block" to state.value.selectedBlock,"lines" to lines)}
    fun scrollToBottom() {if(!navigationRequest.pending && state.value.screen!=null) command("scroll","block" to state.value.selectedBlock,"bottom" to true)}
    fun key(name: String) = type(TerminalInput.key(name))
    fun paste(text: String) { if (visible && state.value.controlled && !navigationRequest.pending) {flushImeComposition();command("paste", "text" to text,"session" to state.value.selectedSession,"block" to state.value.selectedBlock,"navigationId" to state.value.navigationId,"connectionEpoch" to state.value.connectionEpoch)} }
    fun resize(columns: Int, rows: Int) {
        val size = columns.coerceIn(1, 512) to rows.coerceIn(1, 256)
        lastSize = size
        resizeJob?.cancel()
        claimActivePane()
        val current=state.value
        if (!visible || !foreground || !current.controlled || navigationRequest.pending) return
        resizeJob = scope.launch {
            delay(150)
            command("resize", "session" to current.selectedSession,"block" to current.selectedBlock,"navigationId" to current.navigationId,
                "columns" to size.first, "rows" to size.second)
        }
    }
    fun applySize() = resize(lastSize.first.coerceAtLeast(1), lastSize.second.coerceAtLeast(1))
    fun close() {
        presentation.flush()
        cancelDisplay()
        closed=true;pollJob?.cancel();resizeJob?.cancel()
        if (handle != 0L) NativeBridge.destroy(handle)
        handle = 0
    }
}
