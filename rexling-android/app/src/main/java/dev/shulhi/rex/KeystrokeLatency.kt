package dev.shulhi.rex

import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * One keystroke's journey, in nanoseconds since [startNs] on one monotonic clock:
 * typed (input handler) → sent (native command returned) → echo (first decoded update
 * after sending) → presented (frame handed to the view) → drawn (that frame's onDraw ended).
 */
internal data class KeystrokeSample(val id: Long,val revision: Long,val startNs: Long,val sentNs: Long,val echoNs: Long,val presentNs: Long,val drawNs: Long) {
    val sendMs get()=ms(sentNs-startNs)
    val echoMs get()=ms(echoNs-sentNs)
    val presentMs get()=ms(presentNs-echoNs)
    val drawMs get()=ms(drawNs-presentNs)
    val totalMs get()=ms(drawNs-startNs)
    private fun ms(ns: Long)=ns/1_000_000.0
}

internal interface KeystrokeTracer {
    fun begin(id: Long)
    fun end(id: Long)
}

/**
 * Correlates keystrokes with the terminal frame that first shows a response.
 *
 * The echo is the first decoded update after a keystroke is sent whose screen content
 * differs from the previous one (callers report only those to [decoded]). In a quiet shell that
 * update is the echo itself. If a program is printing at the same time, an unrelated
 * update can arrive first and the sample understates latency.
 *
 * Thread-safe: input and drawing run on the main thread, decoding on the IO thread.
 */
internal class KeystrokeLatency(
    private val now: ()->Long,
    private val emit: (KeystrokeSample)->Unit,
    private val tracer: KeystrokeTracer?=null,
    private val capacity: Int=64,
    private val expiryNs: Long=5_000_000_000L,
) {
    private class Pending(val id: Long,val startNs: Long) {
        var sentNs=UNSET;var revision=-1L;var echoNs=UNSET;var presentNs=UNSET
    }
    private val pending=ArrayDeque<Pending>()
    private var nextId=1L

    /** A keystroke entered the app's input handler. Returns its id for [sent] or [cancel]. */
    @Synchronized fun start(): Long {
        val time=now()
        while(pending.isNotEmpty() && (pending.size>=capacity || time-pending.first().startNs>expiryNs)) drop(pending.removeFirst())
        val id=nextId++
        pending.addLast(Pending(id,time))
        tracer?.begin(id)
        return id
    }
    /** The native layer accepted the input. */
    @Synchronized fun sent(id: Long) {pending.firstOrNull {it.id==id}?.sentNs=now()}
    /** The input was rejected; it will never be echoed. */
    @Synchronized fun cancel(id: Long) {pending.firstOrNull {it.id==id}?.let {pending.remove(it);drop(it)}}
    /** A screen-changing update with [revision] was decoded: the echo for every sent keystroke still waiting for one. */
    @Synchronized fun decoded(revision: Long) {
        val time=now()
        for(item in pending) if(item.sentNs!=UNSET && item.revision<0) {item.revision=revision;item.echoNs=time}
    }
    /** Whether a sent keystroke still waits for its echo; lets callers skip comparing screens otherwise. */
    @Synchronized fun awaitingEcho()=pending.any {it.sentNs!=UNSET && it.revision<0}
    /** The frame for [revision] was handed to the terminal view. */
    @Synchronized fun presented(revision: Long) {
        val time=now()
        for(item in pending) if(item.revision in 0..revision && item.presentNs==UNSET) item.presentNs=time
    }
    /** The view finished drawing the frame for [revision]. */
    @Synchronized fun drawn(revision: Long) {
        val time=now()
        val done=pending.filter {it.presentNs!=UNSET && it.revision in 0..revision}
        for(item in done) {
            pending.remove(item)
            tracer?.end(item.id)
            emit(KeystrokeSample(item.id,item.revision,item.startNs,item.sentNs,item.echoNs,item.presentNs,time))
        }
    }
    /** Navigation, reconnects and hiding make pending keystrokes unmatchable. */
    @Synchronized fun reset() {while(pending.isNotEmpty()) drop(pending.removeFirst())}
    @Synchronized fun pendingCount()=pending.size
    private fun drop(item: Pending) {tracer?.end(item.id)}

    companion object {
        private const val UNSET=Long.MIN_VALUE
        /** Revision from a display token, "<handle>.<revision>". */
        fun revisionOf(token: String)=token.substringAfterLast('.').toLongOrNull()
    }
}

/** Rolling percentiles over the most recent samples. */
internal class LatencyWindow(private val size: Int=200) {
    private val values=ArrayDeque<Double>()
    var count=0L
        private set
    fun add(value: Double) {values.addLast(value);if(values.size>size) values.removeFirst();count++}
    fun percentile(p: Double): Double {
        if(values.isEmpty()) return Double.NaN
        val sorted=values.sorted()
        return sorted[((p/100.0)*(sorted.size-1)).let {Math.round(it).toInt()}]
    }
}

/**
 * Perfetto async slice "Rexling.keystroke" per keystroke, and logcat lines under the
 * RexlingLatency tag. Logging is off unless enabled:
 *   adb shell setprop log.tag.RexlingLatency DEBUG
 */
internal object KeystrokeLatencyLog: KeystrokeTracer {
    const val TAG="RexlingLatency"
    private const val SUMMARY_EVERY=20
    private val totals=LatencyWindow()
    private val echoes=LatencyWindow()
    // Async trace sections need API 29; on Android 9 only the log lines are available.
    override fun begin(id: Long) {if(Build.VERSION.SDK_INT>=29) android.os.Trace.beginAsyncSection("Rexling.keystroke",id.toInt())}
    override fun end(id: Long) {if(Build.VERSION.SDK_INT>=29) android.os.Trace.endAsyncSection("Rexling.keystroke",id.toInt())}
    fun record(sample: KeystrokeSample) {
        if(!Log.isLoggable(TAG,Log.DEBUG)) return
        synchronized(this) {
            totals.add(sample.totalMs);echoes.add(sample.echoMs)
            Log.d(TAG,"keystroke total=%.1f send=%.1f echo=%.1f present=%.1f draw=%.1f rev=%d".format(
                sample.totalMs,sample.sendMs,sample.echoMs,sample.presentMs,sample.drawMs,sample.revision))
            if(totals.count%SUMMARY_EVERY==0L) Log.d(TAG,"summary n=%d total_p50=%.1f total_p95=%.1f echo_p50=%.1f echo_p95=%.1f".format(
                totals.count,totals.percentile(50.0),totals.percentile(95.0),echoes.percentile(50.0),echoes.percentile(95.0)))
        }
    }
    fun tracker()=KeystrokeLatency(SystemClock::elapsedRealtimeNanos,::record,this)
}
