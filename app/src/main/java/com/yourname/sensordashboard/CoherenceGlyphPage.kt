package com.yourname.sensordashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.wear.compose.material.MaterialTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/* ---------------- Visual primitives & helpers shared across pages ---------------- */

@Composable fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22,0xFF,0xFF)))
}

fun fmtPct(v: Float): String = "${(v.coerceIn(0f,1f)*100f).roundToInt()}%"
fun fmtMs(v: Float): String  = "${v.coerceIn(0f, 9999f).roundToInt()} ms"
fun fmt1(v: Float): String   = "%.1f".format(v.coerceIn(0f,1f))

@Composable
fun PagerDots(total: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(total) { i ->
            Box(
                Modifier.size(if (current == i) 8.dp else 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (current == i) Color(0xFF, 0xD7, 0x00) else Color(0x44, 0xFF, 0xFF))
            )
            if (i != total - 1) Spacer(Modifier.width(6.dp))
        }
    }
}

/* ---------------- Dashboard page ---------------- */

@Composable
fun DashboardPage(availableSensors: List<String>, readings: Map<String, FloatArray>) {
    val ordered = listOf(
        "Accelerometer","Linear Accel","Gravity","Gyroscope",
        "Rotation Vector","Magnetic","Light","Pressure",
        "Humidity","Ambient Temp","Heart Rate","HRV","Step Counter"
    )
    val items by remember {
        derivedStateOf {
            val base = readings.toMutableMap()
            base["HRV"] = floatArrayOf(HRVHistory.rmssd())
            base.entries.sortedWith(
                compareBy(
                    { ordered.indexOf(it.key).let { i -> if (i == -1) Int.MAX_VALUE else i } },
                    { it.key })
            )
        }
    }
    Column(
        Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Sensor Dashboard", fontSize = 18.sp,
            color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        if (readings.isEmpty()) {
            WaitingPulseDots(); Spacer(Modifier.height(16.dp))
        }

        items.forEach { (name, values) ->
            SensorCard(name, values)
        }

        Spacer(Modifier.height(8.dp))
        Text("Available Sensors (${availableSensors.size})", fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF))
        availableSensors.take(20).forEach { line -> Text(line, fontSize = 11.sp, color = Color(0x99,0xFF,0xFF)) }
    }
}

@Composable
private fun SensorCard(name: String, values: FloatArray) {
    Text(name, fontSize = 12.sp, color = Color.White)
    LiveValuesLine(values)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0x10, 0xFF, 0xFF)).padding(8.dp)
    ) {
        when (name) {
            "Gyroscope" -> GyroWaveform(SensorHistory.gyroX, SensorHistory.gyroY, SensorHistory.gyroZ)
            "Gravity"   -> GravityTuner(values)
            "Linear Accel" -> CenteredZeroBar(magnitude(values), visualRange = 4f)
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
                val heading = orientationDegState.value.getOrNull(0) ?: 0f
                MagneticDial(heading = heading, strengthNorm = magScale.norm(mag))
            }
            "Light" -> InverseSquareLight(values.getOrNull(0) ?: 0f)
            "Heart Rate" -> {
                val bpm = values.getOrNull(0) ?: 0f; HeartPulse(bpm = bpm.coerceIn(30f, 200f))
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

@Composable private fun LiveValuesLine(values: FloatArray) {
    val txt = values.joinToString(limit = 3, truncated = "…") { v -> "%.2f".format(v) }
    Text(txt, fontSize = 10.sp, color = Color(0xAA, 0xFF, 0xFF))
}

/* ---------- Re-usable visuals (unchanged from your last stable, with small fixes) ---------- */

@Composable private fun WaitingPulseDots() {
    var dots by remember { mutableStateOf(0) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            alpha.animateTo(0.3f, tween(800))
            alpha.animateTo(1f, tween(800))
            dots = (dots + 1) % 4
        }
    }
    Text("Listening" + ".".repeat(dots), fontSize = 12.sp, color = Color.Gray.copy(alpha = alpha.value))
}

@Composable private fun NeonHeatBar(name: String, values: FloatArray) {
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

@Composable private fun NeonHeatBarNormalized(norm: Float) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(norm) { anim.animateTo(norm.coerceIn(0f, 1f), tween(220)) }
    val track = Color(0x33,0xFF,0xFF); val glow = Color(0x66,0x00,0xEA); val core = Color(0xFF,0xD7,0x00)
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(anim.value).height(10.dp).background(glow.copy(alpha = 0.6f)))
        Box(Modifier.fillMaxWidth((anim.value*0.98f).coerceAtLeast(0.02f)).height(6.dp)
            .padding(vertical = 2.dp).clip(RoundedCornerShape(3.dp)).background(core))
    }
}

@Composable private fun GyroWaveform(hx: List<Float>, hy: List<Float>, hz: List<Float>, range: Float = 6f) {
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
            var prev = Offset(0f, mapY(series[0]))
            for (i in 1 until series.size) {
                val x = step*i; val y = mapY(series[i])
                drawLine(core, prev, Offset(x,y), 2f); prev = Offset(x,y)
            }
        }
        val gold = Color(0xFF,0xD7,0x00); val violet = Color(0x66,0x00,0xEA); val cyan = Color(0x00,0xD0,0xFF)
        drawSeries(hx, gold); drawSeries(hy, violet); drawSeries(hz, cyan)
    }
}

@Composable private fun GravityTuner(values: FloatArray) {
    val g = magnitude(values)
    val center = 9.81f
    val span = 0.30f
    val norm = ((g - (center - span/2f)) / span).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(54.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h*0.65f
        val r = min(w,h)*0.45f
        drawArc(Color(0x22,0xFF,0xFF), 180f, 180f, false,
            topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2), style = Stroke(6f, StrokeCap.Round))
        val ang = 180f + 180f * norm
        val rad = ang * (PI/180f).toFloat()
        val nx = cx + kotlin.math.cos(rad)*r
        val ny = cy + kotlin.math.sin(rad)*r
        drawLine(Color(0xFF,0xD7,0x00), Offset(cx,cy), Offset(nx,ny), 4f)
    }
}

@Composable private fun StepsRow(raw: Float, session: Float) {
    Column {
        Text("Raw: ${raw.toInt()} • Session: ${session.toInt()}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
        val norm = (session / 12_000f).coerceIn(0f,1f)
        NeonHeatBarNormalized(norm)
    }
}

@Composable private fun CenteredZeroBar(value: Float, visualRange: Float) {
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

@Composable private fun RotationPseudo3D(x: Float, y: Float, z: Float) {
    val pitchDeg = y.coerceIn(-30f, 30f)
    val rollDeg  = x.coerceIn(-30f, 30f)
    val tiltX = kotlin.math.sin(rollDeg * (Math.PI / 180f).toFloat())
    val tiltY = kotlin.math.sin(pitchDeg * (Math.PI / 180f).toFloat())
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

@Composable private fun InverseSquareLight(lux: Float) {
    val t = (kotlin.math.ln(1f + lux) / kotlin.math.ln(1f + 40_000f)).coerceIn(0f,1f)
    val inv = 1f - t
    val emphasis = (1f - (inv*inv))
    val bar = (0.15f + 0.85f*emphasis).coerceIn(0f,1f)
    NeonHeatBarNormalized(bar)
}

/* ------------ Small chart for trends page & compass swipe-up ------------- */

@Composable
fun QuickSparkline(data: List<Float>, modifier: Modifier = Modifier, color: Color = Color(0xFF,0xD7,0x00)) {
    Canvas(modifier.height(48.dp).fillMaxWidth()) {
        if (data.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        val n = data.size
        val minV = data.minOrNull() ?: 0f
        val maxV = data.maxOrNull() ?: 1f
        val span = (maxV - minV).coerceAtLeast(1e-3f)
        var prev = Offset(0f, h - ((data[0] - minV)/span)*h)
        for (i in 1 until n) {
            val x = w * (i/(n-1).toFloat())
            val y = h - ((data[i] - minV)/span)*h
            val cur = Offset(x, y)
            drawLine(color, prev, cur, 2f)
            prev = cur
        }
    }
}

/* ---------------- Trends Page ---------------- */

@Composable
fun TrendsPage() {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Coherence Trends", color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(6.dp)); DividerLine(); Spacer(Modifier.height(8.dp))
        QuickSparkline(CompassModel.coherenceHistory)
        Spacer(Modifier.height(8.dp))
        Text("Last ${CompassModel.coherenceHistory.size} pts • Current ${fmtPct(CompassModel.composite.value)}", color = Color(0xCC,0xFF,0xFF), fontSize = 12.sp)
    }
}
