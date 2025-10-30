package com.yourname.sensordashboard

import android.content.Context
import android.hardware.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.ScrollState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/* ----------------------- GLOBAL STATE & SCALES ----------------------- */

val orientationDegState = mutableStateOf(floatArrayOf(0f, 0f, 0f))
val availableSensorsState = mutableStateOf(listOf<String>())
val readingsState = mutableStateMapOf<String, FloatArray>()

internal val lightScale = AutoScaler(decay = 0.997f, floor = 0.1f, ceil = 40_000f)
internal val magScale   = AutoScaler(decay = 0.995f, floor = 5f,   ceil = 150f)

/* ----------------------- ACTIVITY ----------------------- */

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private var stepBaseline by mutableStateOf<Float?>(null)
    private var lastAccel: FloatArray? = null
    private var lastMag:   FloatArray? = null
    private var lastRotVec: FloatArray? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> subscribeSensors(registerHeartRate = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // List sensors once so the UI can show a stable list
        availableSensorsState.value = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }
            .sorted()

        setContent {
            MaterialTheme {
                // Optional micro-weather aura background
                val auraColor = MicroWeatherAuraColor(
                    lux = readingsState["Light"]?.getOrNull(0),
                    pressure = readingsState["Pressure"]?.getOrNull(0),
                    humidity = readingsState["Humidity"]?.getOrNull(0)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (AppSettings.enableAura) auraColor else Color.Black)
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                ) {
                    AppPager()
                }
            }
        }

        ensurePermissionsThenSubscribe()
    }

    private fun ensurePermissionsThenSubscribe() {
        // Register what we can immediately
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
        // Core motion / orientation
        reg(Sensor.TYPE_ACCELEROMETER)
        reg(Sensor.TYPE_LINEAR_ACCELERATION)
        reg(Sensor.TYPE_GYROSCOPE)
        reg(Sensor.TYPE_GRAVITY)
        reg(Sensor.TYPE_ROTATION_VECTOR)
        reg(Sensor.TYPE_MAGNETIC_FIELD)

        // Environment
        reg(Sensor.TYPE_LIGHT)
        reg(Sensor.TYPE_PRESSURE)
        reg(Sensor.TYPE_RELATIVE_HUMIDITY)
        reg(Sensor.TYPE_AMBIENT_TEMPERATURE)

        // Steps
        reg(Sensor.TYPE_STEP_COUNTER)

        // HR/HRV (if permission granted)
        if (registerHeartRate) {
            reg(Sensor.TYPE_HEART_RATE)
            reg(Sensor.TYPE_HEART_BEAT)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = labelFor(event.sensor.type)

        // Step counter (session baseline handling)
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val raw = event.values.firstOrNull() ?: 0f
            val base = stepBaseline
            if (base == null || raw < base - 10f) {
                stepBaseline = raw
                CompassModel.notifySessionReset()
            }
            val session = raw - (stepBaseline ?: raw)
            readingsState[key] = floatArrayOf(raw, session)
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                SensorHistory.pushAccel(magnitude(event.values))
                CompassModel.pushAccelMag(magnitude(event.values))
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                CompassModel.addMicroLoad(magnitude(event.values))
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag = event.values.copyOf()
            }
            Sensor.TYPE_GYROSCOPE -> {
                SensorHistory.pushGyro(
                    event.values.getOrNull(0) ?: 0f,
                    event.values.getOrNull(1) ?: 0f,
                    event.values.getOrNull(2) ?: 0f
                )
                CompassModel.pushGyroMag(magnitude(event.values))
            }
            Sensor.TYPE_GRAVITY -> {
                SensorHistory.pushGrav(magnitude(event.values))
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                lastRotVec = event.values.copyOf()
            }
            Sensor.TYPE_HEART_BEAT -> {
                val rr = event.values.getOrNull(0) ?: return
                HRVHistory.push(rr)
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.getOrNull(0) ?: return
                HRVHistory.pushFromHR(bpm)
                CompassModel.pushHR(bpm)
            }
            Sensor.TYPE_PRESSURE -> CompassModel.pushPressure(event.values.getOrNull(0) ?: 0f)
            Sensor.TYPE_LIGHT ->   CompassModel.pushLight(event.values.getOrNull(0) ?: 0f)
        }

        computeOrientationDegrees()?.let { orientationDegState.value = it }
        readingsState[key] = event.values.copyOf()

        // feed HRV to model
        CompassModel.pushHRV(HRVHistory.rmssd())
        // recompute composite/pulse/confidence
        CompassModel.recompute()
    }

    private fun computeOrientationDegrees(): FloatArray? {
        val rMat = FloatArray(9)
        val iMat = FloatArray(9)
        val ori  = FloatArray(3)
        return when {
            lastRotVec != null -> {
                SensorManager.getRotationMatrixFromVector(rMat, lastRotVec)
                SensorManager.getOrientation(rMat, ori)
                floatArrayOf(ori[0]*180f/PI.toFloat(), ori[1]*180f/PI.toFloat(), ori[2]*180f/PI.toFloat())
            }
            lastAccel != null && lastMag != null -> {
                if (SensorManager.getRotationMatrix(rMat, iMat, lastAccel, lastMag)) {
                    SensorManager.getOrientation(rMat, ori)
                    floatArrayOf(ori[0]*180f/PI.toFloat(), ori[1]*180f/PI.toFloat(), ori[2]*180f/PI.toFloat())
                } else null
            }
            else -> null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onDestroy() { super.onDestroy(); sensorManager.unregisterListener(this) }
}

/* ----------------------- PAGER ----------------------- */

@Composable
private fun AppPager() {
    // 0 Dashboard • 1 Glyph • 2 Compass • 3 Trends • 4 Settings
    val pagerState = rememberPagerState(pageCount = { 5 })
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> DashboardPage(availableSensorsState.value, readingsState)
                1 -> CoherenceGlyphPage(readingsState)
                2 -> CompassPage(readingsState)
                3 -> TrendsPage()      // sparkline history of composite
                4 -> SettingsPage()
            }
        }
        PagerDots(5, pagerState.currentPage)
    }
}

/* ----------------------- SMALL HELPERS & MODELS ----------------------- */

private class AutoScaler(
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
}

fun magnitude(v: FloatArray): Float = sqrt(v.fold(0f) { s, x -> s + x*x })

fun labelFor(type: Int): String = when (type) {
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
    Sensor.TYPE_HEART_BEAT          -> "Heart Beat"
    else -> "Type $type"
}

/* Micro-weather aura: smooth background wash */
@Composable
fun MicroWeatherAuraColor(lux: Float?, pressure: Float?, humidity: Float?): Color {
    if (!AppSettings.enableAura) return Color.Black
    val l = (lux ?: 0f).coerceAtLeast(0f)
    val p = (pressure ?: 1000f)
    val h = (humidity ?: 40f)
    // map to soft 0..1
    val tLux = (kotlin.math.ln(1f + l) / kotlin.math.ln(1f + 40_000f)).coerceIn(0f, 1f)
    val tP   = ((p - 980f)/70f).coerceIn(0f,1f)
    val tH   = (h / 100f).coerceIn(0f,1f)
    // blend: warm for bright/dry, cool for dim/humid
    val r = 0.08f + 0.55f*tLux + 0.15f*(1f - tH)
    val g = 0.10f + 0.45f*tLux + 0.10f*tH + 0.10f*tP
    val b = 0.12f + 0.60f*(1f - tLux) + 0.18f*tH
    return Color(r.coerceIn(0f,1f), g.coerceIn(0f,1f), b.coerceIn(0f,1f))
}
