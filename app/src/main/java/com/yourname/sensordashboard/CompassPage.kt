package com.yourname.sensordashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * Public, reusable divider so other files can call it.
 */
@Composable
fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .clip(RoundedCornerShape(0.dp))
            .background(Color(0x22, 0xFF, 0xFF))
    )
}

/**
 * The Compass page you wanted live (not the “coming soon” stub).
 * It reads from current sensor values passed in from MainActivity.
 */
@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    // Pull signals
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f, 0f, 0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0)   ?: 1000f
    val hrv   = HRVHistory.rmssd()

    // Human-calibrated norms
    fun soft01(x: Float) = x.coerceIn(0f, 1f)
    fun softKnee(x: Float, knee: Float = 0.6f): Float {
        val t = x.coerceIn(0f, 1f)
        return if (t < knee) t / knee * 0.7f else 0.7f + (t - knee) / (1f - knee) * 0.3f
    }

    val accelMag = magnitude(accel)                // m/s²
    val gyroMag  = magnitude(gyro)                 // rad/s
    val nAccel   = soft01(accelMag / 6f)           // lively 0..~6
    val nGyro    = soft01(gyroMag  / 4f)
    val hrMid    = 65f
    val hrSpan   = 50f
    val nHR      = soft01(0.5f + (hr - hrMid) / (2f * hrSpan))
    val nP       = soft01((press - 980f) / 70f)
    val nHRV     = soft01(hrv / 80f)

    // Smoothing
    val ema = remember { mutableStateOf(floatArrayOf(nAccel, nGyro, nHR, nP, nHRV)) }
    val alpha = 0.12f
    val target = floatArrayOf(nAccel, nGyro, nHR, nP, nHRV)
    val smoothed = FloatArray(5) { i -> ema.value[i] + alpha * (target[i] - ema.value[i]) }
    ema.value = smoothed

    val hrvPresence      = softKnee(smoothed[4])
    val hrPresence       = softKnee(1f - abs(smoothed[2] - 0.5f) * 2f)
    val motionStability  = softKnee(1f - smoothed[1])
    val accelPresence    = softKnee(smoothed[0])
    val envBalance       = softKnee(1f - abs(smoothed[3] - 0.5f) * 2f)

    var showDetail by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Coherence",
            fontSize = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(4.dp))
        DividerLine()
        Spacer(Modifier.height(6.dp))

        // Glyph
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseR = min(size.width, size.height) * 0.28f
            val gap = 14f

            fun ring(idx: Int, pct: Float, glow: Color, core: Color) {
                val r = baseR + gap * idx
                val d = r * 2f
                val topLeft = Offset(cx - r, cy - r)
                val sz = Size(d, d)

                // Use NAMED parameters so StrokeCap isn’t mistaken for alpha
                drawArc(
                    color = Color(0x22, 0xFF, 0xFF),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = sz,
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = glow,
                    startAngle = -90f,
                    sweepAngle = 360f * pct.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = sz,
                    style = Stroke(width = 10f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = core,
                    startAngle = -90f,
                    sweepAngle = (360f * pct).coerceAtLeast(6f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = sz,
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )
            }

            ring(0, hrvPresence,     Color(0x55, 0xFF, 0xAA), Color(0xFF, 0xCC, 0x66)) // HRV
            ring(1, hrPresence,      Color(0x66, 0x80, 0xFF), Color(0xFF, 0xE0, 0x80)) // HR
            ring(2, motionStability, Color(0x55, 0xD0, 0xFF), Color(0xAA, 0xFF, 0xFF)) // Gyro
            ring(3, accelPresence,   Color(0x66, 0xFF, 0xD7), Color(0xFF, 0xE6, 0x88)) // Accel
            ring(4, envBalance,      Color(0x55, 0xFF, 0x99), Color(0xDD, 0xFF, 0x99)) // Env
        }

        Spacer(Modifier.height(8.dp))

        // Readouts
        Text(
            "HRV ${fmtMs(hrv)} • HR ${hr.toInt()} bpm • Motion ${fmtPct(1f - nGyro)}",
            fontSize = 12.sp,
            color = Color(0xCC, 0xFF, 0xFF)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Accel ${fmt1(nAccel)} • Env ${fmtPct(1f - abs(nP - 0.5f) * 2f)}",
            fontSize = 11.sp,
            color = Color(0x99, 0xFF, 0xFF)
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (showDetail) "Hide explanation ▲" else "What is this? ▼",
            fontSize = 11.sp,
            color = Color(0xFF, 0xD7, 0x00),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { showDetail = !showDetail }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        if (showDetail) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Rings show HRV capacity, HR mid-banding, motion stability, movement, and pressure centering. " +
                        "Signals are normalized and smoothed so you see trend over jitter.",
                fontSize = 11.sp,
                color = Color(0xAA, 0xFF, 0xFF),
                lineHeight = 14.sp
            )
        }
    }
}
