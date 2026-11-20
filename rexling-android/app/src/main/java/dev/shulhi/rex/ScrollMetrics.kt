package dev.shulhi.rex

import android.os.Build
import android.util.Log

/** One scrollback gesture (a drag, optionally followed by a fling), summarized when it settles. */
internal data class ScrollSummary(
    val flung: Boolean,
    val durationMs: Double,
    val lines: Long,
    /** Touch move → the frame that drew it. */
    val touch: Percentiles,
    /** Whole-line scroll request → a drawn frame at that scrollback offset (native catch-up). */
    val catchUp: Percentiles,
    val catchUpUnresolved: Int,
    /** Intervals between frames drawn while the fling animated. */
    val flingFrames: Percentiles,
    val flingFrameCount: Int,
    /** Fling frames that took more than 1.5 display refresh intervals. */
    val flingJanky: Int,
    /** Frames whose local slide outran the overscan rows, so content stuck at the edge. */
    val clamped: Int,
) {
    val linesPerSecond get()=if(durationMs>0) lines*1000.0/durationMs else 0.0
}

internal data class Percentiles(val count: Int,val p50: Double,val p95: Double,val max: Double) {
    companion object {
        fun of(values: List<Double>): Percentiles {
            if(values.isEmpty()) return Percentiles(0,Double.NaN,Double.NaN,Double.NaN)
            val sorted=values.sorted()
            fun at(p: Double)=sorted[Math.round((p/100.0)*(sorted.size-1)).toInt()]
            return Percentiles(sorted.size,at(50.0),at(95.0),sorted.last())
        }
    }
}

/**
 * Measures local scrollback from TerminalView's own events. Main thread only.
 *
 * Times are CLOCK_MONOTONIC nanoseconds, the clock behind both System.nanoTime() and
 * MotionEvent.eventTime, so touch timestamps compare directly with draw times.
 */
internal class ScrollMetrics(
    private val now: ()->Long,
    private val refreshIntervalNs: ()->Long,
    private val emit: (ScrollSummary)->Unit,
    private val settleTimeoutNs: Long=1_000_000_000L,
) {
    private class Request(val from: Long,val target: Long,val atNs: Long)
    private var active=false
    private var ending=false
    private var flung=false
    private var flinging=false
    private var startNs=0L
    private var endNs=0L
    private var lines=0L
    private var oldestUndrawnTouch: Long?=null
    private var lastFlingFrame: Long?=null
    private var clamped=0
    private val touch=mutableListOf<Double>()
    private val catchUp=mutableListOf<Double>()
    private val flingFrames=mutableListOf<Double>()
    private val requests=ArrayDeque<Request>()

    val isActive get()=active

    /** Scrolling started (the drag crossed the touch slop). Flushes a previous gesture still settling. */
    fun begin() {
        if(active) finish()
        active=true;ending=false;flung=false;flinging=false;startNs=now();lines=0;clamped=0
        oldestUndrawnTouch=null;lastFlingFrame=null
        touch.clear();catchUp.clear();flingFrames.clear();requests.clear()
    }
    /** A touch move was applied; [eventTimeNs] is its MotionEvent time. */
    fun touchMoved(eventTimeNs: Long) {if(active && oldestUndrawnTouch==null) oldestUndrawnTouch=eventTimeNs}
    /** The view asked the native layer to move scrollback from offset [from] to [target]. */
    fun requested(from: Long,target: Long) {
        if(!active || from==target) return
        lines+=kotlin.math.abs(target-from)
        requests.addLast(Request(from,target,now()))
    }
    fun flingStarted() {if(active) {flung=true;flinging=true;lastFlingFrame=null}}
    fun flingFinished() {flinging=false;lastFlingFrame=null}
    /** Input stopped: no fling, or the fling came to rest. Emits once native frames catch up. */
    fun end() {
        if(!active || ending) return
        ending=true;flinging=false;endNs=now()
        if(requests.isEmpty()) finish()
    }
    /** A frame finished drawing at scrollback [offset]; [slideClamped] if the local slide hit the overscan edge. */
    fun drawn(offset: Long,slideClamped: Boolean) {
        if(!active) return
        val time=now()
        oldestUndrawnTouch?.let {touch+=ms(time-it);oldestUndrawnTouch=null}
        if(slideClamped) clamped++
        if(flinging) {
            lastFlingFrame?.let {flingFrames+=ms(time-it)}
            lastFlingFrame=time
        }
        val iterator=requests.iterator()
        while(iterator.hasNext()) {
            val request=iterator.next()
            // Reached when the offset is at or past the target in the request's direction.
            val reached=if(request.target>request.from) offset>=request.target else offset<=request.target
            if(reached) {catchUp+=ms(time-request.atNs);iterator.remove()}
        }
        if(ending && (requests.isEmpty() || time-endNs>settleTimeoutNs)) finish()
    }
    private fun finish() {
        if(!active) return
        active=false
        val interval=refreshIntervalNs().coerceAtLeast(1)
        emit(ScrollSummary(
            flung=flung,
            durationMs=ms((if(ending) endNs else now())-startNs),
            lines=lines,
            touch=Percentiles.of(touch),
            catchUp=Percentiles.of(catchUp),
            catchUpUnresolved=requests.size,
            flingFrames=Percentiles.of(flingFrames),
            flingFrameCount=flingFrames.size,
            flingJanky=flingFrames.count {it*1_000_000>interval*1.5},
            clamped=clamped,
        ))
        requests.clear()
    }
    private fun ms(ns: Long)=ns/1_000_000.0
}

/**
 * Perfetto async slice "Rexling.scroll" per gesture and one RexlingScroll log line per gesture.
 * Logging is off unless enabled:  adb shell setprop log.tag.RexlingScroll DEBUG
 */
internal object ScrollMetricsLog {
    const val TAG="RexlingScroll"
    private var traceCookie=0
    fun begin() {if(Build.VERSION.SDK_INT>=29) android.os.Trace.beginAsyncSection("Rexling.scroll",++traceCookie)}
    fun record(summary: ScrollSummary) {
        if(Build.VERSION.SDK_INT>=29) android.os.Trace.endAsyncSection("Rexling.scroll",traceCookie)
        if(!Log.isLoggable(TAG,Log.DEBUG)) return
        fun p(value: Percentiles)="n=%d p50=%.1f p95=%.1f max=%.1f".format(value.count,value.p50,value.p95,value.max)
        Log.d(TAG,("scroll kind=%s duration=%.0f lines=%d lines_per_s=%.0f | touch %s | catchup %s unresolved=%d | " +
            "fling_frames %s janky=%d | clamped=%d").format(
            if(summary.flung) "fling" else "drag",summary.durationMs,summary.lines,summary.linesPerSecond,
            p(summary.touch),p(summary.catchUp),summary.catchUpUnresolved,p(summary.flingFrames),summary.flingJanky,summary.clamped))
    }
}
