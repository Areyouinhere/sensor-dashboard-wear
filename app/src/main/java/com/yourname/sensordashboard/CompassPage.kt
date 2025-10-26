package com.yourname.sensordashboard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Page 3: Coherence Compass – composite & next-stage guidance + Notes + Tone.
 * - Reuses canonical helpers (magnitude, fmtPct, fmtMs) from MainActivity.kt
 * - No sliders; tiny stepper buttons to avoid extra deps.
 * - Audio tone generator kept minimal and lifecycle-safe.
 */
@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    // --- Inputs (same as before) ---
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f, 0f, 0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0)   ?: 1013f
    val stepsSession = readings["Step Counter"]?.getOrNull(1) ?: 0f
    val hrv   = HRVHistory.rmssd()

    fun soft01(x: Float) = x.coerceIn(0f, 1f)
    fun knee(x: Float, k: Float = 0.6f): Float {
        val t = x.coerceIn(0f, 1f)
        return if (t < k) t / k * 0.7f else 0.7f + (t - k) / (1f - k) * 0.3f
    }

    val accelMag = magnitude(accel)
    val gyroMag  = magnitude(gyro)
    val nAccel   = soft01(accelMag / 6f)
    val nGyro    = soft01(gyroMag  / 4f)
    val hrMid    = 65f
    val hrSpan   = 50f
    val hrBand   = soft01(1f - abs((0.5f + (hr - hrMid)/(2f*hrSpan)) - 0.5f)*2f)
    val envBal   = soft01(1f - abs(((press - 980f)/70f).coerceIn(0f,1f) - 0.5f)*2f)
    val hrvCap   = soft01(hrv / 80f)

    val motionStability = knee(1f - nGyro)
    val movement        = knee(nAccel)
    val recovery        = knee(hrvCap)
    val hrCentered      = knee(hrBand)
    val envCentered     = knee(envBal)

    // Readiness leans slightly recovery-forward
    val readiness = (0.4f*recovery + 0.25f*hrCentered + 0.2f*motionStability + 0.1f*movement + 0.05f*envCentered)
        .coerceIn(0f,1f)
    val readinessLabel = when {
        readiness >= 0.66f -> "GREEN"
        readiness >= 0.40f -> "YELLOW"
        else -> "RED"
    }

    // --- Notes (session-only memory) ---
    var notes by remember { mutableStateOf("") }       // was rememberSaveable

    // --- Simple tone generator state (stepper controls) ---
    var freq by remember { mutableStateOf(174f) }      // was rememberSaveable
    var playing by remember { mutableStateOf(false) }
    val player = remember { SinePlayer() }
    DisposableEffect(playing, freq) {
        player.setFreq(freq)
        if (playing) player.start() else player.stop()
        onDispose { player.stop() } // ensure we stop if Composable leaves
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Readiness Compass",
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(4.dp))
        DividerLine()
        Spacer(Modifier.height(8.dp))

        // Readiness arc (red→yellow→green)
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r  = min(size.width, size.height) * 0.35f

                // track
                drawArc(
                    color = Color(0x22,0xFF,0xFF),
                    startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(cx - r, cy - r), size = Size(r*2, r*2),
                    style = Stroke(width = 12f, cap = StrokeCap.Round)
                )

                fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
                fun mix(c1: Color, c2: Color, t: Float) = Color(
                    lerp(c1.red, c2.red, t), lerp(c1.green, c2.green, t), lerp(c1.blue, c2.blue, t), 1f
                )
                val red = Color(0xFF,0x44,0x44)
                val yel = Color(0xFF,0xD7,0x00)
                val grn = Color(0x44,0xFF,0x88)
                val mid = mix(red, yel, readiness)
                val valColor = mix(mid, grn, readiness)

                drawArc(
                    color = valColor,
                    startAngle = 135f,
                    sweepAngle = max(6f, 270f * readiness),
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r*2, r*2),
                    style = Stroke(width = 14f, cap = StrokeCap.Round)
                )
            }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("${fmtPct(readiness)} • $readinessLabel",
                    fontSize = 14.sp,
                    color = when (readinessLabel) {
                        "GREEN" -> Color(0x88,0xFF,0xAA)
                        "YELLOW"-> Color(0xFF,0xD7,0x00)
                        else    -> Color(0xFF,0x66,0x66)
                    }
                )
                Spacer(Modifier.height(2.dp))
                Text("HRV ${fmtMs(hrv)} • HR ${hr.roundToInt()} bpm", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            }
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        // Sub-signal tiles
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x10,0xFF,0xFF)).padding(10.dp)) {
            Text("Signals", fontSize = 12.sp, color = Color(0xEE,0xFF,0xFF))
            Spacer(Modifier.height(6.dp))
            Text("Recovery (HRV capacity) ${fmtPct(recovery)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(recovery)
            Spacer(Modifier.height(6.dp))
            Text("HR Centering ${fmtPct(hrCentered)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(hrCentered)
            Spacer(Modifier.height(6.dp))
            Text("Motion Stability ${fmtPct(motionStability)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(motionStability)
            Spacer(Modifier.height(6.dp))
            Text("Movement (accel) ${fmtPct(movement)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(movement)
            Spacer(Modifier.height(6.dp))
            Text("Env Balance ${fmtPct(envCentered)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(envCentered)
        }

        Spacer(Modifier.height(10.dp))

        // Steps tile
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x10,0xFF,0xFF)).padding(10.dp)) {
            Text("Today’s Steps", fontSize = 12.sp, color = Color(0xEE,0xFF,0xFF))
            Spacer(Modifier.height(4.dp))
            val norm = (stepsSession / 12_000f).coerceIn(0f,1f)
            Text("${stepsSession.toInt()} session", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            NeonHeatBarNormalized(norm)
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        // Next steps (adaptive)
        val tips = buildList {
            if (recovery < 0.45f) add("Keep it easy: prioritize sleep & light activity.")
            if (hrCentered < 0.5f) add("HR drifting: 5–7 min nasal breathing / easy walk.")
            if (motionStability < 0.55f) add("High jitter: 1–2 sets balance/mobility.")
            if (movement < 0.25f) add("Low movement: 10–15 min walk to prime.")
            if (envCentered < 0.45f) add("Pressure shift: ease into intensity; hydrate.")
        }.ifEmpty { listOf("You’re in a good zone — green light for planned work.") }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x12,0xFF,0xFF)).padding(10.dp)) {
            Text("Next Steps", fontSize = 12.sp, color = Color(0xFF,0xD7,0x00))
            Spacer(Modifier.height(6.dp))
            tips.forEach { line -> Text("• $line", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF)) }
        }

        Spacer(Modifier.height(10.dp))

        // Notes (session quick jot) — disambiguated BasicTextField
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0f,0f,0f,0.80f)) // 80% black bubble
                .padding(10.dp)
        ) {
            Text("Notes (today)", fontSize = 12.sp, color = Color(0xFF,0xD7,0x00))
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0f,0f,0f,0.4f)).padding(8.dp)
            ) {
                BasicTextField(
                    value = notes,
                    onValueChange = { newText -> notes = newText },
                    textStyle = TextStyle(color = Color(0xFF,0xFF,0xFF), fontSize = 12.sp),
                    maxLines = 6,
                    cursorBrush = SolidColor(Color(0xFF,0xD7,0x00)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Simple Tone (frequency stepper + play/stop)
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0f,0f,0f,0.80f))
                .padding(10.dp)
        ) {
            Text("Tone (focus aid)", fontSize = 12.sp, color = Color(0xFF,0xD7,0x00))
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                @Composable
                fun Btn(label: String, onClick: () -> Unit) {  // <- mark as @Composable
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Color(0x22,0xFF,0xFF))
                            .clickable { onClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text(label, fontSize = 11.sp, color = Color(0xEE,0xFF,0xFF)) }
                }
                Row {
                    Btn("−10") { freq = (freq - 10f).coerceIn(50f, 1000f) }
                    Spacer(Modifier.width(6.dp))
                    Btn("−1")  { freq = (freq - 1f).coerceIn(50f, 1000f) }
                    Spacer(Modifier.width(6.dp))
                    Text("${freq.toInt()} Hz", fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF))
                    Spacer(Modifier.width(6.dp))
                    Btn("+1")  { freq = (freq + 1f).coerceIn(50f, 1000f) }
                    Spacer(Modifier.width(6.dp))
                    Btn("+10") { freq = (freq + 10f).coerceIn(50f, 1000f) }
                }
                val playLabel = if (playing) "Stop" else "Play"
                Btn(playLabel) { playing = !playing }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (playing) "Playing sine ${freq.toInt()} Hz" else "Tap Play to start",
                fontSize = 11.sp, color = Color(0x99,0xFF,0xFF)
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

/* ========= Tiny audio helper ========= */
private class SinePlayer {
    @Volatile private var running = false
    private var track: AudioTrack? = null
    @Volatile private var freqHz: Double = 174.0
    private val sampleRate = 44100
    private val buffer = ShortArray(1024)

    fun setFreq(f: Float) { freqHz = f.coerceIn(50f, 1000f).toDouble() }

    fun start() {
        if (running) return
        running = true
        if (track == null) {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .build()
        }
        track?.play()
        // Lightweight producer thread
        Thread {
            var phase = 0.0
            val twoPi = Math.PI * 2
            while (running) {
                val inc = twoPi * freqHz / sampleRate
                var i = 0
                while (i < buffer.size) {
                    val s = kotlin.math.sin(phase)
                    buffer[i] = (s * 32767).toInt().toShort()
                    phase += inc
                    if (phase >= twoPi) phase -= twoPi
                    i++
                }
                track?.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        track?.pause()
        track?.flush()
    }
}
