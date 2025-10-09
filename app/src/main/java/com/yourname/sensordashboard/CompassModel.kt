package com.yourname.sensordashboard

import kotlin.math.max
import kotlin.math.sqrt

data class DaySnapshot(
    val hrNow: Int = 0,
    val hrvNow: Int = 0,      // RMSSD, ms
    val stepsToday: Int = 0
)

class Rolling(val max: Int = 21) {
    private val hr = ArrayDeque<Int>()
    private val hrv = ArrayDeque<Int>()

    fun pushHR(v: Int) { hr += v; while (hr.size > max) hr.removeFirst() }
    fun pushHRV(v: Int) { hrv += v; while (hrv.size > max) hrv.removeFirst() }

    fun muHR(): Double  = if (hr.isEmpty()) 0.0 else hr.map { it.toDouble() }.average()
    fun sdHR(): Double  = stdev(hr.map { it.toDouble() })
    fun muHRV(): Double = if (hrv.isEmpty()) 0.0 else hrv.map { it.toDouble() }.average()
    fun sdHRV(): Double = stdev(hrv.map { it.toDouble() })

    private fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        val v = xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
        return sqrt(v)
    }
}

object Compass {
    data class Result(val state: State, val notes: List<String>)
    enum class State { GREEN, YELLOW, RED }

    fun readiness(day: DaySnapshot, roll: Rolling): Result {
        val muHr  = roll.muHR()
        val sdHr  = max(1e-6, roll.sdHR())
        val muHrv = roll.muHRV()
        val sdHrv = max(1e-6, roll.sdHRV())

        val zHr  = ((day.hrNow  - muHr ) / sdHr ).toFloat()
        val zHrv = ((day.hrvNow - muHrv) / sdHrv).toFloat()

        var pts = 0
        val notes = mutableListOf<String>()

        if (day.hrvNow >= 0.8 * muHrv && zHrv > -0.5f) { pts++; notes += "HRV holding" }
        else notes += "HRV downshift"

        if (zHr < 0.5f) { pts++; notes += "HR centered" }
        else notes += "HR elevated"

        when {
            day.stepsToday < 4000  -> notes += "Low movement → recovery bias"
            day.stepsToday > 14000 -> notes += "High load → groove/technique"
            else                   -> notes += "Movement in sweet spot"
        }

        val state = when {
            pts >= 2 -> State.GREEN
            pts == 1 -> State.YELLOW
            else     -> State.RED
        }
        return Result(state, notes)
    }
}
