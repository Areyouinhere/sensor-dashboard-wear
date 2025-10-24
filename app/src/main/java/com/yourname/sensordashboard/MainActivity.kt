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
import androidx.compose.foundation.background
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

/* =========================
   GLOBAL STATE + UTILITIES
   ========================= */

val orientationDegState = mutableStateOf(floatArrayOf(0f, 0f, 0f))
val stepBaselineState   = mutableStateOf<Float?>(null) // shared steps baseline

private val lightScale = AutoScaler(decay = 0.997f, floor = 0.1f, ceil = 40_000f)
val magScale   = AutoScaler(decay = 0.995f, floor = 5f,   ceil = 150f) // public for UiBits

/** Shared math + formatting helpers */
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

/* ================= ACTIVITY ================= */

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val readings = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<String>())

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
            reg(Sensor.TYPE_HEART_BEAT) // RR if supported
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
            Sensor.TYPE_HEART_BEAT -> {
                val rr = event.values.getOrNull(0) ?: return
                HRVHistory.push(rr)
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.getOrNull(0) ?: return
                HRVHistory.pushFromHR(bpm)
                CompassModel.pushHR(bpm)
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

/* ================= HRV HISTORY ================= */

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
        val raw = kotlin.math.sqrt(sum / (rrIntervals.size - 1))
        return HRVSmoother.filter(raw)
    }
}
private object HRVSmoother {
    private var last = 0f
    fun filter(v: Float, alpha: Float = 0.15f): Float { last += alpha * (v - last); return last }
}

/* ================= HISTORIES ================= */

object SensorHistory {
    val gyroX = mutableStateListOf<Float>()
    val gyroY = mutableStateListOf<Float>()
    val gyroZ = mutableStateListOf<Float>()
    val accel = mutableStateListOf<Float>()
    val grav  = mutableStateListOf<Float>()
    private fun push(list: MutableList<Float>, v: Float, max: Int = 120) {
        if (list.size >= max) list.removeAt(0); list.add(v)
    }
    fun pushGyro(x: Float, y: Float, z: Float) { push(gyroX, x); push(gyroY, y); push(gyroZ, z) }
    fun pushAccel(m: Float) = push(accel, m)
    fun pushGrav(m: Float)  = push(grav, m)
}

/* ================= PAGER + DASHBOARD ================= */

@Composable
private fun PagerRoot(
    availableSensors: List<String>,
    readings: Map<String, FloatArray>
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
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
                else -> CoherenceGlyphPage(readings)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(3) { i ->
                Dot(active = pagerState.currentPage == i)
                if (i != 2) Spacer(Modifier.width(6.dp))
            }
        }
    }
}
@Composable private fun Dot(active: Boolean) {
    Box(
        Modifier.size(if (active) 8.dp else 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF, 0xD7, 0x00) else Color(0x44, 0xFF, 0xFF))
    )
}

@Composable
private fun Dashboard(
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
        Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Sensor Dashboard", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(4.dp))
        DividerLine()
        Spacer(Modifier.height(8.dp))

        if (readings.isEmpty()) {
            WaitingPulseDots()
            Spacer(Modifier.height(16.dp))
        }

        items.forEach { (name, values) ->
            SensorCard(
                name = name,
                values = values,
                onResetSteps = {
                    if (name == "Step Counter") {
                        stepBaselineState.value = readings["Step Counter"]?.getOrNull(0)
                        CompassModel.notifySessionReset()
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Available Sensors (${availableSensors.size})", fontWeight = FontWeight.SemiBold)
        availableSensors.take(20).forEach { line -> Text(line, fontSize = 12.sp) }
    }
}

/* ================= COHERENCE GLYPH (gradient center + details) ================= */

@Composable
private fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f, 0f, 0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0)   ?: 1000f
    val hrv   = HRVHistory.rmssd()

    fun soft01(x: Float) = x.coerceIn(0f, 1f)
    fun knee(x: Float, k: Float = 0.6f) = run {
        val t = x.coerceIn(0f,1f); if (t < k) t/k*0.7f else 0.7f + (t-k)/(1f-k)*0.3f
    }

    val accelMag = magnitude(accel)
    val gyroMag  = magnitude(gyro)
    val nAccel   = soft01(accelMag / 6f)
    val nGyro    = soft01(gyroMag  / 4f)
    val hrMid    = 65f
    val hrSpan   = 50f
    val nHR      = soft01(0.5f + (hr - hrMid)/(2f*hrSpan))
    val nP       = soft01((press - 980f)/70f)
    val nHRV     = soft01(hrv/80f)

    val ema = remember { mutableStateOf(floatArrayOf(nAccel, nGyro, nHR, nP, nHRV)) }
    val alpha = 0.12f
    val target = floatArrayOf(nAccel, nGyro, nHR, nP, nHRV)
    val s = FloatArray(5) { i -> ema.value[i] + alpha*(target[i]-ema.value[i]) }
    ema.value = s

    val hrvPresence      = knee(s[4])
    val hrPresence       = knee(1f - abs(s[2]-0.5f)*2f)
    val motionStability  = knee(1f - s[1])
    val accelPresence    = knee(s[0])
    val envBalance       = knee(1f - abs(s[3]-0.5f)*2f)

    val composite = (0.35f*hrvPresence + 0.25f*hrPresence + 0.2f*motionStability + 0.1f*accelPresence + 0.1f*envBalance)
        .coerceIn(0f,1f)

    var showDetail by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Coherence", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val cx = size.width/2f
            val cy = size.height/2f
            val baseR = min(size.width,size.height)*0.26f
            val gap = 14f

            fun ring(idx: Int, pct: Float, glow: Color, core: Color) {
                val r = baseR + gap*idx
                val d = r*2f
                val tl = androidx.compose.ui.geometry.Offset(cx-r, cy-r)
                val sz = androidx.compose.ui.geometry.Size(d,d)
                // track
                drawArc(
                    color = Color(0x22,0xFF,0xFF),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = tl, size = sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                // glow
                drawArc(
                    color = glow,
                    startAngle = -90f, sweepAngle = 360f*pct.coerceIn(0f,1f), useCenter = false,
                    topLeft = tl, size = sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 10f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                // core
                drawArc(
                    color = core,
                    startAngle = -90f, sweepAngle = (360f*pct).coerceAtLeast(6f), useCenter = false,
                    topLeft = tl, size = sz,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }

            ring(0, hrvPresence,     Color(0x55,0xFF,0xAA), Color(0xFF,0xCC,0x66))
            ring(1, hrPresence,      Color(0x66,0x80,0xFF), Color(0xFF,0xE0,0x80))
            ring(2, motionStability, Color(0x55,0xD0,0xFF), Color(0xAA,0xFF,0xFF))
            ring(3, accelPresence,   Color(0x66,0xFF,0xD7), Color(0xFF,0xE6,0x88))
            ring(4, envBalance,      Color(0x55,0xFF,0x99), Color(0xDD,0xFF,0x99))

            // Dynamic center gradient: low=purple→red, high=teal/blue→bright
            val maxR = baseR - 6f
            val coreR = (maxR * (0.35f + 0.65f*composite))
            val t = composite.coerceIn(0f,1f)
            // colors
            val lowEdge   = Color(0x88, 0x00, 0xAA)   // purple edge
            val lowCore   = Color(0xCC, 0x22, 0x22)   // reddish center
            val highEdge  = Color(0x11, 0xCC, 0xEE)   // teal edge
            val highCore  = Color(0xFF, 0xFF, 0xFF)   // bright center
            fun lerp(c1: Color, c2: Color, u: Float): Color = Color(
                red   = (c1.red   + (c2.red   - c1.red)   * u),
                green = (c1.green + (c2.green - c1.green) * u),
                blue  = (c1.blue  + (c2.blue  - c1.blue)  * u),
                alpha = 1f
            )
            val edge = lerp(lowEdge,  highEdge,  t)
            val core = lerp(lowCore,  highCore,  t)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(core.copy(alpha = 0.85f), edge.copy(alpha = 0.0f)),
                    center = androidx.compose.ui.geometry.Offset(cx, cy),
                    radius = coreR * 1.2f
                ),
                radius = coreR * 1.2f,
                center = androidx.compose.ui.geometry.Offset(cx, cy)
            )
            // subtle inner bloom
            drawCircle(
                color = core.copy(alpha = 0.35f + 0.45f*t),
                radius = coreR * (0.45f + 0.25f*t),
                center = androidx.compose.ui.geometry.Offset(cx, cy)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "HRV ${fmtMs(hrv)} • HR ${hr.toInt()} bpm • Motion ${fmtPct(1f - nGyro)}",
            fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Accel ${fmt1(nAccel)} • Env ${fmtPct(1f - abs(nP-0.5f)*2f)} • Coherence ${fmtPct(composite)}",
            fontSize = 11.sp, color = Color(0x99,0xFF,0xFF)
        )

        Spacer(Modifier.height(10.dp))
        var show by remember { mutableStateOf(false) }
        Text(
            text = if (show) "Hide explanation ▲" else "What is this? ▼",
            fontSize = 11.sp,
            color = Color(0xFF, 0xD7, 0x00),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .let { it } // keep minimal; toggle handled below
        )
        Spacer(Modifier.height(4.dp))
        // Tap anywhere in this area to toggle (easier on-watch)
        Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x11,0xFF,0xFF))
                .padding(8.dp)
        ) {
            androidx.compose.foundation.clickable(enabled = true) { show = !show }
            if (show) {
                Text(
                    "- Center glow = overall coherence (blue/bright when high; purple→red when low).\n" +
                    "- Rings (inner→outer): HRV capacity, HR mid-banding, motion stability, movement, env balance.\n" +
                    "- Signals are normalized & smoothed; look for **trend**, not single ticks.\n" +
                    "- Coherence% blends HRV (35%), HR banding (25%), motion stability (20%), accel (10%), env (10%).",
                    fontSize = 11.sp,
                    color = Color(0xAA,0xFF,0xFF),
                    lineHeight = 14.sp
                )
            }
        }
    }
}
