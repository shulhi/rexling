package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class ScrollMetricsTest {
    private class Clock {var ns=0L;fun ms(value: Long) {ns+=value*1_000_000}}
    private val refresh=16_666_667L
    private fun metrics(clock: Clock,out: MutableList<ScrollSummary>)=ScrollMetrics({clock.ns},{refresh},{out+=it})

    @Test fun aDragReportsTouchLatencyCatchUpAndLines() {
        val clock=Clock();val out=mutableListOf<ScrollSummary>();val m=metrics(clock,out)
        m.begin()
        m.touchMoved(clock.ns);m.requested(0,3)            // touch at t=0 asks for 3 lines
        clock.ms(8);m.drawn(0,false)                        // local slide drawn 8 ms later; native not there yet
        clock.ms(16);m.drawn(3,false)                       // native frame at offset 3, 24 ms after the request
        clock.ms(1);m.end()
        val s=out.single()
        assertFalse(s.flung);assertEquals(3L,s.lines)
        assertEquals(8.0,s.touch.p50,0.0);assertEquals(1,s.touch.count)
        assertEquals(24.0,s.catchUp.p50,0.0);assertEquals(0,s.catchUpUnresolved)
        assertEquals(25.0,s.durationMs,0.0);assertEquals(120.0,s.linesPerSecond,0.01)
    }
    @Test fun onlyTheOldestUndrawnTouchIsTimedPerFrame() {
        val clock=Clock();val out=mutableListOf<ScrollSummary>();val m=metrics(clock,out)
        m.begin();m.touchMoved(clock.ns);clock.ms(4);m.touchMoved(clock.ns);clock.ms(6);m.drawn(0,false);m.end()
        assertEquals(10.0,out.single().touch.max,0.0);assertEquals(1,out.single().touch.count)
    }
    @Test fun theSummaryWaitsForNativeCatchUpAfterTheGestureEnds() {
        val clock=Clock();val out=mutableListOf<ScrollSummary>();val m=metrics(clock,out)
        m.begin();m.requested(10,4);m.end()
        assertTrue(out.isEmpty())
        clock.ms(30);m.drawn(5,false);assertTrue(out.isEmpty())   // not yet at 4 going up
        clock.ms(5);m.drawn(4,false)
        assertEquals(35.0,out.single().catchUp.p50,0.0)
    }
    @Test fun unresolvedRequestsAreReportedAfterTheTimeout() {
        val clock=Clock();val out=mutableListOf<ScrollSummary>();val m=metrics(clock,out)
        m.begin();m.requested(0,2);m.end()
        clock.ms(1001);m.drawn(0,false)
        assertEquals(1,out.single().catchUpUnresolved);assertEquals(0,out.single().catchUp.count)
    }
    @Test fun flingFramesCountJankAboveOneAndAHalfRefreshIntervals() {
        val clock=Clock();val out=mutableListOf<ScrollSummary>();val m=metrics(clock,out)
        m.begin();m.flingStarted()
        m.drawn(0,false)
        listOf(16L,17L,40L,16L).forEach {clock.ms(it);m.drawn(0,false)}
        m.flingFinished();clock.ms(100);m.drawn(0,false)        // after the fling: not a fling frame
        m.end()
        val s=out.single()
        assertTrue(s.flung);assertEquals(4,s.flingFrameCount);assertEquals(1,s.flingJanky);assertEquals(40.0,s.flingFrames.max,0.0)
    }
    @Test fun clampedSlidesAreCounted() {
        val clock=Clock();val out=mutableListOf<ScrollSummary>();val m=metrics(clock,out)
        m.begin();m.drawn(0,true);m.drawn(0,false);m.drawn(0,true);m.end()
        assertEquals(2,out.single().clamped)
    }
    @Test fun eventsOutsideAGestureAreIgnoredAndANewGestureFlushesTheOld() {
        val clock=Clock();val out=mutableListOf<ScrollSummary>();val m=metrics(clock,out)
        m.touchMoved(0);m.requested(0,1);m.drawn(1,true);m.end()
        assertTrue(out.isEmpty())
        m.begin();m.requested(0,5);m.begin()                     // a new touch before the old one settled
        assertEquals(1,out.size);assertEquals(1,out.single().catchUpUnresolved)
    }
}
