package com.yourname.sensordashboard

import kotlin.math.sqrt

/**
 * Shared HRV store (RMSSD) usable from any page.
 * Push either RR intervals (ms) via push(rr) or heart rate via pushFromHR(bpm).
 */
object HRVHistory {
    private val rrIntervals = mutableListOf<Float>() // ms
    private var lastBeatMs: Long? = null

    @Synchronized
    fun push(rr: Float, max: Int = 30) {
        if (rr <= 0f) return
        if (rrIntervals.size >= max) rrIntervals.removeAt(0)
        rrIntervals.add(rr)
    }

    @Synchronized
    fun pushFromHR(bpm: Float) {
        val now = System.currentTimeMillis()
        val last = lastBeatMs
        lastBeatMs = now
        if (last != null) {
            push((now - last).toFloat())
        } else {
            // bootstrap with a synthetic RR from bpm
            push(60000f / bpm.coerceAtLeast(30f))
        }
    }

    @Synchronized
    fun rmssd(): Float {
        if (rrIntervals.size < 2) return 0f
        var sum = 0f
        for (i in 1 until rrIntervals.size) {
            val d = rrIntervals[i] - rrIntervals[i - 1]
            sum += d * d
        }
        val raw = sqrt(sum / (rrIntervals.size - 1))
        return smoother.filter(raw)
    }

    private object smoother {
        private var last = 0f
        fun filter(v: Float, alpha: Float = 0.15f): Float {
            last += alpha * (v - last)
            return last
        }
    }
}
