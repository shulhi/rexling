package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class KeystrokeLatencyTest {
    private class Clock {var ns=0L;fun ms(value: Long) {ns+=value*1_000_000}}
    private class Tracer: KeystrokeTracer {
        val open=mutableSetOf<Long>()
        override fun begin(id: Long) {open+=id}
        override fun end(id: Long) {open-=id}
    }
    private fun tracker(clock: Clock,samples: MutableList<KeystrokeSample>,tracer: Tracer?=null,capacity: Int=64)=
        KeystrokeLatency({clock.ns},{samples+=it},tracer,capacity)

    @Test fun aKeystrokeIsTimedThroughEveryStage() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val tracer=Tracer();val k=tracker(clock,samples,tracer)
        val id=k.start();clock.ms(1);k.sent(id);clock.ms(20);k.decoded(7);clock.ms(5);k.presented(7);clock.ms(3);k.drawn(7)
        val sample=samples.single()
        assertEquals(7L,sample.revision)
        assertEquals(1.0,sample.sendMs,0.0);assertEquals(20.0,sample.echoMs,0.0)
        assertEquals(5.0,sample.presentMs,0.0);assertEquals(3.0,sample.drawMs,0.0);assertEquals(29.0,sample.totalMs,0.0)
        assertTrue(tracer.open.isEmpty());assertEquals(0,k.pendingCount())
    }
    @Test fun updatesDecodedBeforeSendingAreNotTheEcho() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val k=tracker(clock,samples)
        val id=k.start();k.decoded(3)                      // in flight before the native call returned
        k.sent(id);clock.ms(10);k.decoded(4);k.presented(4);k.drawn(4)
        assertEquals(4L,samples.single().revision);assertEquals(10.0,samples.single().echoMs,0.0)
    }
    @Test fun aCoalescedFrameCompletesEveryKeystrokeItContains() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val k=tracker(clock,samples)
        val a=k.start();k.sent(a);k.decoded(10)
        val b=k.start();k.sent(b);k.decoded(11)
        k.presented(11);k.drawn(11)                         // revision 10 was never presented on its own
        assertEquals(listOf(10L,11L),samples.map {it.revision})
    }
    @Test fun aFrameOlderThanTheEchoDoesNotCompleteIt() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val k=tracker(clock,samples)
        val id=k.start();k.sent(id);k.decoded(5)
        k.presented(4);k.drawn(4);assertTrue(samples.isEmpty())
        k.presented(5);k.drawn(5);assertEquals(1,samples.size)
    }
    @Test fun drawingBeforePresentationDoesNotComplete() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val k=tracker(clock,samples)
        val id=k.start();k.sent(id);k.decoded(2);k.drawn(2)   // a redraw of the previous frame
        assertTrue(samples.isEmpty());assertEquals(1,k.pendingCount())
    }
    @Test fun cancelledAndResetKeystrokesEmitNothingAndCloseTheirTraces() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val tracer=Tracer();val k=tracker(clock,samples,tracer)
        k.cancel(k.start())
        val id=k.start();k.sent(id);k.decoded(1);k.reset()
        k.presented(1);k.drawn(1)
        assertTrue(samples.isEmpty());assertTrue(tracer.open.isEmpty());assertEquals(0,k.pendingCount())
    }
    @Test fun staleAndExcessKeystrokesAreDropped() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val tracer=Tracer();val k=tracker(clock,samples,tracer,capacity=3)
        repeat(5) {k.start()}
        assertEquals(3,k.pendingCount());assertEquals(3,tracer.open.size)
        clock.ms(6000);k.start()                             // the others are older than 5 s
        assertEquals(1,k.pendingCount());assertEquals(1,tracer.open.size)
    }
    @Test fun awaitingEchoOnlyWhileASentKeystrokeHasNone() {
        val clock=Clock();val samples=mutableListOf<KeystrokeSample>();val k=tracker(clock,samples)
        assertFalse(k.awaitingEcho())
        val id=k.start();assertFalse(k.awaitingEcho())       // not sent yet
        k.sent(id);assertTrue(k.awaitingEcho())
        k.decoded(1);assertFalse(k.awaitingEcho())
    }
    @Test fun displayTokensCarryTheRevision() {
        assertEquals(42L,KeystrokeLatency.revisionOf("123456.42"))
        assertNull(KeystrokeLatency.revisionOf("garbage"))
    }
    @Test fun percentilesUseTheRecentWindow() {
        val window=LatencyWindow(size=100)
        (1..100).forEach {window.add(it.toDouble())}
        assertEquals(50.0,window.percentile(50.0),1.0);assertEquals(95.0,window.percentile(95.0),1.0)
        repeat(100) {window.add(1000.0)};assertEquals(1000.0,window.percentile(50.0),0.0)
        assertTrue(LatencyWindow().percentile(50.0).isNaN())
    }
}
