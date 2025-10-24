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
import kotlin.math.*

/* ================= GLOBAL STATE ================= */

val orientationDegState = mutableStateOf(floatArrayOf(0f, 0f, 0f))
private val lightScale = AutoScaler(decay = 0.997f, floor = 0.1f, ceil = 40_000f)
private val magScale   = AutoScaler(decay = 0.995f, floor = 5f,   ceil = 150f)
val stepBaselineState = mutableStateOf<Float?>(null)

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
            reg(Sensor.TYPE_HEART_BEAT) // RR if supported
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = labelFor(event.sensor.type)

        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val raw = event.values.firstOrNull() ?: 0f
            val base = stepBaselineState.value
            // auto-reset baseline if counter rolled back (reboot or device reset)
            if (base == null || raw < base - 10f) {
                stepBaselineState.value = raw
                CompassModel.notifySessionReset() // keep ACWR sane
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
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag = event.values.copyOf()
            }
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
                CompassModel.pushHR(bpm)            // feed rolling HR for readiness
            }
        }

        computeOrientationDegrees()?.let { orientationDegState.value = it }
        readings[key] = event.values.copyOf()

        // feed HRV to rolling model (low freq)
        CompassModel.pushHRV(HRVHistory.rmssd())
        // movement load hint from linear accel magnitude (coarse)
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

/* ================= HRV HISTORY (public) ================= */

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
        return HRVSmoother.filter(raw)
    }
}
private object HRVSmoother {
    private var last = 0f
    fun filter(v: Float, alpha: Float = 0.15f): Float { last += alpha * (v - last); return last }
}

/* ================= HISTORIES ================= */

private object SensorHistory {
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

/* ================= PAGER ================= */

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

/* ================= DASHBOARD ================= */

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
            SensorCard(name, values, onResetSteps = {
                onResetSteps = {
                    if (name == "Step Counter") {
                        stepBaselineState.value = readings["Step Counter"]?.getOrNull(0)
                        CompassModel.notifySessionReset()
                    }
                }

        }

        Spacer(Modifier.height(8.dp))
        Text("Available Sensors (${availableSensors.size})", fontWeight = FontWeight.SemiBold)
        availableSensors.take(20).forEach { line -> Text(line, fontSize = 12.sp) }
    }
}

@Composable
private fun LiveValuesLine(values: FloatArray) {
    val txt = values.joinToString(limit = 3, truncated = "…") { v -> "%.2f".format(v) }
    Text(txt, fontSize = 10.sp, color = Color(0xAA, 0xFF, 0xFF))
}

@Composable
private fun SensorCard(name: String, values: FloatArray, onResetSteps: () -> Unit) {
    Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    LiveValuesLine(values)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0x10, 0xFF, 0xFF)).padding(8.dp)
            .then(if (name == "Step Counter") Modifier.clickable { onResetSteps() } else Modifier)
    ) {
        when (name) {
            "Gyroscope" -> GyroWaveform(SensorHistory.gyroX, SensorHistory.gyroY, SensorHistory.gyroZ)
            "Gravity"   -> GravityTuner(values)                 // NEW
            "Linear Accel" -> {
                val mag = magnitude(values); CenteredZeroBar(mag, visualRange = 4f)
            }
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
                MagneticDial(heading = heading, strengthNorm = magScale.norm(mag))
            }
            "Light" -> InverseSquareLight(values.getOrNull(0) ?: 0f)   // NEW
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

/* ================= COHERENCE GLYPH (recalibrated + composite center) ================= */

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

    // Composite coherence (weighted)
    val composite = (0.35f*hrvPresence + 0.25f*hrPresence + 0.2f*motionStability + 0.1f*accelPresence + 0.1f*envBalance)
        .coerceIn(0f,1f)

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Coherence", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val cx = size.width/2f
            val cy = size.height/2f
            val baseR = min(size.width,size.height)*0.26f
            val gap = 14f

            fun ring(idx: Int, pct: Float, glow: Color, core: Color) {
                val r = baseR + gap*idx
                val d = r*2f
                val tl = Offset(cx-r, cy-r)
                val sz = Size(d,d)
                // track
                drawArc(
                    color = Color(0x22, 0xFF, 0xFF),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = 8f, cap = StrokeCap.Round)
                )

                // glow
                drawArc(
                    color = glow,
                    startAngle = -90f,
                    sweepAngle = 360f * pct,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = 10f, cap = StrokeCap.Round)
                )

                // core
                drawArc(
                    color = core,
                    startAngle = -90f,
                    sweepAngle = (360f * pct).coerceAtLeast(6f),
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )


            ring(0, hrvPresence,     Color(0x55,0xFF,0xAA), Color(0xFF,0xCC,0x66))
            ring(1, hrPresence,      Color(0x66,0x80,0xFF), Color(0xFF,0xE0,0x80))
            ring(2, motionStability, Color(0x55,0xD0,0xFF), Color(0xAA,0xFF,0xFF))
            ring(3, accelPresence,   Color(0x66,0xFF,0xD7), Color(0xFF,0xE6,0x88))
            ring(4, envBalance,      Color(0x55,0xFF,0x99), Color(0xDD,0xFF,0x99))

            // composite center glow
            val maxR = baseR - 6f
            val coreR = (maxR * (0.35f + 0.65f*composite))
            drawCircle(Color(0x33,0xFF,0xD7,0x00), radius = coreR*1.15f, center = Offset(cx,cy))
            drawCircle(Color(0xFF,0xD7,0x00), radius = coreR*0.75f, center = Offset(cx,cy))
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
    }
}

/* ================= VISUAL HELPERS ================= */

@Composable private fun WaitingPulseDots() {
    var dots by remember { mutableStateOf(0) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch { while(true){ alpha.animateTo(0.3f, tween(800)); alpha.animateTo(1f, tween(800)) } }
        scope.launch { while(true){ delay(500); dots = (dots + 1) % 4 } }
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
            fun pass(a: Float, s: Float) {
                var prev = Offset(0f, mapY(series[0]))
                for (i in 1 until series.size) {
                    val x = step*i; val y = mapY(series[i])
                    drawLine(core.copy(alpha=a), prev, Offset(x,y), s); prev = Offset(x,y)
                }
            }
            pass(0.22f,7f); pass(0.35f,4f); pass(1f,2f)
        }
        val gold = Color(0xFF,0xD7,0x00); val violet = Color(0x66,0x00,0xEA); val cyan = Color(0x00,0xD0,0xFF)
        drawSeries(hx, gold); drawSeries(hy, violet); drawSeries(hz, cyan)
    }
}

@Composable private fun GravityTuner(values: FloatArray) {
    // ultra-sensitive gauge around 1g (9.81 m/s²)
    val g = magnitude(values)
    val center = 9.81f
    val span = 0.30f // ±0.15g window
    val norm = ((g - (center - span/2f)) / span).coerceIn(0f, 1f) // 0..1 across the window
    Canvas(Modifier.fillMaxWidth().height(54.dp)) {
        val w = size.width; val h = size.height
        val cx = w/2f; val cy = h*0.65f
        val r = min(w,h)*0.45f
        // arc track
        drawArc(Color(0x22,0xFF,0xFF), 180f, 180f, false,
            topLeft = Offset(cx-r, cy-r), size = Size(r*2, r*2), style = Stroke(6f, StrokeCap.Round))
        // needle
        val ang = 180f + 180f * norm
        val rad = ang * (PI/180f).toFloat()
        val nx = cx + cos(rad)*r
        val ny = cy + sin(rad)*r
        drawLine(Color(0xFF,0xD7,0x00), Offset(cx,cy), Offset(nx,ny), 4f)
    }
}

@Composable private fun StepsRow(raw: Float, session: Float) {
    Column {
        Text("Raw: ${raw.toInt()} • Session: ${session.toInt()} (tap to reset)", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
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
    val tiltX = sin(rollDeg * (Math.PI / 180f).toFloat())
    val tiltY = sin(pitchDeg * (Math.PI / 180f).toFloat())
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
    // inverse-square feel with inverted gradient: low light shows as “shadow”, bright blooms
    // Map lux (0..40k) -> t (0..1) with log-ish compression
    val t = (ln(1f + lux) / ln(1f + 40_000f)).coerceIn(0f,1f)
    val inv = 1f - t
    val emphasis = (1f - (inv*inv)) // inverse-square on the “darkness” side
    val bar = (0.15f + 0.85f*emphasis).coerceIn(0f,1f)
    NeonHeatBarNormalized(bar)
}

@Composable private fun MicrogridParallax() {
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { delay(24L); phase = (phase + 0.6f) % 20f } }
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 20f; val w = size.width; val h = size.height
        val line = Color(0x13,0xFF,0xFF)
        var x = -phase; while (x < w) { drawLine(line, Offset(x,0f), Offset(x,h), 1f); x += spacing }
        var y = -phase; while (y < h) { drawLine(line, Offset(0f,y), Offset(w,y), 1f); y += spacing }
    }
}

/* ================= UTILITIES ================= */

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
