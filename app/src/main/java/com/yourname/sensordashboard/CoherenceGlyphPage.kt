package com.yourname.sensordashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.abs
import kotlin.math.min

@Composable
fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
    // source signals (safe defaults)
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f, 0f, 0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0)   ?: 1000f
    val hrv   = HRVHistory.rmssd() // shared in UiBits

    val accelMag = magnitude(accel)
    val gyroMag  = magnitude(gyro)

    fun soft01(x: Float) = x.coerceIn(0f, 1f)
    fun knee(x: Float, k: Float = 0.6f): Float {
        val t = x.coerceIn(0f,1f)
        return if (t < k) t/k*0.7f else 0.7f + (t-k)/(1f-k)*0.3f
    }

    val nAccel   = soft01(accelMag / 6f)
    val nGyro    = soft01(gyroMag  / 4f)
    val hrMid    = 65f
    val hrSpan   = 50f
    val nHR      = soft01(0.5f + (hr - hrMid)/(2f*hrSpan))
    val nP       = soft01((press - 980f)/70f)
    val nHRV     = soft01(hrv/80f)

    val ema = remember { mutableStateOf(floatArrayOf(nAccel, nGyro, nHR, nP, nHRV)) }
    val alpha = 0.12f
    val target = floatArrayOf(nAccel, nGyro, nHR, nP, nHRV)
    val s = FloatArray(5) { i -> ema.value[i] + alpha*(target[i]-ema.value[i]) }
    ema.value = s

    val hrvPresence      = knee(s[4])
    val hrPresence       = knee(1f - abs(s[2]-0.5f)*2f)
    val motionStability  = knee(1f - s[1])
    val accelPresence    = knee(s[0])
    val envBalance       = knee(1f - abs(s[3]-0.5f)*2f)

    // confidence gating from CompassModel
    val conf by CompassModel.confidence
    val comp by CompassModel.composite

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Coherence",
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val cx = size.width/2f
            val cy = size.height/2f
            val baseR = min(size.width,size.height)*0.26f
            val gap = 14f

            fun ring(idx: Int, pct: Float, hue: Color) {
                val r = baseR + gap*idx
                val d = r*2f
                val tl = Offset(cx-r, cy-r)
                val sz = Size(d,d)
                // track
                drawArc(
                    color = Color(0x22,0xFF,0xFF),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = tl, size = sz,
                    style = Stroke(8f, cap = StrokeCap.Round)
                )
                // intensity scales with confidence
                val alpha = (0.35f + 0.65f*conf).coerceIn(0f,1f)
                drawArc(
                    color = hue.copy(alpha = alpha),
                    startAngle = -90f, sweepAngle = 360f*pct, useCenter = false,
                    topLeft = tl, size = sz,
                    style = Stroke(6f, cap = StrokeCap.Round)
                )
            }

            ring(0, hrvPresence,     Color(0xFF,0xCC,0x66))
            ring(1, hrPresence,      Color(0xFF,0xE0,0x80))
            ring(2, motionStability, Color(0xAA,0xFF,0xFF))
            ring(3, accelPresence,   Color(0xFF,0xE6,0x88))
            ring(4, envBalance,      Color(0xDD,0xFF,0x99))

            // composite center glow
            val maxR = baseR - 6f
            val coreR = (maxR * (0.35f + 0.65f*comp))
            drawCircle(Color(0x33,0xFF,0xD7,0x00), radius = coreR*1.15f, center = Offset(cx,cy))
            drawCircle(Color(0xFF,0xD7,0x00), radius = coreR*0.75f, center = Offset(cx,cy))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "HRV ${fmtMs(hrv)} • HR ${hr.toInt()} bpm • Motion ${fmtPct(1f - nGyro)}",
            fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Accel ${fmt1(nAccel)} • Env ${fmtPct(1f - abs(nP-0.5f)*2f)} • Coherence ${fmtPct(comp)}",
            fontSize = 11.sp, color = Color(0x99,0xFF,0xFF)
        )
    }
}
