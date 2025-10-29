package com.yourname.sensordashboard

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.*

class Rolling(private val alpha: Float, init: Float = 0f) {
    private var v = init
    fun push(x: Float): Float { v += alpha * (x - v); return v }
    fun value(): Float = v
}

object CompassModel {
    val composite = mutableStateOf(0f)
    val confidence = mutableStateOf(0f)
    val pulseSignal = mutableStateOf(0f)
    val grounded = mutableStateOf(false)

    val coherenceHistory = mutableStateListOf<Float>()
    private const val HISTORY_MAX = 240

    private val rollHRV = Rolling(0.15f)
    private val rollHR  = Rolling(0.10f)
    private val rollGyro= Rolling(0.12f)
    private val rollAccel=Rolling(0.12f)
    private val rollPress=Rolling(0.08f)
    private val rollLight=Rolling(0.06f)
    private val microLoad = Rolling(0.05f)

    private var lastPulseBase = 0f

    fun pushHR(bpm: Float)       { rollHR.push(bpm.coerceAtLeast(0f)) }
    fun pushHRV(rmssd: Float)    { rollHRV.push(rmssd.coerceAtLeast(0f)) }
    fun pushGyroMag(m: Float)    { rollGyro.push(m) }
    fun pushAccelMag(m: Float)   { rollAccel.push(m) }
    fun pushPressure(p: Float)   { rollPress.push(p) }
    fun pushLight(lux: Float)    { rollLight.push(lux) }
    fun addMicroLoad(m: Float)   { microLoad.push(m) }

    fun notifySessionReset() { coherenceHistory.clear() }

    private fun softKnee(x: Float, knee: Float = 0.5f): Float {
        val t = x.coerceIn(0f,1f)
        val k = knee.coerceIn(0.01f, 0.99f)
        return if (t < k) (t/k) * 0.7f else 0.7f + (t-k)/(1f-k)*0.3f
    }

    private fun bandedPeak(x: Float, low: Float, high: Float, soft: Float = 0.25f): Float {
        if (!x.isFinite() || x <= 0f) return 0f
        val cl = when {
            x < low  -> (x / low).pow(0.7f) * 0.7f
            x > high -> (high / x).pow(0.7f) * 0.7f
            else     -> 1f
        }
        return softKnee(cl, knee=soft)
    }

    fun recompute(): Float {
        val nAccel = (rollAccel.value() / 6f).coerceIn(0f, 1f)
        val nGyro  = (rollGyro.value()  / 4f).coerceIn(0f, 1f)
        val hr     = rollHR.value()
        val hrMid  = 65f; val hrSpan = 50f
        val nHR    = (0.5f + (hr - hrMid)/(2f*hrSpan)).coerceIn(0f,1f)
        val nP     = ((rollPress.value() - 980f)/70f).coerceIn(0f,1f)
        val hrv    = rollHRV.value()

        val hrvBand = bandedPeak(hrv, low = 25f, high = 90f)

        val motionPenalty = (nGyro * 0.8f + nAccel * 0.2f).coerceIn(0f,1f)
        val conf = softKnee(1f - motionPenalty, knee = 0.6f)
        confidence.value = conf

        val hrCentered    = softKnee(1f - abs(nHR - 0.5f)*2f, knee = 0.55f)
        val motionStable  = softKnee(1f - nGyro, knee = 0.5f)
        val accelPresence = softKnee(nAccel, knee = 0.65f)
        val envBalance    = softKnee(1f - abs(nP - 0.5f)*2f, knee = 0.5f)

        val base = (0.35f*hrvBand + 0.22f*hrCentered + 0.23f*motionStable +
                    0.10f*accelPresence + 0.10f*envBalance).coerceIn(0f,1f)

        val loadMod = (1f - softKnee((microLoad.value()/3f).coerceIn(0f,1f), knee=0.55f)*0.15f)
        val comp = (base * (0.7f + 0.3f*conf) * loadMod).coerceIn(0f,1f)
        composite.value = comp

        val pulseBase = (0.5f * (rollHRV.value()/90f).coerceIn(0f,1f)
                + 0.3f * nAccel
                + 0.2f * (ln(1f + rollLight.value()) / ln(1f + 40_000f)).coerceIn(0f,1f))
            .coerceIn(0f,1f)
        val delta = (pulseBase - lastPulseBase).absoluteValue
        lastPulseBase = pulseBase
        pulseSignal.value = (delta * 6f).coerceIn(0f, 1f)

        coherenceHistory.add(comp)
        if (coherenceHistory.size > HISTORY_MAX) coherenceHistory.removeAt(0)

        return comp
    }
}
