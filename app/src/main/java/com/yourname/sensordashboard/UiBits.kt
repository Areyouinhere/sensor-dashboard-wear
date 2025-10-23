package com.yourname.sensordashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Made public (no file-private) so MainActivity can call them.
 * If you already have duplicates in MainActivity, DELETE those to avoid redeclarations.
 */

@Composable
fun HeartPulse(bpm: Float) {
    val beatMs = (60000f / bpm.coerceAtLeast(30f)).toLong()
    val scale = remember { Animatable(0.8f) }
    LaunchedEffect(bpm) {
        while (true) {
            scale.animateTo(1.1f, tween((beatMs * 0.35).toInt()))
            scale.animateTo(0.8f, tween((beatMs * 0.65).toInt()))
        }
    }
    Canvas(Modifier.fillMaxWidth().height(38.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h/2f
        val r  = min(w,h) * 0.18f * scale.value
        drawCircle(Color(0x66,0x00,0xEA), radius = r*1.4f, center = Offset(cx, cy))
        drawCircle(Color(0xFF,0xD7,0x00), radius = r,       center = Offset(cx, cy))
    }
}

@Composable
fun MagneticDial(heading: Float, strengthNorm: Float) {
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h/2f
        val r = min(w, h) * 0.42f

        // outer ring
        drawArc(
            color = Color(0x22, 0xFF, 0xFF),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r*2, r*2),
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )

        // needle
        val ang = (-heading + 90f) * (PI/180f).toFloat()
        val nx = cx + cos(ang) * r
        val ny = cy - sin(ang) * r
        drawLine(Color(0xFF,0xD7,0x00), start = Offset(cx, cy), end = Offset(nx, ny), strokeWidth = 4f)

        // inner strength ring
        val ir = r * (0.3f + 0.6f * strengthNorm.coerceIn(0f,1f))
        drawArc(
            color = Color(0x66, 0x00, 0xEA),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(cx - ir, cy - ir),
            size = Size(ir*2, ir*2),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}

/** Small visual divider if you want one here too. */
@Composable
fun ThinDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .clip(RoundedCornerShape(0.dp))
            .background(Color(0x22, 0xFF, 0xFF))
    )
}
