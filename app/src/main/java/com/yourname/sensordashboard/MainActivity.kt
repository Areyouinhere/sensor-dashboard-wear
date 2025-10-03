package com.yourname.sensordashboard

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val readings = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<String>())
    private var stepBaseline by mutableStateOf<Float?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> subscribeSensors(registerHeartRate = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                        .background(Color.Black)
                ) {
                    MicrogridParallax()
                    PagerRoot(availableSensors, readings)
                }
            }
        }

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }
            .sorted()

        ensurePermissionsThenSubscribe()
    }

    private fun ensurePermissionsThenSubscribe() {
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
        } else subscribeSensors(registerHeartRate = true)
    }

    private fun subscribeSensors(registerHeartRate: Boolean) {
        sensorManager.unregisterListener(this)

        fun reg(type: Int, delay: Int = SensorManager.SENSOR_DELAY_UI) {
            sensorManager.getDefaultSensor(type)?.let { s ->
                sensorManager.registerListener(this, s, delay)
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

        // Steps
        reg(Sensor.TYPE_STEP_COUNTER)

        // Biometrics
        if (registerHeartRate) reg(Sensor.TYPE_HEART_RATE)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = labelFor(event.sensor.type)

        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val raw = event.values.firstOrNull() ?: 0f
            if (stepBaseline == null) stepBaseline = raw
            val session = raw - (stepBaseline ?: raw)
            readings[key] = floatArrayOf(raw, session)
            return
        }

        // Feed history buffers for visuals that require time series
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values.getOrNull(0) ?: 0f
                val y = event.values.getOrNull(1) ?: 0f
                val z = event.values.getOrNull(2) ?: 0f
                SensorHistory.pushGyro(x, y, z)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                SensorHistory.pushAccel(magnitude(event.values))
            }
            Sensor.TYPE_GRAVITY -> {
                SensorHistory.pushGrav(magnitude(event.values))
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val x = event.values.getOrNull(0) ?: 0f
                val y = event.values.getOrNull(1) ?: 0f
                val z = event.values.getOrNull(2) ?: 0f
                SensorHistory.pushRot(sqrt(x*x + y*y + z*z))
            }
        }

        readings[key] = event.values.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onDestroy() { super.onDestroy(); sensorManager.unregisterListener(this) }
}

/* ================= HISTORY BUFFERS ================= */

private object SensorHistory {
    val gyroX = mutableStateListOf<Float>()
    val gyroY = mutableStateListOf<Float>()
    val gyroZ = mutableStateListOf<Float>()
    val accel = mutableStateListOf<Float>()
    val grav  = mutableStateListOf<Float>()
    val rot   = mutableStateListOf<Float>()

    private fun push(list: MutableList<Float>, v: Float, max: Int = 120) {
        if (list.size >= max) list.removeAt(0)
        list.add(v)
    }

    fun pushGyro(x: Float, y: Float, z: Float) { push(gyroX, x); push(gyroY, y); push(gyroZ, z) }
    fun pushAccel(m: Float) = push(accel, m)
    fun pushGrav(m: Float)  = push(grav, m)
    fun pushRot(m: Float)   = push(rot, m)
}

/* ================= PAGER ROOT ================= */

@Composable
private fun PagerRoot(
    availableSensors: List<String>,
    readings: Map<String, FloatArray>
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> Dashboard(availableSensors, readings)
                else -> CoherenceGlyphPage(readings)
            }
        }

        // simple indicator
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Dot(active = pagerState.currentPage == 0)
            Spacer(Modifier.width(6.dp))
            Dot(active = pagerState.currentPage == 1)
        }
    }
}

@Composable private fun Dot(active: Boolean) {
    Box(
        Modifier
            .size(if (active) 8.dp else 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF, 0xD7, 0x00) else Color(0x44, 0xFF, 0xFF))
    )
}

/* ================= DASHBOARD ================= */

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

    // Ensure recompute when map content changes
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
        Modifier
            .fillMaxSize()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Sensor Dashboard", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))

        if (readings.isEmpty()) {
            WaitingPulseDots()
            Spacer(Modifier.height(16.dp))
        }

        items.forEach { (name, values) -> SensorCard(name, values) }

        Spacer(Modifier.height(6.dp))
        Text("Available Sensors (${availableSensors.size})", fontWeight = FontWeight.SemiBold)
        availableSensors.take(20).forEach { line -> Text(line, fontSize = 12.sp) }
    }
}

/* ================= SENSOR CARD ================= */

@Composable
private fun SensorCard(name: String, values: FloatArray) {
    Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

    when (name) {
        "Gyroscope" -> {
            GyroWaveform(SensorHistory.gyroX, SensorHistory.gyroY, SensorHistory.gyroZ)
        }
        "Gravity", "Linear Accel" -> {
            val centered = magnitude(values) - if (name == "Gravity") 9.81f else 0f
            CenteredZeroBar(centered, visualRange = 12f)
        }
        "Rotation Vector" -> {
            val x = values.getOrNull(0) ?: 0f
            val y = values.getOrNull(1) ?: 0f
            val z = values.getOrNull(2) ?: 0f
            RotationPseudo3D(x, y, z)
        }
        else -> {
            NeonHeatBar(name, values)
        }
    }

    Spacer(Modifier.height(10.dp))
}

/* ================= COHERENCE GLYPH ================= */

@Composable
private fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f, 0f, 0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0) ?: 1000f

    val nAccel = (magnitude(accel) / 12f).coerceIn(0f, 1f)
    val nGyro  = (magnitude(gyro)  / 6f ).coerceIn(0f, 1f)
    val nHR    = (hr / 150f).coerceIn(0f, 1f)
    val nP     = ((press - 980f) / 60f).coerceIn(0f, 1f)

    val motionStability = 1f - nGyro
    val accelPresence   = nAccel
    val hrPresence      = 1f - abs(nHR - 0.5f) * 2f
    val envBalance      = 1f - abs(nP - 0.5f) * 2f

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Coherence", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseR = min(size.width, size.height) * 0.28f
            val gap = 14f

            fun ring(r: Float, pct: Float, track: Color, glow: Color, core: Color) {
                val d = r * 2f
                val topLeft = Offset(cx - r, cy - r)
                val size = Size(d, d)

                // 360° track
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )
                // glow sweep
                drawArc(
                    color = glow,
                    startAngle = -90f,
                    sweepAngle = 360f * pct.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = 10f, cap = StrokeCap.Round)
                )
                // core sweep
                drawArc(
                    color = core,
                    startAngle = -90f,
                    sweepAngle = (360f * pct).coerceAtLeast(6f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )
            }

            // Pass all required colors (track, glow, core)
            ring(
                baseR + gap * 0f, hrPresence,
                track = Color(0x22, 0xFF, 0xFF),
                glow  = Color(0x66, 0x00, 0xEA),
                core  = Color(0xFF, 0xD7, 0x00)
            )
            ring(
                baseR + gap * 1f, motionStability,
                track = Color(0x22, 0xFF, 0xFF),
                glow  = Color(0x44, 0xD0, 0xFF),
                core  = Color(0xAA, 0xFF, 0xFF)
            )
            ring(
                baseR + gap * 2f, accelPresence,
                track = Color(0x22, 0xFF, 0xFF),
                glow  = Color(0x55, 0xFF, 0xD7),
                core  = Color(0xFF, 0xE6, 0x88)
            )
            ring(
                baseR + gap * 3f, envBalance,
                track = Color(0x22, 0xFF, 0xFF),
                glow  = Color(0x44, 0xFF, 0x99),
                core  = Color(0xDD, 0xFF, 0x99)
            )
        }
    }
}

/* ================= VISUAL HELPERS ================= */

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
        text = "Listening" + ".".repeat(dots),
        fontSize = 12.sp,
        color = Color.Gray.copy(alpha = alpha.value)
    )
}

@Composable
private fun NeonHeatBar(name: String, values: FloatArray) {
    val mag = when (name) {
        "Rotation Vector" -> magnitude(values)
        else -> magnitude(values)
    }
    val scale = when (name) {
        "Accelerometer" -> 20f
        "Linear Accel"  -> 12f
        "Gravity"       -> 12f
        "Gyroscope"     -> 6f
        "Rotation Vector" -> 1.5f
        "Light"         -> 800f
        "Magnetic"      -> 120f
        "Humidity"      -> 100f
        "Ambient Temp"  -> 60f
        "Heart Rate"    -> 200f
        "Pressure"      -> 1100f
        "Step Counter"  -> 20000f
        else -> 50f
    }
    val target = (mag / scale).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(220)) }

    // neon three-layer bar
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
                .background(glow.copy(alpha = 0.6f))
        )
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
private fun GyroWaveform(
    hx: List<Float>,
    hy: List<Float>,
    hz: List<Float>,
    range: Float = 6f // rad/s visual range
) {
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val w = size.width
        val h = size.height
        val mid = h / 2f

        fun mapY(v: Float): Float {
            val clamped = v.coerceIn(-range, range)
            return mid - (clamped / range) * (h * 0.45f)
        }

        // subtle grid
        val grid = Color(0x22, 0xFF, 0xFF)
        drawLine(grid, start = Offset(0f, mid), end = Offset(w, mid), strokeWidth = 1f)
        val columns = 8
        val stepXGrid = w / columns
        for (i in 1 until columns) {
            val x = stepXGrid * i
            drawLine(grid.copy(alpha = 0.15f), Offset(x, 0f), Offset(x, h), 1f)
        }

        fun drawSeries(series: List<Float>, core: Color) {
            if (series.size < 2) return
            val step = w / (series.size - 1).coerceAtLeast(1)

            fun pass(alpha: Float, stroke: Float) {
                var prev = Offset(0f, mapY(series[0]))
                for (i in 1 until series.size) {
                    val x = step * i
                    val y = mapY(series[i])
                    drawLine(
                        color = core.copy(alpha = alpha),
                        start = prev,
                        end = Offset(x, y),
                        strokeWidth = stroke
                    )
                    prev = Offset(x, y)
                }
            }

            // glow back → mid → core
            pass(alpha = 0.22f, stroke = 7f)
            pass(alpha = 0.35f, stroke = 4f)
            pass(alpha = 1.00f, stroke = 2f)
        }

        // axis colors
        val gold   = Color(0xFF, 0xD7, 0x00) // X
        val violet = Color(0x66, 0x00, 0xEA) // Y
        val cyan   = Color(0x00, 0xD0, 0xFF) // Z

        drawSeries(hx, gold)
        drawSeries(hy, violet)
        drawSeries(hz, cyan)
    }
}

@Composable
private fun CenteredZeroBar(value: Float, visualRange: Float) {
    val clamped = (value / visualRange).coerceIn(-1f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(clamped) { anim.animateTo(clamped, tween(220)) }

    val track = Color(0x22, 0xFF, 0xFF)
    val negGlow = Color(0x66, 0x00, 0xEA)
    val posGlow = Color(0xFF, 0xD7, 0x00)

    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(track)
    ) {
        val half = 0.5f
        val amt = abs(anim.value) * half

        // glow layer
        Box(
            Modifier
                .fillMaxWidth(half + amt)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (anim.value >= 0f) posGlow.copy(alpha = 0.35f) else negGlow.copy(alpha = 0.35f))
        )
        // core layer
        Box(
            Modifier
                .fillMaxWidth(half + amt * 0.92f)
                .height(10.dp)
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (anim.value >= 0f) posGlow else negGlow)
        )
    }
}

@Composable
private fun RotationPseudo3D(x: Float, y: Float, z: Float) {
    // Tilt the tile by roll/pitch approximations derived from rotation vector
    val pitchDeg = (y * 20f).coerceIn(-30f, 30f)
    val rollDeg  = (x * 20f).coerceIn(-30f, 30f)
    val tiltX = sin(rollDeg * (Math.PI / 180f).toFloat())
    val tiltY = sin(pitchDeg * (Math.PI / 180f).toFloat())

    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val rw = w * 0.6f
        val rh = h * 0.5f

        val base = Color(0x22, 0xFF, 0xFF)
        val glow = Color(0x66, 0x00, 0xEA)
        val core = Color(0xFF, 0xD7, 0x00)

        val dx = tiltX * 10f
        val dy = tiltY * 8f

        val left   = cx - rw / 2f
        val right  = cx + rw / 2f
        val top    = cy - rh / 2f
        val bottom = cy + rh / 2f

        val p1 = Offset(left  - dx, top    + dy)
        val p2 = Offset(right - dx, top    - dy)
        val p3 = Offset(right + dx, bottom - dy)
        val p4 = Offset(left  + dx, bottom + dy)

        // outer glow wireframe then core wireframe
        listOf(4f to base, 2f to core).forEach { (stroke, col) ->
            drawLine(col, p1, p2, strokeWidth = stroke)
            drawLine(col, p2, p3, strokeWidth = stroke)
            drawLine(col, p3, p4, strokeWidth = stroke)
            drawLine(col, p4, p1, strokeWidth = stroke)
        }

        // cross axes
        drawLine(glow, Offset(cx - rw/2f, cy), Offset(cx + rw/2f, cy), 2f)
        drawLine(glow, Offset(cx, cy - rh/2f), Offset(cx, cy + rh/2f), 2f)

        // center dot intensity from z
        val intensity = (abs(z).coerceIn(0f, 1.5f) / 1.5f)
        val dotR = 3f + 5f * intensity
        drawCircle(core, radius = dotR, center = Offset(cx, cy))
    }
}

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
        val line = Color(0x17, 0xFF, 0xFF)

        var x = -phase
        while (x < w) {
            drawLine(line, Offset(x, 0f), Offset(x, h), 1f)
            x += spacing
        }
        var y = -phase
        while (y < h) {
            drawLine(line, Offset(0f, y), Offset(w, y), 1f)
            y += spacing
        }
    }
}

/* ================= UTILITIES ================= */

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
