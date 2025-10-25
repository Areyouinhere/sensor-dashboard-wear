package com.yourname.sensordashboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.compose.foundation.border
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/* ===== AutoScaler + shared ===== */
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

val orientationDegState = mutableStateOf(floatArrayOf(0f, 0f, 0f))
val stepBaselineState   = mutableStateOf<Float?>(null)
val lightScale = AutoScaler(decay = 0.997f, floor = 0.1f, ceil = 40_000f)
val magScale   = AutoScaler(decay = 0.995f, floor = 5f,   ceil = 150f)

fun magnitude(v: FloatArray): Float = sqrt(v.fold(0f) { s, x -> s + x*x })
fun fmtPct(v: Float): String = "${(v.coerceIn(0f,1f)*100f).roundToInt()}%"
fun fmtMs(v: Float): String  = "${v.roundToInt()} ms"
fun fmt1(v: Float): String   = "%.1f".format(v.coerceIn(0f,1f))
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

/* ===== Activity ===== */
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val readings = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<Sensor>())

    private var lastAccel: FloatArray? = null
    private var lastMag:   FloatArray? = null
    private var lastRotVec: FloatArray? = null

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

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL).sortedBy { it.name }
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
        if (registerHeartRate) {
            reg(Sensor.TYPE_HEART_RATE)
            reg(Sensor.TYPE_HEART_BEAT)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = labelFor(event.sensor.type)

        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val raw = event.values.firstOrNull() ?: 0f
            val base = stepBaselineState.value
            if (base == null || raw < base - 10f) {
                stepBaselineState.value = raw
                CompassModel.notifySessionReset()
            }
            val session = raw - (stepBaselineState.value ?: raw)
            readings[key] = floatArrayOf(raw, session)
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                SensorHistory.pushAccel(magnitude(event.values))
            }
            Sensor.TYPE_MAGNETIC_FIELD -> { lastMag = event.values.copyOf() }
            Sensor.TYPE_GYROSCOPE -> {
                SensorHistory.pushGyro(
                    event.values.getOrNull(0) ?: 0f,
                    event.values.getOrNull(1) ?: 0f,
                    event.values.getOrNull(2) ?: 0f
                )
            }
            Sensor.TYPE_GRAVITY -> {
                lastAccel = event.values.copyOf()
                SensorHistory.pushGrav(magnitude(event.values))
            }
            Sensor.TYPE_ROTATION_VECTOR -> { lastRotVec = event.values.copyOf() }
            Sensor.TYPE_LIGHT -> SensorHistory.pushLight(event.values.getOrNull(0) ?: 0f)
            Sensor.TYPE_PRESSURE -> SensorHistory.pushPressure(event.values.getOrNull(0) ?: 0f)
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.getOrNull(0) ?: 0f
                SensorHistory.pushHR(bpm)
                HRVHistory.pushFromHR(bpm)
                CompassModel.pushHR(bpm)
            }
            Sensor.TYPE_HEART_BEAT -> {
                val rr = event.values.getOrNull(0) ?: return
                HRVHistory.push(rr)
            }
        }

        computeOrientationDegrees()?.let { orientationDegState.value = it }
        readings[key] = event.values.copyOf()

        CompassModel.pushHRV(HRVHistory.rmssd())
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            CompassModel.addMicroLoad(magnitude(event.values))
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

/* ===== HRV HISTORY, HISTORIES (unchanged) ===== */

object HRVHistory {
    private val rrIntervals = mutableStateListOf<Float>()
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
        val raw = kotlin.math.sqrt(sum / (rrIntervals.size - 1))
        return HRVSmoother.filter(raw)
    }
}
private object HRVSmoother {
    private var last = 0f
    fun filter(v: Float, alpha: Float = 0.15f): Float { last += alpha * (v - last); return last }
}

object SensorHistory {
    val gyroX = mutableStateListOf<Float>()
    val gyroY = mutableStateListOf<Float>()
    val gyroZ = mutableStateListOf<Float>()
    val accel = mutableStateListOf<Float>()
    val grav  = mutableStateListOf<Float>()
    val light = mutableStateListOf<Float>()
    val pressure = mutableStateListOf<Float>()
    val hr = mutableStateListOf<Float>()
    private fun push(list: MutableList<Float>, v: Float, max: Int = 120) {
        if (list.size >= max) list.removeAt(0)
        list.add(v)
    }
    fun pushGyro(x: Float, y: Float, z: Float) { push(gyroX,x); push(gyroY,y); push(gyroZ,z) }
    fun pushAccel(m: Float) = push(accel, m)
    fun pushGrav(m: Float)  = push(grav, m)
    fun pushLight(l: Float) = push(light, l)
    fun pushPressure(p: Float) = push(pressure, p)
    fun pushHR(bpm: Float) = push(hr, bpm)
}

/* ===== Pager + Pages ===== */

@Composable
private fun PagerRoot(
    availableSensors: List<Sensor>,
    readings: Map<String, FloatArray>
) {
    val pagerState = rememberPagerState(pageCount = { 4 }) // +1 page for Settings
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> Dashboard(availableSensors, readings)
                1 -> CoherenceGlyphPage(readings)
                2 -> CompassPage(readings)
                3 -> SettingsPage() // NEW
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(4) { i ->
                Dot(active = pagerState.currentPage == i)
                if (i != 3) Spacer(Modifier.width(6.dp))
            }
        }
    }
}

@Composable private fun Dot(active: Boolean) {
    Box(
        Modifier.size(if (active) 8.dp else 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) UiSettings.accentColor else Color(0x44, 0xFF, 0xFF))
    )
}

/* ===== PAGE 1 – Dashboard (unchanged layout; sensor list richer already) ===== */
// (same Dashboard() as you have now; it pulls UiBits components and already prints rich sensor info)

/* ===== PAGE 2 – Coherence Glyph ===== */
// (use your current CoherenceGlyphPage from the last drop; just ensure any info
//  cards use UiSettings.bubbleBgColor instead of light cyan)

/* ===== Settings Page (NEW) ===== */

@Composable
fun SettingsPage() {
    Column(
        Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        Spacer(Modifier.height(6.dp)); DividerLine(); Spacer(Modifier.height(10.dp))

        // Color swatches
        SettingSection("Accent Color") {
            ColorRow(
                listOf(
                    Color(0xFF,0xD7,0x00), Color(0x44,0xFF,0x88), Color(0xFF,0x66,0x66),
                    Color(0x88,0x33,0xCC), Color(0x00,0xD0,0xFF), Color(0xFF,0xA5,0x00)
                ),
                selected = UiSettings.accentColor
            ) { UiSettings.accentColor = it }
        }

        SettingSection("Glow Color") {
            ColorRow(
                listOf(
                    Color(0x66,0x00,0xEA), Color(0x00,0xB3,0xFF), Color(0xFF,0x33,0x99),
                    Color(0x99,0x00,0xFF), Color(0x00,0xFF,0xCC), Color(0xFF,0x00,0x66)
                ),
                selected = UiSettings.glowColor
            ) { UiSettings.glowColor = it }
        }

        SettingSection("Bubble Background") {
            ColorRow(
                listOf(
                    Color(0f,0f,0f,0.80f), Color(1f,1f,1f,0.12f), Color(0f,0f,0f,0.6f),
                    Color(0.1f,0.1f,0.1f,0.9f), Color(0.0f,0.2f,0.2f,0.6f), Color(0.2f,0.0f,0.2f,0.6f)
                ),
                selected = UiSettings.bubbleBgColor
            ) { UiSettings.bubbleBgColor = it }
        }

        SettingSection("Grid Color") {
            ColorRow(
                listOf(
                    Color(0x13,0xFF,0xFF), Color(0x13,0xFF,0x13), Color(0x13,0x13,0xFF),
                    Color(0x22,0xAA,0xFF), Color(0x22,0xFF,0xAA), Color(0xFF,0x22,0xAA)
                ),
                selected = UiSettings.gridColor
            ) { UiSettings.gridColor = it }
        }

        // Sliders (simple discrete chips for watch-friendliness)
        SettingSection("Grid Speed") {
            ChipRow(
                items = listOf(0.0f, 0.3f, 0.6f, 1.0f, 1.5f),
                selected = UiSettings.gridSpeed
            ) { UiSettings.gridSpeed = it }
        }
        SettingSection("Grid Spacing") {
            ChipRow(
                items = listOf(12f, 16f, 20f, 26f, 32f, 40f),
                selected = UiSettings.gridSpacing
            ) { UiSettings.gridSpacing = it }
        }

        SettingSection("Grid Style") {
            ToggleRow(
                label = "Isometric",
                checked = UiSettings.isometric
            ) { UiSettings.isometric = it }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: Accent affects needles/bars, Glow affects fills/outlines. Bubbles use your chosen backdrop for info cards.",
            fontSize = 11.sp, color = Color(0xAA,0xFF,0xFF), lineHeight = 14.sp
        )
        Spacer(Modifier.height(12.dp))
    }
}

/* --- tiny UI helpers --- */
@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(UiSettings.bubbleBgColor).padding(10.dp)
    ) {
        Text(title, fontSize = 12.sp, color = UiSettings.accentColor)
        Spacer(Modifier.height(6.dp))
        content()
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ColorRow(colors: List<Color>, selected: Color, onPick: (Color) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        colors.forEach { c ->
            Box(
                Modifier.size(if (c == selected) 26.dp else 22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun ChipRow(items: List<Float>, selected: Float, onPick: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { v ->
            val active = (v == selected)
            val bg = if (active) UiSettings.accentColor.copy(alpha = 0.25f) else UiSettings.bubbleBgColor
            val bd = if (active) UiSettings.accentColor else Color(0x33,0xFF,0xFF)
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .border(width = 1.dp, color = bd, shape = RoundedCornerShape(10.dp))
                    .clickable { onPick(v) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(if (items.first() < 10f) "${v.toInt()}px" else "%.1f".format(v),
                    fontSize = 11.sp, color = Color(0xDD,0xFF,0xFF))
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xEE,0xFF,0xFF))
        Box(
            Modifier.width(38.dp).height(22.dp).clip(RoundedCornerShape(12.dp))
                .background(if (checked) UiSettings.accentColor.copy(alpha=0.4f) else Color(0x22,0xFF,0xFF))
        ) {
            Box(
                Modifier.offset(x = if (checked) 18.dp else 2.dp, y = 2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (checked) UiSettings.accentColor else Color(0x66,0xFF,0xFF))
            )
        }
    }
}
