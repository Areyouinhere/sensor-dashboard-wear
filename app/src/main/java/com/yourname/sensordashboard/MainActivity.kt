// MainActivity.kt (new version with HRV + upgraded Coherence Glyph)

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
import kotlin.math.*

/* ================= GLOBAL SIGNAL BUS / SCALERS ================= */

private val orientationDegState = mutableStateOf(floatArrayOf(0f, 0f, 0f))
private val lightScale = AutoScaler(decay = 0.995f, floor = 1f, ceil = 20000f)
private val magScale   = AutoScaler(decay = 0.995f, floor = 5f,  ceil = 150f)

/* ================= ACTIVITY ================= */

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val readings = mutableStateMapOf<String, FloatArray>()
    private var availableSensors by mutableStateOf(listOf<String>())
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
            reg(Sensor.TYPE_HEART_BEAT) // new: for RR intervals
        }
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
            Sensor.TYPE_ACCELEROMETER -> { lastAccel = event.values.copyOf(); SensorHistory.pushAccel(magnitude(event.values)) }
            Sensor.TYPE_MAGNETIC_FIELD -> { lastMag = event.values.copyOf() }
            Sensor.TYPE_GYROSCOPE -> { SensorHistory.pushGyro(event.values[0], event.values[1], event.values[2]) }
            Sensor.TYPE_GRAVITY -> { lastAccel = event.values.copyOf(); SensorHistory.pushGrav(magnitude(event.values)) }
            Sensor.TYPE_ROTATION_VECTOR -> { lastRotVec = event.values.copyOf() }
            Sensor.TYPE_HEART_BEAT -> {
                val rr = event.values.getOrNull(0) ?: return
                HRVHistory.push(rr)
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.getOrNull(0) ?: return
                HRVHistory.pushFromHR(bpm)
            }
        }

        computeOrientationDegrees()?.let { orientationDegState.value = it }
        readings[key] = event.values.copyOf()
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

private object HRVHistory {
    private val rrIntervals = mutableStateListOf<Float>() // ms
    private var lastBeatMs: Long? = null

    fun push(rr: Float, max: Int = 30) {
        if (rr <= 0) return
        if (rrIntervals.size >= max) rrIntervals.removeAt(0)
        rrIntervals.add(rr)
    }

    // fallback if only BPM
    fun pushFromHR(bpm: Float) {
        val now = System.currentTimeMillis()
        val beatMs = (60000f / bpm.coerceAtLeast(30f))
        val last = lastBeatMs
        lastBeatMs = now
        if (last != null) {
            val diff = (now - last).toFloat()
            push(diff)
        } else {
            push(beatMs)
        }
    }

    fun rmssd(): Float {
        if (rrIntervals.size < 2) return 0f
        var sum = 0f
        for (i in 1 until rrIntervals.size) {
            val d = rrIntervals[i] - rrIntervals[i-1]
            sum += d*d
        }
        val raw = sqrt(sum / (rrIntervals.size-1))
        // recursive smoothing for coherence stability
        return HRVSmoother.filter(raw)
    }
}

private object HRVSmoother {
    private var last = 0f
    fun filter(v: Float, alpha: Float = 0.15f): Float {
        last += alpha * (v - last)
        return last
    }
}

/* ================= COHERENCE GLYPH ================= */

@Composable
private fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f,0f,0f)
    val gyro  = readings["Gyroscope"] ?: floatArrayOf(0f,0f,0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0) ?: 1000f
    val hrv   = HRVHistory.rmssd()

    val nAccel = (magnitude(accel) / 15f).coerceIn(0f,1f)
    val nGyro  = (magnitude(gyro)  / 8f ).coerceIn(0f,1f)
    val nHR    = ((hr - 50f) / 100f).coerceIn(0f,1f)
    val nP     = ((press - 980f) / 80f).coerceIn(0f,1f)
    val nHRV   = (hrv / 120f).coerceIn(0f,1f) // normalized 0–120 ms

    val ema = remember { mutableStateOf(floatArrayOf(nAccel,nGyro,nHR,nP,nHRV)) }
    val alpha = 0.12f
    val target = floatArrayOf(nAccel,nGyro,nHR,nP,nHRV)
    val smoothed = FloatArray(5) { i -> ema.value[i] + alpha*(target[i]-ema.value[i]) }
    ema.value = smoothed

    val accelPresence = smoothed[0]
    val motionStability = 1f - smoothed[1]
    val hrPresence = 1f - abs(smoothed[2]-0.5f)*2f
    val envBalance = 1f - abs(smoothed[3]-0.5f)*2f
    val hrvPresence = smoothed[4]

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Coherence", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val cx = size.width/2f; val cy = size.height/2f
            val baseR = min(size.width,size.height)*0.28f
            val gap = 14f
            fun ring(idx: Int, pct: Float, glow: Color, core: Color) {
                val r = baseR + gap*idx
                val d = r*2f
                drawArc(Color(0x22,0xFF,0xFF), -90f, 360f, false, Offset(cx-r,cy-r), Size(d,d), Stroke(8f, StrokeCap.Round))
                drawArc(glow, -90f, 360f*pct, false, Offset(cx-r,cy-r), Size(d,d), Stroke(10f, StrokeCap.Round))
                drawArc(core, -90f, (360f*pct).coerceAtLeast(6f), false, Offset(cx-r,cy-r), Size(d,d), Stroke(5f, StrokeCap.Round))
            }
            ring(0, hrvPresence, Color(0x44,0xFF,0xAA), Color(0xFF,0xCC,0x66)) // HRV
            ring(1, hrPresence, Color(0x66,0x00,0xEA), Color(0xFFD700))        // HR
            ring(2, motionStability, Color(0x44,0xD0,0xFF), Color(0xAA,0xFF,0xFF)) // Gyro stability
            ring(3, accelPresence, Color(0x55,0xFF,0xD7), Color(0xFFE688))     // Accel
            ring(4, envBalance, Color(0x44,0xFF,0x99), Color(0xDD,0xFF,0x99))  // Pressure/env
        }
    }
}
