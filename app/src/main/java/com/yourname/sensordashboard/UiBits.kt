package com.yourname.sensordashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Public UI bits used across pages (no duplicate helpers).
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
        drawCircle(UiSettings.glowColor.copy(alpha = 0.5f), radius = r*1.4f, center = Offset(cx, cy))
        drawCircle(UiSettings.accentColor, radius = r, center = Offset(cx, cy))
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
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(r*2, r*2),
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )

        // needle (compose uses degrees clockwise from 3 o'clock)
        val angRad = ((-heading + 90f) * (PI/180.0)).toFloat()
        val nx = cx + cos(angRad) * r
        val ny = cy - sin(angRad) * r
        drawLine(UiSettings.accentColor, start = Offset(cx, cy), end = Offset(nx, ny), strokeWidth = 4f)

        // strength ring
        val ir = r * (0.3f + 0.6f * strengthNorm.coerceIn(0f,1f))
        drawArc(
            color = UiSettings.glowColor,
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(cx - ir, cy - ir), size = Size(ir*2, ir*2),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun ThinDivider() {
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .clip(RoundedCornerShape(0.dp))
            .background(Color(0x22, 0xFF, 0xFF))
    )
}

/* ======= Extras used by Dashboard (if present) ======= */

@Composable
fun NeonHeatBarNormalized(normIn: Float) {
    val norm = normIn.coerceIn(0f,1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(norm) { anim.animateTo(norm, tween(220)) }
    val track = Color(0x33,0xFF,0xFF)
    val glow  = UiSettings.glowColor
    val core  = UiSettings.accentColor
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(anim.value).height(10.dp).background(glow.copy(alpha = 0.6f)))
        Box(
            Modifier.fillMaxWidth((anim.value * 0.98f).coerceAtLeast(0.02f)).height(6.dp)
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(core)
        )
    }
}

@Composable
fun GyroWaveform(hx: List<Float>, hy: List<Float>, hz: List<Float>, range: Float = 6f) {
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val w = size.width; val h = size.height; val mid = h/2f
        fun mapY(v: Float): Float {
            val c = v.coerceIn(-range, range)
            return mid - (c/range)*(h*0.45f)
        }
        val grid = Color(0x22,0xFF,0xFF)
        drawLine(grid, Offset(0f, mid), Offset(w, mid), 1f)
        val columns = 8; val stepX = w/columns
        for (i in 1 until columns) drawLine(grid.copy(alpha = 0.15f), Offset(stepX*i,0f), Offset(stepX*i,h), 1f)

        fun seriesPass(series: List<Float>, a: Float, s: Float, col: Color) {
            if (series.size < 2) return
            val step = w / (series.size - 1).coerceAtLeast(1)
            var prev = Offset(0f, mapY(series[0]))
            for (i in 1 until series.size) {
                val x = step*i
                val y = mapY(series[i])
                drawLine(col.copy(alpha = a), prev, Offset(x, y), s)
                prev = Offset(x, y)
            }
        }
        val gold = UiSettings.accentColor
        val violet = UiSettings.glowColor.copy(alpha = 0.8f)
        val cyan = Color(0x00,0xD0,0xFF)
        seriesPass(hx, 0.22f, 7f, gold); seriesPass(hy, 0.35f, 4f, violet); seriesPass(hz, 1f, 2f, cyan)
    }
}

@Composable
fun GravityTuner(values: FloatArray) {
    // ultra-sensitive gauge around 1g (9.81 m/s²)
    val g = magnitude(values)
    val center = 9.81f
    val span = 0.30f // ±0.15g window
    val norm = ((g - (center - span/2f)) / span).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(54.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h*0.65f
        val r = min(w,h)*0.45f
        // arc track
        drawArc(Color(0x22,0xFF,0xFF), 180f, 180f, false,
            topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2), style = Stroke(6f, StrokeCap.Round))
        // needle
        val ang = 180f + 180f * norm
        val rad = (ang * (PI/180.0)).toFloat()
        val nx = cx + cos(rad)*r
        val ny = cy + sin(rad)*r
        drawLine(UiSettings.accentColor, Offset(cx,cy), Offset(nx,ny), 4f)
    }
}
