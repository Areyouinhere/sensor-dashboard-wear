package com.yourname.sensordashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    val composite by CompassModel.composite
    val confidence by CompassModel.confidence
    val pulse by CompassModel.pulseSignal
    val grounded by CompassModel.grounded

    // Gravity Anchor Gesture: watch level ±5° for ~3s
    LaunchedEffect(orientationDegState.value) {
        val ori = orientationDegState.value
        val pitch = ori.getOrNull(1)?.let { abs(it) } ?: 0f
        val roll  = ori.getOrNull(2)?.let { abs(it) } ?: 0f
        if (pitch < 5f && roll < 5f) {
            delay(3000)
            // Re-check after delay to ensure it stayed steady
            val again = orientationDegState.value
            val p2 = again.getOrNull(1)?.let { abs(it) } ?: 0f
            val r2 = again.getOrNull(2)?.let { abs(it) } ?: 0f
            if (p2 < 5f && r2 < 5f) {
                CompassModel.grounded.value = true
                // optional: haptic cue later
            }
        } else {
            CompassModel.grounded.value = false
        }
    }

    var showTrend by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount < -12f) showTrend = true   // swipe up
                        if (dragAmount > 12f)  showTrend = false  // swipe down
                    }
                )
            }
    ) {
        Text("Coherence Compass", color = Color.White, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(6.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // Dial with moment pulse and confidence outline
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val cx = size.width/2f
                val cy = size.height/2f
                val r  = min(size.width, size.height) * 0.42f

                // confidence outline
                val a = 0.25f + 0.6f*confidence
                drawCircle(Color(0x66,0xFF,0xFF).copy(alpha = a), radius = r+6f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(6f))

                // main ring
                drawCircle(Color(0x22,0xFF,0xFF), radius = r, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(8f))

                // fill proportional to composite
                val inner = r * (0.35f + 0.60f * composite.coerceIn(0f,1f))
                drawCircle(Color(0xFF,0xD7,0x00).copy(alpha = 0.25f + 0.55f*composite), radius = inner, center = Offset(cx, cy))

                // moment signature pulse halo
                val halo = inner * (1.00f + 0.10f * pulse.coerceIn(0f,1f))
                drawCircle(Color(0xFF,0xF0,0xAA).copy(alpha = 0.20f + 0.50f * pulse), radius = halo, center = Offset(cx, cy))
            }

            // grounded badge
            if (grounded) {
                Text("Grounded", color = Color(0xFF,0xF0,0xAA), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        Spacer(Modifier.height(6.dp))
        Text("Coherence ${fmtPct(composite)} • Confidence ${fmtPct(confidence)} • Pulse ${fmt1(pulse)}",
            color = Color(0xCC,0xFF,0xFF), fontSize = 12.sp)

        Spacer(Modifier.height(6.dp))
        if (showTrend) {
            Text("Swipe down to hide trend", color = Color(0x99,0xFF,0xFF), fontSize = 11.sp)
            QuickSparkline(CompassModel.coherenceHistory, modifier = Modifier.fillMaxWidth())
        } else {
            Text("Swipe up for micro-trend", color = Color(0x99,0xFF,0xFF), fontSize = 11.sp)
        }
    }
}
