package dev.shulhi.rex

import android.view.View
import android.view.WindowManager
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.chrisbanes.haze.HazeState
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalImeViewportTest {
    @get:Rule val ui=createComposeRule()
    private fun attach(view: TerminalView,dimensions: MutableList<Pair<Int,Int>>,top: Boolean=false) {
        view.onDimensions={columns,rows->
            dimensions+=columns to rows
            view.screen=TerminalScreen(columns,rows,2,if(top) 1 else rows-1,-1,0,
                (0 until rows).map {y->TerminalCell(0,y,"line $y",-1,0,0,1)})
        }
    }
    @Test fun stackedPaneViewsKeepTheirHeightAndIdentityAcrossImeAndFocusChanges() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        lateinit var client: RexConnection
        ui.runOnUiThread {client=RexConnection(context.applicationContext as android.app.Application,scope,"")}
        val views=mutableMapOf<String,TerminalView>()
        var covered by mutableIntStateOf(0)
        var focused by mutableStateOf("a")
        var expectedBottom=0
        var headerHeightSaved=0
        var headerOverlap=0
        try {
            ui.setContent {MaterialTheme {
                val density=LocalDensity.current
                val ime=WindowInsets(bottom=covered)
                val navigation=WindowInsets(bottom=24.dp)
                val navClearance=14.dp
                val toolbarHeight=40.dp
                Column(Modifier.width(400.dp).height(520.dp-with(density) {covered.toDp()}).consumeWindowInsets(ime)) {
                    KeyboardCollapsingHeader(ime,navigation,{headerHeightSaved=it},onOverlap={headerOverlap=it},
                        full={Spacer(Modifier.fillMaxWidth().height(54.dp))},
                        compact={Spacer(Modifier.fillMaxWidth().height(40.dp))})
                    Box(Modifier.weight(1f).fillMaxWidth().underWorkspaceHeader {headerOverlap}
                        .windowInsetsPadding(WindowInsets(bottom=navClearance)).onGloballyPositioned {
                            val paneMargins=with(density) {4.dp.roundToPx()+1.5.dp.roundToPx()}
                            expectedBottom=it.positionInWindow().y.toInt()+it.size.height-paneMargins
                        }) {
                    MultiPaneLayout(client,"session","tab",
                        listOf(PanePlacement("a",0f,0f,1f,.37f),PanePlacement("b",0f,.37f,1f,.63f)),
                        focused,focused,true,emptyMap(),emptySet(),11f,
                        onFocus={id,_->focused=id},onView={views[focused]=it},onImage={_,_->false},
                        onPaste={false},imeEnterSubmits=false,
                        keyboardChrome={with(density) {toolbarHeight.roundToPx()}},extendsUnderNavigation=true,
                        navigationClearance=navClearance,hazeState=remember {HazeState()},captureBackdrop=false,
                        ime=ime,navigation=navigation,headerHeightSaved={headerHeightSaved},headerOverlap={headerOverlap})
                    }
                    Spacer(Modifier.fillMaxWidth().keyboardRevealed(ime,navigation) {}.height(toolbarHeight))
                }
            }}
            ui.waitForIdle()
            ui.onNodeWithContentDescription("Focus pane").performClick()
            ui.waitForIdle()
            val original=ui.runOnIdle {
                val bottom=views.getValue("b")
                val position=IntArray(2)
                bottom.getLocationInWindow(position)
                assertTrue("The bottom pane keeps its workspace margin",
                    kotlin.math.abs(expectedBottom-position[1]-bottom.height)<=1)
                views.mapValues {(_,view)->view to view.height}
            }
            assertEquals(setOf("a","b"),original.keys)
            for(inset in listOf(0,19,53,99,151,223,267,101,0)) {
                ui.runOnIdle {covered=inset}
                ui.waitForIdle()
                ui.onNodeWithContentDescription("Focus pane").performClick()
                ui.waitForIdle()
                ui.runOnIdle {
                    for((id,pair) in original) {
                        assertSame("Focus must keep pane $id mounted",pair.first,views[id])
                        assertEquals("Keyboard must preserve the grid height of pane $id",pair.second,views[id]!!.height)
                    }
                }
            }
        } finally {
            ui.runOnIdle {client.close()}
        }
    }

    @Test fun paneControlHandoffDoesNotChangeTheKeyboardHeight() {
        lateinit var outgoing: TerminalView;lateinit var incoming: TerminalView
        ui.setContent {Row(Modifier.fillMaxSize()) {
            AndroidView(factory={TerminalView(it).also {v->outgoing=v;v.controlled=true}},modifier=Modifier.weight(1f).fillMaxHeight())
            AndroidView(factory={TerminalView(it).also {v->incoming=v}},modifier=Modifier.weight(1f).fillMaxHeight())
        }}
        ui.runOnIdle {outgoing.keyboard()}
        ui.waitUntil(7000) {ViewCompat.getRootWindowInsets(outgoing)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        android.os.SystemClock.sleep(1200)
        val heights=mutableListOf<Int>();var sampling=true
        val sampler=object: android.view.Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                heights+=ViewCompat.getRootWindowInsets(outgoing)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                if(sampling) android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        try {
            ui.runOnIdle {android.view.Choreographer.getInstance().postFrameCallback(sampler);outgoing.controlled=false}
            // Include the real navigation gap before the next pane is acknowledged.
            android.os.SystemClock.sleep(250)
            ui.runOnIdle {incoming.controlled=true;incoming.requestFocus()}
            android.os.SystemClock.sleep(700)
            ui.runOnIdle {
                sampling=false;android.view.Choreographer.getInstance().removeFrameCallback(sampler)
                assertTrue("Sample the keyboard throughout the handoff",heights.size>10)
                assertTrue("Gboard must keep its layout while transferring pane focus: $heights",heights.min()>0 && heights.max()-heights.min()<=2)
                assertTrue(incoming.hasFocus())
            }
        } finally {ui.runOnIdle {sampling=false;android.view.Choreographer.getInstance().removeFrameCallback(sampler);incoming.hideKeyboard()}}
    }
    @Test fun minimizingAFloatingPaneKeepsItsEditorUntilTheTiledEditorIsServed() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        lateinit var client: RexConnection
        ui.runOnUiThread {client=RexConnection(context.applicationContext as android.app.Application,scope,"")}
        val tiled=listOf(PanePlacement("a",0f,0f,1f,1f))
        var floating by mutableStateOf(listOf(PanePlacement("float",.2f,.2f,.6f,.6f,floating=true)))
        var focused by mutableStateOf("float")
        var live by mutableStateOf("float")
        val views=mutableMapOf<String,TerminalView>()
        val frame=TerminalScreen(40,20,2,2,-1,0,emptyList())
        try {
            ui.setContent {MaterialTheme {
                MultiPaneLayout(client,"session","tab",tiled,focused,live,live.isNotBlank(),emptyMap(),emptySet(),11f,
                    onFocus={_,_->},onView={view->view.screen=frame;views[live]=view},
                    onImage={_,_->false},onPaste={false},imeEnterSubmits=false,
                    keyboardChrome={0},extendsUnderNavigation=false,hazeState=remember {HazeState()},captureBackdrop=false,
                    floatingPanes=floating)
            }}
            val outgoing=ui.runOnIdle {views.getValue("float").also {it.requestFocus()}}
            val inputMethod=requireNotNull(context.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
            ui.waitUntil(7000) {ui.runOnIdle {outgoing.hasWindowFocus() && inputMethod.isActive(outgoing)}}
            ui.runOnIdle {outgoing.keyboard()}
            fun keyboardHeight(view: TerminalView) =
                ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            ui.waitUntil(7000) {keyboardHeight(outgoing)>0}
            ui.runOnIdle {
                floating=emptyList()
                focused="a"
                live=""
            }
            ui.runOnIdle {
                assertTrue("The outgoing editor must stay attached and focused during the navigation gap",
                    outgoing.isAttachedToWindow && outgoing.hasFocus())
                assertFalse(outgoing.controlled)
                assertTrue(keyboardHeight(outgoing)>0)
                live="a"
            }
            val incoming=ui.runOnIdle {views.getValue("a").also {it.requestFocus()}}
            ui.waitUntil(5000) {!outgoing.isAttachedToWindow}
            ui.runOnIdle {
                assertTrue(incoming.hasFocus())
                assertTrue(keyboardHeight(incoming)>0)
                incoming.hideKeyboard()
            }
        } finally {ui.runOnIdle {client.close()}}
    }
    @Test fun keyboardAnimationKeepsTheGridAndCachedRowsStable() {
        var covered by mutableIntStateOf(0);var legacy by mutableStateOf(true)
        val dimensions=mutableListOf<Pair<Int,Int>>();lateinit var view: TerminalView
        var fullHeight=0;var visibleHeight=0;var viewportTop=0f;var cursorBottom by mutableStateOf<TerminalKeyboardFocus?>(null)
        ui.setContent {MaterialTheme {
            val density=LocalDensity.current
            Box(Modifier.width(360.dp).height(640.dp)) {
                key(legacy) {
                    Box(Modifier.fillMaxWidth().height(with(density){(640.dp.toPx().toInt()-covered).toDp()}).clipToBounds().onSizeChanged {visibleHeight=it.height}) {
                        AndroidView(factory={TerminalView(it).also {v->view=v;attach(v,dimensions);v.onKeyboardFocus={cursorBottom=it}}},
                            modifier=Modifier.fillMaxSize().then(if(legacy) Modifier else Modifier.terminalImeViewport(WindowInsets(bottom=covered),WindowInsets(0,0,0,0)) {cursorBottom}),
                            update={cursorBottom=it.keyboardFocus})
                    }
                }
            }
        }}
        fun animate(): Pair<Int,Int> {
            val before=dimensions.size;val recordings=view.recordedRowCount
            for(step in (1..12)+(11 downTo 0).toList()) {
                ui.runOnIdle {covered=step*24};ui.waitForIdle()
                if(!legacy) ui.runOnIdle {
                    assertEquals("IME must not change the terminal view's measured height",fullHeight,view.height)
                    val child=IntArray(2);view.getLocationInWindow(child)
                    // Parent clips the bottom; transformed view coordinates still drive mouse/selection correctly.
                    assertTrue(terminalImePan(cursorBottom,visibleHeight,covered) in 0..covered)
                }
            }
            return (dimensions.size-before) to (view.recordedRowCount-recordings)
        }
        ui.waitForIdle();val baseline=animate()
        ui.runOnIdle {legacy=false;dimensions.clear()};ui.waitForIdle();ui.runOnIdle {fullHeight=view.height}
        val fixed=animate()
        assertTrue("Legacy layout should reproduce resize churn",baseline.first>0)
        assertEquals("Keyboard toggling must not ask the server to resize",0,fixed.first)
        assertEquals("Keyboard movement must reuse cached terminal rows",0,fixed.second)
        val result=JSONObject().put("legacy_resize_callbacks",baseline.first).put("fixed_resize_callbacks",fixed.first)
            .put("legacy_row_recordings",baseline.second).put("fixed_row_recordings",fixed.second)
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ime-rendering.json").writeText(result.toString(2))
    }
    @Test fun historyAndHiddenCursorFramesDoNotMoveTheKeyboardViewport() {
        val dimensions=mutableListOf<Pair<Int,Int>>();lateinit var view: TerminalView
        var focus by mutableStateOf<TerminalKeyboardFocus?>(null)
        ui.setContent {MaterialTheme {
            Box(Modifier.width(320.dp).height(300.dp).clipToBounds()) {
                AndroidView(factory={TerminalView(it).also {v->view=v;attach(v,dimensions);v.onKeyboardFocus={focus=it}}},
                    modifier=Modifier.fillMaxSize().terminalImeViewport(WindowInsets(bottom=400),WindowInsets(0,0,0,0)) {focus},update={focus=it.keyboardFocus})
            }
        }}
        ui.waitForIdle()
        lateinit var live: TerminalScreen;var anchoredY=0;var size=0
        ui.runOnIdle {
            live=view.screen!!;size=dimensions.size
            assertTrue("Fixture must start with a panned keyboard viewport",terminalImePan(view.keyboardFocus,view.height-400,400)>0)
            val position=IntArray(2);view.getLocationInWindow(position);anchoredY=position[1]
        }
        repeat(8) {index->
            ui.runOnIdle {
                view.screen=when(index%4) {
                    0->live.copy(cursorX=null,cursorY=null)
                    1->live.copy(cursorY=1,scroll=TerminalScroll(live.rows+200L,50,live.rows.toLong()))
                    2->live.copy(cursorX=null,cursorY=null,scroll=TerminalScroll(live.rows+200L,80,live.rows.toLong()))
                    else->live
                }
            }
            ui.waitForIdle()
            ui.runOnIdle {
                val position=IntArray(2);view.getLocationInWindow(position)
                assertEquals("Cursor visibility/history updates must not jump the whole pane",anchoredY,position[1])
                assertEquals(size,dimensions.size)
            }
        }
    }
    @Test fun editorArrowMovementNeverJumpsMoreThanOneRow() {
        val dimensions=mutableListOf<Pair<Int,Int>>();lateinit var view: TerminalView
        var focus by mutableStateOf<TerminalKeyboardFocus?>(null)
        ui.setContent {Box(Modifier.width(320.dp).height(300.dp).clipToBounds()) {
            AndroidView(factory={TerminalView(it).also {v->view=v;attach(v,dimensions);v.onKeyboardFocus={focus=it}}},
                modifier=Modifier.fillMaxSize().terminalImeViewport(WindowInsets(bottom=400),WindowInsets(0,0,0,0)) {focus},update={focus=it.keyboardFocus})
        }}
        ui.waitForIdle()
        val frame=view.screen!!;var previous=0;val sizes=dimensions.size
        fun position(): Int {val at=IntArray(2);view.getLocationInWindow(at);return at[1]}
        ui.runOnIdle {previous=position()}
        for(row in (frame.rows-1 downTo 0)+(0 until frame.rows)) {
            ui.runOnIdle {view.screen=frame.copy(cursorY=row)};ui.waitForIdle()
            ui.runOnIdle {
                val next=position()
                assertTrue("One arrow step must not jump the viewport: $previous -> $next",kotlin.math.abs(next-previous)<=kotlin.math.ceil(view.height.toDouble()/frame.rows).toInt()+2)
                previous=next
                assertEquals("Arrow movement must not resize the terminal",sizes,dimensions.size)
            }
        }
    }
    @Test fun realKeyboardDoesNotResizeTheTerminalAndTopPromptStaysPut() {
        val dimensions=mutableListOf<Pair<Int,Int>>();lateinit var view: TerminalView
        var visibleHeight=0;var viewportTop=0f;var cursorBottom by mutableStateOf<TerminalKeyboardFocus?>(null)
        var headerHeightSaved=0
        var headerOverlap=0
        val appSurface=Color.Magenta
        val toolbarSurfaces=mutableListOf<Color>()
        ui.setContent {MaterialTheme(colorScheme=lightColorScheme(surface=appSurface)) {
            val ime=WindowInsets.ime;val navigation=WindowInsets.navigationBars
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                SessionHeader(listOf(SessionItem("a","alpha · Mac")),"a",true,true,emptyList(),0,
                    onSessions={},onSelect={},onLast={},title="Terminal",collapseForIme=ime,
                    onHeaderHeightSaved={headerHeightSaved=it},onHeaderOverlap={headerOverlap=it},menu={})
                Box(Modifier.weight(1f).fillMaxWidth().underWorkspaceHeader {headerOverlap}.clipToBounds()
                    .onSizeChanged {visibleHeight=it.height}.onGloballyPositioned {viewportTop=it.positionInWindow().y}) {
                    AndroidView(factory={TerminalView(it).also {v->view=v;attach(v,dimensions,true);v.controlled=true;v.onKeyboardFocus={cursorBottom=it}}},
                        modifier=Modifier.fillMaxSize().terminalImeViewport(ime,navigation,headerHeightSaved={headerHeightSaved},headerOverlap={headerOverlap}) {cursorBottom},
                        update={cursorBottom=it.keyboardFocus})
                }
                KeyboardToolbarTheme(true) {
                    val surface=MaterialTheme.colorScheme.surface
                    SideEffect {if(toolbarSurfaces.lastOrNull()!=surface) toolbarSurfaces+=surface}
                    Spacer(Modifier.height(48.dp))
                }
            }
        }}
        ui.runOnIdle {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            WindowCompat.setDecorFitsSystemWindows(activity.window,false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        fun waitForKeyboard(height: Int) {
            ui.waitUntil(7000) {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true &&
                    headerOverlap>0 && visibleHeight in 1 until height
            }
            ui.waitForIdle()
        }
        fun hideKeyboard() {
            ui.runOnIdle {view.hideKeyboard()}
            ui.waitUntil(7000) {ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==false && headerOverlap==0}
            ui.waitForIdle()
        }
        ui.waitForIdle();var height=0;var count=0
        ui.runOnIdle {height=view.height;count=dimensions.size;view.keyboard()}
        try {
            waitForKeyboard(height)
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) {
                val context=view.context
                val night=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
                val systemSurface=(if(night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).surface
                ui.waitUntil(7000) {ui.runOnUiThread {toolbarSurfaces.lastOrNull()==systemSurface}}
                ui.runOnIdle {
                    assertEquals(appSurface,toolbarSurfaces.first())
                    assertTrue("The actual IME transition should produce intermediate toolbar colors: $toolbarSurfaces",
                        toolbarSurfaces.any {it!=appSurface && it!=systemSurface})
                }
            }
            ui.runOnIdle {
                assertTrue("Keyboard should reduce the visible viewport",visibleHeight<height)
                assertTrue("The terminal should extend under the collapsed header",headerOverlap>0)
                assertEquals(height,view.height);assertEquals(count,dimensions.size)
                val position=IntArray(2);view.getLocationInWindow(position)
                assertEquals("The first row, including output above the cursor, must stay clear of the glass",
                    viewportTop+headerOverlap,position[1].toFloat(),1f)
            }
            hideKeyboard()
            ui.runOnIdle {
                val grid=view.screen!!;view.screen=grid.copy(cursorY=grid.rows-4)
                view.keyboard()
            }
            waitForKeyboard(height)
            ui.runOnIdle {
                assertTrue("Bottom prompt must move above the keyboard",terminalImePan(cursorBottom,visibleHeight,height-visibleHeight)>0)
                val position=IntArray(2);view.getLocationInWindow(position)
                assertTrue("The actual cursor must be above the controls",position[1]+cursorBottom!!.cursorBottom<=viewportTop+visibleHeight+1)
                assertTrue("Prompt footer and status lines must also be visible",position[1]+cursorBottom!!.contentBottom<=viewportTop+visibleHeight+1)
                assertTrue("Only enough of the pane should move",position[1]<viewportTop)
            }
            hideKeyboard()
            ui.runOnIdle {assertEquals(height,view.height);assertEquals(count,dimensions.size)}
        } finally {ui.runOnIdle {view.hideKeyboard()}}
    }
    @Test fun hiddenPiCursorPansAboveTheKeyboardWithoutResizingTheGrid() {
        val sizes=mutableListOf<Pair<Int,Int>>();lateinit var view:TerminalView
        val program=ProgramIdentity.resolve(listOf("/opt/pi-coding-agent/pi"))
        var focus by mutableStateOf<TerminalKeyboardFocus?>(null)
        ui.setContent {MaterialTheme {
            Box(Modifier.width(320.dp).height(300.dp).clipToBounds()) {
                AndroidView(factory={TerminalView(it).also {v->
                    view=v;v.allowHiddenCursorInput=program.supportsHiddenCursorInput;v.onKeyboardFocus={focus=it}
                    v.onDimensions={cols,rows->sizes+=cols to rows;v.screen=TerminalScreen(cols,rows,null,null,-1,0,emptyList(),inputFlags=7,cursorAnchorX=2,cursorAnchorY=rows-2)}
                }},modifier=Modifier.fillMaxSize().terminalImeViewport(WindowInsets(bottom=400),WindowInsets(0,0,0,0)) {focus},update={focus=it.keyboardFocus})
            }
        }}
        ui.waitForIdle();var count=0
        ui.runOnIdle {
            count=sizes.size;assertNull(view.screen!!.cursorY);assertNotNull(view.keyboardFocus)
            assertTrue("Hidden Pi prompt must pan above the keyboard",terminalImePan(view.keyboardFocus,view.height-400,400)>0)
        }
        ui.runOnIdle {view.screen=view.screen!!.copy(cursorAnchorY=view.screen!!.rows-3)}
        ui.waitForIdle();ui.runOnIdle {assertEquals(count,sizes.size)}
    }

}
