package com.yourname.sensordashboard

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shared math + formatting helpers */
fun magnitude(v: FloatArray): Float = sqrt(v.fold(0f) { s, x -> s + x*x })
fun fmtPct(v: Float): String = "${(v.coerceIn(0f,1f)*100f).roundToInt()}%"
fun fmtMs(v: Float): String  = "${v.roundToInt()} ms"
fun fmt1(v: Float): String   = "%.1f".format(v.coerceIn(0f,1f))
