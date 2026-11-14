package dev.shulhi.rex

import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
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
    @Test fun realKeyboardDoesNotResizeTheTerminalAndTopPromptStaysPut() {
        val dimensions=mutableListOf<Pair<Int,Int>>();lateinit var view: TerminalView
        var visibleHeight=0;var viewportTop=0f;var cursorBottom by mutableStateOf<TerminalKeyboardFocus?>(null)
        ui.setContent {MaterialTheme {
            val ime=WindowInsets.ime;val navigation=WindowInsets.navigationBars
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Spacer(Modifier.height(48.dp))
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged {visibleHeight=it.height}.onGloballyPositioned {viewportTop=it.positionInWindow().y}) {
                    AndroidView(factory={TerminalView(it).also {v->view=v;attach(v,dimensions,true);v.controlled=true;v.onKeyboardFocus={cursorBottom=it}}},
                        modifier=Modifier.fillMaxSize().terminalImeViewport(ime,navigation) {cursorBottom},update={cursorBottom=it.keyboardFocus})
                }
                Spacer(Modifier.height(48.dp))
            }
        }}
        ui.runOnIdle {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            WindowCompat.setDecorFitsSystemWindows(activity.window,false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        ui.waitForIdle();var height=0;var count=0
        ui.runOnIdle {height=view.height;count=dimensions.size;view.keyboard()}
        try {
            ui.waitUntil(7000) {ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true}
            ui.waitForIdle()
            ui.runOnIdle {
                assertTrue("Keyboard should reduce the visible viewport",visibleHeight<height)
                assertEquals(height,view.height);assertEquals(count,dimensions.size)
                assertEquals("A prompt near the top must not move",0,terminalImePan(cursorBottom,visibleHeight,height-visibleHeight))
                val position=IntArray(2);view.getLocationInWindow(position)
                assertEquals(viewportTop.toInt(),position[1])
                val grid=view.screen!!;view.screen=grid.copy(cursorY=grid.rows-4)
            }
            ui.waitForIdle()
            ui.runOnIdle {
                assertTrue("Bottom prompt must move above the keyboard",terminalImePan(cursorBottom,visibleHeight,height-visibleHeight)>0)
                val position=IntArray(2);view.getLocationInWindow(position)
                assertTrue("The actual cursor must be above the controls",position[1]+cursorBottom!!.cursorBottom<=viewportTop+visibleHeight+1)
                assertTrue("Prompt footer and status lines must also be visible",position[1]+cursorBottom!!.contentBottom<=viewportTop+visibleHeight+1)
                assertTrue("Only enough of the pane should move",position[1]<viewportTop)
                view.hideKeyboard()
            }
            ui.waitUntil(7000) {ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==false}
            ui.waitForIdle();ui.runOnIdle {assertEquals(height,view.height);assertEquals(count,dimensions.size)}
        } finally {ui.runOnIdle {view.hideKeyboard()}}
    }
}
