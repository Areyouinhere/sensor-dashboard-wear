package com.yourname.sensordashboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.Canvas
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Page 3: Coherence Compass – composite & next-stage guidance.
 * Distinct from the ring glyph: readiness arc + sub-signal tiles + adaptive tips.
 */
@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f, 0f, 0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0)   ?: 1013f
    val stepsSession = readings["Step Counter"]?.getOrNull(1) ?: 0f
    val hrv   = HRVHistory.rmssd()

    fun soft01(x: Float) = x.coerceIn(0f, 1f)
    fun knee(x: Float, k: Float = 0.6f): Float {
        val t = x.coerceIn(0f, 1f)
        return if (t < k) t / k * 0.7f else 0.7f + (t - k) / (1f - k) * 0.3f
    }

    val accelMag = magnitude(accel)
    val gyroMag  = magnitude(gyro)
    val nAccel   = soft01(accelMag / 6f)
    val nGyro    = soft01(gyroMag  / 4f)
    val hrMid    = 65f
    val hrSpan   = 50f
    val hrBand   = soft01(1f - abs((0.5f + (hr - hrMid)/(2f*hrSpan)) - 0.5f)*2f)
    val envBal   = soft01(1f - abs(((press - 1013f)/50f).coerceIn(-1f,1f)))

    val hrvCap   = soft01(hrv / 80f)

    val motionStability = knee(1f - nGyro)
    val movement        = knee(nAccel)
    val recovery        = knee(hrvCap)
    val hrCentered      = knee(hrBand)
    val envCentered     = knee(envBal)

    val readiness = (0.4f*recovery + 0.25f*hrCentered + 0.2f*motionStability + 0.1f*movement + 0.05f*envCentered)
        .coerceIn(0f,1f)
    val readinessLabel = when {
        readiness >= 0.66f -> "GREEN"
        readiness >= 0.40f -> "YELLOW"
        else -> "RED"
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Readiness Compass",
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(4.dp))
        DividerLine()
        Spacer(Modifier.height(8.dp))

        // Readiness arc (red→yellow→green)
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r  = min(size.width, size.height) * 0.35f

                // track
                drawArc(
                    color = Color(0x22,0xFF,0xFF),
                    startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(cx - r, cy - r), size = Size(r*2, r*2),
                    style = Stroke(width = 12f, cap = StrokeCap.Round)
                )

                fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
                fun mix(c1: Color, c2: Color, t: Float) = Color(
                    lerp(c1.red, c2.red, t), lerp(c1.green, c2.green, t), lerp(c1.blue, c2.blue, t), 1f
                )
                val red = Color(0xFF,0x44,0x44)
                val yel = Color(0xFF,0xD7,0x00)
                val grn = Color(0x44,0xFF,0x88)
                val mid = mix(red, yel, readiness)
                val valColor = mix(mid, grn, readiness)

                drawArc(
                    color = valColor,
                    startAngle = 135f,
                    sweepAngle = max(6f, 270f * readiness),
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r*2, r*2),
                    style = Stroke(width = 14f, cap = StrokeCap.Round)
                )
            }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("${fmtPct(readiness)} • $readinessLabel",
                    fontSize = 14.sp,
                    color = when (readinessLabel) {
                        "GREEN" -> Color(0x88,0xFF,0xAA)
                        "YELLOW"-> Color(0xFF,0xD7,0x00)
                        else    -> Color(0xFF,0x66,0x66)
                    }
                )
                Spacer(Modifier.height(2.dp))
                Text("HRV ${fmtMs(hrv)} • HR ${hr.toInt()} bpm", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            }
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        // --- Signals tile ---
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(UiSettings.bubbleBgColor)
                .padding(10.dp)
        ) {
            Text("Signals", fontSize = 12.sp, color = Color(0xEE,0xFF,0xFF))
            Spacer(Modifier.height(6.dp))
            Text("Recovery (HRV capacity) ${fmtPct(recovery)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(recovery)
            Spacer(Modifier.height(6.dp))
            Text("HR Centering ${fmtPct(hrCentered)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(hrCentered)
            Spacer(Modifier.height(6.dp))
            Text("Motion Stability ${fmtPct(motionStability)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(motionStability)
            Spacer(Modifier.height(6.dp))
            Text("Movement (accel) ${fmtPct(movement)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(movement)
            Spacer(Modifier.height(6.dp))
            Text("Env Balance ${fmtPct(envCentered)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(envCentered)
        }

        Spacer(Modifier.height(10.dp))

        // --- Steps tile ---
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(UiSettings.bubbleBgColor)
                .padding(10.dp)
        ) {
            Text("Today’s Steps", fontSize = 12.sp, color = Color(0xEE,0xFF,0xFF))
            Spacer(Modifier.height(4.dp))
            val norm = (stepsSession / 12_000f).coerceIn(0f,1f)
            Text("${stepsSession.toInt()} session", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(norm)
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        // --- Next steps tile ---
        val tipsBase = buildList {
            if (recovery < 0.45f) add("Keep it easy: prioritize sleep & light activity.")
            if (hrCentered < 0.5f) add("HR drifting: 5–7 min nasal breathing / easy walk.")
            if (motionStability < 0.55f) add("High jitter: 1–2 sets balance/mobility.")
            if (movement < 0.25f) add("Low movement: take a 10–15 min walk to prime.")
            if (envCentered < 0.45f) add("Pressure shift: ease into intensity; hydrate.")
        }
        val tips = if (tipsBase.isEmpty()) listOf("You’re in a good zone — green light for planned work.") else tipsBase

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(UiSettings.bubbleBgColor)
                .padding(10.dp)
        ) {
            Text("Next Steps", fontSize = 12.sp, color = Color(0xFF,0xD7,0x00))
            Spacer(Modifier.height(6.dp))
            tips.forEach { line -> Text("• $line", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF)) }
        }

        Spacer(Modifier.height(12.dp))
    }
}
