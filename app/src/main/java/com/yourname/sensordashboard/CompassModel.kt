package com.yourname.sensordashboard

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.min

data class DaySnapshot(
    val hrNow: Int,        // bpm
    val hrvNow: Int,       // RMSSD ms
    val stepsToday: Int    // steps (session delta or daily)
)

class Rolling(private val window: Int = 21) {
    private val hr = ArrayList<Int>()
    private val hrv = ArrayList<Int>()

    fun pushHR(v: Int) {
        if (v <= 0) return
        hr.add(v)
        if (hr.size > window) hr.removeAt(0)
    }

    fun pushHRV(v: Int) {
        if (v <= 0) return
        hrv.add(v)
        if (hrv.size > window) hrv.removeAt(0)
    }

    fun muHR(): Double = if (hr.isEmpty()) 0.0 else hr.average()
    fun muHRV(): Double = if (hrv.isEmpty()) 0.0 else hrv.average()

    fun sdHR(): Double {
        if (hr.size < 2) return 0.0
        val mu = muHR()
        val varSum = hr.sumOf { (it - mu).pow(2) }
        return sqrt(varSum / (hr.size - 1))
    }

    fun sdHRV(): Double {
        if (hrv.size < 2) return 0.0
        val mu = muHRV()
        val varSum = hrv.sumOf { (it - mu).pow(2) }
        return sqrt(varSum / (hrv.size - 1))
    }

    fun last7MeanHRV(): Double? {
        if (hrv.isEmpty()) return null
        val take = min(7, hrv.size)
        return hrv.takeLast(take).average()
    }
}

object Compass {
    enum class State { GREEN, YELLOW, RED }
    data class Result(val state: State, val notes: List<String>)

    fun readiness(day: DaySnapshot, hist: Rolling): Result {
        val notes = mutableListOf<String>()

        val muHR = hist.muHR()
        val sdHR = hist.sdHR().coerceAtLeast(1e-6)
        val zHR = (day.hrNow - muHR) / sdHR

        val muHRV = hist.muHRV()
        val sdHRV = hist.sdHRV().coerceAtLeast(1e-6)
        val zHRV = (day.hrvNow - muHRV) / sdHRV

        val last7 = hist.last7MeanHRV()
        val hrvDrop20 = last7?.let { day.hrvNow < 0.8 * it } ?: false

        var pts = 0
        if (zHRV > -0.5 && !hrvDrop20) { pts += 1 } else notes += "HRV off baseline"
        if (zHR   <  0.5)              { pts += 1 } else notes += "HR elevated vs baseline"
        if (day.stepsToday in 6000..12000) { pts += 1 } else {
            if (day.stepsToday < 4000)  notes += "Very low movement"
            if (day.stepsToday > 14000) notes += "High-load steps"
        }
        // Placeholder for subjective checks
        pts += 1

        val state = when {
            pts >= 3 -> State.GREEN
            pts <= 1 -> State.RED
            else     -> State.YELLOW
        }

        if (notes.isEmpty()) notes += "On track with baseline today"
        return Result(state, notes)
    }
}
