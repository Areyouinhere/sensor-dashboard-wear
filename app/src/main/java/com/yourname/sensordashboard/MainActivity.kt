package com.yourname.sensordashboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlin.math.*

/** ====== ACTIVITY ====== */

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepBaseline by mutableStateOf<Float?>(null)
    private var lastAccel: FloatArray? = null
    private var lastMag:   FloatArray? = null
    private var lastRotVec: FloatArray? = null
    private val readings = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<String>())
    private var groundedStart: Long? = null

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
                        .background(envAuraColor())
                ) {
                    PagerRoot(availableSensors, readings)
                }
            }
        }

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }
            .sorted()

        ensurePermissionsThenSubscribe()
    }

    private fun envAuraColor(): Color {
        val lux = readings["Light"]?.getOrNull(0) ?: 0f
        val p   = readings["Pressure"]?.getOrNull(0) ?: 1000f
        val rh  = readings["Humidity"]?.getOrNull(0) ?: 40f
        val tLux = (ln(1f + lux) / ln(1f + 40_000f)).coerceIn(0f,1f)
        val tP   = ((p - 980f)/70f).coerceIn(0f,1f)
        val tRh  = (rh/100f).coerceIn(0f,1f)
        val r = (0.06f + 0.3f*tLux)
        val g = (0.08f + 0.28f*(1f-abs(tP-0.5f)*2f))
        val b = (0.10f + 0.25f*tRh)
        return Color(r, g, b, alpha = 1f).copy(alpha = 0.25f)
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
        if (registerHeartRate) {
            reg(Sensor.TYPE_HEART_RATE)
            reg(Sensor.TYPE_HEART_BEAT)
        }
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        val key = labelFor(event.sensor.type)

        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val raw = event.values.firstOrNull() ?: 0f
            val base = stepBaseline
            if (base == null || raw < base - 10f) {
                stepBaseline = raw
                CompassModel.notifySessionReset()
            }
            val session = raw - (stepBaseline ?: raw)
            readings[key] = floatArrayOf(raw, session)
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                SensorHistory.pushAccel(magnitude(event.values))
                CompassModel.pushAccelMag(magnitude(event.values))
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag = event.values.copyOf()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values.getOrNull(0) ?: 0f
                val gy = event.values.getOrNull(1) ?: 0f
                val gz = event.values.getOrNull(2) ?: 0f
                SensorHistory.pushGyro(gx, gy, gz)
                CompassModel.pushGyroMag(sqrt(gx*gx + gy*gy + gz*gz))
            }
            Sensor.TYPE_GRAVITY -> {
                lastAccel = event.values.copyOf()
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
            Sensor.TYPE_LIGHT -> {
                val lux = event.values.getOrNull(0) ?: 0f
                SensorHistory.pushLight(lux)
                CompassModel.pushLight(lux)
            }
            Sensor.TYPE_PRESSURE -> CompassModel.pushPressure(event.values.getOrNull(0) ?: 1000f)
        }

        computeOrientationDegrees()?.let { orientationDegState.value = it }
        readings[key] = event.values.copyOf()

        // drive HRV rolling value
        CompassModel.pushHRV(HRVHistory.rmssd())

        // micro-load hint
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            CompassModel.addMicroLoad(magnitude(event.values))
        }

        // gravity anchor gesture (±5° level for ~3s)
        val ori = orientationDegState.value
        val pitch = ori.getOrNull(1)?.absoluteValue ?: 90f
        val roll  = ori.getOrNull(2)?.absoluteValue ?: 90f
        val isLevel = (pitch <= 5f && roll <= 5f)
        val now = System.currentTimeMillis()
        if (isLevel) {
            if (groundedStart == null) groundedStart = now
            if (!CompassModel.grounded.value && (now - (groundedStart ?: now)) > 2900L) {
                CompassModel.grounded.value = true
                vibrate(30)
            }
        } else {
            groundedStart = null
            CompassModel.grounded.value = false
        }

        // recompute composite/pulse
        CompassModel.recompute()
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            @Suppress("DEPRECATION") vib.vibrate(ms)
        }
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

/** ====== HRV HISTORY (shared) ====== */
object HRVHistory {
    private val rrIntervals = mutableStateListOf<Float>() // ms
    private var lastBeatMs: Long? = null

    fun push(rr: Float, max: Int = 30) {
        if (rr <= 0) return
        if (rrIntervals.size >= max) rrIntervals.removeAt(0)
        rrIntervals.add(rr)
    }

    fun pushFromHR(bpm: Float) {
        val now = System.currentTimeMillis()
        val last = lastBeatMs
        lastBeatMs = now
        if (last != null) push((now - last).toFloat())
        else push(60000f / bpm.coerceAtLeast(30f))
    }

    fun rmssd(): Float {
        if (rrIntervals.size < 2) return 0f
        var sum = 0f
        for (i in 1 until rrIntervals.size) {
            val d = rrIntervals[i] - rrIntervals[i - 1]
            sum += d * d
        }
        val raw = sqrt(sum / (rrIntervals.size - 1))
        return hrvSmooth.filter(raw)
    }
    private object hrvSmooth {
        private var last = 0f
        fun filter(v: Float, alpha: Float = 0.15f): Float { last += alpha * (v - last); return last }
    }
}

/** ====== PAGER / PAGES ====== */

@Composable
private fun PagerRoot(
    availableSensors: List<String>,
    readings: Map<String, FloatArray>
) {
    // 5-page layout: 0 Dashboard / 1 Coherence Glyph / 2 Compass / 3 Settings / 4 About
    val pagerState = rememberPagerState(pageCount = { 5 })
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> DashboardPage(availableSensors, readings)
                1 -> CoherenceGlyphPage(readings)
                2 -> CompassPage(readings)
                3 -> SettingsPage()
                4 -> AboutPage()
                else -> CoherenceGlyphPage(readings)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(5) { i ->
                Box(
                    Modifier.size(if (pagerState.currentPage == i) 8.dp else 6.dp)
                        .background(if (pagerState.currentPage == i) Color(0xFFE0CC00) else Color(0x44FFFFFF))
                )
                if (i != 4) Spacer(Modifier.width(6.dp))
            }
        }
    }
}

@Composable
private fun DashboardPage(
    availableSensors: List<String>,
    readings: Map<String, FloatArray>
) {
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
        Modifier.fillMaxSize().padding(10.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
    ) {
        Text("Sensor Dashboard", fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        if (readings.isEmpty()) {
            WaitingPulseDots()
            Spacer(Modifier.height(16.dp))
        }

        items.forEach { (name, values) ->
            androidx.wear.compose.material.Text(name, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            val txt = values.joinToString(limit = 3, truncated = "…") { v -> "%.2f".format(v) }
            Text(txt, fontSize = 10.sp, color = Color(0xAA, 0xFF, 0xFF))
            Box(
                Modifier.fillMaxWidth()
                    .background(Color(0x10, 0xFF, 0xFF))
                    .padding(8.dp)
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
                        val mag = sqrt(hx*hx + hy*hy + hz*hz)
                        val heading = orientationDegState.value.getOrNull(0) ?: 0f
                        // simple dial: strength bar + heading text
                        Text("Heading ${heading.toInt()}° • ${"%.1f".format(mag)} µT", fontSize = 11.sp)
                    }
                    "Light" -> InverseSquareLight(values.getOrNull(0) ?: 0f)
                    "Heart Rate" -> CenteredZeroBar((values.getOrNull(0) ?: 0f) - 65f, visualRange = 50f)
                    "HRV" -> CenteredZeroBar((values.getOrNull(0) ?: 0f) - 50f, visualRange = 80f)
                    "Step Counter" -> {
                        val raw = values.getOrNull(0) ?: 0f
                        val session = values.getOrNull(1) ?: 0f
                        StepsRow(raw, session)
                    }
                    else -> NeonHeatBarNormalized((magnitude(values)/50f).coerceIn(0f,1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text("Available Sensors (${availableSensors.size})", fontSize = 12.sp)
        availableSensors.take(20).forEach { line -> Text(line, fontSize = 10.sp, color = Color(0xCC,0xFF,0xFF)) }
    }
}

@Composable private fun SettingsPage() {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Settings", fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))
        Text("• Toggle pages (coming soon)")
        Text("• Font size presets (coming soon)")
        Text("• Tone/haptic presets (coming soon)")
        Text("• Parallax background (coming soon)")
    }
}

@Composable private fun AboutPage() {
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("About", fontSize = 18.sp)
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))
        Text("Galaxy Watch 7 Sensor Dashboard")
        Text("Coherence Engine — field node")
        Spacer(Modifier.height(8.dp))
        Text("Built with love by Jason + Caelo")
    }
}

/** ====== LABELS ====== */
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
    Sensor.TYPE_HEART_BEAT          -> "Heart Beat"
    else -> "Type $type"
}
