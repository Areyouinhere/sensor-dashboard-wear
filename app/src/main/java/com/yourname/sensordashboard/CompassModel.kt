package com.yourname.sensordashboard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tiny rolling baselines + load tracker kept in memory while the app is open.
 * No storage or Samsung Health required.
 */
object CompassModel {

    // 21-sample rolling stats (approx 10–30s cadence via pushes)
    private val hrWindow  = Rolling(21)
    private val hrvWindow = Rolling(21)

    // movement "micro load" bucket -> rolls into daily-ish ACWR-lite
    private var microLoad: Float = 0f
    private val acuteWindow = Rolling(7)   // last 7 buckets
    private val chronicWindow = Rolling(28) // last 28 buckets (weekly averaged in logic)

    fun pushHR(bpm: Float) {
        if (bpm.isFinite() && bpm > 0f) hrWindow.push(bpm)
    }
    fun pushHRV(rmssdMs: Float) {
        if (rmssdMs.isFinite() && rmssdMs > 0f) hrvWindow.push(rmssdMs)
    }

    fun addMicroLoad(m: Float) {
        // linear accel magnitude spikes add a bit, clamped to keep sane
        if (!m.isFinite()) return
        microLoad = (microLoad + min(m, 1.5f)).coerceAtMost(500f)
    }

    fun notifySessionReset() {
        // roll current micro bucket into acute/chronic and zero it
        acuteWindow.push(microLoad)
        chronicWindow.push(microLoad)
        microLoad = 0f
    }

    /** 0..1 readiness, and "GREEN|YELLOW|RED" label */
    fun readiness(): Pair<Float, String> {
        val hrMu  = hrWindow.mean()
        val hrvMu = hrvWindow.mean()

        // heuristic center values (personalize quickly as window fills)
        val hrCenter = if (hrMu > 0f) hrMu else 65f
        val hrvMax   = if (hrvMu > 0f) max(60f, hrvMu*1.4f) else 60f

        val hrLatest  = hrWindow.latest() ?: hrCenter
        val hrvLatest = hrvWindow.latest() ?: 0f

        // 0..1 scores
        val hrScore  = bandScore(hrLatest, center = hrCenter, halfSpan = 10f) // best near center
        val hrvScore = (hrvLatest / hrvMax).coerceIn(0f, 1f)

        // ACWR-lite: acute / chronic (chronic ~ average of last 28)
        val acute = acuteWindow.sum()
        val chronicAvg = (chronicWindow.sum() / max(1f, chronicWindow.count().toFloat())) // per "bucket"
        val acwr = if (chronicAvg > 0f) (acute / (chronicAvg * 7f)).coerceIn(0f, 3f) else 1f
        val acwrScore = when {
            acwr in 0.8f..1.3f -> 1f
            acwr < 0.8f        -> 0.6f
            else               -> 0.6f - min(0.4f, (acwr - 1.3f)) // degrade if too high
        }.coerceIn(0f,1f)

        val readiness = (0.45f*hrvScore + 0.35f*hrScore + 0.20f*acwrScore).coerceIn(0f,1f)
        val label = when {
            readiness >= 0.66f -> "GREEN"
            readiness >= 0.40f -> "YELLOW"
            else -> "RED"
        }
        return readiness to label
    }

    private fun bandScore(value: Float, center: Float, halfSpan: Float): Float {
        val dist = abs(value - center)
        val s = 1f - (dist / halfSpan).coerceIn(0f, 1f)
        return 0.7f*s + 0.3f*(s*s)
    }

    private class Rolling(private val cap: Int) {
        private val buf = ArrayList<Float>(cap)
        fun push(v: Float) {
            if (!v.isFinite()) return
            if (buf.size >= cap) buf.removeAt(0)
            buf.add(v)
        }
        fun mean(): Float = if (buf.isEmpty()) 0f else buf.sum() / buf.size
        fun latest(): Float? = buf.lastOrNull()
        fun sum(): Float = buf.sum()
        fun count(): Int = buf.size
    }
}
