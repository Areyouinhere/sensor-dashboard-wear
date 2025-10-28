package com.yourname.sensordashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.min

@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    // recompute coherence continuously (lightweight)
    LaunchedEffect(readings.hashCode()) { CompassModel.recompute() }

    val comp by CompassModel.composite
    val conf by CompassModel.confidence
    val pulse by CompassModel.pulseSignal
    val grounded by CompassModel.grounded
    val history = CompassModel.coherenceHistory

    Column(
        Modifier.fillMaxSize().padding(12.dp)
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    // Swipe-up micro-trend expose: if dragAmount < 0 show trend (no state needed, kept always visible)
                }
            }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Coherence Compass", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (grounded) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22,0xFF,0xD7))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text("Grounded", fontSize = 10.sp) }
            }
        }
        Spacer(Modifier.height(6.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        // Big dial
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val w = size.width; val h = size.height
            val cx = w/2f; val cy = h/2f
            val r  = min(w,h)*0.42f

            // Aura from Micro-Weather (MainActivity supplies via ambient fields; here we show pulse)
            val pulseAlpha = (0.15f + 0.35f*pulse).coerceIn(0f,0.6f)
            drawCircle(Color(0xFF,0xD7,0x00).copy(alpha=pulseAlpha), radius = r*1.05f, center = Offset(cx,cy))

            // Dial track
            drawCircle(Color(0x22,0xFF,0xFF), radius = r, center = Offset(cx,cy))
            // Fill proportional to coherence
            val angle = 360f * comp
            drawArc(
                color = Color(0xFF,0xD7,0x00),
                startAngle = -90f, sweepAngle = angle, useCenter = false,
                topLeft = Offset(cx-r, cy-r), size = androidx.compose.ui.geometry.Size(r*2, r*2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 10f)
            )
            // Confidence ring
            drawCircle(Color(0x66,0xFF,0xFF).copy(alpha=(0.2f+0.6f*conf)), radius = r*0.8f, center = Offset(cx,cy))
        }

        Spacer(Modifier.height(6.dp))
        Text("Composite: ${fmtPct(comp)} • Confidence: ${fmtPct(conf)}", fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF))
        Spacer(Modifier.height(8.dp))

        // Micro-trend sparkline (last few minutes, no DB)
        Text("Recent Trend", fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(42.dp)) {
            val w = size.width; val h = size.height
            if (history.size >= 2) {
                val step = w / (history.size - 1).coerceAtLeast(1)
                var prev = Offset(0f, h*(1f - history[0].coerceIn(0f,1f)))
                for (i in 1 until history.size) {
                    val y = h*(1f - history[i].coerceIn(0f,1f))
                    val x = step * i
                    drawLine(Color(0xFF,0xD7,0x00), prev, Offset(x,y), 2f)
                    prev = Offset(x,y)
                }
            } else {
                drawLine(Color(0x22,0xFF,0xFF), Offset(0f,h/2f), Offset(w,h/2f), 1f)
            }
        }
    }
}
