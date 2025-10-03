package com.yourname.sensordashboard

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import android.content.Context
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val readings = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<String>())
    private var stepBaseline by mutableStateOf<Float?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // After permission, re-register with HR included
        subscribeSensors(registerHeartRate = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                ) {
                    MicrogridParallax()   // subtle rolling grid in the back
                    PagerRoot(
                        availableSensors = availableSensors,
                        readings = readings
                    )
                }
            }
        }

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }.sorted()

        ensurePermissionsThenSubscribe()
    }

    private fun ensurePermissionsThenSubscribe() {
        // Start all non-sensitive sensors right away
        subscribeSensors(registerHeartRate = false)

        val needsBody = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BODY_SENSORS
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        val needsAct = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACTIVITY_RECOGNITION
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED

        if (needsBody || needsAct) {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.BODY_SENSORS,
                    android.Manifest.permission.ACTIVITY_RECOGNITION
                )
            )
        } else {
            // Already granted → add HR now
            subscribeSensors(registerHeartRate = true)
        }
    }

    private fun subscribeSensors(registerHeartRate: Boolean) {
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

        // Steps (no special permission)
        reg(Sensor.TYPE_STEP_COUNTER)

        // Biometrics (needs BODY_SENSORS)
        if (registerHeartRate) reg(Sensor.TYPE_HEART_RATE)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = labelFor(event.sensor.type)

        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val raw = event.values.firstOrNull() ?: 0f
            if (stepBaseline == null) stepBaseline = raw
            val session = raw - (stepBaseline ?: raw)
            readings[key] = floatArrayOf(raw, session) // [total since boot, session]
            return
        }

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val x = event.values.getOrNull(0) ?: 0f
            val y = event.values.getOrNull(1) ?: 0f
            val z = event.values.getOrNull(2) ?: 0f
            readings[key] = floatArrayOf(x, y, z)
            return
        }

        readings[key] = event.values.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}

/* ====================== PAGER ROOT ====================== */

@Composable
private fun PagerRoot(
    availableSensors: List<String>,
    readings: Map<String, FloatArray>
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column(Modifier.fillMaxSize()) {
        // Pages: 0 = Dashboard, 1 = Coherence Glyph
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> Dashboard(availableSensors, readings)
                else -> CoherenceGlyphPage(readings)
            }
        }

        // Simple page indicator (two dots)
        val active = Color(0xFF, 0xD7, 0x00)
        val idle = Color(0x44, 0xFF, 0xFF)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(2) { i ->
                Box(
                    Modifier
                        .size(if (i == pagerState.currentPage) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (i == pagerState.currentPage) active else idle)
                )
                if (i == 0) Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/* ====================== DASHBOARD PAGE ====================== */

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

    // NEW (tracks the map’s state properly)
val items by remember {
    derivedStateOf {
        readings.entries.sortedWith(
            compareBy(
                { ordered.indexOf(it.key).let { i -> if (i == -1) Int.MAX_VALUE else i } },
                { it.key })
        )
    }
}


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .verticalScroll(rememberScrollState())
    ) {
        Text("Sensor Dashboard", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))

        if (readings.isEmpty()) {
            WaitingPulseDots()
            Spacer(Modifier.height(16.dp))
        }

        items.forEach { (name, values) ->
            SensorCard(name, values)
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

@Composable
private fun WaitingPulseDots() {
    var dots by remember { mutableStateOf(0) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            while (true) {
                alpha.animateTo(0.3f, tween(800))
                alpha.animateTo(1f, tween(800))
            }
        }
        scope.launch {
            while (true) {
                delay(500)
                dots = (dots + 1) % 4
            }
        }
    }

    Text(
        text = "Listening for sensors" + ".".repeat(dots),
        fontSize = 12.sp,
        color = Color.Gray.copy(alpha = alpha.value)
    )
}

@Composable
private fun SensorCard(name: String, values: FloatArray) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x22, 0x22, 0x22))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(2.dp))
        Text(prettyValues(name, values), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))

        when (name) {
            "Accelerometer", "Linear Accel", "Gravity", "Gyroscope", "Rotation Vector" -> {
                MotionArcs(name, values)
                Spacer(Modifier.height(6.dp))
                NeonHeatBar(name, values)
            }
            "Heart Rate", "Pressure" -> {
                BalancedDialBar(name, values)
            }
            "Step Counter" -> {
                StepCounterBar(values)
            }
            else -> NeonHeatBar(name, values)
        }
    }
}

/* ====================== COHERENCE PAGE ====================== */

@Composable
private fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
    // Extract a few channels (safe defaults when missing)
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro = readings["Gyroscope"] ?: floatArrayOf(0f, 0f, 0f)
    val rot = readings["Rotation Vector"] ?: floatArrayOf(0f, 0f, 0f)
    val hr = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val pressure = readings["Pressure"]?.getOrNull(0) ?: 1000f
    val light = readings["Light"]?.getOrNull(0) ?: 0f

    // Magnitudes
    val mAccel = magnitude(accel)
    val mGyro = magnitude(gyro)
    val mRot = magnitude(rot)

    // Normalize channels (rough, but gives us something alive immediately)
    val nAccel = (mAccel / 12f).coerceIn(0f, 1f)        // motion
    val nGyro = (mGyro / 6f).coerceIn(0f, 1f)           // twist
    val nRot = (mRot / 1.5f).coerceIn(0f, 1f)           // orientation energy
    val nHR = (hr / 150f).coerceIn(0f, 1f)              // HR band
    val nP = ((pressure - 980f) / 60f).coerceIn(0f, 1f) // ambient pressure window
    val nLight = (light / 800f).coerceIn(0f, 1f)        // ambient light

    // A simple “coherence” blend (tuneable):
    // - favor stability (low gyro) + presence (accel near gravity) + HR mid-band
    val motionStability = 1f - nGyro
    val accelPresence = nAccel
    val hrPresence = 1f - abs(nHR - 0.5f) * 2f          // centered best
    val envBalance = 1f - abs(nP - 0.5f) * 2f

    var coh = (0.35f * motionStability +
               0.25f * accelPresence +
               0.25f * hrPresence +
               0.15f * envBalance)
    coh = coh.coerceIn(0f, 1f)

    val anim = remember { Animatable(0f) }
    LaunchedEffect(coh) { anim.animateTo(coh, tween(280)) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        Text("Coherence Glyph", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))

        // Central animated ring + sector overlays
        // ---- Concentric rings glyph ----
Canvas(
    Modifier
        .fillMaxWidth()
        .height(140.dp)
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val baseR = min(w, h) * 0.34f
    val gap = 12f

    // Helper to draw one ring
    fun ring(radius: Float, pct: Float, track: Color, glow: Color, core: Color) {
        // track
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 8f, cap = StrokeCap.Round)
        )
        // glow
        drawArc(
            color = glow,
            startAngle = -90f,
            sweepAngle = 360f * pct.coerceIn(0f, 1f),
            useCenter = false,
            style = Stroke(width = 10f, cap = StrokeCap.Round)
        )
        // core
        drawArc(
            color = core,
            startAngle = -90f,
            sweepAngle = (360f * pct).coerceAtLeast(6f),
            useCenter = false,
            style = Stroke(width = 5f, cap = StrokeCap.Round)
        )
    }

    // Animated values (re-use your computed nAccel, motionStability, hrPresence, envBalance)
    val accA = nAccel
    val stabA = motionStability
    val hrA = hrPresence
    val envA = envBalance

    // draw from inner to outer for nice layering
    withTransform({
        translate(left = cx, top = cy)
    }) {
        // set a local radius for each ring
        var r = baseR
        fun drawRing(pct: Float, track: Color, glow: Color, core: Color) {
            inset(horizontal = -r, vertical = -r) {
                ring(r, pct, track, glow, core)
            }
            r += gap
        }

        // Inner → Outer: HR, Motion Stability, Accel Presence, Env Balance
        drawRing(
            pct = hrA,
            track = Color(0x22, 0xFF, 0xFF),
            glow  = Color(0x66, 0x00, 0xEA),
            core  = Color(0xFF, 0xD7, 0x00)
        )
        drawRing(
            pct = stabA,
            track = Color(0x22, 0xFF, 0xFF),
            glow  = Color(0x44, 0xD0, 0xFF),
            core  = Color(0xAA, 0xFF, 0xFF)
        )
        drawRing(
            pct = accA,
            track = Color(0x22, 0xFF, 0xFF),
            glow  = Color(0x55, 0xFF, 0xD7),
            core  = Color(0xFF, 0xE6, 0x88)
        )
        drawRing(
            pct = envA,
            track = Color(0x22, 0xFF, 0xFF),
            glow  = Color(0x44, 0xFF, 0x99),
            core  = Color(0xDD, 0xFF, 0x99)
        )
    }
}

        Spacer(Modifier.height(8.dp))
        // Quick bars for channels under the glyph
        ChannelBar("Motion", nAccel)
        Spacer(Modifier.height(4.dp))
        ChannelBar("Stability", motionStability)
        Spacer(Modifier.height(4.dp))
        ChannelBar("HR Centering", hrPresence)
        Spacer(Modifier.height(4.dp))
        ChannelBar("Env Balance", envBalance)
        Spacer(Modifier.height(8.dp))
        Text("coherence: ${(coh * 100).toInt()}%", fontSize = 14.sp)
    }
}

@Composable
private fun ChannelBar(label: String, value01: Float) {
    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value01) { anim.animateTo(value01.coerceIn(0f, 1f), tween(220)) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x22, 0xFF, 0xFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(anim.value)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF, 0xD7, 0x00))
        )
    }
}

/* ====================== VISUALIZERS (Dashboard) ====================== */

@Composable
private fun NeonHeatBar(name: String, values: FloatArray) {
    val mag = when (name) {
        "Light" -> values.getOrNull(0)?.coerceAtLeast(0f) ?: 0f
        "Rotation Vector" -> {
            val x = values.getOrNull(0) ?: 0f
            val y = values.getOrNull(1) ?: 0f
            val z = values.getOrNull(2) ?: 0f
            sqrt(x * x + y * y + z * z)
        }
        else -> magnitude(values)
    }

    val scale = when (name) {
        "Accelerometer" -> 20f
        "Linear Accel"  -> 12f
        "Gravity"       -> 9.81f * 1.2f
        "Gyroscope"     -> 6f
        "Rotation Vector" -> 1.5f
        "Light"         -> 800f
        "Magnetic"      -> 120f
        "Humidity"      -> 100f
        "Ambient Temp"  -> 60f
        "Heart Rate"    -> 200f
        "Pressure"      -> 1100f
        "Step Counter"  -> 20000f
        else            -> 50f
    }

    val target = (mag / scale).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(220)) }

    val track = Color(0x33, 0xFF, 0xFF)
    val glow  = Color(0x66, 0x00, 0xEA)
    val core  = Color(0xFF, 0xD7, 0x00)

    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(anim.value)
                .height(10.dp)
                .background(glow)
        )
        // core bar centered via padding (no align calls)
        Box(
            Modifier
                .fillMaxWidth((anim.value * 0.98f).coerceAtLeast(0.02f))
                .height(6.dp)
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(core)
        )
    }
}

@Composable
private fun BalancedDialBar(name: String, values: FloatArray) {
    val (value, center, span) = when (name) {
        "Heart Rate" -> Triple(values.getOrNull(0) ?: 0f, 75f, 150f)
        else         -> Triple(values.getOrNull(0) ?: 0f, 1000f, 200f)
    }
    val delta = ((value - center) / (span / 2f)).coerceIn(-1f, 1f)
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

@Composable
private fun StepCounterBar(values: FloatArray) {
    val cumulative = values.getOrNull(0) ?: 0f
    val goal = 20000f
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

@Composable
private fun MotionArcs(name: String, values: FloatArray) {
    val mag = when (name) {
        "Rotation Vector" -> {
            val x = values.getOrNull(0) ?: 0f
            val y = values.getOrNull(1) ?: 0f
            val z = values.getOrNull(2) ?: 0f
            sqrt(x * x + y * y + z * z)
        }
        else -> magnitude(values)
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

/* ====================== BACKGROUND ====================== */

@Composable
private fun MicrogridParallax() {
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(24L)
            phase = (phase + 0.6f) % 20f
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val spacing = 20f
        val w = size.width
        val h = size.height
        val xOffset = phase
        val yOffset = phase * 0.7f
        val line = Color(0x17, 0xFF, 0xFF)

        var x = -xOffset
        while (x < w) {
            drawLine(line, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f)
            x += spacing
        }
        var y = -yOffset
        while (y < h) {
            drawLine(line, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
            y += spacing
        }
    }
}

/* ====================== UTILITIES ====================== */

private fun prettyValues(name: String, values: FloatArray): String {
    return when (name) {
        "Step Counter" -> {
            val total = values.getOrNull(0) ?: 0f
            val session = values.getOrNull(1) ?: 0f
            "steps: [session=%.0f | total=%.0f]".format(session, total)
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
