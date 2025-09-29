package com.yourname.sensordashboard

import androidx.wear.compose.material.AutoCenteringParams
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*

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

        setContent { MaterialTheme { Dashboard(availableSensors, sensorValues) } }

        availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .map { "${it.name} (type ${it.type})" }.sorted()

        ensurePermissionsThenSubscribe()
    }

    private fun ensurePermissionsThenSubscribe() {
        val needsBody = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED
        val needsAct = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        if (needsBody || needsAct) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION
            ))
        } else subscribeSensors()
    }

    private fun subscribeSensors() {
        sensorManager.unregisterListener(this)
        fun reg(type: Int, delay: Int = SensorManager.SENSOR_DELAY_NORMAL) {
            sensorManager.getDefaultSensor(type)?.let { sensorManager.registerListener(this, it, delay) }
        }
        reg(Sensor.TYPE_ACCELEROMETER)
        reg(Sensor.TYPE_GYROSCOPE)
        reg(Sensor.TYPE_STEP_COUNTER)
        reg(Sensor.TYPE_HEART_RATE)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onSensorChanged(event: SensorEvent) {
        val key = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer (m/s²)"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope (rad/s)"
            Sensor.TYPE_STEP_COUNTER -> "Step Counter (steps)"
            Sensor.TYPE_HEART_RATE -> "Heart Rate (bpm)"
            else -> "Type ${event.sensor.type}"
        }
        val value = event.values.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
        sensorValues[key] = value
    }

    override fun onDestroy() { super.onDestroy(); sensorManager.unregisterListener(this) }
}

@Composable
private fun Dashboard(available: List<String>, readings: Map<String, String>) {
    val readingItems = readings.entries.sortedBy { it.key }
    ScalingLazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        item { Text("Sensor Dashboard", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        items(readingItems) { (name, value) ->
            Spacer(Modifier.height(6.dp)); Text("$name: $value", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { Text("Available Sensors (${available.size})") }
        items(available.take(30)) { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
