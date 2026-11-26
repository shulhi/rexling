package dev.shulhi.rex

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

internal class PaneSwipeBackdrop(val side: TabBarPosition) {
    var railWidth by mutableIntStateOf(0)
    var reveal by mutableStateOf<()->Float>({0f})
}

@Composable internal fun TerminalCarousel(blocks: List<BlockItem>,selectedBlock: String,liveBlock: String,
    liveScreen: TerminalScreen?,previewFrame: (String)->TerminalScreen?,programs: Map<String,ProgramIdentity>,controlled: Boolean,fontSize: Float,
    onInput: (String)->Unit,onScroll: (Int)->Unit,onWheel: (Int,Int,Int,Int,Int)->Unit,onActivate: (Boolean)->Unit,onBottom: ()->Unit,onDimensions: (Int,Int)->Unit,onSelect: (String)->Unit,onPanes: ()->Unit,
    onView: (TerminalView)->Unit,modifier: Modifier=Modifier,bindLiveView: ((String,TerminalView)->Boolean)?=null,
    onMouseClick: (Int,Int,Int,Int)->Unit={_,_,_,_->},mouseTarget: ()->String={""},onImage: (android.net.Uri,()->Unit)->Boolean={_,release->release();false},onPaste: ()->Boolean={false},imageTarget: ()->String=mouseTarget,bottomControlsInset: androidx.compose.ui.unit.Dp=0.dp,paneLayout: List<PanePlacement> = emptyList(),titles: Map<String,String> = emptyMap(),directories: Map<String,String> = emptyMap(),hazeState: HazeState?=null,captureBackdrop: Boolean=false,imeEnterSubmits: Boolean=false,keyboardChrome: ()->Int={0},squareCorners: Boolean=false,extendsUnderNavigation: Boolean=false,
    navigationClearance: Dp=0.dp,bottomOverlayInset: Dp=0.dp,headerHeightSaved: ()->Int={0},headerOverlap: ()->Int={0},floatingMotion: FloatingPaneMotion?=null,swipeBackdrop: PaneSwipeBackdrop?=null) {
    val switchLayout=remember(paneLayout,blocks) {paneLayout.filter {p->blocks.any {it.id==p.id}}.sortedBy {it.layer}}
    val hasMinimap=blocks.all {b->switchLayout.any {it.id==b.id}}
    val localHaze=remember {HazeState()}
    val haze=hazeState ?: localHaze
    val imeInsets=WindowInsets.ime
    val navigationInsets=WindowInsets.navigationBars
    val palette=LocalTerminalPalette.current
    val backdrop=MaterialTheme.colorScheme.background
    val scope=rememberCoroutineScope()
    val keepAwake=LocalKeepScreenAwake.current
    val imeHandoff=remember {TerminalImeHandoff(BlockItem::id)}
    var visualId by remember(blocks.map {it.id}) {mutableStateOf(selectedBlock)}
    var drag by remember {mutableFloatStateOf(0f)}
    var dragging by remember {mutableStateOf(false)}
    var settling by remember {mutableStateOf(false)}
    var toast by remember {mutableStateOf(false)}
    var settlingTarget by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(dragging || settling) {
        if(android.os.Build.VERSION.SDK_INT>=29) android.os.Trace.setCounter("Rexlink.paneMotion",if(dragging || settling) 1L else 0L)
    }
    DisposableEffect(Unit) {onDispose {
        if(android.os.Build.VERSION.SDK_INT>=29) android.os.Trace.setCounter("Rexlink.paneMotion",0L)
    }}
    val latestSelect by rememberUpdatedState(onSelect)
    val index=blocks.indexOfFirst {it.id==visualId}.coerceAtLeast(0)
    LaunchedEffect(selectedBlock,settling,dragging) {
        if(!settling && !dragging && visualId!=selectedBlock) {
            Snapshot.withMutableSnapshot {visualId=selectedBlock;settlingTarget=selectedBlock;drag=0f;toast=true}
        }
    }
    // Start the dismissal timer only after motion ends, without hiding the badge
    // between the animation and its post-switch display.
    LaunchedEffect(visualId,toast,dragging,settling) {
        if(toast && !dragging && !settling) {delay(1000);toast=false}
    }
    BoxWithConstraints(if(swipeBackdrop==null) modifier.clipToBounds() else modifier) {
        val cardWidth=maxWidth.coerceAtLeast(80.dp)
        val cardLeft=0.dp
        val stride=with(LocalDensity.current){(cardWidth+8.dp).toPx()}
        val leftPx=with(LocalDensity.current){cardLeft.toPx()}
        val revealDistance=with(LocalDensity.current) {24.dp.toPx()}
        DisposableEffect(swipeBackdrop,stride,revealDistance) {
            swipeBackdrop?.reveal={
                val distance=abs(drag)
                (minOf(distance,abs(stride-distance))/revealDistance).coerceIn(0f,1f)
            }
            onDispose {swipeBackdrop?.reveal={0f}}
        }
        val candidate by remember(stride,blocks) {derivedStateOf {
            if(settling || toast) blocks.indexOfFirst {it.id==(settlingTarget ?: visualId)}.coerceAtLeast(0) else {
                val current=blocks.indexOfFirst {it.id==visualId}.coerceAtLeast(0)
                if(blocks.isEmpty()) 0 else Math.floorMod(current+if(drag< -stride*.12f) 1 else if(drag>stride*.12f) -1 else 0,blocks.size)
            }
        }}
        val flingSpeed=with(LocalDensity.current){450.dp.toPx()}
        fun finish(dx: Float,velocity: Float) {
            val step=if(dx < -stride*.18f || (dx< -20f && velocity < -flingSpeed)) 1 else if(dx > stride*.18f || (dx>20f && velocity>flingSpeed)) -1 else 0
            val target=if(blocks.isEmpty()) 0 else Math.floorMod(index+step,blocks.size)
            Snapshot.withMutableSnapshot {
                settlingTarget=blocks.getOrNull(target)?.id;toast=target!=index;settling=true;dragging=false
            }
            if(target!=index) blocks.getOrNull(target)?.let {latestSelect(it.id)}
            scope.launch {
                val animation=Animatable(drag)
                animation.animateTo(if(target==index) 0f else if(step>0) -stride else stride,tween(190)) {drag=value}
                Snapshot.withMutableSnapshot {
                    if(target!=index) visualId=blocks[target].id
                    drag=0f;settling=false
                }
            }
        }
        // Two-pane rings share one neighbor: render it on the gesture side so
        // each real pane keeps one AndroidView and one stable key.
        val offsets=when(blocks.size) {0->emptyList();1->listOf(0);2->listOf(0,if(drag>0) -1 else 1);else->listOf(-1,0,1)}
        val showSwitch=(dragging || settling || toast) && blocks.size>1 && blocks.getOrNull(candidate)!=null
        val pages=offsets.map {offset->blocks[Math.floorMod(index+offset,blocks.size)] to offset}
        val retained=imeHandoff.retainFor(pages.map {it.first})
        val mountedPages=pages+listOfNotNull(retained?.let {it.pane to 4})
        val motion=dragging || settling
        val railExtension=if(motion && swipeBackdrop!=null) Modifier.layout {measurable,constraints->
            // Give the haze source room behind the rail and report the original viewport width.
            val inset=swipeBackdrop.railWidth
            val backdrop=measurable.measure(constraints.offset(horizontal=inset))
            layout(constraints.maxWidth,backdrop.height) {
                backdrop.placeRelative(if(swipeBackdrop.side==TabBarPosition.LEFT) -inset else 0,0)
            }
        } else Modifier
        val pageInset=if(motion && swipeBackdrop!=null) Modifier.layout {measurable,constraints->
            val inset=swipeBackdrop.railWidth
            val page=measurable.measure(constraints.offset(horizontal=-inset))
            layout(page.width+inset,page.height) {
                page.placeRelative(if(swipeBackdrop.side==TabBarPosition.LEFT) inset else 0,0)
            }
        } else Modifier
        Box(Modifier.fillMaxSize().then(railExtension)
            .then(if(showSwitch || captureBackdrop || (motion && swipeBackdrop!=null)) Modifier.hazeSource(haze) else Modifier)
            .clipToBounds().then(pageInset)) {
        for((block,offset) in mountedPages) {
            val retiring=retained?.pane?.id==block.id
            val center=offset==0 && !retiring
            val preferredFrame=if(retiring) retained!!.frame else liveScreen.takeIf {block.id==liveBlock}
            val pageOffset=offset*stride
            key(block.id) {
                DisposableEffect(block.id,floatingMotion) {onDispose {imeHandoff.remove(block.id);floatingMotion?.forget(block.id)}}
                val trackFloating=center && paneLayout.any {it.id==block.id && it.floating}
                var keyboardFocus by remember {mutableStateOf<TerminalKeyboardFocus?>(null)}
                val keyboardPan=remember {TerminalImePanState()}
                Box(Modifier.width(cardWidth).fillMaxHeight().graphicsLayer {
                    val position=pageOffset+drag
                    translationX=kotlin.math.round(leftPx+position)
                    alpha=if(retiring) 0f else 1f
                }.then(if(trackFloating) Modifier.floatingPaneViewport(block.id,floatingMotion) else Modifier)
                    .clip(if(squareCorners) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(21.dp)).background(Color(palette.background))
                    .paneScrim(backdrop) {(abs(pageOffset+drag)/stride).coerceIn(0f,1f)*.48f}
                    .then(if(center || retiring) Modifier else Modifier.clickable(role=Role.Button){latestSelect(block.id)})) {
                    CarouselPageFrame(block.id,preferredFrame,previewFrame,imeHandoff) {frame->
                        Box(Modifier.fillMaxSize()) {
                            if(frame!=null) AndroidView(
                                factory={TerminalView(it)},modifier=Modifier.fillMaxSize().terminalImeViewport(imeInsets,navigationInsets,keyboardChrome,
                                    extendsUnderNavigation,headerHeightSaved,headerOverlap,pan=keyboardPan) {keyboardFocus}.clipToBounds(),
                                update={view ->
                                    view.keepAwakeMode=if(center) keepAwake else KeepScreenAwake.SYSTEM
                                    if(trackFloating) floatingMotion?.trackView(block.id,view)
                                    imeHandoff.mount(block,view)
                                    view.onInputConnectionReady={if(center) imeHandoff.served(block.id,view)}
                                    view.onKeyboardFocus={keyboardFocus=it}
                                    keyboardFocus=view.keyboardFocus
                                    view.keyboardPan=keyboardPan.takeIf {center && block.id==liveBlock}
                                    if(view.keyboardPan==null) keyboardPan.followCursor()
                                    view.onDimensions=if(center && block.id==liveBlock) onDimensions else {_,_->}
                                    val bottomPadding=if(bottomControlsInset>0.dp || blocks.size>1) 28 else 6
                                    view.setTerminalPaddingDp(12,8,bottomPadding+navigationClearance.value.roundToInt())
                                    view.palette=palette;view.textSp=fontSize
                                    if(retiring || block.id!=liveBlock || bindLiveView?.invoke(block.id,view)!=true) view.screen=frame
                                    view.interactive=center
                                    view.keyboardSuggestions=programs[block.id]?.kind!=ProgramKind.NEOVIM
                                    view.imeEnterSubmits=imeEnterSubmits
                                    view.controlled=center && controlled && block.id==liveBlock
                                    view.imageInputTarget=imageTarget
                                    view.onImage=if(center && block.id==liveBlock) onImage else {_,release->release();false}
                                    view.onPaste=if(center && block.id==liveBlock) onPaste else ({false})
                                    view.onInput=onInput
                                    view.onMouseClick=if(center && block.id==liveBlock) onMouseClick else ({_,_,_,_->})
                                    view.mouseTarget=mouseTarget
                                    view.onActivate=if(center && block.id==liveBlock) onActivate else ({})
                                    view.onWheel=if(center && block.id==liveBlock) onWheel else ({_,_,_,_,_->})
                                    view.onScroll=if(center && block.id==liveBlock) onScroll else {_->}
                                    view.onPaneDrag=if(center) {dx,active ->
                                        if(!settling) {
                                            dragging=active;toast=false
                                            drag=if(blocks.size<2) dx*.2f else dx
                                            if(!active) drag=0f
                                        }
                                    } else null
                                    view.onPaneDragEnd=if(center) {dx,velocity->if(!settling) finish(dx,velocity)} else null
                                    view.importantForAccessibility=if(center) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                    if(center) onView(view)
                                })
                            else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                                Text(if(center) "Opening terminal…" else block.label,fontFamily=MonoUi,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if(center && (frame?.scroll?.below ?: 0)>0) {
                            ScrollToLatestButton(haze,Modifier.align(Alignment.BottomEnd)
                                .padding(end=12.dp,bottom=(if(blocks.size>1) if(hasMinimap) 128.dp else 88.dp else 10.dp)+bottomControlsInset+bottomOverlayInset),
                                onClick=onBottom)
                        }
                    }
                }
            }
        }
        }
        if(blocks.size>1) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom=bottomOverlayInset).fillMaxWidth().height(paneIndicatorStripHeight)
                .clickable(role=Role.Button,onClick=onPanes)
                .semantics {contentDescription="Pane position indicator";stateDescription="Pane ${candidate+1} of ${blocks.size}"},contentAlignment=Alignment.Center) {
                PanePositionIndicator(index,blocks.size,offset={-drag/stride})
            }
        }
        if(showSwitch) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom=34.dp+bottomOverlayInset)
                .widthIn(max=(maxWidth-32.dp).coerceIn(80.dp,240.dp))
                .semantics {contentDescription="Pane switch indicator";stateDescription=blocks[candidate].id},
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(hasMinimap) PaneSwitchMinimap(switchLayout,blocks[candidate].id,Modifier.size(56.dp,36.dp),hazeState=haze)
                    Row(Modifier.paneFrost(haze,RoundedCornerShape(13.dp)).semantics {contentDescription="Pane switch caption"}.padding(horizontal=6.dp,vertical=4.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        val block=blocks[candidate]
                        val program=programs[block.id] ?: ProgramIdentity.Loading
                        PaneProgramTile(program,Modifier.size(22.dp,18.dp),false,0f)
                        Column(Modifier.weight(1f,fill=false)) {
                            Text(program.paneTitle(block.label,titles[block.id].orEmpty()),
                                fontWeight=FontWeight.Bold,fontSize=12.sp,lineHeight=18.sp,letterSpacing=0.sp,
                                style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(directories[block.id].orEmpty(),fontFamily=MonoUi,fontSize=10.sp,lineHeight=14.sp,letterSpacing=0.sp,maxLines=1,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
            }
        }
    }
}

@Composable private fun CarouselPageFrame(
    block: String,preferred: TerminalScreen?,previewFrame: (String)->TerminalScreen?,handoff: TerminalImeHandoff<BlockItem>,
    content: @Composable (TerminalScreen?)->Unit,
) {
    // Observe this pane from its own recompose scope, independently of the other mounted pages.
    val frame=preferred ?: remember(block,previewFrame) {
        derivedStateOf(referentialEqualityPolicy()) {previewFrame(block)}
    }.value ?: handoff.frame(block)
    content(frame)
}
