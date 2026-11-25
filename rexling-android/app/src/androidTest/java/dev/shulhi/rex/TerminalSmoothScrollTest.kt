package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TerminalSmoothScrollTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun row(number: Int,y: Int)=List(40) {x->TerminalCell(x,y,"row $number".padEnd(40)[x].toString(),-1,
        if(number%2==0) 0xff243455.toInt() else 0xff662244.toInt(),0,1,if(x<7) "https://example.test/$number" else null)}
    private fun frame(offset: Long): TerminalScreen {
        val lines=List(20) {row(offset.toInt()+it,it)}
        return TerminalScreen(40,20,null,null,-1,0,lines.flatten(),TerminalScroll(200,offset,20),lines,
            above=List(4) {row(offset.toInt()-4+it,0)},below=List(4) {row(offset.toInt()+20+it,0)})
    }
    private fun view(offset: Long=50): TerminalView {
        val metrics=TerminalFonts(context).configure(Paint(),10f)
        return TerminalView(context).apply {controlled=true;setTerminalPaddingDp(0,0,0);screen=frame(offset)
            layout(0,0,kotlin.math.ceil(metrics.cellWidth*40).toInt(),kotlin.math.ceil(metrics.cellHeight*20).toInt())}
    }
    private fun draw(view: TerminalView)=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also {view.draw(Canvas(it))}
    @Test fun nativePreviewPacketsRoundTripThroughTheNewDecoder() {
        val handle=NativeBridge.create();val decoder=TerminalUpdates()
        try {
            NativeBridge.command(handle,"{\"type\":\"preview\"}")
            val deadline=android.os.SystemClock.uptimeMillis()+5000
            var found=false
            while(android.os.SystemClock.uptimeMillis()<deadline && !found) {
                if(NativeBridge.awaitUpdate(handle,decoder.revision,500)>decoder.revision) {
                    val bytes=NativeBridge.readUpdate(handle,decoder.revision)
                    if(bytes.isNotEmpty()) {
                        assertEquals("RXT8",bytes.take(4).toByteArray().toString(Charsets.US_ASCII))
                        val state=decoder.decode(bytes);found=state.phase=="preview" && state.screen!=null
                        if(found) assertTrue("Default cursor must blink after restoring a native snapshot",state.screen!!.cursorBlinking)
                    }
                }
            }
            assertTrue("Native frame format and Android decoder must agree",found)
        } finally {NativeBridge.destroy(handle)}
    }
    @Test fun oneRowScrollReusesInteriorDrawingsAndMatchesAFreshRender() {
        instrumentation.runOnMainSync {
            val view=view();draw(view).recycle();val before=view.recordedRowCount
            view.screen=frame(51)
            val reused=draw(view);val fresh=draw(view(51))
            val optimized=view.recordedRowCount-before
            assertTrue("Only edge/uncovered rows need recording",optimized<=3)
            assertTrue("Moved drawings, text and hyperlink underlines match a full redraw",reused.sameAs(fresh))
            // Controlled old-policy baseline: retain all row recordings, but prevent
            // moving them to another screen position. This matches the prior renderer
            // for this fixture; it does not measure an old APK or end-to-end frame time.
            val baseline=view();draw(baseline).recycle();val baselineBefore=baseline.recordedRowCount
            TerminalView::class.java.getDeclaredField("recordedOffset").apply {isAccessible=true}.set(baseline,null)
            baseline.screen=frame(51);val legacy=draw(baseline)
            val original=baseline.recordedRowCount-baselineBefore
            assertEquals("Previous row-position policy re-records every changed row",20,original)
            assertTrue("Both policies must render identical pixels",legacy.sameAs(reused))
            val result=org.json.JSONObject().put("visible_rows",20).put("scroll_rows",1)
                .put("baseline_recordings",original).put("optimized_recordings",optimized)
                .put("identical_pixels",true).put("baseline","Current renderer with row-moving reuse disabled; not an old APK benchmark")
            java.io.File(context.cacheDir,"smooth-scroll-baseline.json").writeText(result.toString(2))
            legacy.recycle();reused.recycle();fresh.recycle()
        }
    }
    @Test fun aPartialRowDragMovesPixelsBeforeAnyWholeRowRequest() {
        instrumentation.runOnMainSync {
            val view=view();var requests=0;view.onScroll={requests+=it}
            val height=TerminalFonts(context).configure(Paint(),10f).cellHeight
            val before=draw(view);view.scrollByPixels(height/2);val after=draw(view)
            assertEquals(0,requests);assertEquals(height/2,view.scrollTranslation,.01f)
            assertFalse("Fractional motion changes the rendered pixels",before.sameAs(after))
            val x=view.width-2;val y=(height*.75f).toInt()
            assertNotEquals("The next row fills the moving edge",before.getPixel(x,y),after.getPixel(x,y))
            before.recycle();after.recycle()
        }
    }
    @Test fun applicationScrollReusesMovedRowsAndPreservesFixedRowsAndLinks() {
        instrumentation.runOnMainSync {
            val original=frame(50).copy(inputFlags=1,scroll=TerminalScroll(20,0,20))
            val shifted=frame(51).lines.mapIndexed {y,cells->if(y==1 || y==18) original.lines[y] else cells}
            val scrolled=original.copy(lines=shifted,cells=shifted.flatten())
            val cached=view().apply {screen=original}
            draw(cached).recycle()
            for(next in listOf(scrolled,original)) {
                val before=cached.recordedRowCount
                cached.scrollByPixels(if(next===scrolled) -100f else 100f)
                cached.screen=next
                val reused=draw(cached)
                val fresh=draw(view().apply {screen=next})
                assertTrue("Reuse must preserve the pixels after scrolling in either direction",reused.sameAs(fresh))
                assertTrue("Only new rows and the padded edges should be recorded",cached.recordedRowCount-before<=5)
                reused.recycle();fresh.recycle()
            }
        }
    }
    @Test fun tappingDuringMovementOnlyStopsItThenLinksUseTheSettledGrid() {
        instrumentation.runOnMainSync {
            val view=view();var opened="";view.onOpenLink={opened=it}
            val height=TerminalFonts(context).configure(Paint(),10f).cellHeight
            view.scrollByPixels(height/3)
            fun tap(time: Long) {
                for((action,at) in listOf(MotionEvent.ACTION_DOWN to time,MotionEvent.ACTION_UP to time+30)) {
                    val event=MotionEvent.obtain(time,at,action,5f,height/2,0);view.onTouchEvent(event);event.recycle()
                }
            }
            tap(100);assertEquals("",opened);assertEquals(0f,view.scrollTranslation,0f)
            tap(200);assertEquals("https://example.test/50",opened)
        }
    }
    @Test fun applicationScrollingKeepsWheelEventsAndNoLocalTranslation() {
        instrumentation.runOnMainSync {
            val view=view();var wheel=0;var local=0
            view.screen=frame(180).copy(inputFlags=1);view.onScroll={local+=it};view.onWheel={n,_,_,_,_->wheel+=n}
            val height=TerminalFonts(context).configure(Paint(),10f).cellHeight
            view.scrollByPixels(height/2);view.scrollByPixels(height/2)
            assertEquals(1,wheel);assertEquals(0,local);assertEquals(0f,view.scrollTranslation,0f)
        }
    }

    @Test fun inactivePaneScrollsAndASeparateTapFocusesIt() {
        instrumentation.runOnMainSync {
            val view=view().apply {controlled=false;interactive=false}
            var focus=0;var local=0;var wheel=0
            view.onFocusTap={focus++}
            view.onActivate={error("An inactive tap must only focus its pane")}
            view.onOpenLink={error("An inactive tap must only focus its pane")}
            view.onScroll={local+=it}
            view.onWheel={lines,_,_,_,_->wheel+=lines}

            fun touch(action: Int,at: Long,y: Float,down: Long) {
                val event=MotionEvent.obtain(down,at,action,5f,y,0)
                try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
            }
            fun drag(at: Long) {
                val center=view.height/2f
                touch(MotionEvent.ACTION_DOWN,at,center,at)
                touch(MotionEvent.ACTION_MOVE,at+100,center+view.height/4f,at)
                touch(MotionEvent.ACTION_UP,at+200,center+view.height/4f,at)
            }
            drag(100)
            assertTrue(local<0)
            assertEquals(0,focus)

            view.screen=frame(180).copy(inputFlags=1)
            drag(400)
            assertTrue(wheel<0)
            assertEquals(0,focus)

            touch(MotionEvent.ACTION_DOWN,700,10f,700)
            touch(MotionEvent.ACTION_UP,730,10f,700)
            assertEquals(1,focus)
        }
    }
}
