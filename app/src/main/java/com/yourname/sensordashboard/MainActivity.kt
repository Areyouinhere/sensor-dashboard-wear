package com.yourname.sensordashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val sensorValues = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<String>())
    private var stepBaseline by mutableStateOf<Float?>(null) // for session steps

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { subscribeSensors() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    MicrogridParallax() // subtle grid behind everything
                    Dashboard(
                        availableSensors = availableSensors,
                        readings = sensorValues
                    )
                }
            }
        }

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }.sorted()

        ensurePermissionsThenSubscribe()
    }

    private fun ensurePermissionsThenSubscribe() {
        val needsBody = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) != PackageManager.PERMISSION_GRANTED
        val needsAct = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACTIVITY_RECOGNITION
        ) != PackageManager.PERMISSION_GRANTED

        if (needsBody || needsAct) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BODY_SENSORS,
                    Manifest.permission.ACTIVITY_RECOGNITION
                )
            )
        } else {
            subscribeSensors()
        }
    }

    private fun subscribeSensors() {
        sensorManager.unregisterListener(this)

        fun reg(type: Int, delay: Int = SensorManager.SENSOR_DELAY_UI) {
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(this, sensor, delay)
            }
        }

        // Motion / orientation
        reg(Sensor.TYPE_ACCELEROMETER)
        reg(Sensor.TYPE_GYROSCOPE)
        reg(Sensor.TYPE_LINEAR_ACCELERATION)
        reg(Sensor.TYPE_GRAVITY)
        reg(Sensor.TYPE_ROTATION_VECTOR)

        // Environment
        reg(Sensor.TYPE_MAGNETIC_FIELD)
        reg(Sensor.TYPE_LIGHT)
        reg(Sensor.TYPE_PRESSURE)
        reg(Sensor.TYPE_RELATIVE_HUMIDITY)
        reg(Sensor.TYPE_AMBIENT_TEMPERATURE)

        // Activity / biometrics
        reg(Sensor.TYPE_STEP_COUNTER)
        reg(Sensor.TYPE_HEART_RATE)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        val key = labelFor(type)

        // STEP COUNTER: label shows session steps; bar fills cumulatively
        if (type == Sensor.TYPE_STEP_COUNTER) {
            val raw = event.values.firstOrNull() ?: 0f
            if (stepBaseline == null) stepBaseline = raw
            val sessionSteps = raw - (stepBaseline ?: raw)
            sensorValues[key] = floatArrayOf(raw, sessionSteps) // [cumulative, session]
            return
        }

        // Rotation vector: magnitude from x,y,z components feels best visually
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            val x = event.values.getOrNull(0) ?: 0f
            val y = event.values.getOrNull(1) ?: 0f
            val z = event.values.getOrNull(2) ?: 0f
            sensorValues[key] = floatArrayOf(x, y, z)
            return
        }

        sensorValues[key] = event.values.copyOf()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}

/* =============== UI ROOT =============== */

@Composable
private fun Dashboard(
    availableSensors: List<String>,
    readings: Map<String, FloatArray>
) {
    val ordered = listOf(
        "Accelerometer", "Linear Accel", "Gravity", "Gyroscope",
        "Rotation Vector", "Magnetic", "Light", "Pressure",
        "Humidity", "Ambient Temp", "Heart Rate", "Step Counter"
    )

    val items = remember(readings) {
        readings.entries.sortedWith(
            compareBy(
                { ordered.indexOf(it.key).let { i -> if (i == -1) Int.MAX_VALUE else i } },
                { it.key })
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Sensor Dashboard", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))

        items.forEach { (name, values) ->
            SensorCard(name = name, values = values)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(6.dp))
        Text("Available Sensors (${availableSensors.size})", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        availableSensors.take(30).forEach { line ->
            Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
    }
}

/* =============== SENSOR CARDS =============== */

@Composable
private fun SensorCard(name: String, values: FloatArray) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x22, 0x22, 0x22))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // Title
        Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp)

        Spacer(Modifier.height(4.dp))

        // Pretty readout line
        Text(prettyValues(name, values), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.height(8.dp))

        when {
            name in listOf("Accelerometer", "Linear Accel", "Gravity", "Gyroscope", "Rotation Vector") -> {
                MotionArcs(name = name, values = values)
                Spacer(Modifier.height(6.dp))
                NeonHeatBar(name = name, values = values) // linear neon bar too
            }
            name == "Heart Rate" || name == "Pressure" -> {
                BalancedDialBar(name = name, values = values)
            }
            name == "Step Counter" -> {
                StepCounterBar(values) // cumulative fill + session label already above
            }
            else -> {
                NeonHeatBar(name = name, values = values)
            }
        }
    }
}

/* =============== VISUALIZERS =============== */

// 1) Neon heat bar with layered glow + eased animation
@Composable
private fun NeonHeatBar(name: String, values: FloatArray) {
    val mag = when (name) {
        "Light" -> values.getOrNull(0)?.coerceAtLeast(0f) ?: 0f
        "Pressure" -> abs(values.getOrNull(0) ?: 0f) // unused here (handled in dial)
        "Heart Rate" -> abs(values.getOrNull(0) ?: 0f) // unused here (handled in dial)
        "Rotation Vector" -> {
            val x = values.getOrNull(0) ?: 0f
            val y = values.getOrNull(1) ?: 0f
            val z = values.getOrNull(2) ?: 0f
            sqrt(x * x + y * y + z * z) // ~0..1.5 typical
        }
        else -> magnitude(values)
    }

    val scale = when (name) {
        "Accelerometer" -> 20f          // was too hot; now responsive
        "Linear Accel"  -> 12f          // more sensitive than accel
        "Gravity"       -> 9.81f * 1.2f // 0..~9.81, show 0..~12 for sensitivity
        "Gyroscope"     -> 6f
        "Rotation Vector" -> 1.5f
        "Light"         -> 800f         // indoor range feels alive
        "Magnetic"      -> 120f
        "Humidity"      -> 100f
        "Ambient Temp"  -> 60f
        else            -> 50f
    }

    val target = (mag / scale).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) {
        anim.animateTo(target, animationSpec = tween(durationMillis = 220))
    }

    // Glow stack (track + glow + core)
    val track = Color(0x33, 0xFF, 0xFF)          // faint cyan
    val glow  = Color(0x66, 0x00, 0xEA)          // violet glow layer
    val core  = Color(0xFF, 0xD7, 0x00)          // warm core (yellow)

    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(track)
    ) {
        // outer glow
        Box(
            Modifier
                .fillMaxWidth(anim.value)
                .height(10.dp)
                .background(glow)
        )
        // bright core
        Box(
            Modifier
                .fillMaxWidth((anim.value * 0.98f).coerceAtLeast(0.02f))
                .height(6.dp)
                .padding(horizontal = 0.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(core)
                .alignByBaseline()
        )
    }
}

// 2) Balanced dial bar (grow from center) for HR & Pressure
@Composable
private fun BalancedDialBar(name: String, values: FloatArray) {
    val (value, center, span) = when (name) {
        "Heart Rate" -> {
            val v = values.getOrNull(0) ?: 0f
            Triple(v, 75f, 150f) // center ~75 bpm, span ~150
        }
        else -> { // Pressure
            val v = values.getOrNull(0) ?: 0f
            Triple(v, 1000f, 200f) // 900..1100 hPa
        }
    }
    val delta = ((value - center) / (span / 2f)).coerceIn(-1f, 1f) // -1..1
    val anim = remember { Animatable(0f) }
    LaunchedEffect(delta) { anim.animateTo(delta, tween(240)) }

    val track = Color(0x22, 0xFF, 0xFF)
    val neg   = Color(0x66, 0x00, 0xEA)
    val pos   = Color(0xFF, 0xD7, 0x00)

    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(track)
    ) {
        val half = 0.5f
        if (anim.value >= 0f) {
            Box(
                Modifier
                    .fillMaxWidth(half + (anim.value * half))
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(pos)
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth(half)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(neg)
            )
            // overlay left side by drawing a smaller bar starting mid and animating left
            val leftWidth = (abs(anim.value) * half)
            Box(
                Modifier
                    .fillMaxWidth(half + leftWidth)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(neg)
            )
        }
    }
}

// 3) Step counter cumulative bar; label already shows session steps
@Composable
private fun StepCounterBar(values: FloatArray) {
    val cumulative = values.getOrNull(0) ?: 0f       // total since boot
    val goal = 20000f                                // rough max for scale
    val target = (cumulative / goal).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(220)) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x22, 0xFF, 0xFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(anim.value)
                .height(10.dp)
                .background(Color(0x00, 0xD0, 0xFF))
        )
    }
}

// 4) Radial motion arcs (clean minimal rings)
@Composable
private fun MotionArcs(name: String, values: FloatArray) {
    val mag = when (name) {
        "Rotation Vector" -> {
            val x = values.getOrNull(0) ?: 0f
            val y = values.getOrNull(1) ?: 0f
            val z = values.getOrNull(2) ?: 0f
            sqrt(x * x + y * y + z * z) // typical 0..~1.5
        }
        else -> magnitude(values) // 0..varies
    }

    val scale = when (name) {
        "Accelerometer" -> 20f
        "Linear Accel"  -> 12f
        "Gravity"       -> 12f
        "Gyroscope"     -> 6f
        "Rotation Vector" -> 1.5f
        else -> 50f
    }
    val pct = (mag / scale).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(pct) { anim.animateTo(pct, tween(220)) }

    val ring = Color(0x33, 0xFF, 0xFF)
    val glow = Color(0x66, 0x00, 0xEA)
    val core = Color(0xFF, 0xD7, 0x00)

    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val w = size.width
        val h = size.height
        val radius = min(w, h) * 0.45f
        // base ring
        drawArc(
            color = ring,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )
        // glow arc
        drawArc(
            color = glow,
            startAngle = 180f,
            sweepAngle = 180f * anim.value,
            useCenter = false,
            style = Stroke(width = 8f, cap = StrokeCap.Round)
        )
        // core arc
        drawArc(
            color = core,
            startAngle = 180f,
            sweepAngle = (180f * anim.value).coerceAtLeast(4f),
            useCenter = false,
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}

/* =============== BACKGROUND (PARALLAX MICROGRID) =============== */

@Composable
private fun MicrogridParallax() {
    // simple animated phase for subtle parallax shimmer
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            // slow drift
            kotlinx.coroutines.delay(24L)
            phase = (phase + 0.6f) % 20f
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val spacing = 20f
        val w = size.width
        val h = size.height
        val xOffset = phase
        val yOffset = phase * 0.7f

        // ultra-subtle microgrid
        val line = Color(0x17, 0xFF, 0xFF)
        // verticals
        var x = -xOffset
        while (x < w) {
            drawLine(line, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, h), strokeWidth = 1f)
            x += spacing
        }
        // horizontals
        var y = -yOffset
        while (y < h) {
            drawLine(line, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
            y += spacing
        }
    }
}

/* =============== UTILITIES =============== */

private fun prettyValues(name: String, values: FloatArray): String {
    return when (name) {
        "Step Counter" -> {
            val cumulative = values.getOrNull(0) ?: 0f
            val session    = values.getOrNull(1) ?: 0f
            "steps: [session=%.0f | total=%.0f]".format(session, cumulative)
        }
        else -> when (values.size) {
            3 -> {
                val (x, y, z) = values
                val m = magnitude(values)
                "[x=%.2f, y=%.2f, z=%.2f | |v|=%.2f]".format(x, y, z, m)
            }
            2 -> "[%.2f, %.2f]".format(values[0], values[1])
            1 -> "[%.2f]".format(values[0])
            else -> values.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
        }
    }
}

private fun magnitude(values: FloatArray): Float {
    var s = 0f
    for (v in values) s += v * v
    return sqrt(s)
}

private fun labelFor(type: Int): String = when (type) {
    Sensor.TYPE_ACCELEROMETER       -> "Accelerometer"
    Sensor.TYPE_GYROSCOPE           -> "Gyroscope"
    Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Accel"
    Sensor.TYPE_GRAVITY             -> "Gravity"
    Sensor.TYPE_ROTATION_VECTOR     -> "Rotation Vector"
    Sensor.TYPE_MAGNETIC_FIELD      -> "Magnetic"
    Sensor.TYPE_LIGHT               -> "Light"
    Sensor.TYPE_PRESSURE            -> "Pressure"
    Sensor.TYPE_RELATIVE_HUMIDITY   -> "Humidity"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Temp"
    Sensor.TYPE_HEART_RATE          -> "Heart Rate"
    Sensor.TYPE_STEP_COUNTER        -> "Step Counter"
    else -> "Type $type"
}
