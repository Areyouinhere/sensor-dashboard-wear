package com.yourname.sensordashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.abs
import kotlin.math.min

@Composable
fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f,0f,0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f,0f,0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0)   ?: 1000f
    val hrv   = HRVHistory.rmssd()

    fun soft01(x: Float) = x.coerceIn(0f, 1f)
    fun knee(x: Float, k: Float = 0.6f): Float {
        val t = x.coerceIn(0f,1f)
        return if (t < k) t/k*0.7f else 0.7f + (t-k)/(1f-k)*0.3f
    }

    val accelMag = magnitude(accel)
    val gyroMag  = magnitude(gyro)
    val nAccel   = soft01(accelMag / 6f)
    val nGyro    = soft01(gyroMag  / 4f)
    val hrMid    = 65f
    val hrSpan   = 50f
    val nHR      = soft01(0.5f + (hr - hrMid)/(2f*hrSpan))
    val nP       = soft01((press - 980f)/70f)
    val nHRV     = soft01(hrv/80f)

    // EMA smoothing
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
    val composite = (0.35f*hrvPresence + 0.25f*hrPresence + 0.2f*motionStability +
            0.1f*accelPresence + 0.1f*envBalance).coerceIn(0f,1f)

    // Local "confidence" (motion-gated): more motion -> lower confidence
    val conf = (1f - nGyro).coerceIn(0.1f, 1f)

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Coherence", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        Canvas(Modifier.fillMaxWidth().height(176.dp)) {
            val cx = size.width/2f
            val cy = size.height/2f
            val baseR = min(size.width,size.height)*0.26f
            val gap = 14f

            fun ring(idx: Int, pct: Float, glow: Color, core: Color) {
                val r = baseR + gap*idx
                val d = r*2f
                val tl = Offset(cx-r, cy-r)
                val sz = Size(d,d)
                // track
                drawArc(
                    color = Color(0x22,0xFF,0xFF),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = tl, size = sz, style = Stroke(width = 8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                // glow (confidence gates alpha)
                drawArc(
                    color = glow.copy(alpha = 0.6f * conf + 0.2f),
                    startAngle = -90f, sweepAngle = 360f*pct, useCenter = false,
                    topLeft = tl, size = sz, style = Stroke(width = 10f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                // core
                drawArc(
                    color = core,
                    startAngle = -90f, sweepAngle = (360f*pct).coerceAtLeast(6f), useCenter = false,
                    topLeft = tl, size = sz, style = Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }

            ring(0, hrvPresence,     Color(0x55,0xFF,0xAA), Color(0xFF,0xCC,0x66))
            ring(1, hrPresence,      Color(0x66,0x80,0xFF), Color(0xFF,0xE0,0x80))
            ring(2, motionStability, Color(0x55,0xD0,0xFF), Color(0xAA,0xFF,0xFF))
            ring(3, accelPresence,   Color(0x66,0xFF,0xD7), Color(0xFF,0xE6,0x88))
            ring(4, envBalance,      Color(0x55,0xFF,0x99), Color(0xDD,0xFF,0x99))

            // Center: red → purple → blue gradient sized by composite (toggleable)
            if (AppSettings.showCenterGlow) {
                val maxR = baseR - 6f
                val coreR = (maxR * (0.35f + 0.65f*composite))
                val shader = Brush.radialGradient(
                    colors = listOf(Color(0xFF,0x33,0x33), Color(0x99,0x33,0xCC), Color(0x33,0x66,0xFF)),
                    center = Offset(cx, cy),
                    radius = coreR
                )
                drawCircle(color = Color.White.copy(alpha = 0.06f), radius = coreR * 1.2f, center = Offset(cx, cy))
                drawCircle(brush = shader, radius = coreR, center = Offset(cx, cy))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("HRV ${fmtMs(hrv)} • HR ${hr.toInt()} bpm • Motion ${fmtPct(1f - nGyro)}",
            fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF))
        Text("Accel ${fmt1(nAccel)} • Env ${fmtPct(1f - kotlin.math.abs(nP-0.5f)*2f)} • Coherence ${fmtPct(composite)}",
            fontSize = 11.sp, color = Color(0x99,0xFF,0xFF))
    }
}
