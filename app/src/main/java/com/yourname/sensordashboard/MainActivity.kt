package com.yourname.sensordashboard

import androidx.compose.ui.text.font.FontWeight
import kotlin.math.min
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val sensorValues = mutableStateMapOf<String, String>()
    private var availableSensors by mutableStateOf(listOf<String>())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { subscribeSensors() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)   // Dark background
                ) {
                    Dashboard(availableSensors, sensorValues)
                }
            }
        }

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }.sorted()

        ensurePermissionsThenSubscribe()
    }

    private fun ensurePermissionsThenSubscribe() {
        val needsBody = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) != PackageManager.PERMISSION_GRANTED
        val needsAct = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACTIVITY_RECOGNITION
        ) != PackageManager.PERMISSION_GRANTED

        if (needsBody || needsAct) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BODY_SENSORS,
                    Manifest.permission.ACTIVITY_RECOGNITION
                )
            )
        } else {
            subscribeSensors()
        }
    }

    private fun subscribeSensors() {
        sensorManager.unregisterListener(this)

        fun reg(type: Int, delay: Int = SensorManager.SENSOR_DELAY_NORMAL) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, delay)
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

        // Activity / biometrics
        reg(Sensor.TYPE_STEP_COUNTER)
        reg(Sensor.TYPE_HEART_RATE)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val name = labelFor(event.sensor.type)
        val valueStr = formatTriple(event.values)
        sensorValues[name] = valueStr
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}

// ---------- UI + helpers ----------

private fun formatTriple(values: FloatArray): String {
    return when (values.size) {
        3 -> {
            val (x, y, z) = values
            val mag = sqrt(x * x + y * y + z * z)
            "[x=%.1f, y=%.1f, z=%.1f | |v|=%.1f]".format(x, y, z, mag)
        }
        2 -> "[%.1f, %.1f]".format(values[0], values[1])
        1 -> "[%.1f]".format(values[0])
        else -> values.joinToString(prefix = "[", postfix = "]") { "%.1f".format(it) }
    }
}

private fun labelFor(type: Int): String = when (type) {
    Sensor.TYPE_ACCELEROMETER      -> "Accelerometer"
    Sensor.TYPE_GYROSCOPE          -> "Gyroscope"
    Sensor.TYPE_LINEAR_ACCELERATION-> "Linear Accel"
    Sensor.TYPE_GRAVITY            -> "Gravity"
    Sensor.TYPE_ROTATION_VECTOR    -> "Rotation Vector"
    Sensor.TYPE_MAGNETIC_FIELD     -> "Magnetic"
    Sensor.TYPE_LIGHT              -> "Light"
    Sensor.TYPE_PRESSURE           -> "Pressure"
    Sensor.TYPE_RELATIVE_HUMIDITY  -> "Humidity"
    Sensor.TYPE_AMBIENT_TEMPERATURE-> "Ambient Temp"
    Sensor.TYPE_HEART_RATE         -> "Heart Rate"
    Sensor.TYPE_STEP_COUNTER       -> "Step Counter"
    else -> "Type $type"
}
private fun parseMag(values: String): Float {
    // expects "... | |v|=12.34]"
    return Regex("""\|v\|=([0-9.]+)""")
        .find(values)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
}

/** 0.0 -> cool (teal) … 0.5 -> mid (yellow) … 1.0 -> hot (red) */
private fun heatColor01(x: Float): Color {
    val t = x.coerceIn(0f, 1f)
    // simple 2-stop gradient: yellow<->red; prepend a cool phase from teal to yellow
    return when {
        t < 0.5f -> {
            // 0..0.5 : teal(0,180,180) -> yellow(255,215,0)
            val k = t / 0.5f
            Color(
                (0 + (255 - 0) * k).toInt(),
                (180 + (215 - 180) * k).toInt(),
                (180 + (0 - 180) * k).toInt()
            )
        }
        else -> {
            // 0.5..1.0 : yellow(255,215,0) -> red(255,64,64)
            val k = (t - 0.5f) / 0.5f
            Color(
                255,
                (215 + (64 - 215) * k).toInt(),
                (0 + (64 - 0) * k).toInt()
            )
        }
    }
}


@Composable
private fun SensorRow(name: String, value: String) {
    val mag = parseMag(value)

    // crude per-sensor scaling so the bar feels alive; tweak as desired
    val scale = when {
        name.startsWith("Accelerometer") || name.startsWith("Linear Accel") || name.startsWith("Gravity") -> 20f
        name.startsWith("Gyroscope") -> 5f
        name.startsWith("Magnetic") -> 120f
        name.startsWith("Light") -> 1000f
        name.startsWith("Pressure") -> 1100f
        name.startsWith("Heart Rate") -> 200f
        name.startsWith("Step Counter") -> 20000f
        else -> 50f
    }
    val pct = (mag / scale).coerceIn(0f, 1f)
    val barColor = heatColor01(pct)

    Column(Modifier.fillMaxWidth()) {
        Text(name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        // background track
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF))) {
            // heat bar
            Box(
                Modifier
                    .fillMaxWidth(min(1f, pct))
                    .height(4.dp)
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun Separator() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x33FFFFFF))  // subtle line
    )
}

@Composable
private fun Dashboard(
    available: List<String>,
    readings: Map<String, String>
) {
    val ordered = listOf(
        "Accelerometer", "Linear Accel", "Gravity", "Gyroscope",
        "Rotation Vector", "Magnetic", "Light", "Pressure",
        "Humidity", "Ambient Temp", "Heart Rate", "Step Counter"
    )
    val readingItems = readings.entries.sortedWith(
        compareBy({ ordered.indexOf(it.key).let { i -> if (i == -1) Int.MAX_VALUE else i } }, { it.key })
    )

    ScalingLazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        item { Text("Sensor Dashboard", color = Color.White, fontWeight = FontWeight.Bold) }

        items(readingItems) { (name, value) ->
            SensorRow(name = name, value = value)
            Spacer(Modifier.height(4.dp))
            Separator(color = Color(0x33FFFFFF)) // subtle separator
            Spacer(Modifier.height(4.dp))
        }

        item { Spacer(Modifier.height(10.dp)) }
        item { Text("Available Sensors (${available.size})", color = Color.Gray, fontWeight = FontWeight.SemiBold) }
        items(available.take(30)) { line ->
            Text(line, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
private fun MagnitudeBar(label: String, values: String) {
    val mag = Regex("""\|v\|=([0-9.]+)""").find(values)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    val clamped = mag.coerceIn(0f, 20f)
    val pct = clamped / 20f

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("$label: $values", color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.DarkGray)) {
            Box(Modifier.fillMaxWidth(pct).height(4.dp).background(Color.Gray))
        }
    }
}
