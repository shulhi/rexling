package dev.shulhi.rex

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaneChromeTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var terminal: TerminalView
    private val selected=mutableListOf<String>()
    private var opened=0
    private var scrolledToLatest=0
    private fun carousel(initial: String="a",count: Int=3,history: Boolean=false,titles: Map<String,String> = emptyMap(),directories: Map<String,String> = emptyMap(),colored: Boolean=false) {
        val blocks=listOf(BlockItem("a","shell"),BlockItem("b","shell"),BlockItem("c","shell")).take(count)
        val cells=if(colored) (0 until 60).flatMap {y->(0 until 80).map {x->TerminalCell(x,y,"",-1,if(x%2==0) 0xffff0000.toInt() else 0xff0000ff.toInt(),0,1)}} else emptyList()
        val screen=TerminalScreen(if(colored) 80 else 20,if(colored) 60 else 10,null,null,-1,0,cells,if(history) TerminalScroll(80,10,10) else TerminalScroll())
        ui.setContent {
            var selected by remember {mutableStateOf(initial)}
            MaterialTheme {
                TerminalCarousel(blocks,selected,selected,screen,{screen},
                    mapOf("a" to ProgramIdentity(ProgramKind.TERMINAL,"fish"),
                        "b" to ProgramIdentity(ProgramKind.CODEX,"Codex"),"c" to ProgramIdentity(ProgramKind.NEOVIM,"Neovim")),
                    false,10f,{},{},{_,_,_,_,_->},{},{scrolledToLatest++},{_,_->},{selected=it;this@PaneChromeTest.selected.add(it)},{opened++},{terminal=it},Modifier.size(320.dp,480.dp),paneLayout=listOf(PanePlacement("a",0f,0f,.5f,1f),PanePlacement("b",.5f,0f,.5f,.5f),PanePlacement("c",.5f,.5f,.5f,.5f)).filter {p->blocks.any {it.id==p.id}},titles=titles,directories=directories)
            }
        }
        ui.mainClock.autoAdvance=false
    }
    private fun badge()=ui.onNodeWithContentDescription("Pane switch indicator")
    @Test fun badgeNeverDisappearsOrChangesPaneAcrossTheAnimationHandoff() {
        carousel()
        badge().assertDoesNotExist()
        ui.onNodeWithContentDescription("Pane switch layout").assertDoesNotExist()
        val dx=-terminal.width*.55f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)}
        ui.mainClock.advanceTimeByFrame()
        badge().assertExists().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"b"))
        ui.onNodeWithContentDescription("Pane switch layout").assertExists().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"b"))
        val map=ui.onNodeWithContentDescription("Pane switch layout").fetchSemanticsNode().boundsInRoot
        val caption=ui.onNodeWithText("codex").fetchSemanticsNode().boundsInRoot
        assertTrue("Caption must sit below the minimap",caption.top>=map.bottom)
        badge().captureToImage().asAndroidBitmap().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pane-switch-badge-minimap.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)}
        // Sample every frame, including the frame where visualId changes.
        repeat(22) {
            ui.mainClock.advanceTimeByFrame()
            badge().assertExists().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"b"))
        }
        ui.mainClock.advanceTimeBy(1200)
        badge().assertDoesNotExist()
    }
    @Test fun cancelledSwipeDoesNotRestartTheBadgeAfterSettling() {
        carousel()
        val dx=-terminal.width*.14f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)}
        ui.mainClock.advanceTimeByFrame();badge().assertExists()
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)}
        ui.mainClock.advanceTimeBy(250)
        badge().assertDoesNotExist()
        ui.mainClock.advanceTimeBy(1200)
        badge().assertDoesNotExist()
    }
    private fun swipe(step: Int,expected: String) {
        val dx=terminal.width*.55f*if(step>0) -1f else 1f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)}
        ui.mainClock.advanceTimeByFrame()
        badge().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,expected))
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)}
        repeat(22) {ui.mainClock.advanceTimeByFrame();badge().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,expected))}
    }
    @Test fun firstAndLastPanesWrapWithoutReversingTheAnimationTarget() {
        carousel();swipe(-1,"c");swipe(1,"a")
        ui.runOnIdle {assertEquals(listOf("c","a"),selected)}
    }
    @Test fun twoPanesLoopInBothDirectionsWithoutDuplicateSelections() {
        carousel(count=2);swipe(-1,"b");swipe(-1,"a");swipe(1,"b");swipe(1,"a")
        ui.runOnIdle {assertEquals(listOf("b","a","b","a"),selected)}
    }
    @Test fun cancellingAtTheWrapBoundaryDoesNotSelectTheOppositePane() {
        carousel();val dx=terminal.width*.14f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)};ui.mainClock.advanceTimeByFrame()
        badge().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"c"))
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)};ui.mainClock.advanceTimeBy(1200)
        badge().assertDoesNotExist();ui.runOnIdle {assertTrue(selected.isEmpty())}
    }
    @Test fun singlePaneDoesNotNavigateToItself() {
        carousel(count=1);val dx=terminal.width*.55f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true);terminal.onPaneDragEnd?.invoke(dx,0f)}
        ui.mainClock.advanceTimeBy(500);badge().assertDoesNotExist();ui.runOnIdle {assertTrue(selected.isEmpty())}
        ui.onNodeWithContentDescription("Pane position indicator").assertDoesNotExist()
    }

    @Test fun oneFixedIndicatorAndProgramAnchorStayPutThroughoutASwipe() {
        carousel()
        val fixed=ui.onNodeWithContentDescription("Pane position indicator")
        val before=fixed.fetchSemanticsNode().boundsInRoot
        val dimensions=terminal.width to terminal.height
        ui.onAllNodesWithContentDescription("Pane position indicator").assertCountEquals(1)
        val next=-terminal.width*.4f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(next,true)};ui.mainClock.advanceTimeByFrame()
        assertEquals(before,fixed.fetchSemanticsNode().boundsInRoot)
        val program=badge().fetchSemanticsNode().boundsInRoot
        assertEquals(before.center.x,program.center.x,1f)
        assertTrue("Short labels should fit their content",program.width<before.width*.5f)
        ui.runOnIdle {terminal.onPaneDrag?.invoke(-next,true)};ui.mainClock.advanceTimeByFrame()
        val opposite=badge().fetchSemanticsNode().boundsInRoot
        assertEquals(program.center.x,opposite.center.x,1f)
        assertEquals(program.top,opposite.top,1f)
        assertEquals(program.bottom,opposite.bottom,1f)
        assertEquals(before,fixed.fetchSemanticsNode().boundsInRoot)
        assertEquals(dimensions,terminal.width to terminal.height)
        ui.onAllNodes(hasContentDescription("Switch pane",substring=true)).assertCountEquals(0)
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(-next,0f)}
        ui.mainClock.advanceTimeBy(300)
        ui.onAllNodesWithContentDescription("Pane position indicator").assertCountEquals(1)
        assertEquals(before,fixed.fetchSemanticsNode().boundsInRoot)
    }
    @Test fun theFixedIndicatorStillOpensPaneSelection() {
        carousel()
        ui.onNodeWithContentDescription("Pane position indicator").performClick()
        ui.runOnIdle {assertEquals(1,opened)}
    }
    @Test fun theProgramLabelDoesNotOverlapTheReturnToLatestButton() {
        carousel(history=true)
        ui.runOnIdle {terminal.onPaneDrag?.invoke(-terminal.width*.4f,true)};ui.mainClock.advanceTimeByFrame()
        val latestButton=ui.onNodeWithContentDescription("Scroll to latest")
            .assertHasClickAction().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val latest=latestButton.fetchSemanticsNode().boundsInRoot
        val program=badge().fetchSemanticsNode().boundsInRoot
        assertTrue("$latest overlaps $program",latest.bottom<=program.top)
        latestButton.performClick()
        ui.runOnIdle {assertEquals(1,scrolledToLatest)}
    }

    @Test fun switchCaptionUsesTheTopBarTitleAndDirectoryOnSeparateLines() {
        carousel(titles=mapOf("b" to "Investigate sync"),directories=mapOf("b" to "~/Dev/rexling"))
        ui.runOnIdle {terminal.onPaneDrag?.invoke(-terminal.width*.4f,true)};ui.mainClock.advanceTimeByFrame()
        val map=ui.onNodeWithContentDescription("Pane switch layout").fetchSemanticsNode().boundsInRoot
        val title=ui.onNodeWithText("Investigate sync").fetchSemanticsNode().boundsInRoot
        val directory=ui.onNodeWithText("~/Dev/rexling").fetchSemanticsNode().boundsInRoot
        assertTrue(title.top>=map.bottom);assertTrue(directory.top>=title.bottom)
        assertEquals(title.left,directory.left,1f)
        badge().captureToImage().asAndroidBitmap().let {bitmap->
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pane-switch-title-directory.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }

    @Test fun captionUsesZeroLetterSpacingAndBlursTheAndroidTerminalBehindIt() {
        carousel(initial="b",titles=mapOf("b" to "Glass title"),directories=mapOf("b" to "/tmp/glass"),colored=true)
        ui.runOnIdle {terminal.onPaneDrag?.invoke(0f,true)};ui.mainClock.advanceTimeBy(120)
        var text: androidx.compose.ui.text.TextLayoutResult?=null
        ui.onNodeWithText("Glass title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {get->
            val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();get(results);text=results.single()
        }
        assertEquals(0.sp,text!!.layoutInput.style.letterSpacing)
        assertEquals(12.sp,text!!.layoutInput.style.fontSize)
        val image=ui.onNodeWithContentDescription("Pane switch caption").captureToImage()
        val pixels=image.toPixelMap()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val y=(2*density).toInt();val x=image.width/2
        val color=pixels[x,y]
        assertTrue("Frost should sample the red/blue terminal rather than draw an opaque surface: $color",minOf(color.red,color.blue)>color.green+.07f)
        val left=pixels[(x-8*density).toInt(),y];val right=pixels[(x+8*density).toInt(),y]
        assertTrue("Terminal stripes should be blurred",kotlin.math.abs(left.red-right.red)<.20f)
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pane-frosted-caption.png").outputStream().use {image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun singlePaneCornerPillShowsSplitFloatDockAndCloseInOneRow() {
        var floating by mutableStateOf(false)
        val actions=mutableListOf<String>()
        ui.setContent {MaterialTheme {
            Box(Modifier.size(320.dp,380.dp)) {
                PaneCornerActions(Modifier.align(Alignment.TopEnd),enabled=true,
                    onSplit={actions+=it.label},onFloat=if(!floating) ({actions+="float";floating=true}) else null,
                    onDock=if(floating) ({actions+="dock";floating=false}) else null,onClose={actions+="close"})
            }
        }}
        ui.onNodeWithContentDescription("More pane actions").assertDoesNotExist()
        ui.onNodeWithContentDescription("Zoom pane").assertDoesNotExist()
        ui.onNodeWithContentDescription("Dock pane").assertDoesNotExist()
        val buttons=listOf(PaneSplit.RIGHT.label,PaneSplit.BELOW.label,"Float pane","Close pane")
            .map {ui.onNodeWithContentDescription(it).assertIsDisplayed()}
        val bounds=buttons.map {it.fetchSemanticsNode().boundsInRoot}
        for((left,right) in bounds.zipWithNext()) {
            assertEquals(left.center.y,right.center.y,.5f)
            assertTrue(left.right<=right.left)
        }
        buttons.take(3).forEach {it.performClick()}
        ui.onNodeWithContentDescription("Float pane").assertDoesNotExist()
        ui.onNodeWithContentDescription("Dock pane").performClick()
        buttons.last().performClick()
        ui.runOnIdle {assertEquals(listOf(PaneSplit.RIGHT.label,PaneSplit.BELOW.label,"float","dock","close"),actions)}
    }

    @Test fun flushSideTabsKeepTheIconStackAndFooterActionsVisible() {
        val windows=listOf(
            WindowItem("tab","PDF preview",listOf(BlockItem("a","shell"),BlockItem("b","shell"))),
            WindowItem("other","Other work",listOf(BlockItem("c","shell"))),
            WindowItem("progress","Reported progress",listOf(BlockItem("d","shell"))),
        )
        val programs=listOf(ProgramIdentity.resolve(listOf("codex")),ProgramIdentity.resolve(listOf("fish")))
        val actions=mutableListOf<String>()
        val unread=mutableIntStateOf(0)
        var keyboardVisible by mutableStateOf(false)
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            var style by remember {mutableStateOf(TabRailStyle.TITLES)}
            Row(Modifier.size(440.dp,380.dp).background(MaterialTheme.colorScheme.background).testTag("Side tabs fixture")) {
                TerminalTabRail(windows,"tab",true,{actions+=it},{programs},{0},TabBarPosition.LEFT,style,
                    paneWorking={true},paneProgress={if(it.id=="progress") TerminalProgress("indeterminate",null) else null},
                    tabAttention={if(it.id=="tab") unread.intValue else 0},onStyle={style=it},animateWorking=false,onNewTab={actions+="new tab"}) {
                    TerminalActions(true,true,{actions+="activity"},{actions+="switcher"},vertical=style==TabRailStyle.ICONS) {
                        TerminalKeyboardButton(true,true,keyboardVisible) {actions+="keyboard"}
                    }
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            }
        }}
        ui.onNodeWithContentDescription("Tab PDF preview, Codex, working").assertIsDisplayed()
        ui.onNodeWithTag("Tab unread dot tab",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("Tab working dot tab",useUnmergedTree=true).assertDoesNotExist()
        ui.runOnIdle {unread.intValue=1}
        fun check(width: androidx.compose.ui.unit.Dp,mode: String) {
            val rail=ui.onNodeWithContentDescription("Tabs").assertWidthIsEqualTo(width)
            val fixture=ui.onNodeWithTag("Side tabs fixture")
            assertEquals(fixture.fetchSemanticsNode().boundsInRoot.left,rail.fetchSemanticsNode().boundsInRoot.left,1f)
            val tab=ui.onNodeWithContentDescription("Tab PDF preview, Codex",substring=true).assertIsDisplayed().assertIsSelected()
            val newTab=ui.onNodeWithContentDescription("New tab").assertIsDisplayed()
            val tabBounds=tab.fetchSemanticsNode().boundsInRoot
            val addBounds=newTab.fetchSemanticsNode().boundsInRoot
            val railBounds=rail.fetchSemanticsNode().boundsInRoot
            assertTrue(addBounds.bottom<=tabBounds.top)
            val lastTab=ui.onNodeWithContentDescription("Tab Reported progress,",substring=true)
            assertTrue(railBounds.bottom-lastTab.fetchSemanticsNode().boundsInRoot.bottom<=with(ui.density) {4.dp.toPx()})
            if(mode=="titles") {
                val collapse=ui.onNodeWithContentDescription("Show icons only").fetchSemanticsNode().boundsInRoot
                val keyboard=ui.onNodeWithContentDescription("Show keyboard").fetchSemanticsNode().boundsInRoot
                val activity=ui.onNodeWithContentDescription("Activity").fetchSemanticsNode().boundsInRoot
                assertEquals(railBounds.left+with(ui.density) {26.dp.toPx()},collapse.center.x,1f)
                assertTrue(collapse.right<=keyboard.left)
                assertEquals(collapse.center.y,keyboard.center.y,1f)
                assertEquals(keyboard.center.y,activity.center.y,1f)
                val circle=ui.onNodeWithTag("Activity button surface",useUnmergedTree=true)
                    .assertWidthIsEqualTo(activityButtonDiameter).fetchSemanticsNode().boundsInRoot
                assertEquals(with(ui.density) {6.dp.toPx()},railBounds.right-circle.right,1f)
            }
            newTab.performClick()
            val pixels=tab.captureToImage().toPixelMap()
            val markerX=with(ui.density) {6.5.dp.toPx()}.toInt()
            val rowCenter=pixels.height/2
            assertNotEquals("The selected tab should have an inset marker",pixels[1,rowCenter],pixels[markerX,rowCenter])
            assertEquals("The outside edge should be clear of the marker",pixels[1,pixels.height-3],pixels[1,rowCenter])
            for(title in listOf("Other work","Reported progress")) {
                val inactive=ui.onNodeWithContentDescription("Tab $title,",substring=true).captureToImage().toPixelMap()
                assertEquals("Only the active tab should have a marker",inactive[1,rowCenter],inactive[markerX,rowCenter])
            }
            val dot=ui.onNodeWithTag("Tab unread dot tab",useUnmergedTree=true).assertIsDisplayed()
                .assertWidthIsEqualTo(10.dp).fetchSemanticsNode().boundsInRoot
            val icon=ui.onNodeWithTag("Tab program icon tab",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val paintedRight=icon.center.x+with(ui.density) {12.5.dp.toPx()}
            val paintedTop=icon.top+with(ui.density) {3.5.dp.toPx()}
            assertTrue("The dot should overlap the icon's top-right edge",
                dot.left<paintedRight && dot.right>paintedRight && dot.top<paintedTop && dot.bottom>paintedTop)
            listOf("Show keyboard","Tabs and panes","Activity").forEach {description->
                ui.onNodeWithContentDescription(description).assertIsDisplayed().assertWidthIsEqualTo(36.dp).performClick()
            }
            val bitmap=fixture.captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"side-tabs-$mode.png")
                .outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        fun tabIconInset(): Float {
            val row=ui.onNodeWithContentDescription("Tab PDF preview, Codex",substring=true).fetchSemanticsNode().boundsInRoot
            val icon=ui.onNodeWithTag("Tab program icon tab",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            return icon.left-row.left
        }
        check(172.dp,"titles")
        val expandedIconInset=tabIconInset()
        val collapse=ui.onNodeWithContentDescription("Show icons only")
        val expandedToggle=collapse.fetchSemanticsNode().boundsInRoot
        collapse.performClick()
        check(52.dp,"icons")
        assertEquals("The tab icon should keep the same distance from the marker",
            expandedIconInset,tabIconInset(),.5f)
        val expand=ui.onNodeWithContentDescription("Show tab titles")
        val collapsedToggle=expand.fetchSemanticsNode().boundsInRoot
        assertEquals(expandedToggle,collapsedToggle)
        val collapsedRail=ui.onNodeWithContentDescription("Tabs").fetchSemanticsNode().boundsInRoot
        assertEquals(collapsedRail.center.x,collapsedToggle.center.x,1f)
        val activity=ui.onNodeWithContentDescription("Activity").fetchSemanticsNode().boundsInRoot
        val keyboard=ui.onNodeWithContentDescription("Show keyboard").fetchSemanticsNode().boundsInRoot
        assertTrue(activity.bottom<=keyboard.top && keyboard.bottom<=collapsedToggle.top)
        assertEquals(keyboard.center.x,collapsedToggle.center.x,1f)
        ui.runOnIdle {keyboardVisible=true}
        val hideKeyboard=ui.onNodeWithContentDescription("Hide keyboard").assertIsDisplayed()
        assertEquals(keyboard,hideKeyboard.fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {keyboardVisible=false}
        expand.performClick()
        assertEquals(expandedToggle,collapse.fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {assertEquals(List(2) {listOf("new tab","keyboard","switcher","activity")}.flatten(),actions)}
    }

    @Test fun sidebarNewTabButtonMovesWithTheTabsWhenTheKeyboardCloses() {
        var height by mutableStateOf(240.dp)
        var count by mutableIntStateOf(2)
        var created=0
        ui.setContent {MaterialTheme {
            Box(Modifier.size(320.dp,height)) {
                TerminalTabRail(List(count) {WindowItem("tab-$it","Shell $it",listOf(BlockItem("pane-$it","shell")))},
                    "tab-0",true,{}, {listOf(ProgramIdentity.resolve(listOf("fish")))},{0},TabBarPosition.LEFT,TabRailStyle.ICONS,
                    onNewTab={created++}) {}
            }
        }}
        val newTab=ui.onNodeWithContentDescription("New tab")
        val firstTab=ui.onNodeWithContentDescription("Tab Shell 0,",substring=true)
        fun top(node: SemanticsNodeInteraction)=node.fetchSemanticsNode().boundsInRoot.top
        val initialTab=top(firstTab)
        val initialButton=top(newTab)
        ui.mainClock.autoAdvance=false
        ui.runOnIdle {height=380.dp}
        ui.mainClock.advanceTimeBy(64)
        val movingTab=top(firstTab)
        val movingButton=top(newTab)
        ui.mainClock.advanceTimeBy(1000)
        assertTrue("The tab should animate toward its new position",movingTab>initialTab && movingTab<top(firstTab))
        assertEquals(movingTab-initialTab,movingButton-initialButton,1f)
        ui.mainClock.autoAdvance=true
        ui.runOnIdle {count=12}
        ui.onNodeWithContentDescription("Tabs").performScrollToIndex(12)
        ui.onNodeWithContentDescription("Tab Shell 11,",substring=true).assertIsDisplayed()
        newTab.assertIsDisplayed().performClick()
        ui.runOnIdle {assertEquals(1,created)}
    }

    @Test fun wideSidebarSwitchesBetweenTabsAndSessionsWithoutResizingTheTerminal() {
        val mac=ServerProfile("mac","MacBook","https://mac.example",ServerIcon.MACBOOK)
        val remote=ServerProfile("remote","Shipyard","https://remote.example",ServerIcon.CLOUD)
        val localSession=SessionTarget(mac,SessionItem("shared-id","Local work"))
        val remoteSession=SessionTarget(remote,SessionItem("shared-id","Remote work"))
        var windowWidth by mutableIntStateOf(800)
        var overlap by mutableIntStateOf(0)
        var side by mutableStateOf(TabBarPosition.LEFT)
        var windowOrientation by mutableIntStateOf(Configuration.ORIENTATION_LANDSCAPE)
        var current by mutableStateOf(localSession.key)
        val selectedSessions=mutableListOf<String>()
        ui.setContent {MaterialTheme {
            var style by remember {mutableStateOf(TabRailStyle.TITLES)}
            val rail: @Composable ()->Unit={
                TerminalTabRail(listOf(WindowItem("tab","Shell workspace",emptyList())),"tab",true,{},
                    {listOf(ProgramIdentity.resolve(listOf("fish")))},{0},side,style,onStyle={style=it},
                    onNewTab={},windowWidthDp=windowWidth,headerOverlap={overlap},sessions=TabRailSessions(listOf(localSession,remoteSession),current) {
                        selectedSessions+=it
                        current=it
                    }) {}
            }
            val configuration=Configuration(LocalConfiguration.current).apply {orientation=windowOrientation}
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                Row(Modifier.size(360.dp,420.dp).underWorkspaceHeader {overlap}) {
                    if(side==TabBarPosition.LEFT) rail()
                    Box(Modifier.weight(1f).fillMaxSize().testTag("Terminal area"))
                    if(side==TabBarPosition.RIGHT) rail()
                }
            }
        }}
        val tabs=ui.onNodeWithText("Tabs")
        val sessions=ui.onNodeWithText("Sessions")
        val terminal=ui.onNodeWithTag("Terminal area")
        val tabsList=ui.onNodeWithContentDescription("Tabs")
        val activeTab=ui.onNodeWithContentDescription("Tab Shell workspace, Fish",substring=true,ignoreCase=true)
        val newTab=ui.onNodeWithContentDescription("New tab")
        fun bounds(node: SemanticsNodeInteraction)=node.fetchSemanticsNode().boundsInRoot
        tabs.assertIsDisplayed().assertIsSelected()
        fun assertSidebarText(node: SemanticsNodeInteraction,weight: FontWeight) {
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {get->assertTrue(get(layouts))}
            assertEquals(11.sp,layouts.single().layoutInput.style.fontSize)
            assertEquals(weight,layouts.single().layoutInput.style.fontWeight)
        }
        assertSidebarText(ui.onNodeWithText("Tabs",useUnmergedTree=true),FontWeight.SemiBold)
        assertSidebarText(ui.onNodeWithText("Shell workspace",useUnmergedTree=true),FontWeight.SemiBold)
        tabsList.assertWidthIsEqualTo(200.dp)
        assertTrue(bounds(activeTab).top-bounds(tabsList).top<=with(ui.density) {4.dp.toPx()})
        assertTrue(bounds(activeTab).bottom<=bounds(newTab).top)
        val terminalBounds=terminal.fetchSemanticsNode().boundsInRoot
        sessions.performClick().assertIsSelected()
        assertEquals(terminalBounds,terminal.fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithText("MacBook").assertIsDisplayed()
        ui.onNodeWithText("Shipyard").assertIsDisplayed()
        ui.onNodeWithText("Local work").assertIsSelected()
        assertSidebarText(ui.onNodeWithText("Local work",useUnmergedTree=true),FontWeight.SemiBold)
        ui.onNodeWithText("Remote work").assertIsNotSelected().performClick()
        tabs.assertIsSelected()
        ui.onNodeWithContentDescription("New tab").assertIsDisplayed()
        ui.runOnIdle {assertEquals(listOf(remoteSession.key),selectedSessions)}
        sessions.performClick()
        ui.onNodeWithText("Remote work").assertIsSelected()
        val tabTop=tabs.fetchSemanticsNode().boundsInRoot.top
        ui.runOnIdle {overlap=96}
        assertEquals(tabTop,tabs.fetchSemanticsNode().boundsInRoot.top,1f)
        ui.runOnIdle {overlap=0}

        tabs.performClick()
        val expandedTop=bounds(activeTab).top
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Show icons only").performClick()
        ui.mainClock.advanceTimeBy(96)
        val movingTop=bounds(activeTab).top
        ui.mainClock.advanceTimeBy(1000)
        val collapsedTop=bounds(activeTab).top
        ui.mainClock.autoAdvance=true
        assertTrue("The tab should travel toward its collapsed position: $expandedTop, $movingTop, $collapsedTop",
            movingTop>expandedTop && movingTop<collapsedTop)
        sessions.assertDoesNotExist()
        tabsList.assertWidthIsEqualTo(52.dp)
        ui.onNodeWithContentDescription("Show tab titles").performClick()
        tabs.assertIsSelected()
        ui.runOnIdle {windowWidth=1000}
        tabsList.assertWidthIsEqualTo(240.dp)
        for((width,railWidth) in listOf(759 to 189.75.dp,720 to 180.dp,440 to 172.dp,439 to 172.dp)) {
            ui.runOnIdle {windowWidth=width}
            sessions.assertDoesNotExist()
            tabsList.assertWidthIsEqualTo(railWidth)
            assertTrue(bounds(newTab).bottom<=bounds(activeTab).top)
            assertTrue(bounds(tabsList).bottom-bounds(activeTab).bottom<=with(ui.density) {4.dp.toPx()})
        }
        ui.runOnIdle {side=TabBarPosition.RIGHT;windowWidth=800}
        val collapse=ui.onNodeWithContentDescription("Show icons only")
        val rightToggle=bounds(collapse)
        assertEquals(bounds(tabsList).right-with(ui.density) {26.dp.toPx()},rightToggle.center.x,1f)
        collapse.performClick()
        val expand=ui.onNodeWithContentDescription("Show tab titles")
        assertEquals(rightToggle,bounds(expand))
        assertEquals(bounds(tabsList).center.x,bounds(expand).center.x,1f)
        expand.performClick()
        assertEquals(rightToggle,bounds(collapse))

        ui.runOnIdle {windowOrientation=Configuration.ORIENTATION_PORTRAIT}
        val portraitSwitcher=bounds(tabs)
        assertTrue(bounds(activeTab).bottom<=portraitSwitcher.top)
        assertTrue(bounds(tabsList).bottom-bounds(activeTab).bottom<=with(ui.density) {4.dp.toPx()})
        assertTrue(portraitSwitcher.bottom<=bounds(collapse).top)
        assertEquals(rightToggle,bounds(collapse))
        val portraitExpandedTop=bounds(activeTab).top
        val portraitExpandedBottom=bounds(tabsList).bottom
        ui.mainClock.autoAdvance=false
        collapse.performClick()
        ui.mainClock.advanceTimeBy(96)
        val portraitMovingTop=bounds(activeTab).top
        val portraitMovingBottom=bounds(tabsList).bottom
        assertEquals(rightToggle,bounds(expand))
        ui.mainClock.advanceTimeBy(1000)
        val portraitCollapsedTop=bounds(activeTab).top
        val portraitCollapsedBottom=bounds(tabsList).bottom
        assertTrue("The tabs should move with the switcher as it closes. " +
            "Tab top $portraitExpandedTop → $portraitMovingTop → $portraitCollapsedTop. " +
            "List bottom $portraitExpandedBottom → $portraitMovingBottom → $portraitCollapsedBottom.",
            portraitMovingTop>portraitExpandedTop && portraitMovingTop<portraitCollapsedTop)
        expand.performClick()
        ui.mainClock.advanceTimeBy(96)
        val portraitOpeningTop=bounds(activeTab).top
        assertTrue("The tabs should follow the switcher as it opens",
            portraitOpeningTop<portraitCollapsedTop && portraitOpeningTop>portraitExpandedTop)
        ui.mainClock.advanceTimeBy(1000)
        ui.mainClock.autoAdvance=true
        assertEquals(portraitSwitcher,bounds(tabs))
        sessions.performClick().assertIsSelected()
        val sessionsList=ui.onNodeWithContentDescription("Sessions by server")
        assertTrue(bounds(sessionsList).bottom<=bounds(sessions).top)
        assertEquals(portraitSwitcher,bounds(tabs))
        ui.runOnIdle {windowOrientation=Configuration.ORIENTATION_LANDSCAPE}
        sessions.assertIsSelected()
        assertTrue(bounds(sessions).bottom<=bounds(sessionsList).top)
    }
}
