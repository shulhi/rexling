package dev.shulhi.rex

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val model: RexModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) installLaunchReveal()
        consumeAlert(intent)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val dark=when(model.appearance) {Appearance.SYSTEM->isSystemInDarkTheme();Appearance.DARK->true;Appearance.LIGHT->false}
            val immersive=model.immersiveStatusBar
            LaunchedEffect(immersive) {window.applyImmersiveStatusBar(immersive)}
            SideEffect {WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=!dark}
            CompositionLocalProvider(LocalImmersiveStatusBar provides immersive) {
                RexTheme(dark,model.theme(dark)) {RexApp(model)}
            }
        }
    }
    private fun consumeAlert(intent: Intent?) {
        AlertDestinations(BackgroundAlerts.preferences(this)).consume(intent?.data)?.let(model::openAlert)
        intent?.data=null
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);consumeAlert(intent)}
    override fun onWindowFocusChanged(hasFocus: Boolean) {super.onWindowFocusChanged(hasFocus);model.windowFocusChanged(hasFocus)}
    override fun onStart(){super.onStart();BackgroundAlerts.foreground.value=true;model.resume()}
    override fun onStop(){if(!isChangingConfigurations) BackgroundAlerts.foreground.value=false;model.pause();super.onStop()}
}

@Composable internal fun RexTheme(dark: Boolean,theme: AppTheme,content: @Composable ()->Unit) {
    CompositionLocalProvider(LocalTerminalPalette provides theme.terminal) {
        MaterialTheme(colorScheme=rexColorScheme(dark,theme.ui),content=content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexApp(model: RexModel) {
    val client=model.activeClient
    val noticeHaze=remember {HazeState()}
    val captureNotice=rememberBackdropCapture(PortForwards.linkNotice!=null)
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalKeepScreenAwake provides model.keepScreenAwake) {
            Box(Modifier.fillMaxSize().then(if(captureNotice) Modifier.hazeSource(noticeHaze) else Modifier)) {
                key(model.activeServerId) {RexWorkspace(model,client)}
            }
        }
        ForwardLinkUi(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(end=12.dp,bottom=116.dp),hazeState=noticeHaze)
    }
    if(model.notificationNotice.isNotBlank()) AlertDialog(onDismissRequest={model.notificationNotice=""},title={Text("Notification")},text={Text(model.notificationNotice)},confirmButton={TextButton(onClick={model.notificationNotice=""}) {Text("OK")}})
}

private data class TerminalCloseTarget(val session: String,val id: String,val label: String,val tab: Boolean,val blocks: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexWorkspace(model: RexModel,client: RexConnection) {
    val state by client.chromeState.collectAsState()
    val connectionRequested=model.activeConnectionRequested
    val connectionPhase=if(connectionRequested && state.phase in listOf("disconnected","paused")) {
        if(client.hasScreen) "reconnecting" else "connecting"
    } else state.phase
    val navigation=client.navigationRequest
    val displayedSession=navigation.session ?: state.selectedSession
    val currentSessionKey=sessionKey(model.activeServerId,displayedSession)
    val sessionLabel=state.sessions.firstOrNull {it.id==displayedSession}?.label
    val sessionStrip=SessionCatalog.strip(model.sessionTargets,SessionTarget(model.activeServer,
        SessionItem(displayedSession,sessionLabel ?: if(state.connected) "No session" else state.phase)))
    val displayedBlock=navigation.block ?: state.selectedBlock
    val changingSession=navigation.session!=null && navigation.session!=state.selectedSession
    val windows=if(changingSession) emptyList() else client.orderedWindows(state)
    val window=windows.firstOrNull {w->w.blocks.any {it.id==displayedBlock}} ?: windows.firstOrNull {it.blocks.isNotEmpty()}
    val activeWindowId=window?.id.orEmpty()
    val ready=state.connected && !navigation.pending && !state.creating
    var dismissedUndo by remember(client) {mutableLongStateOf(0)}
    val keyTarget=client.keyTarget()
    val terminalHaze=remember {HazeState()}
    val workspaceHaze=remember {HazeState()}
    val headerHaze=remember {HazeState()}
    var readyBackdrop by remember {mutableStateOf(false)}
    var floatingBackdrop by remember {mutableStateOf(false)}
    var wheelBackdrop by remember {mutableStateOf(false)}
    var ownerBackdrop by remember {mutableStateOf(false)}
    val palette=LocalTerminalPalette.current
    var endpoint by rememberSaveable {mutableStateOf(client.savedEndpoint)}
    var servers by remember {mutableStateOf(false)}
    var addServer by remember {mutableStateOf(false)}
    var forwards by remember {mutableStateOf(false)}
    var connectionError by remember {mutableStateOf<String?>(null)}
    var picker by remember {mutableStateOf("")}
    var newSession by remember {mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
    var appearance by remember {mutableStateOf(false)}
    var about by remember {mutableStateOf(false)}
    var networkSettings by remember {mutableStateOf(false)}
    var sshKeysSettings by remember {mutableStateOf(false)}
    var preferencesOpen by remember {mutableStateOf(false)}
    var neovimSettings by remember {mutableStateOf(false)}
    var toolbarSettings by remember {mutableStateOf(false)}
    var controlSettings by remember {mutableStateOf(false)}
    var alerts by remember {mutableStateOf(false)}
    var closeTarget by remember {mutableStateOf<TerminalCloseTarget?>(null)}
    var fontPicker by remember {mutableStateOf(false)}
    var terminalView by remember {mutableStateOf<TerminalView?>(null)}
    val context=LocalContext.current
    var networkAction by remember {mutableStateOf<(() ->Unit)?>(null)}
    var networkDenied by remember {mutableStateOf<(() ->Unit)?>(null)}
    val localNetwork=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        val action=networkAction;val denied=networkDenied;networkAction=null;networkDenied=null
        if(granted) action?.invoke() else {denied?.invoke();model.images.notice="Allow local network access to connect to Rex."}
    }
    DisposableEffect(Unit) {onDispose {networkDenied?.invoke();networkAction=null;networkDenied=null}}
    fun withUploadNetwork(action: ()->Unit,onDenied: ()->Unit={}) {
        if(Build.VERSION.SDK_INT>=37 && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if(networkAction!=null) {onDenied();return}
            networkAction=action;networkDenied=onDenied;localNetwork.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else action()
    }
    val imageCommit: (android.net.Uri,()->Unit)->Boolean={uri,release->
        val target=model.imageTarget()
        if(target==null) model.attachImage(uri,target,release)
        else {withUploadNetwork({model.attachImage(uri,target,release)},release);true}
    }
    fun pasteClipboard(): Boolean = runCatching {
        pasteTerminalClipboard(context,{uri->imageCommit(uri,{})},client::paste)
    }.getOrElse {model.images.notice="Could not read the clipboard file. Try Attach file instead.";false}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri->
        val target=model.pendingImageTarget;model.pendingImageTarget=null
        if(uri!=null) withUploadNetwork({model.attachImage(uri,target)})
    }
    fun chooseImage() {
        val target=model.imageTarget()
        if(target==null) {model.images.notice="Take control of a pane before attaching a file.";return}
        model.pendingImageTarget=target
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    // Rex accepts any file type, so the document picker allows everything.
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        val target=model.pendingImageTarget;model.pendingImageTarget=null
        if(uri!=null) withUploadNetwork({model.attachImage(uri,target)})
    }
    fun chooseFile() {
        val target=model.imageTarget()
        if(target==null) {model.images.notice="Take control of a pane before attaching a file.";return}
        model.pendingImageTarget=target
        filePicker.launch(arrayOf("*/*"))
    }

    DisposableEffect(client) {onDispose {model.setVisibleActivityPanes(null)}}
    val keyboardDensity=LocalDensity.current
    val keyboardInsets=WindowInsets.ime
    val keyboardVisible by remember(keyboardInsets,keyboardDensity) {derivedStateOf {keyboardInsets.getBottom(keyboardDensity)>0}}
    // Follow where the keyboard is heading, not its animated inset: the inset stays above zero until
    // the hide animation ends, which left the toolbar showing after the keyboard was gone.
    @OptIn(ExperimentalLayoutApi::class) val keyboardShown=WindowInsets.isImeVisible
    val toolbarHidden=model.toolbarWithKeyboardOnly && !keyboardShown
    val tabWidth=currentTabWidth()
    val fontSize=model.fontSizes.getValue(tabWidth.first)
    // Whether the bottom panel shows (it then takes the bottom inset), and whether the terminal row
    // instead extends under the gesture handle: side tabs with the key toolbar hidden.
    val panelShown=state.connected || state.phase=="preview" || client.hasScreen
    if(!panelShown || model.toolbarWithKeyboardOnly) AppGestureBarContrast(!palette.dark)
    val unfoldedFullScreen=isUnfoldedFullScreen()
    val paneOrientation=currentPaneOrientation()
    val multiplePanes=model.paneLayouts[paneOrientation]==true
    val hasHinge=remember(context) {context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)}
    val allowFloatingOverlay=unfoldedFullScreen && hasHinge
    val tabLayout=model.tabLayout(tabWidth.first)
    // A foldable's cover screen can fall in the medium tab width class.
    val squarePaneCorners=tabLayout.side || tabWidth.first==TabWidthClass.NARROW || (hasHinge && !unfoldedFullScreen)
    // From the setting, not the keyboard, so the layout keeps one mode while the keyboard animates.
    val extendUnderNav=panelShown && tabLayout.side && model.toolbarWithKeyboardOnly
    // Keep text and controls clear of the navigation area when the workspace extends under it.
    val navigationClearance=with(keyboardDensity) {
        val inset=WindowInsets.navigationBars.getBottom(this).toDp()
        when {
            !extendUnderNav || inset==0.dp -> 0.dp
            inset<40.dp -> 14.dp
            else -> inset+3.dp
        }
    }
    // The auto-hidden toolbar's unclipped height (divider + key row until measured). Written during its
    // measurement and read by the terminal's, so it is a plain holder rather than snapshot state.
    val toolbarHeight=remember(keyboardDensity) {intArrayOf(with(keyboardDensity) {1.dp.roundToPx()+45.dp.roundToPx()})}
    val blocks=window?.blocks ?: if(state.phase=="preview") listOf(BlockItem("preview","Terminal")) else emptyList()
    val compactHeaderMode=model.compactHeaderMode
    val keyboardHeader=compactHeaderMode!=CompactHeaderMode.NEVER && blocks.isNotEmpty()
    val captureHeaderBackdrop=keyboardHeader && keyboardVisible
    // The header is measured before the weighted workspace in the same layout pass.
    val headerHeightSaved=remember {intArrayOf(0)}
    val headerOverlap=remember {intArrayOf(0)}
    val currentHeaderSavings: ()->Int={if(keyboardHeader) headerHeightSaved[0] else 0}
    val currentHeaderOverlap: ()->Int={if(keyboardHeader) headerOverlap[0] else 0}
    val requested=window?.layout?.firstOrNull {it.id==displayedBlock}
    val visibleBlocks=window?.visiblePanes() ?: blocks
    val chosen=displayedBlock.takeUnless {it.isBlank() || requested?.minimized==true}
        ?: visibleBlocks.firstOrNull {it.id==state.selectedBlock}?.id.orEmpty().ifBlank {visibleBlocks.firstOrNull()?.id.orEmpty()}
    val tiles=if(multiplePanes && unfoldedFullScreen) multiPaneTiles(window,chosen,allowFloatingOverlay) else null
    val alignHeaderToRail=tabLayout.position==TabBarPosition.LEFT && blocks.isNotEmpty()
    val headerIconSlot=if(alignHeaderToRail) tabRailWidth(TabRailStyle.ICONS) else HeaderIconSlotWidth
    val showAllFloating=model.minimizeFloatingPanes || (allowFloatingOverlay && tiles!=null)
    val floating=window?.floatingPanes(minimizedOnly=!showAllFloating).orEmpty()
    val carouselBlocks=window?.carouselPanes(model.minimizeFloatingPanes,chosen) ?: blocks
    val singlePane=if(window!=null && tiles==null && ready) carouselBlocks.firstOrNull {it.id==chosen} else null
    var showSinglePaneActions by remember(displayedSession,window?.id,singlePane?.id) {mutableStateOf(false)}
    LaunchedEffect(showSinglePaneActions) {
        if(showSinglePaneActions) {kotlinx.coroutines.delay(4000);showSinglePaneActions=false}
    }
    val activeFloating=window?.layout?.firstOrNull {it.id==chosen && it.floating && !it.minimized}
        ?.takeIf {pane->floating.any {it.id==pane.id}}
    val flushSinglePane=tabLayout.side && tiles==null
    val tiledNavigationClearance=if(tiles!=null) navigationClearance else 0.dp
    val singlePaneNavigationClearance=if(flushSinglePane) navigationClearance else 0.dp
    val bottomOverlayInset=if(keyboardShown) 0.dp else singlePaneNavigationClearance
    val floatingOverlay=if(allowFloatingOverlay && tiles!=null) window?.visibleFloatingPlacements().orEmpty()
        else emptyList()
    val floatingMotion=remember(client,displayedSession,window?.id) {FloatingPaneMotion()}
    LaunchedEffect(floatingMotion,floatingMotion.restoring) {
        val id=floatingMotion.restoring ?: return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        if(floatingMotion.restoring==id) floatingMotion.restoring=null
    }
    var dockFloatingPane by remember(displayedSession,window?.id) {mutableStateOf<String?>(null)}
    val controlled=state.controlled && ready && client.hasScreen
    fun showKeyboard() {if(!controlled) client.takeControl() else terminalView?.keyboard()}
    fun toggleKeyboard() {if(controlled && keyboardVisible) terminalView?.hideKeyboard() else showKeyboard()}
    val keyboardAction: (@Composable ()->Unit)?=when {
        tabLayout.side -> ({
            TerminalKeyboardButton(controlled,ready,keyboardVisible,readable=state.connected,inputKey=keyTarget,
                onPaste={pasteClipboard()},onClick=::toggleKeyboard)
        })
        toolbarHidden -> ({
            ChromeButton(ChromeIcon.KEYBOARD,if(controlled) "Show keyboard" else "Take control and show keyboard",
                enabled=state.connected && state.selectedSession.isNotBlank(),onClick=::showKeyboard)
        })
        else -> null
    }
    val paneObscured=menu || model.activityVisible || picker.isNotEmpty() || preferencesOpen || networkSettings || sshKeysSettings || servers || addServer || forwards || newSession || appearance || about || neovimSettings || toolbarSettings || controlSettings || alerts || fontPicker || closeTarget!=null || dockFloatingPane!=null
    val zoomed=window?.zoomed.orEmpty()
    val activityPaneIds=remember(chosen,tiles,floatingOverlay,zoomed) {activityPaneCandidates(chosen,tiles,floatingOverlay,zoomed)}
    val display=client.displayLayout.collectAsState()
    // Frame contents change far more often than which panes have something on screen.
    val visibleActivityBlocks by remember(client,display,activityPaneIds,ready,paneObscured,state.selectedBlock) {
        derivedStateOf<Set<String>>(structuralEqualityPolicy()) {
            if(!ready || paneObscured) emptySet() else activityPaneIds.filterTo(mutableSetOf()) {id->
                id==state.selectedBlock && display.value.hasScreen || id in client.paneFrames
            }
        }
    }
    val activityServer=model.activeServer
    val activityBadges=remember(model.activitySources,activityServer,displayedSession,windows,visibleActivityBlocks) {
        ActivityBadgePolicy.route(model.activitySources,activityServer,displayedSession,windows,visibleActivityBlocks)
    }
    SideEffect {
        model.setVisibleActivityPanes(visibleActivityBlocks.takeIf {it.isNotEmpty()}?.let {
            WatchedPanes(activityServer.id,activityServer.endpoint,displayedSession,state.selectedBlock,it)
        })
    }
    fun openPane(id: String) {
        client.selectBlock(id,null,keepTiled=tiles!=null,restoreMinimized=true)
    }
    fun restoreFloating(id: String) {
        if(window?.layout?.any {it.id==id && it.floating && (it.minimized || tiles==null)}==true) floatingMotion.restoring=id
        openPane(id)
    }
    fun minimizeFloating(id: String) {
        val tab=window ?: return
        val pane=tab.layout.firstOrNull {it.id==id && it.floating && !it.minimized} ?: return
        if(client.minimizeFloating(displayedSession,tab.id,pane)) floatingMotion.capture(id)
    }
    LaunchedEffect(model.minimizeFloatingPanes) {
        if(model.minimizeFloatingPanes && activeFloating!=null && tiles==null)
            client.floatingNavigation.returnPane(displayedSession,window)?.let(client::selectBlock)
    }
    SideEffect {window?.let {client.floatingNavigation.remember(displayedSession,it,chosen)}}
    BackHandler(enabled=ready && !newSession && !appearance && !about && !neovimSettings && !alerts && !fontPicker && closeTarget==null && activeFloating?.layerId?.isNotBlank()==true && picker.isEmpty() && !preferencesOpen && !model.activityVisible) {activeFloating?.let {minimizeFloating(it.id)}}
    val liveBlock=state.selectedBlock.ifBlank {if(state.phase=="preview") "preview" else ""}
    fun closePaneTarget(block: BlockItem)=TerminalCloseTarget(state.selectedSession,block.id,
        (state.programs[block.id] ?: ProgramIdentity.Loading).paneTitle(block.label,state.titles[block.id].orEmpty()),false,listOf(block.id))
    fun closeTabTarget(tab: WindowItem): TerminalCloseTarget {
        val pane=client.tabPaneIndex(tab);val block=tab.blocks.getOrNull(pane)
        val program=state.programs[block?.id] ?: ProgramIdentity.Loading
        return TerminalCloseTarget(state.selectedSession,tab.id,program.tabTitle(tab.label,block?.label.orEmpty(),1,state.titles[block?.id].orEmpty()),true,tab.blocks.map {it.id})
    }
    LaunchedEffect(state.selectedSession) {closeTarget=null}
    LaunchedEffect(palette) { model.updateTerminalTheme(palette) }
    LaunchedEffect(state.connected,state.selectedSession,state.selectedBlock,state.windows,navigation.pending) {
        client.openPreferredTerminal();client.rememberCurrentTerminal()
    }
    LaunchedEffect(state.connected,state.selectedSession,window?.id,state.selectedBlock,carouselBlocks,tiles!=null) {
        if(tiles!=null) return@LaunchedEffect
        val blocks=carouselBlocks
        val index=blocks.indexOfFirst {it.id==state.selectedBlock}
        if(index>=0 && blocks.size>1) client.prefetchPanes(listOf(blocks[Math.floorMod(index-1,blocks.size)].id,blocks[(index+1)%blocks.size].id).distinct())
    }
    LaunchedEffect(ready,chosen,client.hasScreen) {if(ready) client.claimActivePane()}
    LaunchedEffect(controlled,terminalView) {
        if(controlled) {
            terminalView?.requestFocus()
            client.applySize()
        }
    }
    // A modal drawer holds window focus until it closes. Keep the keyboard request until the terminal can receive it.
    val windowFocused=LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(controlled,client.keyboardRequest,terminalView,displayedBlock,menu,windowFocused) {
        val view=terminalView
        if(windowFocused && !menu && controlled && view!=null && client.keyboardRequest.isNotEmpty() && client.keyboardRequest==displayedBlock) {
            view.keyboard()
            client.keyboardShown()
        }
    }
    val chosenBlock=blocks.firstOrNull {it.id==chosen}
    val paneTitle=if(model.servers.isEmpty()) stringResource(R.string.app_name) else
        (state.programs[chosen] ?: ProgramIdentity.Loading).paneTitle(chosenBlock?.label.orEmpty(),state.titles[chosen].orEmpty())
    val workspaceBackdrop=rememberBackdropCapture(menu || forwards || preferencesOpen || servers || networkSettings || sshKeysSettings || appearance || about || neovimSettings || toolbarSettings || controlSettings || fontPicker || alerts || picker in setOf("tabs","panes","sessions") || model.activityVisible || dockFloatingPane!=null)
    if(workspaceBackdrop && tiles!=null) DisposableEffect(client) {
        client.setMirrorObscured(true)
        onDispose {client.setMirrorObscured(false)}
    }
    val topCutout=if(model.immersiveStatusBar) workspaceTopCutout() else null
    val safety=WindowInsets.safeDrawing
    val immersiveTop=immersiveTopInsets()
    val workspaceInsets=if(model.immersiveStatusBar &&
        (topCutout!=null || WindowInsets.displayCutout.getTop(keyboardDensity)<=immersiveTop.getTop(keyboardDensity))) {
        safety.only(WindowInsetsSides.Horizontal).union(immersiveTop)
    } else safety.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)
    Surface(Modifier.fillMaxSize().then(if(workspaceBackdrop) Modifier.hazeSource(workspaceHaze) else Modifier),color=MaterialTheme.colorScheme.background) {
        // The bottom navigation inset (the gesture handle) is left to the lowest element, so it can
        // extend its background to the screen edge. imePadding consumes the keyboard first, so
        // that element's navigation padding shrinks to nothing as the keyboard opens.
        Column(Modifier.fillMaxSize().windowInsetsPadding(workspaceInsets).imePadding()
            .then(if(panelShown) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))) {
            SessionHeader(sessionStrip,currentSessionKey,state.connected,model.sessionTargets.isNotEmpty(),
                blocks.map {state.programs[it.id] ?: ProgramIdentity.Loading},blocks.indexOfFirst {it.id==chosen}.coerceAtLeast(0),{picker="sessions"},model::selectSessionTarget,model::jumpLastSession,
                serverIcons=model.sessionTargets.associate {it.key to it.server.icon}+(currentSessionKey to model.activeServer.icon),
                working=chosen in state.workingBlocks,title=paneTitle,directory=state.directories[chosen].orEmpty(),collapseForIme=keyboardInsets.takeIf {keyboardHeader},
                alwaysCompact=compactHeaderMode==CompactHeaderMode.ALWAYS,
                onHeaderHeightSaved={headerHeightSaved[0]=it},headerHaze=headerHaze.takeIf {captureHeaderBackdrop},onHeaderOverlap={headerOverlap[0]=it},
                iconSlotWidth=headerIconSlot,topCutout=topCutout,modifier=Modifier.zIndex(1f),menu={
                    ChromeButton(ChromeIcon.MORE,"Terminal menu") {menu=true}
                })
            Box(Modifier.weight(1f).fillMaxWidth()
                .then(if(keyboardHeader) Modifier.underWorkspaceHeader(currentHeaderOverlap) else Modifier)
                .then(if(captureHeaderBackdrop) Modifier.hazeSource(headerHaze) else Modifier)) {
                Column(Modifier.fillMaxSize()) {
                    if(model.servers.isEmpty() || (!connectionRequested && state.phase=="disconnected" && !client.hasScreen)) {
                        Surface(Modifier.padding(12.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
                            if(model.servers.isEmpty()) EmptyServerContent(onAdd={addServer=true}) else Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.app_name),fontWeight=FontWeight.Bold,fontSize=20.sp)
                                Text(stringResource(R.string.app_subtitle),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Connect over Tailscale",fontWeight=FontWeight.SemiBold)
                                OutlinedTextField(endpoint,{endpoint=it},Modifier.fillMaxWidth(),label={Text("Rex server URL")},singleLine=true,
                                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),shape=RoundedCornerShape(12.dp))
                                connectionError?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=11.sp)}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={connectionError=model.connect(endpoint)},enabled=endpoint.isNotBlank()){Text("Connect")}
                                    TextButton(onClick={servers=true}) {Text("Servers")}
                                    TextButton(onClick={model.previewServer(model.activeServerId)}){Text("Try preview")}
                                }
                            }
                        }
                    }
                    if(blocks.isEmpty() && model.servers.isNotEmpty()) ConnectionNotice(connectionPhase,model.networkAvailable,client::retryNow)
                    if(blocks.isEmpty() && state.connected && !state.creating && state.sessions.isEmpty()) {
                        EmptySessionContent(model.activeServer.label,ready,{newSession=true},Modifier.weight(1f).fillMaxWidth())
                    }
                    if(state.error.isNotBlank() && state.phase !in listOf("connecting","reconnecting")) Text(state.error,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
                        color=MaterialTheme.colorScheme.onErrorContainer,fontSize=11.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    val tabActions: @Composable (Boolean)->Unit={vertical->
                        TerminalActions(state.connected && state.selectedSession.isNotBlank(),model.connectedCount>0,
                            {model.showActivity(true)},{picker="tabs"},attentionCount=activityBadges.elsewhere,
                            vertical=vertical,keyboard=keyboardAction)
                    }
                    val railBackdrop=remember(tabLayout.position) {PaneSwipeBackdrop(tabLayout.position)}
                    val swipeBackdrop=railBackdrop.takeIf {tabLayout.side && tiles==null && carouselBlocks.size>1}
                    val tabRail: @Composable ()->Unit={
                        TerminalTabRail(windows,activeWindowId,state.connected&&!state.creating&&!changingSession,client::selectWindow,client::tabPrograms,client::tabPaneIndex,
                            tabLayout.position,tabLayout.style,paneTitle={w,pane->state.titles[w.blocks.getOrNull(pane)?.id].orEmpty()},paneWorking={w->w.blocks.any {it.id in state.workingBlocks}},
                            onClose={closeTarget=closeTabTarget(it)},onMove=client::moveTab,reorderKey=displayedSession,
                            paneProgress={w->state.terminalProgress[w.blocks.getOrNull(client.tabPaneIndex(w))?.id] ?: w.blocks.mapNotNull {state.terminalProgress[it.id]}.firstOrNull()},
                            tabAttention={w->activityBadges.tabs[w.id] ?: 0},animateWorking=!workspaceBackdrop,
                            onStyle={model.changeTabLayout(tabWidth.first,tabLayout.copy(style=it))},extendUnderNav=extendUnderNav,
                            onNewTab=client::createTab,windowWidthDp=tabWidth.second,
                            sessions=TabRailSessions(model.sessionTargets,currentSessionKey,model::selectSessionTarget),
                            headerOverlap=currentHeaderOverlap,swipeBackdrop=railBackdrop,hazeState=terminalHaze) {
                            tabActions(tabLayout.style==TabRailStyle.ICONS)
                        }
                    }
                    if(blocks.isNotEmpty()) Row(Modifier.weight(1f).fillMaxWidth()
                        .then(if(swipeBackdrop==null) Modifier else Modifier.clipToBounds())) {
                        if(tabLayout.position==TabBarPosition.LEFT) tabRail()
                        val paneCoordinates=remember {arrayOfNulls<LayoutCoordinates>(1)}
                        val viewLocation=remember {IntArray(2)}
                        val cornerSize=with(keyboardDensity) {44.dp.toPx()}
                        val rightCorner=LocalLayoutDirection.current==LayoutDirection.Ltr
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .windowInsetsPadding(WindowInsets(bottom=tiledNavigationClearance))
                            .onPlaced {floatingMotion.workspaceOrigin=it.positionInWindow()}
                            .then(if(singlePane==null) Modifier else Modifier.onGloballyPositioned {paneCoordinates[0]=it}
                                .semantics {
                                    if(!showSinglePaneActions) customActions=listOf(CustomAccessibilityAction("Show pane actions") {
                                        showSinglePaneActions=true;true
                                    })
                                })) {
                        val onView: (TerminalView)->Unit={view->
                            terminalView=view
                            view.onPaneControlsTap=if(singlePane==null) null else tap@{x,y->
                                val coordinates=paneCoordinates[0]?.takeIf {it.isAttached} ?: return@tap false
                                view.getLocationInWindow(viewLocation)
                                val point=coordinates.windowToLocal(Offset(viewLocation[0]+x,viewLocation[1]+y))
                                val top=currentHeaderOverlap().toFloat()
                                val fromEnd=if(rightCorner) coordinates.size.width-point.x else point.x
                                val inCorner=fromEnd>=0 && fromEnd<cornerSize && point.y>=top && point.y<top+cornerSize
                                if(inCorner) showSinglePaneActions=true
                                inCorner
                            }
                            view.onOpenLink={url->
                                val remotePath=TerminalLinks.filePath(url)
                                val loopback=LoopbackLink.parse(url)
                                if(remotePath!=null) {
                                    if(ready && chosen==liveBlock && displayedSession.isNotBlank()) {
                                        model.downloads.offer(FileDownloadTarget(model.activeServer,displayedSession,chosen,remotePath))
                                    } else model.downloads.notice="Wait for this pane to connect before downloading its file."
                                }
                                else if(loopback==null) PortForwards.openBrowser(context,url)
                                else if(ready && chosen==liveBlock && displayedSession.isNotBlank()) {
                                    val sourceServer=model.activeServer;val sourceSession=displayedSession
                                    withUploadNetwork({if(model.servers.any {it.id==sourceServer.id && it.endpoint==sourceServer.endpoint}) PortForwards.openLink(context,sourceServer,sourceSession,loopback)})
                                }
                                else PortForwards.notice="Wait for this pane to connect before forwarding its link."
                            }
                        }
                        val captureTerminal=floatingBackdrop || wheelBackdrop || ownerBackdrop || readyBackdrop || model.images.state!=null
                        if(carouselBlocks.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                            Text("Floating panes minimized",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else if(tiles!=null && window!=null) MultiPaneLayout(
                            client,displayedSession,window.id,tiles,chosen,liveBlock,controlled,state.programs,state.mirrorWaiting,
                            fontSize,onFocus={id,grid->client.selectBlock(id,grid,keepTiled=true)},
                            onView=onView,onImage=imageCommit,onPaste=::pasteClipboard,imeEnterSubmits=model.enterSubmits,
                            keyboardChrome={if(model.toolbarWithKeyboardOnly) toolbarHeight[0] else 0},
                            headerHeightSaved=currentHeaderSavings,headerOverlap=currentHeaderOverlap,
                            extendsUnderNavigation=extendUnderNav,hazeState=terminalHaze,captureBackdrop=captureTerminal,
                            bottomPaddingDp=if(floating.isNotEmpty()) 28 else 6,navigationClearance=tiledNavigationClearance,
                            splits=window.splits,zoomed=window.zoomed,paneActionsEnabled=ready,
                            floatingPanes=floatingOverlay,allowFloat=allowFloatingOverlay && tiles.size>1,
                            onMinimizeFloating=::minimizeFloating,onDockFloating={dockFloatingPane=it},
                            onClosePane={id->blocks.firstOrNull {it.id==id}?.let {closeTarget=closePaneTarget(it)}},
                            floatingMotion=floatingMotion,
                        )
                        else LiveTerminal(client,carouselBlocks,chosen,liveBlock,state.programs,controlled,fontSize,
                            client::type,client::scroll,client::wheel,client::takeControl,client::scrollToBottom,client::resize,::openPane,{picker="panes"},onView,
                            Modifier.fillMaxSize(),floatingDock=floating.isNotEmpty(),paneLayout=window?.layout.orEmpty(),titles=state.titles,directories=state.directories,
                            hazeState=terminalHaze,captureBackdrop=captureTerminal,onImage=imageCommit,onPaste=::pasteClipboard,imeEnterSubmits=model.enterSubmits,
                            keyboardChrome={if(model.toolbarWithKeyboardOnly) toolbarHeight[0] else 0},
                            headerHeightSaved=currentHeaderSavings,headerOverlap=currentHeaderOverlap,
                            squareCorners=squarePaneCorners,extendsUnderNavigation=extendUnderNav,
                            navigationClearance=singlePaneNavigationClearance,bottomOverlayInset=bottomOverlayInset,floatingMotion=floatingMotion,swipeBackdrop=swipeBackdrop)
                        if(showSinglePaneActions && window!=null && singlePane!=null) {
                            val placement=window.layout.firstOrNull {it.id==singlePane.id}
                            val tiledCount=window.layout.count {!it.floating}
                            PaneCornerActions(
                                Modifier.align(Alignment.TopEnd).offset {IntOffset(0,currentHeaderOverlap())}.padding(6.dp),
                                enabled=true,onSplit={showSinglePaneActions=false;client.createPane(it,singlePane.id)},
                                onFloat=if(placement?.floating==false && tiledCount>1) ({
                                    showSinglePaneActions=false
                                    client.editTerminal(displayedSession,window.id,singlePane.id,"float_pane")
                                }) else null,
                                onDock=if(placement?.floating==true && tiledCount>0) ({showSinglePaneActions=false;dockFloatingPane=singlePane.id}) else null,
                                onClose={showSinglePaneActions=false;closeTarget=closePaneTarget(singlePane)},
                            )
                        }
                        ConnectionNotice(connectionPhase,model.networkAvailable,client::retryNow,Modifier.align(Alignment.TopCenter).padding(8.dp))
                        val belowPaneControls=if(showSinglePaneActions) Modifier.offset {IntOffset(0,currentHeaderOverlap()+46.dp.roundToPx())} else Modifier
                        ReadyNotice(state.connectionEpoch,controlled,ready,Modifier.align(Alignment.TopCenter).then(belowPaneControls).padding(8.dp),
                            hazeState=terminalHaze,onVisibleChange={readyBackdrop=it})
                        OtherOwnerNotice(chosen,state.otherOwner && !controlled,ready,client.screenDimensions,{client.takeControl()},
                            Modifier.align(Alignment.TopEnd).then(belowPaneControls)
                                .padding(top=10.dp,end=if(singlePane!=null && !showSinglePaneActions) 54.dp else 10.dp),
                            hazeState=terminalHaze,onVisibleChange={ownerBackdrop=it})
                        if(model.neovimShortcutsEnabled && ready && chosen==liveBlock && chosen !in state.cachedPrograms && state.programs[chosen]?.kind==ProgramKind.NEOVIM && model.neovimShortcuts.isNotEmpty()) {
                            NeovimShortcutButton(model.neovimShortcuts,keyTarget,controlled,
                                onChoose={item,target->client.neovimShortcut(item,target)},onCustomize={neovimSettings=true},layout=model.neovimShortcutLayout,effect=model.neovimShortcutEffect,hapticsEnabled=model.neovimShortcutHaptics,hazeState=terminalHaze,onExpandedChange={wheelBackdrop=it},
                                modifier=Modifier.fillMaxSize(),position=model.neovimShortcutPosition,onPosition=model::moveNeovimShortcut,bottomInset=bottomOverlayInset+(if(floating.isNotEmpty() || carouselBlocks.size>1) 64.dp else 10.dp))
                        }
                        val minimizingId=floatingMotion.minimizing?.id
                        MinimizingFloatingPane(floatingMotion,window?.layout?.any {it.id==minimizingId && it.minimized}==true)
                        if(floating.isNotEmpty()) FloatingPaneDock(floating,activeFloating?.id,state.programs,state.titles,ready,
                            ::restoreFloating,{activeFloating?.let {minimizeFloating(it.id)}},Modifier.matchParentSize().padding(bottom=bottomOverlayInset),
                            directories=state.directories,hazeState=terminalHaze,onExpandedChange={floatingBackdrop=it},onDockBounds={floatingMotion.dockBounds=it},
                            position=model.floatingDockPosition,onMove=model::moveFloatingDock,unread=activityBadges.panes,topOcclusion=currentHeaderOverlap)
                        }
                        if(tabLayout.position==TabBarPosition.RIGHT) tabRail()
                    } else Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center) {
                        if(state.connected || !connectionRequested) Text(if(state.connected) "Choose a session, or start a new one." else "Your terminals, anywhere.",
                            fontFamily=MonoUi,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(state.connected || state.phase=="preview" || client.hasScreen) {
                        WorkspaceBottomBar(model,client,state,windows,activeWindowId,displayedSession,
                            tabLayout.side,extendUnderNav,changingSession,!workspaceBackdrop,activityBadges.tabs,
                            controlled,ready,keyboardVisible,keyTarget,toolbarHeight,
                            onCloseTab={closeTarget=closeTabTarget(it)},onPaste=::pasteClipboard,onKeyboard=::toggleKeyboard,
                            actions={tabActions(false)})
                    }
                }
                model.images.state?.let {status->ImageUploadOverlay(status,model.images::retry,model.images::cancel,
                    modifier=Modifier.align(Alignment.BottomEnd).padding(horizontal=16.dp).padding(bottom=if(state.connected) if(floating.isNotEmpty()) 152.dp else 112.dp else 16.dp),retryable=model.images.retryable,hazeState=terminalHaze)}
                state.undoClose?.takeIf {ready && !paneObscured && it.serial!=dismissedUndo}?.let {close->
                    Snackbar(
                        modifier=Modifier.align(Alignment.BottomCenter).widthIn(max=380.dp).padding(horizontal=12.dp)
                            .padding(bottom=if(tabLayout.side) 62.dp else 110.dp),
                        action={TextButton(onClick={client.undoClose(close.serial)},
                            colors=ButtonDefaults.textButtonColors(contentColor=SnackbarDefaults.actionColor)) {Text("Undo")}},
                        dismissAction={IconButton(onClick={dismissedUndo=close.serial},
                            modifier=Modifier.semantics {contentDescription="Dismiss Undo"}) {
                            ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(18.dp),LocalContentColor.current)
                        }},
                    ) {Text(if(close.tab) "Tab closed" else "Pane closed")}
                }
                // Loading feedback must not resize the terminal grid during navigation.
                if(state.creating || navigation.pending)
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))

            }
        }
    }
    if(menu) {
        val showProgramResume=ready && chosenBlock!=null && chosen==liveBlock && state.supportsProgramResume
        if(showProgramResume) {
            DisposableEffect(client,state.selectedSession,liveBlock,state.connectionEpoch,state.navigationId) {
                client.watchProgramResume(state,true)
                onDispose {client.watchProgramResume(state,false)}
            }
        }
        val canTakeControl=ready && client.hasScreen
        val canAttach=controlled && model.images.state==null
        TerminalActionsDrawer(
            title=if(chosenBlock==null) "Terminal actions" else paneTitle,
            subtitle=listOfNotNull(sessionLabel,model.activeServer.label.takeIf {model.servers.isNotEmpty()}).joinToString(" · "),
            status=if(state.connected) if(controlled) "In control" else "Viewing terminal" else state.phase,
            dimensions=client.screenDimensions,
            controlled=controlled,
            ownership=if(canTakeControl) ({
                if(controlled) client.releaseControl() else client.takeControl()
            }) else null,
            quickActions=listOfNotNull(
                TerminalDrawerAction("Paste",ChromeIcon.PASTE,enabled=controlled) {pasteClipboard()},
                TerminalDrawerAction("Image",ChromeIcon.IMAGE,enabled=canAttach,run=::chooseImage),
                TerminalDrawerAction("Attach file",ChromeIcon.FILE,enabled=canAttach,run=::chooseFile),
                TerminalDrawerAction("Copy viewport",ChromeIcon.COPY,enabled=client.hasScreen) {
                    client.terminal.value?.let {screen->
                        (getClipboard(context) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rex terminal",screen.plainText()))
                    }
                },
                if(unfoldedFullScreen) {
                    TerminalDrawerAction(if(multiplePanes) "Single pane" else "Multiple panes",
                        if(multiplePanes) ChromeIcon.ZOOM else ChromeIcon.SPLIT) {
                        model.changePaneLayout(paneOrientation,!multiplePanes)
                    }
                } else null,
                TerminalDrawerAction(if(palette.dark) "Light theme" else "Dark theme",
                    if(palette.dark) ChromeIcon.SUN else ChromeIcon.MOON) {
                    model.changeAppearance(if(palette.dark) Appearance.LIGHT else Appearance.DARK)
                },
            ),
            actions=listOf(
                TerminalDrawerAction("New session",ChromeIcon.ADD,enabled=ready,opensDrawer=true) {newSession=true},
                TerminalDrawerAction("Port forwarding",ChromeIcon.SERVER,opensDrawer=true) {forwards=true},
                TerminalDrawerAction("Servers · ${model.connectedCount} connected",ChromeIcon.SERVER,opensDrawer=true) {servers=true},
                TerminalDrawerAction("Refresh sessions",ChromeIcon.REFRESH,enabled=state.connected) {client.refresh()},
            ),
            showProgramResume=showProgramResume,
            programResume=state.programResume,
            onPreferences={preferencesOpen=true},
            onClosePane=chosenBlock?.takeIf {ready}?.let {block-> {closeTarget=closePaneTarget(block)} },
            onDismiss={menu=false},
            hazeState=workspaceHaze,
        )
    }
    if(model.activityVisible) ActivitySheet(model.activitySources,model::refreshActivity,model::openActivity,onClose={model.showActivity(false)},onSeen=model::markActivitySeen,onMarkAll=model::markAllActivitySeen,hazeState=workspaceHaze)
    NetworkPrompts()
    if(sshKeysSettings) SshKeysSheet(onClose={sshKeysSettings=false},hazeState=workspaceHaze)
    if(networkSettings) NetworkSheet(model.activeServer,state.connected,hazeState=workspaceHaze) {networkSettings=false}
    if(preferencesOpen) PreferencesSheet({preferencesOpen=false;appearance=true},{preferencesOpen=false;fontPicker=true},{preferencesOpen=false;alerts=true},{preferencesOpen=false;about=true},
        onNetwork={preferencesOpen=false;networkSettings=true},onSshKeys={preferencesOpen=false;sshKeysSettings=true},onClose={preferencesOpen=false},shortcutGesture=model.shortcutGesture,onShortcutGesture=model::changeShortcutGesture,
        onNeovim={preferencesOpen=false;neovimSettings=true},arrowJoystickEnabled=model.arrowJoystickEnabled,onArrowJoystick=model::changeArrowJoystick,
        enterSubmits=model.enterSubmits,onEnterSubmits=model::changeEnterSubmits,
        toolbarWithKeyboardOnly=model.toolbarWithKeyboardOnly,onToolbarWithKeyboardOnly=model::changeToolbarWithKeyboardOnly,
        toolbarSystemColors=model.toolbarSystemColors,onToolbarSystemColors=model::changeToolbarSystemColors,
        compactHeaderMode=model.compactHeaderMode,onCompactHeaderMode=model::changeCompactHeaderMode,
        immersiveStatusBar=model.immersiveStatusBar,onImmersiveStatusBar=model::changeImmersiveStatusBar,
        tabLayouts=model.tabLayouts,tabWidth=tabWidth,onTabLayout=model::changeTabLayout,
        paneLayouts=model.paneLayouts,paneOrientation=paneOrientation,onPaneLayout=model::changePaneLayout,
        codexTitleAttention=model.experimentalCodexAttention,onCodexTitleAttention=model::changeCodexAttention,
        minimizeFloatingPanes=model.minimizeFloatingPanes,onMinimizeFloatingPanes=model::changeMinimizeFloatingPanes,
        keepScreenAwake=model.keepScreenAwake,onKeepScreenAwake=model::changeKeepScreenAwake,onToolbar={preferencesOpen=false;toolbarSettings=true},onControlItems={preferencesOpen=false;controlSettings=true},hazeState=workspaceHaze)
    if(controlSettings) ControlShortcutsSheet(model.controlItems,model::saveControlShortcuts,onClose={controlSettings=false},hazeState=workspaceHaze)
    if(toolbarSettings) KeyboardToolbarSheet(model.toolbarKeys,model::changeToolbar,onClose={toolbarSettings=false},hazeState=workspaceHaze)
    if(neovimSettings) NeovimShortcutsSheet(model.neovimShortcuts,model::saveNeovimShortcuts,onClose={neovimSettings=false},
        enabled=model.neovimShortcutsEnabled,onEnabled=model::enableNeovimShortcuts,
        layout=model.neovimShortcutLayout,onLayout=model::changeNeovimShortcutLayout,effect=model.neovimShortcutEffect,onEffect=model::changeNeovimShortcutEffect,
        hapticsEnabled=model.neovimShortcutHaptics,onHaptics=model::changeNeovimShortcutHaptics,hazeState=workspaceHaze)
    if(model.images.notice.isNotEmpty()) AlertDialog(onDismissRequest={model.images.notice=""},title={Text("File upload")},text={Text(model.images.notice)},confirmButton={TextButton(onClick={model.images.notice=""}) {Text("OK")}})
    FileDownloadDialogs(model.downloads) {action->
        withUploadNetwork(action) {model.downloads.notice="Allow local network access to download from this server."}
    }
    if(about) AboutSheet(hazeState=workspaceHaze) {about=false}
    if(alerts) BackgroundAlertsDialog(model,hazeState=workspaceHaze) {alerts=false}
    if(forwards) PortForwardSheet(model.forwardServers(),model.activeServerId,workspaceHaze,onClose={forwards=false},onStart={id->
        val saved=PortForwards.items.firstOrNull {it.id==id}
        val target=model.forwardServers().firstOrNull {it.profile.id==saved?.serverId && it.profile.endpoint==saved.endpoint}
        if(saved!=null && target!=null && target.session.isNotBlank()) PortForwards.update(saved.copy(session=target.session,server=target.profile.label))
        withUploadNetwork({PortForwards.requestStart(context,id)})
    }) {target,host,port,local->
        withUploadNetwork({PortForwards.start(context,target.profile,target.session,host,port,local)})
    }
    if(servers) ServersSheet(model,hazeState=workspaceHaze){servers=false}
    if(addServer) ServerEditor(null,onClose={addServer=false},onSave={label,url,icon->model.saveServer(null,label,url,icon)})
    // One drawer for sessions, tabs and panes; each entry point opens it on its own page.
    if(picker=="sessions" || picker=="tabs" || picker=="panes") TabPaneSwitcher(windows,window?.id.orEmpty(),chosen,
        state.programs,state.titles,state.directories,client::tabPaneIndex,ready,
        canCreateTab=ready && state.selectedSession.isNotBlank(),canCreatePane=ready && state.selectedSession.isNotBlank(),
        onTab={picker="";client.selectWindow(it)},onPane={picker="";openPane(it)},
        onNewTab={picker="";client.createTab()},onNewPane={anchor,split->
            picker="";client.createPane(split,anchor,keepTiled=allowFloatingOverlay && tiles!=null && floating.any {it.id==anchor})
        },
        onCloseTab={picker="";closeTarget=closeTabTarget(it)},onClosePane={picker="";closeTarget=closePaneTarget(it)},
        onClose={picker=""},initialPage=if(picker=="panes") 1 else 0,onMoveTab=client::moveTab,onMovePane=client::movePane,reorderKey=currentSessionKey,
        onRenameTab={tab,label->client.editTerminal(displayedSession,tab,tab,"rename_tab",label=label)},
        onRenamePane={tab,id,label->client.editTerminal(displayedSession,tab,id,"rename_pane",label=label)},
        onFloatPane={tab,id->client.editTerminal(displayedSession,tab,id,"float_pane")},
        onDockPane={tab,id,anchor,split->client.editTerminal(displayedSession,tab,id,"dock_pane",anchor=anchor,split=split)},error=state.error,hazeState=workspaceHaze,
        sessions=SwitcherSessions(model.sessionTargets.size,
            model.sessionTargets.firstOrNull {it.key==currentSessionKey}?.let {"${it.session.label} · ${it.server.label}"}.orEmpty(),
            onShown=model::refreshSessionDetails) {
            SessionsPage(model.sessionTargets,currentSessionKey,model.lastSessionKey,ready,model.closingSessionKey.ifBlank {model.renamingSessionKey},model.sessionActionError,model::closeSession,model::renameSession,
                onSelect={picker="";model.selectSessionTarget(it)},onNew={picker="";newSession=true},
                allPanes={back->
                    DisposableEffect(model) {
                        model.showPaneCatalog(true)
                        onDispose {model.showPaneCatalog(false)}
                    }
                    AllPanesPage(model.paneCatalogSources,model.activeServerId,displayedSession,chosen,back,model::refreshPaneCatalog) {target->
                        model.openCatalogPane(target).also {opened->if(opened) picker=""}
                    }
                })
        },openSessions=picker=="sessions")
    dockFloatingPane?.let {id->
        if(window!=null && window.layout.any {it.id==id && it.floating && !it.minimized}) {
            DockPaneSheet(window,state.programs,state.titles,ready,workspaceHaze,
                onDock={anchor,split->client.editTerminal(displayedSession,window.id,id,"dock_pane",anchor=anchor,split=split)},
                onClose={dockFloatingPane=null})
        }
    }
    LaunchedEffect(window?.layout,dockFloatingPane) {
        if(window?.layout?.any {it.id==dockFloatingPane && it.floating && !it.minimized}!=true) dockFloatingPane=null
    }
    closeTarget?.let {target->
        AlertDialog(onDismissRequest={closeTarget=null},title={Text(if(target.tab) "Close tab?" else "Close pane?")},
            text={Text(if(state.supportsUndoClose) "Close “${target.label}”? Rex can keep the running programs available briefly for Undo."
                else if(target.tab) "Close “${target.label}”? This ends every program in its ${target.blocks.size} pane${if(target.blocks.size==1) "" else "s"}."
                else "Close “${target.label}”? This ends the program running in this pane.")},
            confirmButton={TextButton(enabled=ready,onClick={closeTarget=null
                menu=false
                if(target.tab) client.closeTab(target.session,target.id,target.blocks) else client.closePane(target.session,target.id)
            }) {Text("Close",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={closeTarget=null}) {Text("Cancel")}})
    }
    if(appearance) AppearanceSheet(model.appearance,model.themes,model.theme(false),model.theme(true),model::changeAppearance,model::chooseTheme,hazeState=workspaceHaze) {appearance=false}
    if(fontPicker) FontSizeDialog(model.fontSizes,tabWidth.first,model::changeFontSize,hazeState=workspaceHaze) {fontPicker=false}
    if(newSession) NewSessionDialog(model.activeServer.label,ready,client::createSession) {newSession=false}

}

@Composable private fun WorkspaceBottomBar(
    model: RexModel,client: RexConnection,state: AppState,windows: List<WindowItem>,activeWindowId: String,session: String,
    sideTabs: Boolean,extendUnderNav: Boolean,changingSession: Boolean,animateWorking: Boolean,tabAttention: Map<String,Int>,
    controlled: Boolean,ready: Boolean,keyboardVisible: Boolean,keyTarget: String,toolbarHeight: IntArray,
    onCloseTab: (WindowItem)->Unit,onPaste: ()->Boolean,onKeyboard: ()->Unit,actions: @Composable ()->Unit,
) {
    val navigation=WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
    val toolbarCoversNavigation=!model.toolbarWithKeyboardOnly && !extendUnderNav
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .then(if(extendUnderNav || toolbarCoversNavigation) Modifier else Modifier.windowInsetsPadding(navigation))) {
        if(!sideTabs) TerminalTabs(windows,activeWindowId,state.connected && !state.creating && !changingSession,client::selectWindow,
            panePrograms=client::tabPrograms,selectedPane=client::tabPaneIndex,
            paneTitle={window,pane->state.titles[window.blocks.getOrNull(pane)?.id].orEmpty()},
            paneWorking={window->window.blocks.any {it.id in state.workingBlocks}},
            onClose=onCloseTab,onMove=client::moveTab,reorderKey=session,animateWorking=animateWorking,
            tabAttention={window->tabAttention[window.id] ?: 0},paneProgress={window->
                state.terminalProgress[window.blocks.getOrNull(client.tabPaneIndex(window))?.id]
                    ?: window.blocks.firstNotNullOfOrNull {state.terminalProgress[it.id]}
            },contentReady=client.hasScreen && !state.creating && !changingSession && windows.isNotEmpty() && windows.all {window->
                val block=window.blocks.getOrNull(client.tabPaneIndex(window))
                block!=null && state.programs[block.id]?.pending==false
            },onNewTab=client::createTab,actions=actions)
        Box(Modifier.fillMaxWidth().then(if(model.toolbarWithKeyboardOnly)
            Modifier.keyboardRevealed(WindowInsets.ime,WindowInsets.navigationBars) {toolbarHeight[0]=it} else Modifier)) {
            KeyboardToolbarTheme(model.toolbarSystemColors,coverNavigation=toolbarCoversNavigation) {
                KeyboardBar(controlled,ready,client.controlModifier,client.altModifier,client.shiftModifier,keyboardVisible,
                    client::toggleControl,client::toggleAlt,client::toggleShift,client::pressShift,client::releaseShift,
                    {client.keyForTarget(it,keyTarget)},readable=state.connected,onControlShortcut={client.controlShortcut(it,keyTarget)},
                    inputKey=keyTarget,shortcutGesture=model.shortcutGesture,arrowJoystickEnabled=model.arrowJoystickEnabled,onPaste={onPaste()},
                    onPrompt=if(client.hasSemanticPrompts && !changingSession) client::scrollPrompt else null,
                    toolbarKeys=model.toolbarKeys,controlItems=model.controlItems,showTopDivider=sideTabs,
                    onKeyboard=onKeyboard.takeUnless {sideTabs})
            }
        }
    }
}

@Composable private fun AppGestureBarContrast(useDarkHandle: Boolean) {
    val window=LocalActivity.current?.window ?: return
    LaunchedEffect(window,useDarkHandle) {
        WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightNavigationBars=useDarkHandle
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun KeyboardToolbarTheme(
    enabled: Boolean,
    coverNavigation: Boolean=false,
    ime: WindowInsets=WindowInsets.ime,
    source: WindowInsets=WindowInsets.imeAnimationSource,
    target: WindowInsets=WindowInsets.imeAnimationTarget,
    navigation: WindowInsets=WindowInsets.navigationBars,
    content: @Composable ()->Unit,
) {
    val navigationPadding=if(coverNavigation) Modifier.windowInsetsPadding(navigation.only(WindowInsetsSides.Bottom)) else Modifier
    val appDark=LocalTerminalPalette.current.dark
    if(!enabled || Build.VERSION.SDK_INT<Build.VERSION_CODES.S) {
        if(coverNavigation) AppGestureBarContrast(!appDark)
        Box(navigationPadding) {content()}
        return
    }
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    val dark=isSystemInDarkTheme()
    val systemColors=remember(context,configuration,dark) {
        if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    val appColors=MaterialTheme.colorScheme
    val density=LocalDensity.current
    // Keep the fade in step with the keyboard's movement.
    val blend by remember(ime,source,target,navigation,density) {
        derivedStateOf {
            val nav=navigation.getBottom(density)
            val current=(ime.getBottom(density)-nav).coerceAtLeast(0)
            val before=(source.getBottom(density)-nav).coerceAtLeast(0)
            val after=(target.getBottom(density)-nav).coerceAtLeast(0)
            when {
                current==0 -> 0f
                before>0 && after>0 -> 1f
                else -> (current.toFloat()/maxOf(1,before,after)).coerceAtMost(1f)
            }
        }
    }
    val toolbarColors=when(blend) {
        0f -> appColors
        1f -> systemColors
        else -> appColors.copy(
            surface=lerp(appColors.surface,systemColors.surface,blend),
            surfaceVariant=lerp(appColors.surfaceVariant,systemColors.surfaceVariant,blend),
            surfaceContainer=lerp(appColors.surfaceContainer,systemColors.surfaceContainer,blend),
            onSurface=lerp(appColors.onSurface,systemColors.onSurface,blend),
            onSurfaceVariant=lerp(appColors.onSurfaceVariant,systemColors.onSurfaceVariant,blend),
            primary=lerp(appColors.primary,systemColors.primary,blend),
            onPrimary=lerp(appColors.onPrimary,systemColors.onPrimary,blend),
            outline=lerp(appColors.outline,systemColors.outline,blend),
        )
    }
    val background=lerp(appColors.surface,systemColors.surfaceContainer,blend)
    if(coverNavigation) AppGestureBarContrast(if(blend==0f) !appDark else background.luminance()>.18f)
    MaterialTheme(colorScheme=toolbarColors) {
        Box(Modifier.background(background).then(navigationPadding)) {content()}
    }
}

private fun getClipboard(context: Context)=context.getSystemService(Context.CLIPBOARD_SERVICE)

/** Sessions grouped by server, with search, New session, rename and close. A page of [TabPaneSwitcher]. */
@Composable internal fun SessionsPage(targets: List<SessionTarget>,current: String,last: String,canCreate: Boolean,closing: String,error: String,onCloseSession: (SessionTarget)->Unit,onRenameSession: (SessionTarget,String)->Boolean,onSelect: (String)->Unit,onNew: ()->Unit,
    allPanes: (@Composable (onBack: ()->Unit)->Unit)?=null) {
    var search by rememberSaveable {mutableStateOf("")}
    var showAllPanes by rememberSaveable {mutableStateOf(false)}
    if(showAllPanes && allPanes!=null) {
        BackHandler {showAllPanes=false}
        allPanes {showAllPanes=false}
        return
    }
    var closeTarget by remember {mutableStateOf<SessionTarget?>(null)}
    var renameTarget by remember {mutableStateOf<SessionTarget?>(null)}
    var revealedRow by remember(search) {mutableStateOf<String?>(null)}
    val groups=remember(targets,current,search) {SessionCatalog.groups(targets,current,search)}
    val scheme=MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            SwitcherSearchField(search,{search=it},"Find session…",Modifier.weight(1f))
            if(allPanes!=null) TextButton(onClick={showAllPanes=true},contentPadding=PaddingValues(horizontal=6.dp)) {
                Text("All panes",style=SettingsType.paneDetail,maxLines=1)
            }
            SwitcherAddButton("New session",canCreate,onNew)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(groups.isEmpty()) item {Text(if(targets.isEmpty()) "No sessions yet" else "No matching sessions",Modifier.padding(vertical=16.dp),style=SettingsType.paneTitle,color=scheme.onSurfaceVariant)}
            groups.forEach {group->
                item(key="server:${group.server.id}") {
                    Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        ServerGlyph(group.server.icon,Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(group.server.label,Modifier.weight(1f),style=SettingsType.section,color=scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(group.sessions.size.toString(),style=SettingsType.paneDetail,color=scheme.onSurfaceVariant)
                    }
                }
                items(group.sessions,key={it.key}) {target->
                    SessionRow(target,current==target.key,last==target.key,closing==target.key,
                        onSelect={onSelect(target.key)},onClose={closeTarget=target},onRename={renameTarget=target},
                        revealed=revealedRow==target.key,onReveal={open->
                            if(open) revealedRow=target.key else if(revealedRow==target.key) revealedRow=null
                        })
                }
            }
        }
        if(error.isNotBlank()) Text(error,Modifier.padding(horizontal=16.dp,vertical=4.dp),color=scheme.error,style=SettingsType.paneDetail)
    }
    renameTarget?.let {target->
        SessionRenameDialog(target,closing.isBlank(),onSave={label->if(onRenameSession(target,label)) renameTarget=null},onDismiss={renameTarget=null})
    }
    closeTarget?.let {target->
        AlertDialog(onDismissRequest={closeTarget=null},title={Text("Close session?")},
            text={Text("Close “${target.session.label}” on ${target.server.label}? All its tabs and panes will close, ending the programs running in them.")},
            confirmButton={TextButton(onClick={closeTarget=null;onCloseSession(target)},enabled=closing.isBlank()) {
                Text("Close session",color=MaterialTheme.colorScheme.error)
            }},dismissButton={TextButton(onClick={closeTarget=null}){Text("Cancel")}})
    }

}

@Composable private fun LiveTerminal(client: RexConnection,blocks: List<BlockItem>,chosen: String,liveBlock: String,
    programs: Map<String,ProgramIdentity>,controlled: Boolean,fontSize: Float,
    onInput: (String)->Unit,onScroll: (Int)->Unit,onWheel: (Int,Int,Int,Int,Int)->Unit,onActivate: (Boolean)->Unit,onBottom: ()->Unit,onDimensions: (Int,Int)->Unit,
    onSelect: (String)->Unit,onPanes: ()->Unit,onView: (TerminalView)->Unit,modifier: Modifier=Modifier,
    floatingDock: Boolean=false,paneLayout: List<PanePlacement> = emptyList(),titles: Map<String,String> = emptyMap(),directories: Map<String,String> = emptyMap(),hazeState: HazeState?=null,captureBackdrop: Boolean=false,onImage: (android.net.Uri,()->Unit)->Boolean={_,release->release();false},onPaste: ()->Boolean={false},imeEnterSubmits: Boolean=false,keyboardChrome: ()->Int={0},squareCorners: Boolean=false,extendsUnderNavigation: Boolean=false,
    navigationClearance: Dp=0.dp,bottomOverlayInset: Dp=0.dp,headerHeightSaved: ()->Int={0},headerOverlap: ()->Int={0},floatingMotion: FloatingPaneMotion?=null,swipeBackdrop: PaneSwipeBackdrop?=null) {
    val layout by client.displayLayout.collectAsState()
    val screen=client.screenForLayout(layout)
    TerminalCarousel(blocks,chosen,liveBlock,screen,{client.paneFrames[it]},programs,controlled,fontSize,
        onInput,onScroll,onWheel,onActivate,onBottom,onDimensions,onSelect,onPanes,onView,modifier,client::bindTerminalView,onMouseClick=client::click,mouseTarget=client::keyTarget,onImage=onImage,onPaste=onPaste,imageTarget=client::imageInputTarget,bottomControlsInset=if(floatingDock) 56.dp else 0.dp,paneLayout=paneLayout,titles=titles,directories=directories,hazeState=hazeState,captureBackdrop=captureBackdrop,imeEnterSubmits=imeEnterSubmits,keyboardChrome=keyboardChrome,squareCorners=squareCorners,extendsUnderNavigation=extendsUnderNavigation,
        navigationClearance=navigationClearance,bottomOverlayInset=bottomOverlayInset,headerHeightSaved=headerHeightSaved,headerOverlap=headerOverlap,floatingMotion=floatingMotion,swipeBackdrop=swipeBackdrop)
}

@Composable private fun BackgroundAlertsDialog(model: RexModel,hazeState: HazeState?=null,onClose: ()->Unit) {
    val context=LocalContext.current
    var denied by remember {mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) {model.setBackgroundAlerts(true);denied=false} else denied=true
    }
    SettingsDrawer("Background alerts","Close background alerts",onClose,hazeState) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Get notified when a task finishes, needs input, approval, sign-in, or fails while Rexling is in the background or your phone is locked.",style=SettingsType.body)
            Text("A quiet monitoring notification stays visible. Stop it there, or turn this off. Closing Rexling stops monitoring.",style=SettingsType.body)
            Text("Your phone may delay alerts while sleeping.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Notify about task updates",Modifier.weight(1f),style=SettingsType.row)
                Switch(checked=model.backgroundAlerts,onCheckedChange={enabled->
                    if(!enabled) model.setBackgroundAlerts(false)
                    else if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if(BackgroundAlerts.allowed(context)) {model.setBackgroundAlerts(true);denied=false} else denied=true
                },enabled=model.servers.any {model.serverStatuses[it.id]?.maintained==true})
            }
            if(model.servers.none {model.serverStatuses[it.id]?.maintained==true}) Text("Connect a server to enable alerts.",style=SettingsType.detail)
            if(denied || (model.backgroundAlerts && !BackgroundAlerts.allowed(context))) {
                Text("Allow notifications in Android settings to receive alerts.",style=SettingsType.detail)
                TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text("Notification settings")}
            }
        }
            TextButton(onClick=onClose,modifier=Modifier.align(Alignment.End)) {Text("Done")}
    }
}
