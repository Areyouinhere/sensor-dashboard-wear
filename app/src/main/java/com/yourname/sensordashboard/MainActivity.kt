package com.yourname.sensordashboard

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.max

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var container: LinearLayout
    private val sensorViews = mutableMapOf<Int, SensorVisualizer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            background = MicrogridBackground(this@MainActivity)
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        scrollView.addView(container)
        setContentView(scrollView)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL)

        for (sensor in sensorList) {
            val vis = SensorVisualizer(this, sensor)
            container.addView(vis)
            sensorViews[sensor.type] = vis
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        sensorViews[event.sensor.type]?.updateValues(event.values)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// === CUSTOM VISUALIZER WIDGET ===
class SensorVisualizer(context: Context, private val sensor: Sensor) : LinearLayout(context) {
    private val title: TextView
    private val value: TextView
    private val bar: NeonBar

    init {
        orientation = VERTICAL
        setPadding(8, 16, 8, 16)

        title = TextView(context).apply {
            text = sensor.name
            setTextColor(Color.CYAN)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }

        value = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        bar = NeonBar(context, sensor)

        addView(title)
        addView(value)
        addView(bar)
    }

    fun updateValues(values: FloatArray) {
        val displayText = values.joinToString(", ") { "%.2f".format(it) }
        value.text = "[$displayText]"
        bar.setValues(values)
    }
}

// === NEON VISUALIZER BAR ===
class NeonBar(context: Context, private val sensor: Sensor) : View(context) {
    private var currentValue = 0f
    private var targetValue = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
        color = Color.CYAN
    }

    private val springInterpolator = AccelerateDecelerateInterpolator()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Smooth interpolation
        currentValue += (targetValue - currentValue) * 0.15f

        val width = width.toFloat()
        val height = height.toFloat()

        val barLength = width * currentValue

        // Neon base bar
        paint.color = Color.CYAN
        canvas.drawRect(0f, height / 4, barLength, 3 * height / 4, paint)

        // Glow effect
        canvas.drawLine(0f, height / 2, barLength, height / 2, glowPaint)

        invalidate()
    }

    fun setValues(values: FloatArray) {
        val mag = sqrt(values.map { it * it }.sum())
        val scaled = when (sensor.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION -> min(mag / 20f, 1f) // more sensitive
            Sensor.TYPE_GYROSCOPE -> min(mag / 10f, 1f)
            Sensor.TYPE_LIGHT -> min(values[0] / 1000f, 1f)
            Sensor.TYPE_PRESSURE -> min((values[0] - 950f) / 100f, 1f) // normalize around atm
            Sensor.TYPE_HEART_RATE -> min(values[0] / 200f, 1f) // bpm scale
            Sensor.TYPE_STEP_COUNTER -> min(values[0] / 20000f, 1f) // filling bar up to ~20k
            else -> min(mag / 50f, 1f)
        }
        targetValue = scaled
    }
}

// === MICROGRID BACKGROUND WITH PARALLAX ===
class MicrogridBackground(context: Context) : View(context) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        val spacing = 20
        for (x in 0 until width.toInt() step spacing) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height, gridPaint)
        }
        for (y in 0 until height.toInt() step spacing) {
            canvas.drawLine(0f, y.toFloat(), width, y.toFloat(), gridPaint)
        }

        invalidate()
    }
}
