package com.yourname.sensordashboard

import androidx.compose.runtime.mutableStateListOf
import kotlin.math.sqrt

/** Holds RR intervals (ms) and provides a smoothed RMSSD. */
object HRVHistory {
    private val rrIntervals = mutableStateListOf<Float>() // ms
    private var lastBeatMs: Long? = null

    /** Push a new RR interval (ms). Keeps only the latest ~30 samples. */
    fun push(rr: Float, max: Int = 30) {
        if (rr <= 0f) return
        if (rrIntervals.size >= max) rrIntervals.removeAt(0)
        rrIntervals.add(rr)
    }

    /** Try to infer RR from heart-beat events or heart rate (bpm). */
    fun pushFromHR(bpm: Float) {
        val now = System.currentTimeMillis()
        val last = lastBeatMs
        lastBeatMs = now
        if (last != null) {
            // We have two beat timestamps -> direct RR
            push((now - last).toFloat())
        } else if (bpm > 0f) {
            // First sample in a session: approximate RR from bpm
            push(60000f / bpm.coerceAtLeast(30f))
        }
    }

    /** Smoothed RMSSD over the RR list. Returns 0 if insufficient data. */
    fun rmssd(): Float {
        if (rrIntervals.size < 2) return 0f
        var sum = 0f
        for (i in 1 until rrIntervals.size) {
            val d = rrIntervals[i] - rrIntervals[i - 1]
            sum += d * d
        }
        val raw = sqrt(sum / (rrIntervals.size - 1))
        return HRVSmoother.filter(raw)
    }
}

private object HRVSmoother {
    private var last = 0f
    fun filter(v: Float, alpha: Float = 0.15f): Float {
        last += alpha * (v - last)
        return last
    }
}
