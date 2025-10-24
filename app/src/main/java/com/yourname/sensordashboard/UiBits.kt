package com.yourname.sensordashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/* ==== Dividers ==== */
@Composable fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22, 0xFF, 0xFF)))
}

/* ==== Loading state ==== */
@Composable
fun WaitingPulseDots() {
    var dots by remember { mutableStateOf(0) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch { while(true){ alpha.animateTo(0.3f, tween(800)); alpha.animateTo(1f, tween(800)) } }
        scope.launch { while(true){ delay(500); dots = (dots + 1) % 4 } }
    }
    Text("Listening" + ".".repeat(dots), fontSize = 12.sp, color = Color.Gray.copy(alpha = alpha.value))
}

/* ==== Core atoms ==== */
@Composable
fun NeonHeatBar(name: String, values: FloatArray) {
    val mag = magnitude(values)
    val scale = when (name) {
        "Accelerometer" -> 8f
        "Linear Accel"  -> 4f
        "Gravity"       -> 1.2f
        "Gyroscope"     -> 4f
        "Rotation Vector" -> 1.5f
        "Light"         -> 800f
        "Magnetic"      -> 80f
        "Humidity"      -> 100f
        "Ambient Temp"  -> 40f
        "Heart Rate"    -> 160f
        "Pressure"      -> 60f
        "Step Counter"  -> 20_000f
        "HRV"           -> 80f
        else -> 50f
    }
    NeonHeatBarNormalized((mag / scale).coerceIn(0f, 1f))
}

@Composable
fun NeonHeatBarNormalized(norm: Float) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(norm) { anim.animateTo(norm.coerceIn(0f, 1f), tween(220)) }
    val track = Color(0x33,0xFF,0xFF); val glow = Color(0x66,0x00,0xEA); val core = Color(0xFF,0xD7,0x00)
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(anim.value).height(10.dp).background(glow.copy(alpha = 0.6f)))
        Box(Modifier.fillMaxWidth((anim.value*0.98f).coerceAtLeast(0.02f)).height(6.dp)
            .padding(vertical = 2.dp).clip(RoundedCornerShape(3.dp)).background(core))
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
        Box(Modifier.fillMaxWidth(half + amt).height(14.dp).clip(RoundedCornerShape(7.dp))
            .background(if (anim.value >= 0f) posGlow.copy(alpha=0.35f) else negGlow.copy(alpha=0.35f)))
        Box(Modifier.fillMaxWidth(half + amt*0.92f).height(10.dp).padding(vertical=2.dp)
            .clip(RoundedCornerShape(5.dp)).background(if (anim.value >= 0f) posGlow else negGlow))
    }
}

/* ==== Graphs ==== */
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
            val step = w/ (series.size-1).coerceAtLeast(1)
            fun pass(a: Float, s: Float) {
                var prev = Offset(0f, mapY(series[0]))
                for (i in 1 until series.size) {
                    val x = step*i; val y = mapY(series[i])
                    drawLine(core.copy(alpha=a), prev, Offset(x,y), s); prev = Offset(x,y)
                }
            }
            pass(0.22f,7f); pass(0.35f,4f); pass(1f,2f)
        }
        val gold = Color(0xFF,0xD7,0x00); val violet = Color(0x66,0x00,0xEA); val cyan = Color(0x00,0xD0,0xFF)
        drawSeries(hx, gold); drawSeries(hy, violet); drawSeries(hz, cyan)
    }
}

/** Simple sparkline for monotonic-ish ranges (light, pressure (after transform), HR) */
@Composable
fun Sparkline(normSeries: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val w = size.width; val h = size.height
        val grid = Color(0x22,0xFF,0xFF)
        drawLine(grid, Offset(0f, h*0.5f), Offset(w, h*0.5f), 1f)
        if (normSeries.size < 2) return@Canvas
        val step = w / (normSeries.size-1).coerceAtLeast(1)
        var prev = Offset(0f, h*(1f - normSeries[0].coerceIn(0f,1f)))
        for (i in 1 until normSeries.size) {
            val y = h*(1f - normSeries[i].coerceIn(0f,1f))
            val x = step*i
            drawLine(Color(0xFF,0xD7,0x00).copy(alpha=0.35f), prev, Offset(x,y), 6f)
            drawLine(Color(0xFF,0xD7,0x00), prev, Offset(x,y), 2f)
            prev = Offset(x,y)
        }
    }
}

/* ==== Special readouts ==== */
@Composable
fun GravityTuner(values: FloatArray) {
    val g = magnitude(values)
    val center = 9.81f
    val span = 0.30f // ±0.15g window
    val norm = ((g - (center - span/2f)) / span).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(54.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h*0.65f
        val r = min(w,h)*0.45f
        drawArc(
            color = Color(0x22,0xFF,0xFF),
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2),
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )
        val ang = 180f + 180f * norm
        val rad = ang * (PI/180f).toFloat()
        val nx = cx + cos(rad)*r
        val ny = cy + sin(rad)*r
        drawLine(Color(0xFF,0xD7,0x00), Offset(cx,cy), Offset(nx,ny), 4f)
    }
}

@Composable fun HeartPulse(bpm: Float) {
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

        drawArc(
            color = Color(0x22, 0xFF, 0xFF),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(r*2, r*2),
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )

        val ang = (- (orientationDegState.value.getOrNull(0) ?: 0f) + 90f) * (PI/180f).toFloat()
        val nx = cx + cos(ang) * r
        val ny = cy - sin(ang) * r
        drawLine(Color(0xFF,0xD7,0x00), start = Offset(cx, cy), end = Offset(nx, ny), strokeWidth = 4f)

        val ir = r * (0.3f + 0.6f * strengthNorm.coerceIn(0f,1f))
        drawArc(
            color = Color(0x66, 0x00, 0xEA),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(cx - ir, cy - ir), size = Size(ir*2, ir*2),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun StepsRow(raw: Float, session: Float) {
    Column {
        Text("Raw: ${raw.toInt()} • Session: ${session.toInt()} (tap reset in list)", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
        val norm = (session / 12_000f).coerceIn(0f,1f)
        NeonHeatBarNormalized(norm)
    }
}

/* ==== Sensor card (Page 1) ==== */

@Composable
fun LiveValuesLine(values: FloatArray) {
    val txt = values.joinToString(limit = 3, truncated = "…") { v -> "%.2f".format(v) }
    Text(txt, fontSize = 10.sp, color = Color(0xAA, 0xFF, 0xFF))
}

@Composable
fun SensorCard(name: String, values: FloatArray, onResetSteps: () -> Unit) {
    Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    LiveValuesLine(values)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0x10, 0xFF, 0xFF)).padding(8.dp)
    ) {
        when (name) {
            "Gyroscope" -> GyroWaveform(SensorHistory.gyroX, SensorHistory.gyroY, SensorHistory.gyroZ)
            "Gravity"   -> GravityTuner(values)

            "Accelerometer" -> {
                // accel magnitude sparkline
                val series = SensorHistory.accel.map { (it / 8f).coerceIn(0f,1f) }
                Sparkline(series)
            }

            "Linear Accel" -> {
                val mag = magnitude(values)
                CenteredZeroBar(mag, visualRange = 4f)
            }

            "Rotation Vector" -> {
                val ori = orientationDegState.value
                RotationPseudo3D(
                    x = ori.getOrNull(2) ?: 0f,
                    y = ori.getOrNull(1) ?: 0f,
                    z = (ori.getOrNull(0) ?: 0f) / 360f
                )
            }

            "Magnetic" -> {
                val hx = values.getOrNull(0) ?: 0f
                val hy = values.getOrNull(1) ?: 0f
                val hz = values.getOrNull(2) ?: 0f
                val mag = kotlin.math.sqrt(hx*hx + hy*hy + hz*hz)
                MagneticDial(heading = orientationDegState.value.getOrNull(0) ?: 0f, strengthNorm = magScale.norm(mag))
            }

            "Light" -> {
                val series = SensorHistory.light.map { lightScale.norm(it) }
                Sparkline(series)
            }

            "Pressure" -> {
                // deviation from ~1013hPa
                val dev = (values.getOrNull(0) ?: 1013f) - 1013f
                CenteredZeroBar(dev, visualRange = 25f)
            }

            "Heart Rate" -> {
                val bpm = values.getOrNull(0) ?: 0f
                Column {
                    HeartPulse(bpm = bpm.coerceIn(30f, 200f))
                    val hrSeries = SensorHistory.hr.map { ((it - 40f) / 120f).coerceIn(0f,1f) }
                    Sparkline(hrSeries)
                }
            }

            "HRV" -> {
                val rmssd = values.getOrNull(0) ?: 0f
                CenteredZeroBar(rmssd - 50f, visualRange = 80f)
            }

            "Step Counter" -> {
                val raw = values.getOrNull(0) ?: 0f
                val session = values.getOrNull(1) ?: 0f
                StepsRow(raw, session)
            }

            else -> NeonHeatBar(name, values)
        }
    }
    Spacer(Modifier.height(10.dp))
}

/* ==== Rotation viz + microgrid ==== */
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

@Composable
fun MicrogridParallax() {
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { delay(24L); phase = (phase + 0.6f) % 20f } }
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 20f; val w = size.width; val h = size.height
        val line = Color(0x13,0xFF,0xFF)
        var x = -phase; while (x < w) { drawLine(line, Offset(x,0f), Offset(x,h), 1f); x += spacing }
        var y = -phase; while (y < h) { drawLine(line, Offset(0f,y), Offset(w,y), 1f); y += spacing }
    }
}
