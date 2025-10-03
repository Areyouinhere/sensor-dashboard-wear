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

        reg(Sensor.TYPE_ACCELEROMETER)
        reg(Sensor.TYPE_GYROSCOPE)
        reg(Sensor.TYPE_LINEAR_ACCELERATION)
        reg(Sensor.TYPE_GRAVITY)
        reg(Sensor.TYPE_ROTATION_VECTOR)

        reg(Sensor.TYPE_MAGNETIC_FIELD)
        reg(Sensor.TYPE_LIGHT)
        reg(Sensor.TYPE_PRESSURE)
        reg(Sensor.TYPE_RELATIVE_HUMIDITY)
        reg(Sensor.TYPE_AMBIENT_TEMPERATURE)

        reg(Sensor.TYPE_STEP_COUNTER)
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

    fun pushGyro(x: Float, y: Float, z: Float) {
        push(gyroX, x); push(gyroY, y); push(gyroZ, z)
    }
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
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> Dashboard(availableSensors, readings)
                else -> CoherenceGlyphPage(readings)
            }
        }
    }
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
        Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Sensor Dashboard", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))

        if (readings.isEmpty()) WaitingPulseDots()

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
        "Gyroscope" -> GyroWaveform(SensorHistory.gyroX, SensorHistory.gyroY, SensorHistory.gyroZ)
        "Gravity", "Linear Accel" -> {
            CenteredZeroBar(magnitude(values) - if (name == "Gravity") 9.81f else 0f, 12f)
        }
        "Rotation Vector" -> {
            val x = values.getOrNull(0) ?: 0f
            val y = values.getOrNull(1) ?: 0f
            val z = values.getOrNull(2) ?: 0f
            RotationPseudo3D(x, y, z)
        }
        else -> NeonHeatBar(name, values)
    }
    Spacer(Modifier.height(10.dp))
}

/* ================= COHERENCE GLYPH ================= */

@Composable
private fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f,0f,0f)
    val gyro  = readings["Gyroscope"] ?: floatArrayOf(0f,0f,0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0) ?: 1000f

    val nAccel = (magnitude(accel) / 12f).coerceIn(0f,1f)
    val nGyro  = (magnitude(gyro) / 6f).coerceIn(0f,1f)
    val nHR    = (hr / 150f).coerceIn(0f,1f)
    val nP     = ((press - 980f) / 60f).coerceIn(0f,1f)

    val motionStability = 1f - nGyro
    val accelPresence   = nAccel
    val hrPresence      = 1f - abs(nHR - 0.5f) * 2f
    val envBalance      = 1f - abs(nP - 0.5f) * 2f

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Coherence", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))

        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val cx = size.width/2f; val cy = size.height/2f
            val baseR = min(size.width, size.height) * 0.28f; val gap = 14f

            fun ring(r: Float, pct: Float, col: Color) {
                val d = r*2f; val rect = androidx.compose.ui.geometry.Rect(cx-r, cy-r, cx+r, cy+r)
                drawArc(col.copy(alpha=0.2f), -90f, 360f, false, rect.topLeft, Size(d,d), Stroke(8f))
                drawArc(col.copy(alpha=0.6f), -90f, 360f*pct, false, rect.topLeft, Size(d,d), Stroke(10f))
                drawArc(col, -90f, (360f*pct).coerceAtLeast(6f), false, rect.topLeft, Size(d,d), Stroke(5f))
            }

            ring(baseR+0*gap, hrPresence, Color.Yellow)
            ring(baseR+1*gap, motionStability, Color.Cyan)
            ring(baseR+2*gap, accelPresence, Color.Magenta)
            ring(baseR+3*gap, envBalance, Color.Green)
        }
    }
}

/* ================= VISUAL HELPERS ================= */

@Composable private fun WaitingPulseDots() { /* same as before */ }
@Composable private fun NeonHeatBar(name: String, values: FloatArray) { /* same as before */ }
@Composable private fun GyroWaveform(hx: List<Float>, hy: List<Float>, hz: List<Float>, range: Float=6f) { /* as given */ }
@Composable private fun CenteredZeroBar(value: Float, visualRange: Float) { /* as given */ }
@Composable private fun RotationPseudo3D(x: Float,y: Float,z: Float) { /* as given */ }
@Composable private fun MicrogridParallax() { /* as before */ }

/* ================= UTILITIES ================= */

private fun prettyValues(values: FloatArray): String = values.joinToString(prefix="[", postfix="]"){ "%.2f".format(it) }
private fun magnitude(values: FloatArray): Float = sqrt(values.fold(0f){s,v->s+v*v})
private fun labelFor(type: Int): String = when (type) {
    Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
    Sensor.TYPE_GYROSCOPE -> "Gyroscope"
    Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Accel"
    Sensor.TYPE_GRAVITY -> "Gravity"
    Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
    Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic"
    Sensor.TYPE_LIGHT -> "Light"
    Sensor.TYPE_PRESSURE -> "Pressure"
    Sensor.TYPE_RELATIVE_HUMIDITY -> "Humidity"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Temp"
    Sensor.TYPE_HEART_RATE -> "Heart Rate"
    Sensor.TYPE_STEP_COUNTER -> "Step Counter"
    else -> "Type $type"
}
