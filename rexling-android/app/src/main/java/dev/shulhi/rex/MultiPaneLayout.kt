package dev.shulhi.rex

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.util.Consumer

internal enum class PaneOrientation(val title: String,val preferenceKey: String) {
    PORTRAIT("Portrait","multi_pane_unfolded_portrait"),
    LANDSCAPE("Landscape","multi_pane_unfolded_landscape"),
}

@Composable internal fun currentPaneOrientation(): PaneOrientation =
    if(LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE) PaneOrientation.LANDSCAPE
    else PaneOrientation.PORTRAIT

internal enum class PaneDropPosition {
    CENTER, LEFT, RIGHT, ABOVE, BELOW;

    fun preview(size: Size): Rect = when(this) {
        CENTER -> Rect(0f,0f,size.width,size.height)
        LEFT -> Rect(0f,0f,size.width/2,size.height)
        RIGHT -> Rect(size.width/2,0f,size.width,size.height)
        ABOVE -> Rect(0f,0f,size.width,size.height/2)
        BELOW -> Rect(0f,size.height/2,size.width,size.height)
    }
}

internal fun paneDropPosition(bounds: Rect,point: Offset): PaneDropPosition? {
    if(!bounds.contains(point)) return null
    val x=(point.x-bounds.left)/bounds.width
    val y=(point.y-bounds.top)/bounds.height
    val horizontal=minOf(x,1f-x)
    val vertical=minOf(y,1f-y)
    return when {
        minOf(horizontal,vertical)>=.25f -> PaneDropPosition.CENTER
        horizontal<=vertical -> if(x<.5f) PaneDropPosition.LEFT else PaneDropPosition.RIGHT
        else -> if(y<.5f) PaneDropPosition.ABOVE else PaneDropPosition.BELOW
    }
}

private data class PaneDrop(val target: String,val position: PaneDropPosition)
private data class PaneMove(val source: String,val drop: PaneDrop?=null)
private data class PaneResize(val id: String,val ratio: Float,val active: Boolean=true)

/** Server layout with one mounted terminal view per visible pane. */
@Composable
internal fun MultiPaneLayout(
    client: RexConnection, session: String, window: String, placements: List<PanePlacement>,
    focused: String, liveBlock: String, controlled: Boolean, programs: Map<String, ProgramIdentity>,
    waiting: Set<String>, fontSize: Float, onFocus: (String, Pair<Int,Int>?) -> Unit,
    onView: (TerminalView) -> Unit, onImage: (android.net.Uri, () -> Unit) -> Boolean,
    onPaste: () -> Boolean, imeEnterSubmits: Boolean,
    keyboardChrome: () -> Int, extendsUnderNavigation: Boolean, hazeState: HazeState, captureBackdrop: Boolean,
    bottomPaddingDp: Int = 6, navigationClearance: Dp = 0.dp,
    splits: List<PaneSplitLine> = emptyList(), zoomed: String = "",
    ime: WindowInsets = WindowInsets.ime, navigation: WindowInsets = WindowInsets.navigationBars,
    headerHeightSaved: () -> Int = {0},headerOverlap: () -> Int = {0},
    paneActionsEnabled: Boolean = true,
    floatingPanes: List<PanePlacement> = emptyList(),
    allowFloat: Boolean = false,
    onMinimizeFloating: (String)->Unit = {},
    onDockFloating: (String)->Unit = {},
    onClosePane: ((String)->Unit)? = null,
    onFloat: (String)->Unit = {id->client.editTerminal(session,window,id,"float_pane",focus=true)},
    onFloatingBounds: (PanePlacement,PaneBounds)->Boolean = {pane,bounds->client.resizeFloating(session,window,pane,bounds)},
    onSplit: (String,PaneSplit)->Unit = {id,split->client.createPane(split,id,keepTiled=floatingPanes.any {it.id==id})},
    onDrop: (String,String,PaneDropPosition)->Unit = {source,target,position->
        client.dropPane(session,window,source,target,position,placements.map {it.id})
    },
    floatingMotion: FloatingPaneMotion?=null,
) {
    val palette=LocalTerminalPalette.current
    val scheme=MaterialTheme.colorScheme
    // Rects are fractions of the tab; normalize to the tiled panes' own bounds.
    val left=placements.minOf {it.x};val top=placements.minOf {it.y}
    val spanX=(placements.maxOf {it.x+it.width}-left).coerceAtLeast(.01f)
    val spanY=(placements.maxOf {it.y+it.height}-top).coerceAtLeast(.01f)
    val sizes=remember(session,window) { mutableStateMapOf<String,Pair<Int,Int>>() }
    val density=LocalDensity.current
    val keepAwake=LocalKeepScreenAwake.current
    // A pane zoomed in Rex covers the whole area; the rest stay in the layout but are not shown or mirrored.
    val zoomedPane=placements.firstOrNull {it.id==zoomed}
    val zoomedFloat=floatingPanes.firstOrNull {it.id==zoomed}
    var menuFor by remember(session,window) {mutableStateOf<String?>(null)}
    val selectedFloat=floatingPanes.firstOrNull {it.id==menuFor} ?: floatingPanes.firstOrNull {it.id==focused}
    val originalBounds=selectedFloat?.layerBounds
    var draftBounds by remember(session,window,selectedFloat?.layerId,originalBounds,zoomed) {mutableStateOf<PaneBounds?>(null)}
    var adjustingFloat by remember(session,window,selectedFloat?.layerId,zoomed) {mutableStateOf(false)}
    val layerBounds=draftBounds ?: originalBounds
    val shownFloating=when {
        zoomedPane!=null -> emptyList()
        zoomedFloat!=null -> listOf(zoomedFloat.copy(x=0f,y=0f,width=1f,height=1f))
        draftBounds!=null && originalBounds!=null -> floatingPanes.map {
            if(it.layer==selectedFloat.layer) draftBounds!!.positionIn(originalBounds,it) else it
        }
        else -> floatingPanes
    }
    val floatingLayers=shownFloating.groupBy {it.layer}
    fun layerZ(layer: Int)=layer.coerceAtLeast(1)*3f
    val mirrorTiles=if(zoomedFloat!=null) emptyList() else zoomedPane?.let {listOf(it)} ?: placements
    val mirrors=(mirrorTiles+shownFloating).map {it.id}.filter {it!=liveBlock}
    val request=mirrors.mapNotNull {id->sizes[id]?.let {(columns,rows)->RexConnection.MirrorPane(id,columns,rows)}}
    // Debounced: while a border is dragged the panes resize on every frame, but their PTYs only once
    // the finger pauses or lifts (the focused pane's resize is debounced the same way).
    LaunchedEffect(client,session,window,request) {
        kotlinx.coroutines.delay(100)
        client.mirror(session,window,request)
    }
    // A server layout update may acknowledge an earlier resize while a new drag is underway.
    var splitResize by remember(session,window,zoomed) {mutableStateOf<PaneResize?>(null)}
    val pendingResize=splitResize?.takeUnless {it.active}
    LaunchedEffect(splits,pendingResize) {
        if(pendingResize!=null) {
            val serverSplit=splits.firstOrNull {it.id==pendingResize.id}
            if(serverSplit!=null && kotlin.math.abs(serverSplit.ratio-pendingResize.ratio)>.001f) {
                kotlinx.coroutines.delay(2000)
            }
            if(splitResize===pendingResize) splitResize=null
        }
    }
    val (laidOut,laidOutSplits)=splitResize?.let {(id,ratio)->splits.firstOrNull {it.id==id}?.let {movedSplit(placements,splits,it,ratio)}}
        ?: (placements to splits)
    val shownTiles=if(zoomedFloat!=null) emptyList() else
        zoomedPane?.let {listOf(it.copy(x=left,y=top,width=spanX,height=spanY))} ?: laidOut
    val shownSplits=if(zoomedPane!=null || zoomedFloat!=null) emptyList() else laidOutSplits
    val imeHandoff=remember {TerminalImeHandoff(PanePlacement::id)}
    val shownPanes=shownTiles+shownFloating
    val showFocusOutline=shownPanes.size>1
    val outgoingEditor=imeHandoff.retainFor(shownPanes)
    val mountedPanes=shownPanes+listOfNotNull(outgoingEditor?.pane)
    // Keep the controls visible for as long as the move handle is held.
    var move by remember(session,window,placements,zoomed,paneActionsEnabled) {mutableStateOf<PaneMove?>(null)}
    val moving=move!=null || adjustingFloat
    LaunchedEffect(draftBounds,adjustingFloat) {
        if(draftBounds!=null && !adjustingFloat) {
            kotlinx.coroutines.delay(2000)
            draftBounds=null
        }
    }
    LaunchedEffect(menuFor,moving) {
        if(menuFor!=null && !moving) {
            kotlinx.coroutines.delay(4000)
            menuFor=null
        }
    }
    val paneCoordinates=remember(session,window) {mutableMapOf<String,LayoutCoordinates>()}
    val dropPane by rememberUpdatedState(onDrop)
    fun completeMove(source: String,drop: PaneDrop) {
        menuFor=null
        dropPane(source,drop.target,drop.position)
    }
    DisposableEffect(client,session,window) {onDispose {client.mirror(session,window,emptyList())}}
    // Like the desktop: each pane a rounded card on the window background, with room between them and
    // a thin accent outline on the focused one.
    val shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val paneInset=2.dp
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().then(if(captureBackdrop) Modifier.hazeSource(hazeState) else Modifier)
        .background(scheme.background).padding(paneInset)) {
        val visibleHeight=maxHeight
        val canAdjustFloat=paneActionsEnabled && selectedFloat?.layerId?.isNotBlank()==true && originalBounds!=null && zoomedFloat==null
        val viewportWidth=with(density) {maxWidth.toPx()}.coerceAtLeast(1f)
        val viewportHeight=with(density) {visibleHeight.toPx()}.coerceAtLeast(1f)
        fun adjustFloat(delta: Offset,resize: Boolean) {
            val bounds=draftBounds ?: originalBounds ?: return
            adjustingFloat=true
            val dx=delta.x/viewportWidth;val dy=delta.y/viewportHeight
            draftBounds=if(resize) bounds.resize(dx,dy,120.dp/maxWidth,100.dp/visibleHeight) else bounds.move(dx,dy)
        }
        fun cancelFloatAdjustment() {adjustingFloat=false;draftBounds=null}
        fun finishFloatAdjustment() {
            adjustingFloat=false
            val bounds=draftBounds
            if(bounds==null || selectedFloat==null || bounds==originalBounds || !onFloatingBounds(selectedFloat,bounds)) draftBounds=null
        }
        if(zoomedFloat==null) for((layer,panes) in floatingLayers) key(layer) {
            val bounds=if(layer==selectedFloat?.layer) layerBounds else panes.first().layerBounds
            val restoringSinglePane=floatingMotion?.restoring?.let {it==panes.singleOrNull()?.id}==true
            if(bounds!=null && !restoringSinglePane) Box(Modifier.offset(maxWidth*bounds.x,visibleHeight*bounds.y)
                .size(maxWidth*bounds.width,visibleHeight*bounds.height).zIndex(layerZ(layer))
                .shadow(16.dp,shape,clip=false).background(scheme.background,shape))
        }
        for(pane in mountedPanes) key(pane.id) {
            DisposableEffect(pane.id,floatingMotion) {onDispose {imeHandoff.remove(pane.id);floatingMotion?.forget(pane.id)}}
            val retiring=outgoingEditor?.pane?.id==pane.id
            val floating=pane.floating
            val inset=if(floating && floatingLayers[pane.layer]?.size==1) 0.dp else paneInset
            val x=maxWidth*(if(floating) pane.x else (pane.x-left)/spanX)
            val y=visibleHeight*(if(floating) pane.y else (pane.y-top)/spanY)
            val width=maxWidth*(if(floating) pane.width else pane.width/spanX)
            val heightFraction=if(floating) pane.height else pane.height/spanY
            val height=visibleHeight*heightFraction
            val active=!retiring && pane.id==focused
            val dropPosition=move?.drop?.takeIf {it.target==pane.id}?.position
            val moveSource=move?.source==pane.id
            val trim=inset*2
            Box(Modifier.offset(x+inset,y+inset).size((width-inset*2).coerceAtLeast(0.dp),(height-trim).coerceAtLeast(0.dp))
                .then(if(floating && !retiring) Modifier.floatingPaneViewport(pane.id,floatingMotion) else Modifier)
                .then(if(retiring) Modifier.alpha(0f) else if(floating) Modifier.zIndex(layerZ(pane.layer)+1f)
                    .then(if((pane.layerBounds==null || floatingMotion?.restoring==pane.id) && zoomedFloat==null) Modifier.shadow(16.dp,shape,clip=false) else Modifier)
                    .testTag("Floating terminal pane") else Modifier)
                .onGloballyPositioned {if(!retiring) paneCoordinates[pane.id]=it}
                .clip(shape).background(Color(palette.background))
                .drawWithContent {
                    drawContent()
                    if(dropPosition!=null) {
                        val preview=dropPosition.preview(size)
                        val edge=1.5.dp.toPx()
                        if(preview.width>edge*2 && preview.height>edge*2) {
                            val origin=preview.topLeft+Offset(edge,edge)
                            val extent=Size(preview.width-edge*2,preview.height-edge*2)
                            val radius=CornerRadius(8.dp.toPx())
                            drawRoundRect(scheme.primary.copy(alpha=.22f),origin,extent,radius)
                            drawRoundRect(scheme.primary,origin,extent,radius,style=Stroke(edge))
                        }
                    }
                }
                // Keep the border and padding fixed while moving so the PTY grid stays the same.
                .border(1.5.dp,when {
                    showFocusOutline && (moveSource || active) -> scheme.primary
                    else -> scheme.outline.copy(alpha=.25f)
                },shape)
                .padding(1.5.dp)) {
                val live=active && pane.id==liveBlock
                val paneFrame by remember(client,pane.id) {
                    derivedStateOf(referentialEqualityPolicy()) { client.paneFrames[pane.id] }
                }
                var keyboardFocus by remember { mutableStateOf<TerminalKeyboardFocus?>(null) }
                val keyboardPan=remember(live) {TerminalImePanState()}
                val reportSize by rememberUpdatedState<(Int,Int)->Unit> { columns,rows->
                    sizes[pane.id]=columns to rows
                    if(live) client.resize(columns,rows)
                }
                val dimensions=remember { {columns: Int,rows: Int->reportSize(columns,rows)} }
                LaunchedEffect(live) {
                    if(live) sizes[pane.id]?.let {(columns,rows)->client.resize(columns,rows)}
                }
                // Restore the portion of keyboard coverage this pane lost when the parent shrank.
                // Round against both container heights so the PTY grid stays fixed at row boundaries.
                AndroidView(
                    factory={TerminalView(it)},
                    modifier=Modifier.fillMaxSize().clipToBounds().stableTerminalViewport(panToCursor=live,coverage={
                        // The parent releases its navigation clearance as imePadding consumes the inset.
                        val reclaimed=minOf(navigationClearance.roundToPx(),ime.getBottom(this))
                        val cover=(keyboardCoverage(ime,navigation,keyboardChrome,extendsUnderNavigation,headerHeightSaved)-reclaimed).coerceAtLeast(0)
                        val fullHeight=visibleHeight+cover.toDp()
                        (fullHeight*heightFraction-trim).coerceAtLeast(0.dp).roundToPx() -
                            (height-trim).coerceAtLeast(0.dp).roundToPx()
                    },topOcclusion={headerOverlap()-(paneInset+y+inset+1.5.dp).roundToPx()},pan=keyboardPan) {keyboardFocus.takeIf {live}},
                    update={view->
                        if(floating && !retiring) floatingMotion?.trackView(pane.id,view)
                        imeHandoff.mount(pane,view)
                        view.onInputConnectionReady={if(live) imeHandoff.served(pane.id,view)}
                        view.importantForAccessibility=if(retiring) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        view.palette=palette
                        view.textSp=fontSize
                        view.setTerminalPaddingDp(12,8,if(floating) 8 else bottomPaddingDp)
                        view.interactive=live
                        view.keepAwakeMode=if(live) keepAwake else KeepScreenAwake.SYSTEM
                        view.keyboardSuggestions=programs[pane.id]?.kind!=ProgramKind.NEOVIM
                        view.imeEnterSubmits=imeEnterSubmits
                        view.controlled=live && controlled
                        view.onDimensions=dimensions
                        view.onKeyboardFocus=if(live) ({keyboardFocus=it}) else null
                        keyboardFocus=if(live) view.keyboardFocus else null
                        view.keyboardPan=keyboardPan.takeIf {live}
                        view.onInput=client::type
                        view.onActivate=client::takeControl
                        view.onMouseClick=client::click
                        view.onWheel=client::wheel
                        view.onScroll=client::scroll
                        view.mouseTarget=client::keyTarget
                        view.imageInputTarget=client::imageInputTarget
                        view.onImage=onImage
                        view.onPaste=onPaste
                        if(!retiring && (!live || !client.bindTerminalView(pane.id,view))) {
                            if(!live || view.screen==null) paneFrame?.let {view.screen=it}
                        }
                        if(live) onView(view)
                    },
                )
                if(retiring) return@Box
                if(!active) {
                    Box(Modifier.matchParentSize().semantics {contentDescription="Focus pane"}
                        .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button) {
                            onFocus(pane.id,sizes[pane.id])
                        })
                    if(pane.id in waiting || (paneFrame==null && pane.id!=liveBlock)) Text(
                        if(paneFrame==null) "Opening terminal…" else "Reconnecting…",
                        Modifier.align(Alignment.Center).background(scheme.surface).padding(6.dp),
                        color=scheme.onSurfaceVariant,fontSize=11.sp,
                    )
                } else {
                    val display by client.displayLayout.collectAsState()
                    if(live && display.below) {
                        ScrollToLatestButton(hazeState,Modifier.align(Alignment.BottomEnd).padding(10.dp),onClick=client::scrollToBottom)
                    } else if(paneFrame==null && (!live || !display.hasScreen)) {
                        Text("Opening terminal…",Modifier.align(Alignment.Center),
                            fontFamily=MonoUi,fontSize=12.sp,color=scheme.onSurfaceVariant)
                    }
                }
                val isZoomed=pane.id==zoomed
                val showActions=menuFor==pane.id
                if(isZoomed || showActions) {
                    val splitPane: (PaneSplit)->Unit={split->
                        menuFor=null
                        onSplit(pane.id,split)
                    }
                    val zoomPane: ()->Unit={
                        menuFor=null
                        if(!isZoomed && !active) onFocus(pane.id,sizes[pane.id])
                        client.zoom(pane.id,!isZoomed)
                    }
                    PaneCornerActions(
                        Modifier.align(Alignment.TopEnd).padding(6.dp),
                        zoomed=isZoomed,paneWidth=width-inset*2,
                        enabled=paneActionsEnabled && !moving,
                        onSplit=splitPane,onZoom=zoomPane,
                        onFloat=if(allowFloat && !floating) ({menuFor=null;onFloat(pane.id)}) else null,
                        onDock=if(floating) ({menuFor=null;onDockFloating(pane.id)}) else null,
                        onMinimize=if(floating) ({menuFor=null;onMinimizeFloating(pane.id)}) else null,
                        onClose=onClosePane?.let {close-> {menuFor=null;close(pane.id)}},
                    )
                    if(floating && canAdjustFloat) {
                        FloatingLayerGrip(
                            ChromeIcon.MOVE,"Move floating pane",Modifier.align(Alignment.TopStart).padding(6.dp),
                            onAdjust={adjustFloat(it,false)},onFinish={finishFloatAdjustment()},onCancel={cancelFloatAdjustment()},
                        )
                    } else if(!floating && !isZoomed && paneActionsEnabled && shownTiles.size>1) {
                        val handleCoordinates=remember {arrayOfNulls<LayoutCoordinates>(1)}
                        val otherPanes=shownTiles.filter {it.id!=pane.id}
                        val targetActions=otherPanes.map {other->
                            CustomAccessibilityAction("Swap with pane ${shownTiles.indexOf(other)+1}") {
                                completeMove(pane.id,PaneDrop(other.id,PaneDropPosition.CENTER))
                                true
                            }
                        }
                        PaneActionPill(Modifier.align(Alignment.TopStart).padding(6.dp),active=moveSource) {
                            Box(Modifier.size(36.dp)
                                .onGloballyPositioned {handleCoordinates[0]=it}
                                .semantics {
                                    contentDescription="Move pane"
                                    if(moveSource) stateDescription="Moving"
                                    customActions=targetActions
                                }
                                .pointerInput(session,window,placements,pane.id) {
                                    detectDragGestures(
                                        onDragStart={move=PaneMove(pane.id)},
                                        onDragCancel={move=null},
                                        onDragEnd={
                                            val drop=move?.drop
                                            move=null
                                            if(drop!=null) completeMove(pane.id,drop)
                                        },
                                    ) {change,_->
                                        change.consume()
                                        val point=handleCoordinates[0]?.takeIf {it.isAttached}?.localToWindow(change.position)
                                        val drop=point?.let {position->
                                            val overFloating=shownFloating.any {floatingPane->
                                                paneCoordinates[floatingPane.id]?.takeIf {it.isAttached}
                                                    ?.boundsInWindow()?.contains(position)==true
                                            }
                                            if(overFloating) null else otherPanes.firstNotNullOfOrNull {other->
                                                val bounds=paneCoordinates[other.id]?.takeIf {it.isAttached}?.boundsInWindow()
                                                bounds?.let {paneDropPosition(it,position)}?.let {PaneDrop(other.id,it)}
                                            }
                                        }
                                        move=PaneMove(pane.id,drop)
                                    }
                                },contentAlignment=Alignment.Center) {
                                ChromeSymbol(ChromeIcon.MOVE)
                            }
                        }
                    }
                } else {
                    for(corner in listOf(Alignment.TopStart,Alignment.TopEnd)) {
                        Box(Modifier.align(corner).size(44.dp)
                            .semantics {contentDescription=if(corner==Alignment.TopStart) "Pane actions, top left" else "Pane actions"}
                            .clickable(enabled=!moving,interactionSource=remember {MutableInteractionSource()},
                                indication=null,role=Role.Button) {menuFor=pane.id})
                    }
                }
            }
        }
        val resizePane=selectedFloat?.takeIf {canAdjustFloat && it.id==menuFor}
        if(resizePane!=null && layerBounds!=null) {
            val grip=36.dp
            val gripTop=(visibleHeight*(layerBounds.y+layerBounds.height)-grip-5.dp)
                .coerceAtMost(visibleHeight-grip-34.dp)
            FloatingLayerGrip(
                ChromeIcon.RESIZE,"Resize floating pane",
                Modifier.offset(maxWidth*(layerBounds.x+layerBounds.width)-grip-5.dp,gripTop).zIndex(layerZ(resizePane.layer)+2f),
                onAdjust={adjustFloat(it,true)},onFinish={finishFloatAdjustment()},onCancel={cancelFloatAdjustment()},
            )
        }
        // Pane borders: drag the grip in the gap and the panes reflow with it; on release Rex moves the
        // border for every client.
        // Ancestor dividers keep their grab area where a nested divider meets them.
        for(split in shownSplits.asReversed()) key(split.id) {
            val original=splits.firstOrNull {it.id==split.id} ?: split
            val currentSplit by rememberUpdatedState(original)
            val dragging=splitResize?.let {it.id==split.id && it.active}==true
            val shown=split.ratio
            val horizontal=split.horizontal
            val startX=maxWidth*((split.x-left)/spanX);val startY=maxHeight*((split.y-top)/spanY)
            val spanW=maxWidth*(split.width/spanX);val spanH=maxHeight*(split.height/spanY)
            val along=if(horizontal) spanW else spanH
            val alongPx by rememberUpdatedState(with(density) {along.toPx()}.coerceAtLeast(1f))
            val touch=24.dp
            val handle=if(horizontal) Modifier.offset(startX+spanW*shown-touch/2,startY).size(touch,spanH)
                else Modifier.offset(startX,startY+spanH*shown-touch/2).size(spanW,touch)
            Box(handle.semantics {contentDescription=if(horizontal) "Resize panes side by side" else "Resize stacked panes"}
                .pointerInput(client,session,window,split.id,horizontal) {
                    var previous: PaneResize?=null
                    val start: (Offset)->Unit={
                        previous=splitResize
                        val shownRatio=previous?.takeIf {it.id==split.id}?.ratio ?: currentSplit.ratio
                        splitResize=PaneResize(split.id,shownRatio)
                    }
                    val end: ()->Unit={
                        splitResize?.takeIf {it.id==split.id && it.active}?.let {resize->
                            splitResize=resize.copy(active=false)
                            client.resizeSplit(currentSplit,resize.ratio)
                        }
                    }
                    val cancel: ()->Unit={splitResize=previous}
                    val adjust: (androidx.compose.ui.input.pointer.PointerInputChange,Float)->Unit={change,amount->
                        change.consume()
                        splitResize?.takeIf {it.id==split.id && it.active}?.let {resize->
                            splitResize=resize.copy(ratio=(resize.ratio+amount/alongPx).coerceIn(.1f,.9f))
                        }
                    }
                    if(horizontal) detectHorizontalDragGestures(start,end,cancel,adjust)
                    else detectVerticalDragGestures(start,end,cancel,adjust)
                },contentAlignment=androidx.compose.ui.Alignment.Center) {
                Box(Modifier.background(if(dragging) scheme.primary else scheme.outline.copy(alpha=.5f),androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .then(if(horizontal) Modifier.size(2.dp,36.dp) else Modifier.size(36.dp,2.dp)))
            }
        }
    }
}

@Composable
internal fun PaneCornerActions(
    modifier: Modifier,zoomed: Boolean=false,paneWidth: Dp?=null,enabled: Boolean,
    onSplit: (PaneSplit)->Unit,onZoom: (()->Unit)?=null,
    onFloat: (()->Unit)?=null,onDock: (()->Unit)?=null,onMinimize: (()->Unit)?=null,onClose: (()->Unit)?=null,
) {
    val actions=buildList {
        if(!zoomed) {
            add(PaneCornerAction(ChromeIcon.SPLIT_RIGHT,PaneSplit.RIGHT.label) {onSplit(PaneSplit.RIGHT)})
            add(PaneCornerAction(ChromeIcon.SPLIT_BELOW,PaneSplit.BELOW.label) {onSplit(PaneSplit.BELOW)})
            onFloat?.let {add(PaneCornerAction(ChromeIcon.FLOAT,"Float pane",it))}
            onDock?.let {add(PaneCornerAction(ChromeIcon.FLOAT,"Dock pane",it))}
        }
        onZoom?.let {add(PaneCornerAction(if(zoomed) ChromeIcon.UNZOOM else ChromeIcon.ZOOM,
            if(zoomed) "Restore pane layout" else "Zoom pane",it))}
        onMinimize?.let {add(PaneCornerAction(ChromeIcon.MINIMIZE,"Minimize floating pane",it))}
        onClose?.let {add(PaneCornerAction(ChromeIcon.CLOSE,"Close pane",it))}
    }
    if(!zoomed && paneWidth!=null && paneWidth<60.dp+36.dp*actions.size) {
        // Leave room for the move handle on narrow tiles.
        var expanded by remember {mutableStateOf(false)}
        Box(modifier) {
            PaneActionPill(Modifier) {
                ChromeButton(ChromeIcon.MORE,"More pane actions",enabled=enabled) {expanded=true}
            }
            DropdownMenu(expanded=expanded && enabled,onDismissRequest={expanded=false}) {
                for(action in actions) {
                    TerminalMenuItem(action.label,action.icon,destructive=action.icon==ChromeIcon.CLOSE,
                        onClick={expanded=false;action.run()})
                }
            }
        }
    } else {
        PaneActionPill(modifier) {
            for(action in actions) {
                ChromeButton(action.icon,action.label,enabled=enabled,onClick=action.run)
            }
        }
    }
}

private data class PaneCornerAction(val icon: ChromeIcon,val label: String,val run: ()->Unit)

@Composable private fun FloatingLayerGrip(icon: ChromeIcon,label: String,modifier: Modifier,
    onAdjust: (Offset)->Unit,onFinish: ()->Unit,onCancel: ()->Unit) {
    val adjust by rememberUpdatedState(onAdjust)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    PaneActionPill(modifier) {
        Box(Modifier.size(36.dp)
            .semantics {contentDescription=label}
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd={finish()},onDragCancel={cancel()}) {change,amount->
                    adjust(amount)
                    change.consume()
                }
            },contentAlignment=Alignment.Center) {ChromeSymbol(icon)}
    }
}

/** Pane controls float over the terminal without changing its grid. */
@Composable private fun PaneActionPill(modifier: Modifier,active: Boolean=false,content: @Composable RowScope.()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val pill=androidx.compose.foundation.shape.RoundedCornerShape(50)
    val surface=if(active) scheme.primaryContainer else scheme.surface
    Row(modifier.clip(pill).background(surface.copy(alpha=.92f))
        .border(1.dp,scheme.outline.copy(alpha=.3f),pill).padding(horizontal=4.dp,vertical=2.dp),
        verticalAlignment=Alignment.CenterVertically,content=content)
}

/**
 * Whether this window has a foldable's whole inner screen (or a tablet's): at least 600dp in its
 * smaller dimension and not shared with another app. Split-screen and pop-up windows are not.
 */
@Composable internal fun isUnfoldedFullScreen(): Boolean {
    val configuration=LocalConfiguration.current
    val activity=LocalActivity.current as? ComponentActivity ?: return false
    var sharedWindow by remember(activity) {mutableStateOf(activity.isInMultiWindowMode)}
    DisposableEffect(activity) {
        val listener=Consumer<MultiWindowModeChangedInfo> {sharedWindow=it.isInMultiWindowMode}
        activity.addOnMultiWindowModeChangedListener(listener)
        sharedWindow=activity.isInMultiWindowMode
        onDispose {activity.removeOnMultiWindowModeChangedListener(listener)}
    }
    return remember(configuration,activity,sharedWindow) {
        if(android.os.Build.VERSION.SDK_INT<30) return@remember false
        val manager=activity.windowManager
        val screen=manager.maximumWindowMetrics.bounds;val window=manager.currentWindowMetrics.bounds
        fullWorkspaceWindow(IntSize(window.width(),window.height()),IntSize(screen.width(),screen.height()),
            activity.resources.displayMetrics.density,sharedWindow)
    }
}

internal fun fullWorkspaceWindow(window: IntSize,screen: IntSize,density: Float,sharedWindow: Boolean): Boolean =
    !sharedWindow && minOf(window.width,window.height)>=600f*density &&
        window.width>=screen.width*.95f && window.height>=screen.height*.95f

/** The tiled surface remains mounted when [focused] is one of the tab's floating panes. */
internal fun multiPaneTiles(window: WindowItem?,focused: String,allowFloatingOverlay: Boolean=false): List<PanePlacement>? {
    val placements=window?.layout.orEmpty().filter {it.width>0f && it.height>0f}
    val tiled=placements.filterNot {it.floating}
    val hasOverlay=allowFloatingOverlay && placements.any {it.floating}
    val focusable=if(hasOverlay) placements.filterNot {it.minimized} else tiled
    return tiled.takeIf {it.size in 1..4 && focusable.any {pane->pane.id==focused}}
}
