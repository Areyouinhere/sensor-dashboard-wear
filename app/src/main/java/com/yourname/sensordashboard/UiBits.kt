package com.yourname.sensordashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

/** ====== SHARED STATE / HELPERS (used across pages) ====== */

val orientationDegState = mutableStateOf(floatArrayOf(0f, 0f, 0f))

// Dynamic range helpers used by visuals
val lightScale = AutoScaler(decay = 0.997f, floor = 0.1f, ceil = 40_000f)
val magScale   = AutoScaler(decay = 0.995f, floor = 5f,   ceil = 150f)

fun magnitude(v: FloatArray): Float = sqrt(v.fold(0f) { s, x -> s + x*x })

fun fmtPct(v: Float): String = "${(v.coerceIn(0f,1f)*100f).roundToInt()}%"
fun fmtMs(v: Float): String  = "${v.roundToInt()} ms"
fun fmt1(v: Float): String   = "%.1f".format(v.coerceIn(0f,1f))

class AutoScaler(
    private val decay: Float = 0.995f,
    private val floor: Float = 0.1f,
    private val ceil: Float = 100f
) {
    private var hi = floor
    private var lo = floor
    fun norm(value: Float): Float {
        if (!value.isFinite()) return 0f
        if (value > hi) hi = min(value, ceil)
        if (value < lo) lo = max(value, floor)
        hi = max(hi * decay, value)
        lo = min(lo / decay, value)
        val span = (hi - lo).coerceAtLeast(1e-3f)
        return ((value - lo) / span).coerceIn(0f, 1f)
    }
}

// Lightweight histories used by a few visuals
object SensorHistory {
    val gyroX = mutableStateListOf<Float>()
    val gyroY = mutableStateListOf<Float>()
    val gyroZ = mutableStateListOf<Float>()
    val accel = mutableStateListOf<Float>()
    val grav  = mutableStateListOf<Float>()
    val light = mutableStateListOf<Float>()

    private fun push(list: MutableList<Float>, v: Float, max: Int = 180) {
        if (list.size >= max) list.removeAt(0)
        list.add(v)
    }
    fun pushGyro(x: Float, y: Float, z: Float) { push(gyroX, x); push(gyroY, y); push(gyroZ, z) }
    fun pushAccel(m: Float) = push(accel, m)
    fun pushGrav(m: Float)  = push(grav, m)
    fun pushLight(l: Float) = push(light, l)
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Color(0x22,0xFF,0xFF)))
}

/** ====== UI primitives we already used (kept stable) ====== */

@Composable
fun WaitingPulseDots(label: String = "Listening") {
    var dots by remember { mutableStateOf(0) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while(true) {
            alpha.animateTo(0.3f, tween(800))
            alpha.animateTo(1f, tween(800))
        }
    }
    LaunchedEffect(Unit) {
        while(true) { delay(500); dots = (dots + 1) % 4 }
    }
    androidx.wear.compose.material.Text(
        "$label${".".repeat(dots)}",
        fontSize = 12.sp,
        color = Color.Gray.copy(alpha = alpha.value)
    )
}

@Composable
fun NeonHeatBarNormalized(norm: Float) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(norm) { anim.animateTo(norm.coerceIn(0f, 1f), tween(220)) }
    val track = Color(0x33,0xFF,0xFF)
    val glow = Color(0x66,0x00,0xEA)
    val core = Color(0xFF,0xD7,0x00)
    Box(
        Modifier.fillMaxWidth().height(10.dp)
            .clip(RoundedCornerShape(5.dp)).background(track)
    ) {
        Box(Modifier.fillMaxWidth(anim.value).height(10.dp).background(glow.copy(alpha = 0.6f)))
        Box(
            Modifier.fillMaxWidth((anim.value*0.98f).coerceAtLeast(0.02f)).height(6.dp)
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(3.dp)).background(core)
        )
    }
}

@Composable
fun GyroWaveform(hx: List<Float>, hy: List<Float>, hz: List<Float>, range: Float = 6f) {
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val w = size.width; val h = size.height; val mid = h/2f
        fun mapY(v: Float): Float { val c = v.coerceIn(-range, range); return mid - (c/range)*(h*0.45f) }
        val grid = Color(0x22,0xFF,0xFF)
        drawLine(grid, Offset(0f, mid), Offset(w, mid), 1f)
        val columns = 8; val stepX = w/columns
        for (i in 1 until columns) drawLine(grid.copy(alpha = 0.15f), Offset(stepX*i,0f), Offset(stepX*i,h), 1f)
        fun drawSeries(series: List<Float>, core: Color) {
            if (series.size < 2) return
            val step = w / (series.size - 1).coerceAtLeast(1)
            var prev = Offset(0f, mapY(series[0]))
            for (i in 1 until series.size) {
                val x = step * i
                val y = mapY(series[i])
                drawLine(core.copy(alpha=0.9f), prev, Offset(x,y), 2f)
                prev = Offset(x,y)
            }
        }
        val gold = Color(0xFF,0xD7,0x00); val violet = Color(0x66,0x00,0xEA); val cyan = Color(0x00,0xD0,0xFF)
        drawSeries(hx, gold); drawSeries(hy, violet); drawSeries(hz, cyan)
    }
}

@Composable
fun GravityTuner(values: FloatArray) {
    // ultra-sensitive gauge around 1g (9.81 m/s²)
    val g = magnitude(values)
    val center = 9.81f
    val span = 0.30f // ±0.15g window
    val norm = ((g - (center - span/2f)) / span).coerceIn(0f, 1f) // 0..1 across the window
    Canvas(Modifier.fillMaxWidth().height(54.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h*0.65f
        val r = min(w,h)*0.45f
        // arc track
        drawArc(
            color = Color(0x22,0xFF,0xFF),
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2),
            style = Stroke(6f, cap = StrokeCap.Round)
        )
        // needle
        val ang = 180f + 180f * norm
        val rad = ang * (Math.PI/180f).toFloat()
        val nx = cx + cos(rad)*r
        val ny = cy + sin(rad)*r
        drawLine(Color(0xFF,0xD7,0x00), Offset(cx,cy), Offset(nx,ny), 4f)
    }
}

@Composable fun StepsRow(raw: Float, session: Float) {
    Column {
        androidx.wear.compose.material.Text(
            "Raw: ${raw.toInt()} • Session: ${session.toInt()} (tap to reset)",
            fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF)
        )
        val norm = (session / 12_000f).coerceIn(0f,1f)
        NeonHeatBarNormalized(norm)
    }
}

@Composable
fun CenteredZeroBar(value: Float, visualRange: Float) {
    val clamped = (value / visualRange).coerceIn(-1f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(clamped) { anim.animateTo(clamped, tween(220)) }
    val track = Color(0x22,0xFF,0xFF); val negGlow = Color(0x66,0x00,0xEA); val posGlow = Color(0xFF,0xD7,0x00)
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(track)) {
        val half = 0.5f; val amt = abs(anim.value)*half
        Box(
            Modifier.fillMaxWidth(half + amt).height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(if (anim.value >= 0f) posGlow.copy(alpha=0.35f) else negGlow.copy(alpha=0.35f))
        )
        Box(
            Modifier.fillMaxWidth(half + amt*0.92f).height(10.dp).padding(vertical=2.dp)
                .clip(RoundedCornerShape(5.dp)).background(if (anim.value >= 0f) posGlow else negGlow)
        )
    }
}

@Composable
fun RotationPseudo3D(x: Float, y: Float, z: Float) {
    val pitchDeg = y.coerceIn(-30f, 30f)
    val rollDeg  = x.coerceIn(-30f, 30f)
    val tiltX = sin(rollDeg * (Math.PI / 180f).toFloat())
    val tiltY = sin(pitchDeg * (Math.PI / 180f).toFloat())
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h/2f
        val rw = w*0.6f; val rh = h*0.5f
        val base = Color(0x22,0xFF,0xFF); val core = Color(0xFF,0xD7,0x00)
        val dx = tiltX*10f; val dy = tiltY*8f
        val left = cx - rw/2f; val right = cx + rw/2f; val top = cy - rh/2f; val bottom = cy + rh/2f
        val p1 = Offset(left-dx, top+dy); val p2 = Offset(right-dx, top-dy)
        val p3 = Offset(right+dx, bottom-dy); val p4 = Offset(left+dx, bottom+dy)
        listOf(4f to base, 2f to core).forEach { (s,col) ->
            drawLine(col, p1,p2,s); drawLine(col,p2,p3,s); drawLine(col,p3,p4,s); drawLine(col,p4,p1,s)
        }
        drawLine(base, Offset(cx - rw/2f, cy), Offset(cx + rw/2f, cy), 2f)
        drawLine(base, Offset(cx, cy - rh/2f), Offset(cx, cy + rh/2f), 2f)
        val intensity = abs(z).coerceIn(0f,1f)
        val dotR = 3f + 5f*intensity
        drawCircle(core, radius = dotR, center = Offset(cx,cy))
    }
}

@Composable fun InverseSquareLight(lux: Float) {
    val t = (ln(1f + lux) / ln(1f + 40_000f)).coerceIn(0f,1f)
    val inv = 1f - t
    val emphasis = (1f - (inv*inv)) // inverse-square on the “darkness” side
    val bar = (0.15f + 0.85f*emphasis).coerceIn(0f,1f)
    NeonHeatBarNormalized(bar)
}
