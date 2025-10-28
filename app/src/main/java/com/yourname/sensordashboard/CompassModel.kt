package com.yourname.sensordashboard


import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min


/**
* Coherence / Readiness model with HRV banding, soft-knee mixing,
* motion-gated confidence, and short-term pulse amplitude.
*/
object CompassModel {


// 21-sample rolling windows (10–30s depending on push cadence)
private val hrWindow = Rolling(21)
private val hrvWindow = Rolling(21)
private val accelWin = Rolling(21)
private val gyroWin = Rolling(21)
private val lightWin = Rolling(21)


// Micro load bucket → ACWR-lite
private var microLoad: Float = 0f
private val acuteWindow = Rolling(7)
private val chronicWindow = Rolling(28)


// Exposed for Moment Signature Pulse (0..1)
val pulseSignal = MutableStateFlow(0f)


fun pushHR(bpm: Float) { if (bpm.isFinite() && bpm > 0f) hrWindow.push(bpm) }
fun pushHRV(rmssdMs: Float) { if (rmssdMs.isFinite() && rmssdMs > 0f) hrvWindow.push(rmssdMs) }


fun addMicroLoad(m: Float) {
if (!m.isFinite()) return
accelWin.push(m)
microLoad = (microLoad + min(m, 1.5f)).coerceAtMost(500f)
// moment pulse from accel deltas + light changes + HRV change
val hrvs = hrvWindow.deltaNorm()
val accs = accelWin.deltaNorm()
val lights = lightWin.deltaNorm()
val pulse = (0.5f*hrvs + 0.3f*accs + 0.2f*lights).coerceIn(0f,1f)
pulseSignal.value = pulse
}


fun pushGyroNorm(g: Float) { if (g.isFinite()) gyroWin.push(g) }
fun pushLight(lux: Float) { if (lux.isFinite()) lightWin.push(lux) }


fun notifySessionReset() {
acuteWindow.push(microLoad)
chronicWindow.push(microLoad)
microLoad = 0f
}


data class Composite(val composite: Float, val confidence: Float)


/**
* Composite coherence using banded HRV, HR centering, motion stability,
* acceleration presence, and environment normalization.
*/
fun composite(
accelMag: Float, gyroMag: Float, hr: Float,
press: Float, humidity: Float, light: Float, hrv: Float
): Composite {
// HR band — centered around personal mean with soft knee
val hrMu = hrWindow.mean().takeIf { it > 0f } ?: 65f
val hrScore = bandScore(value = if (hr.isFinite()) hr else hrMu, center = hrMu, halfSpan = 10f)


// HRV remodel: peak in an optimal band (triangular w/ soft edges)
// Define personal band: [low, optLo, optHi, high]
val hmu = hrvWindow.mean().takeIf { it > 0f } ?: 40f
val low = max(15f, 0.6f*hmu)
val optLo = max(25f, 0.9f*hmu)
val optHi = max(40f, 1.3f*hmu)
val high = max(70f, 1.8f*hmu)
val hrvScore = bandedPeak(hrv, low, optLo, optHi, high)


// Motion stability → from gyro magnitude (lower is better)
val motionStability = softKnee(1f - (gyroMag/4f).coerceIn(0f,1f))
// Movement presence → from accel magnitude (mid is OK, too high lowers)
val move = softKnee((accelMag/6f).coerceIn(0f,1f))
val movement = 1f - 0.6f*move // prefer calmer movement for coherence reading


// Env balance (pressure around mid; humidity comfortable 35–60; light log-scale)
}
