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
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val readings = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<String>())
    private var stepBaseline by mutableStateOf<Float?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Add HR after permission
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
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                        .background(Color.Black)
                ) {
                    MicrogridParallax()
                    PagerRoot(
                        availableSensors = availableSensors,
                        readings = readings
                    )
                }
            }
        }

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }
            .sorted()

        ensurePermissionsThenSubscribe()
    }

    private fun ensurePermissionsThenSubscribe() {
        // Start non-sensitive sensors immediately
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
            subscribeSensors(registerHeartRate = true)
        }
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

        // Steps (no special permission)
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
            readings[key] = floatArrayOf(raw, session) // [total, session]
            return
        }

        // Rotation vector values handled as-is (x,y,z), others copied
        readings[key] = event.values.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onDestroy() { super.onDestroy(); sensorManager.unregisterListener(this) }
}

/* ===================== COMPOSABLES ===================== */

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
        ) {
            Spacer(Modifier.weight(1f))
            Dot(active = pagerState.currentPage == 0)
            Spacer(Modifier.width(6.dp))
            Dot(active = pagerState.currentPage == 1)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Dot(active: Boolean) {
    Box(
        Modifier
            .size(if (active) 8.dp else 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF, 0xD7, 0x00) else Color(0x44, 0xFF, 0xFF))
    )
}

@Composable
private fun Dashboard(
    availableSensors: List<String>,
    readings: Map<String, FloatArray>
) {
    // Order to keep the common sensors grouped
    val ordered = listOf(
        "Accelerometer", "Linear Accel", "Gravity", "Gyroscope",
        "Rotation Vector", "Magnetic", "Light", "Pressure",
        "Humidity", "Ambient Temp", "Heart Rate", "Step Counter"
    )

    // Live recompute when contents change
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

        items.forEach { (name, values) ->
            Text("$name ${prettyValues(values)}", fontSize = 12.sp)
            NeonHeatBar(name, values)
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(6.dp))
        Text("Available Sensors (${availableSensors.size})", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        availableSensors.take(30).forEach { line ->
            Text(line, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
    }
}

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

        // Concentric rings
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
                val rectTopLeft = Offset(cx - r, cy - r)
                val rectSize = Size(d, d)

                // track 360°
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = rectTopLeft,
                    size = rectSize,
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )
                // glow sweep
                drawArc(
                    color = glow,
                    startAngle = -90f,
                    sweepAngle = 360f * pct,
                    useCenter = false,
                    topLeft = rectTopLeft,
                    size = rectSize,
                    style = Stroke(width = 10f, cap = StrokeCap.Round)
                )
                // core sweep
                drawArc(
                    color = core,
                    startAngle = -90f,
                    sweepAngle = (360f * pct).coerceAtLeast(6f),
                    useCenter = false,
                    topLeft = rectTopLeft,
                    size = rectSize,
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )
            }

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

        Spacer(Modifier.height(8.dp))
        ChannelBar("Motion", accelPresence)
        Spacer(Modifier.height(4.dp))
        ChannelBar("Stability", motionStability)
        Spacer(Modifier.height(4.dp))
        ChannelBar("HR Centering", hrPresence)
        Spacer(Modifier.height(4.dp))
        ChannelBar("Env Balance", envBalance)
    }
}

/* ===================== VISUAL HELPERS ===================== */

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
    // rough per-sensor scaling so bars feel alive
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
        else            -> 50f
    }
    val target = (mag / scale).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(220)) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x33, 0xFF, 0xFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(anim.value)
                .height(10.dp)
                .background(Color(0x66, 0x00, 0xEA))
        )
        Box(
            Modifier
                .fillMaxWidth((anim.value * 0.98f).coerceAtLeast(0.02f))
                .height(6.dp)
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF, 0xD7, 0x00))
        )
    }
}

@Composable
private fun ChannelBar(label: String, pct: Float) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(pct) { anim.animateTo(pct.coerceIn(0f, 1f), tween(300)) }

    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
}

/* ===================== UTILITIES ===================== */

private fun prettyValues(values: FloatArray): String =
    values.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }

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

/* ===================== BACKGROUND ===================== */

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
