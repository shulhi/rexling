package dev.shulhi.rex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal enum class TabBarPosition(val label: String) { BOTTOM("Bottom"), LEFT("Left"), RIGHT("Right") }
internal enum class TabRailStyle(val label: String) { ICONS("Icons"), TITLES("Icons and titles") }

internal fun tabRailWidth(style: TabRailStyle,windowWidthDp: Int=0)=when(style) {
    TabRailStyle.ICONS->52.dp
    TabRailStyle.TITLES->(windowWidthDp/4f).dp.coerceIn(172.dp,240.dp)
}

/** Where the tab bar sits, and how a side rail draws its tabs. Kept separately for folded and unfolded screens. */
internal data class TabBarLayout(val position: TabBarPosition=TabBarPosition.BOTTOM,val style: TabRailStyle=TabRailStyle.ICONS) {
    val side: Boolean get()=position!=TabBarPosition.BOTTOM
    fun encode()="${position.name},${style.name}"
    companion object {
        fun decode(raw: String?,default: TabBarLayout): TabBarLayout {
            val parts=raw?.split(',') ?: return default
            val position=TabBarPosition.entries.firstOrNull {it.name==parts.getOrNull(0)} ?: return default
            val style=TabRailStyle.entries.firstOrNull {it.name==parts.getOrNull(1)} ?: default.style
            return TabBarLayout(position,style)
        }
    }
}

/**
 * Which tab layout setting applies, by the app window's own width: the room the tabs actually have,
 * whether the window is a folded cover screen, the unfolded inner screen, or a split half of it.
 * On a Galaxy Z Fold: a portrait split half (~350dp) is narrow like a phone; the cover screen (~475dp)
 * and a landscape split half (~465dp) are medium; the inner screen held sideways (~933dp) is wide.
 */
internal enum class TabWidthClass(val title: String,val range: String,val key: String) {
    NARROW("Narrow","Under 440 dp","tab_layout_narrow"),
    MEDIUM("Medium","440–759 dp","tab_layout_medium"),
    WIDE("Wide","760 dp and up","tab_layout_wide");
    companion object {
        fun of(widthDp: Int): TabWidthClass = when {
            widthDp<440 -> NARROW
            widthDp<760 -> MEDIUM
            else -> WIDE
        }
    }
}

/** The current app window's width class, and its width in dp; both follow folding, rotation and split-screen resizes. */
@Composable internal fun currentTabWidth(): Pair<TabWidthClass,Int> {
    val width=LocalConfiguration.current.screenWidthDp
    return TabWidthClass.of(width) to width
}

internal data class TabRailSessions(val targets: List<SessionTarget>,val current: String,val onSelect: (String)->Unit)
private val sidebarTextSize=11.sp
private val sidebarLineHeight=14.sp
private val sidebarTabIconInset=10.5.dp
private const val sidebarTransitionMillis=180

/** A vertical tab list beside the terminal; [actions] (switcher, Activity, keyboard) sit at its foot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TerminalTabRail(windows: List<WindowItem>,selectedId: String,enabled: Boolean,
    onSelect: (String)->Unit,panePrograms: (WindowItem)->List<ProgramIdentity>,selectedPane: (WindowItem)->Int,
    side: TabBarPosition,style: TabRailStyle,paneTitle: (WindowItem,Int)->String={_,_->""},paneWorking: (WindowItem)->Boolean={false},
    onClose: (WindowItem)->Unit={},onMove: (String,String?)->Boolean={_,_->false},reorderKey: String="",
    paneProgress: (WindowItem)->TerminalProgress?={null},tabAttention: (WindowItem)->Int={0},animateWorking: Boolean=true,
    onStyle: (TabRailStyle)->Unit={},extendUnderNav: Boolean=false,onNewTab: (() -> Unit)?=null,
    windowWidthDp: Int=0,sessions: TabRailSessions?=null,headerOverlap: ()->Int={0},
    swipeBackdrop: PaneSwipeBackdrop?=null,hazeState: HazeState?=null,actions: @Composable ()->Unit) {
    val titles=style==TabRailStyle.TITLES
    val wideSessions=sessions.takeIf {titles && TabWidthClass.of(windowWidthDp)==TabWidthClass.WIDE}
    val portrait=currentPaneOrientation()==PaneOrientation.PORTRAIT
    val tabsAtTop=wideSessions!=null && !portrait
    var showSessions by rememberSaveable(wideSessions!=null) {mutableStateOf(false)}
    val switcherTransition=updateTransition(wideSessions!=null,label="Sidebar switcher")
    // In portrait the viewport already animates. Let its bottom-aligned tabs follow
    // that motion directly, without adding a second placement spring.
    val tabsFollowSwitcher=portrait && (switcherTransition.isRunning ||
        switcherTransition.currentState!=switcherTransition.targetState)
    // Swipe across the rail toward the terminal to show titles, back toward the screen edge to hide them.
    // Resizes are debounced, so the animated width sends the pane only its final size.
    val width by androidx.compose.animation.core.animateDpAsState(tabRailWidth(style,windowWidthDp),
        tween(sidebarTransitionMillis),label="Tab rail width")
    val tabAlignment=animateFloatAsState(if(tabsAtTop) 0f else 1f,
        spring(dampingRatio=.82f,stiffness=Spring.StiffnessMediumLow),label="Tab rail alignment")
    val tabSpacing=4.dp
    // Animate the free space above the tabs as a group. This also works when
    // collapsing from Sessions, where there are no existing tab items to move.
    val tabArrangement=remember(tabAlignment) {
        Arrangement.spacedBy(tabSpacing,Alignment.Vertical {size,space->
            ((space-size)*tabAlignment.value).roundToInt()
        })
    }
    val outward=if(side==TabBarPosition.LEFT) 1f else -1f
    val threshold=with(androidx.compose.ui.platform.LocalDensity.current) {32.dp.toPx()}
    var swipe by remember {mutableFloatStateOf(0f)}
    // Gesture navigation treats edge swipes as Back. Android lets an app opt out for at most 200dp of
    // each edge, so claim the rail's lowest 200dp (its buttons and nearest tabs); the toggle below
    // always works.
    val exclusion=with(androidx.compose.ui.platform.LocalDensity.current) {200.dp.toPx()}
    val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current
    val scheme=MaterialTheme.colorScheme
    val divider=scheme.outlineVariant.copy(alpha=.65f)
    val list=rememberLazyListState()
    var menuId by remember(reorderKey) {mutableStateOf<String?>(null)}
    val ids=windows.map {it.id}
    val index=ids.indexOf(selectedId)
    val newTabItems=if(tabsAtTop || onNewTab==null) 0 else 1
    val newTabClearance=if(newTabItems==0) 0 else with(androidx.compose.ui.platform.LocalDensity.current) {
        36.dp.roundToPx()+tabSpacing.roundToPx()
    }
    LaunchedEffect(ids) {menuId=null}
    LaunchedEffect(selectedId,reorderKey,index,newTabItems,newTabClearance,showSessions) {
        if(index>=0 && !showSessions) list.animateScrollToItem(index+newTabItems,scrollOffset=-newTabClearance)
    }
    val pageSwitcher: @Composable ()->Unit={
        val edge=if(portrait) Alignment.Bottom else Alignment.Top
        switcherTransition.AnimatedVisibility(visible={it},
            enter=expandVertically(tween(sidebarTransitionMillis),expandFrom=edge)+fadeIn(tween(120,delayMillis=32)),
            exit=shrinkVertically(tween(sidebarTransitionMillis),shrinkTowards=edge)+fadeOut(tween(110))) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=2.dp)
                .background(scheme.surfaceVariant.copy(alpha=.45f),RoundedCornerShape(12.dp))
                .padding(2.dp).selectableGroup()) {
                for(sessionsTab in listOf(false,true)) {
                    val active=showSessions==sessionsTab
                    Box(Modifier.weight(1f).heightIn(min=36.dp)
                        .background(if(active) scheme.primaryContainer else Color.Transparent,RoundedCornerShape(9.dp))
                        .selectable(active,enabled=wideSessions!=null,role=Role.Tab,onClick={showSessions=sessionsTab}),
                        contentAlignment=Alignment.Center) {
                        Text(if(sessionsTab) "Sessions" else "Tabs",fontSize=sidebarTextSize,lineHeight=sidebarLineHeight,
                            fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,
                            color=if(active) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    val glassVisible by remember(swipeBackdrop) {derivedStateOf {(swipeBackdrop?.reveal?.invoke() ?: 0f)>0f}}
    val glass=if(glassVisible && hazeState!=null) Modifier.paneFrost(hazeState,RectangleShape,edge=false,opacity=.55f) else Modifier
    Column(Modifier.zIndex(1f).fillMaxHeight().width(width)
        .onSizeChanged {swipeBackdrop?.railWidth=it.width}.then(glass)
        .drawWithContent {
            val opacity=1f-(swipeBackdrop?.reveal?.invoke() ?: 0f)
            drawRect(scheme.surface.copy(alpha=opacity))
            drawContent()
            val stroke=.5.dp.toPx()
            val x=if(side==TabBarPosition.LEFT) size.width-stroke/2 else stroke/2
            drawLine(divider,Offset(x,0f),Offset(x,size.height),strokeWidth=stroke)
        }
        // Reaches the screen edge under the gesture handle; its buttons stay above it.
        .then(if(extendUnderNav) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier)
        .systemGestureExclusion {coordinates->
            val height=coordinates.size.height.toFloat()
            androidx.compose.ui.geometry.Rect(0f,(height-exclusion).coerceAtLeast(0f),coordinates.size.width.toFloat(),height)
        }
        .draggable(androidx.compose.foundation.gestures.rememberDraggableState {swipe+=it},androidx.compose.foundation.gestures.Orientation.Horizontal,
            onDragStarted={swipe=0f},onDragStopped={
                val next=when {
                    swipe*outward>threshold -> TabRailStyle.TITLES
                    swipe*outward< -threshold -> TabRailStyle.ICONS
                    else -> style
                }
                swipe=0f
                if(next!=style) {haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress);onStyle(next)}
            })
        .semantics {customActions=listOf(CustomAccessibilityAction(if(titles) "Show icons only" else "Show tab titles") {
            onStyle(if(titles) TabRailStyle.ICONS else TabRailStyle.TITLES);true})}
        .then(if(wideSessions!=null) Modifier.layout {measurable,constraints->
            // Keep the expanded sidebar's scrollable lists below the compact header.
            val inset=headerOverlap().coerceIn(0,constraints.maxHeight)
            val content=measurable.measure(constraints.offset(vertical=-inset))
            layout(content.width,content.height+inset) {content.placeRelative(0,inset)}
        } else Modifier)
        .padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        if(!portrait) pageSwitcher()
        if(showSessions && wideSessions!=null) {
            TabRailSessionList(wideSessions,Modifier.weight(1f).fillMaxWidth(),
                alignment=if(tabsAtTop) Alignment.Top else Alignment.Bottom) {key->
                showSessions=false
                wideSessions.onSelect(key)
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics {contentDescription="Tabs"},state=list,
            contentPadding=PaddingValues(vertical=2.dp),
            verticalArrangement=tabArrangement) {
            // Lay out the button with the tabs so their placement can animate as the rail resizes.
            if(!tabsAtTop && onNewTab!=null) stickyHeader(key=0) {
                val placement=if(tabsFollowSwitcher) Modifier else Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null)
                Box(Modifier.fillMaxWidth().then(placement).background(scheme.surface),
                    contentAlignment=Alignment.Center) {
                    ChromeButton(ChromeIcon.ADD,"New tab",enabled=enabled,onClick=onNewTab)
                }
            }
            itemsIndexed(windows,key={_,w->w.id}) {i,w->
                fun moveBy(delta: Int): Boolean {
                    val target=i+delta
                    if(target !in windows.indices) return false
                    val reordered=ids.toMutableList().apply {removeAt(i);add(target,w.id)}
                    return onMove(w.id,reordered.getOrNull(target+1))
                }
                val active=w.id==selectedId
                val programs=panePrograms(w)
                val pane=selectedPane(w)
                val program=programs.getOrNull(pane) ?: ProgramIdentity.Loading
                val title=program.tabTitle(w.label,w.blocks.getOrNull(pane)?.label.orEmpty(),i+1,paneTitle(w,pane))
                val unread=tabAttention(w)
                val progress=paneProgress(w)
                val busy=paneWorking(w) || progress?.busy==true
                val indicator=terminalProgressColor(progress)
                val breathe=active && animateWorking && if(progress!=null) progress.busy && progress.percent==null else busy
                val workPhase=rememberWorkingPhase(breathe)
                val placement=if(tabsFollowSwitcher) Modifier else Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null)
                Box(placement) {
                    Row(Modifier.fillMaxWidth().height(if(titles) 44.dp else 48.dp)
                        .background(if(active) scheme.surfaceVariant else Color.Transparent)
                        .drawWithContent {
                            drawContent()
                            if(active) {
                                val bar=Size(3.dp.toPx(),22.dp.toPx())
                                val inset=5.dp.toPx()
                                val x=if(layoutDirection==LayoutDirection.Ltr) inset else size.width-inset-bar.width
                                val origin=Offset(x,(size.height-bar.height)/2)
                                val brightness=if(breathe) workingBarBrightness(workPhase.value) else 1f
                                drawTabMarker(indicator,origin,bar,progress?.percent,brightness,vertical=true)
                            }
                        }
                        .semantics(mergeDescendants=true) {contentDescription="Tab $title, ${program.label}"+(progress?.let {", ${it.description}"} ?: if(busy) ", working" else "")}
                        .semantics {
                            selected=active
                            if(unread>0) stateDescription="$unread new activity items"
                            if(enabled) customActions=buildList {
                                if(i>0) add(CustomAccessibilityAction("Move up") {moveBy(-1)})
                                if(i<windows.lastIndex) add(CustomAccessibilityAction("Move down") {moveBy(1)})
                            }
                        }
                        .combinedClickable(enabled=enabled,role=Role.Tab,onLongClickLabel="Tab options",
                            onLongClick={menuId=w.id},onClick={onSelect(w.id)})
                        .padding(start=sidebarTabIconInset,end=if(titles) 10.dp else 0.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.semantics {testTag="Tab program icon ${w.id}"},contentAlignment=Alignment.Center) {
                            ProgramStack(programs,pane,fixedSlot=true)
                            if(unread>0) {
                                val iconInset=(36.dp-programStackPaintedWidth(programs.size))/2
                                Box(Modifier.align(Alignment.TopEnd).offset(x=4.dp-iconInset,y=(-2).dp).size(10.dp)
                                    .border(1.5.dp,if(active) scheme.surfaceVariant else scheme.surface,CircleShape)
                                    .background(scheme.primary,CircleShape)
                                    .semantics {testTag="Tab unread dot ${w.id}"})
                            }
                        }
                        if(titles) Text(title,Modifier.weight(1f),fontSize=sidebarTextSize,lineHeight=sidebarLineHeight,
                            maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,
                            color=if(active) scheme.onSurface else scheme.onSurfaceVariant)
                    }
                    DropdownMenu(menuId==w.id,{menuId=null},containerColor=scheme.surface,tonalElevation=0.dp) {
                        TerminalMenuItem("Move up",ChromeIcon.UP,enabled=enabled && i>0,onClick={menuId=null;moveBy(-1)})
                        TerminalMenuItem("Move down",ChromeIcon.DOWN,enabled=enabled && i<windows.lastIndex,onClick={menuId=null;moveBy(1)})
                        TerminalMenuItem("Close tab",ChromeIcon.CLOSE,enabled=enabled,onClick={menuId=null;onClose(w)})
                    }
                }
            }
            if(tabsAtTop && onNewTab!=null) item(key=0) {
                ChromeButton(ChromeIcon.ADD,"New tab",enabled=enabled,
                    modifier=Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null).padding(start=14.dp),onClick=onNewTab)
            }
        }
        if(portrait) pageSwitcher()
        HorizontalDivider(Modifier.padding(horizontal=9.dp,vertical=4.dp),thickness=.5.dp,color=divider)
        val atStart=side==TabBarPosition.LEFT
        val atLeftEdge=atStart==(LocalLayoutDirection.current==LayoutDirection.Ltr)
        val towardTerminal=if(atLeftEdge) -90f else 90f
        val toggleWidth=36.dp
        val toggleInset=(tabRailWidth(TabRailStyle.ICONS)-toggleWidth)/2
        val toggle: @Composable ()->Unit={
            ChromeButton(ChromeIcon.CHEVRON,if(titles) "Show icons only" else "Show tab titles",
                modifier=Modifier.size(toggleWidth)
                    .graphicsLayer {rotationZ=if(titles) -towardTerminal else towardTerminal}) {
                onStyle(if(titles) TabRailStyle.ICONS else TabRailStyle.TITLES)
            }
        }
        if(!titles) Box(Modifier.padding(bottom=4.dp)) {actions()}
        // Center the toggle in the outer icon slot in either style.
        val actionsInset=if(titles) 6.dp else 0.dp
        Row(Modifier.fillMaxWidth().height(activityButtonDiameter)
            .padding(start=if(atStart) toggleInset else actionsInset,end=if(atStart) actionsInset else toggleInset),
            horizontalArrangement=when {
                titles->Arrangement.SpaceBetween
                atStart->Arrangement.Start
                else->Arrangement.End
            },verticalAlignment=Alignment.CenterVertically) {
            if(atStart) toggle()
            if(titles) Box {actions()}
            if(!atStart) toggle()
        }
    }
}

@Composable private fun TabRailSessionList(sessions: TabRailSessions,modifier: Modifier,alignment: Alignment.Vertical,onSelect: (String)->Unit) {
    val groups=remember(sessions.targets,sessions.current) {SessionCatalog.groups(sessions.targets,sessions.current,"")}
    val scheme=MaterialTheme.colorScheme
    LazyColumn(modifier.semantics {contentDescription="Sessions by server"},contentPadding=PaddingValues(8.dp),
        verticalArrangement=Arrangement.spacedBy(4.dp,alignment)) {
        if(groups.isEmpty()) item {
            Text("No sessions available",Modifier.padding(8.dp),fontSize=sidebarTextSize,lineHeight=sidebarLineHeight,
                color=scheme.onSurfaceVariant)
        }
        for(group in groups) {
            item(key="server:${group.server.id}") {
                Row(Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top=8.dp,bottom=2.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    ServerGlyph(group.server.icon,Modifier.size(16.dp))
                    Text(group.server.label,Modifier.weight(1f),fontSize=sidebarTextSize,lineHeight=sidebarLineHeight,
                        fontWeight=FontWeight.SemiBold,color=scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            items(group.sessions,key={it.key}) {target->
                val active=target.key==sessions.current
                Row(Modifier.fillMaxWidth().heightIn(min=44.dp)
                    .background(if(active) scheme.surfaceVariant else Color.Transparent,RoundedCornerShape(10.dp))
                    .clickable(role=Role.Button,onClick={onSelect(target.key)}).semantics {selected=active}
                    .padding(horizontal=8.dp,vertical=4.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(target.session.label,Modifier.weight(1f),fontSize=sidebarTextSize,lineHeight=sidebarLineHeight,
                        fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(active) ChromeSymbol(ChromeIcon.CHECK,Modifier.size(16.dp),scheme.primary)
                }
            }
        }
    }
}
