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
 * Page 3: Coherence Compass – readiness + guidance + notes + tone generator.
 * - No local helper duplicates; uses MainActivity.kt's magnitude/fmtPct/fmtMs.
 * - Simple tone generator (Start/Stop, +/-10 Hz, presets).
 */
@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
    val gyro  = readings["Gyroscope"]     ?: floatArrayOf(0f, 0f, 0f)
    val hr    = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val press = readings["Pressure"]?.getOrNull(0)   ?: 1013f
    val stepsSession = readings["Step Counter"]?.getOrNull(1) ?: 0f
    val hrv   = HRVHistory.rmssd()

    // --- normalize + scores ---
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
    val envBal   = soft01(1f - abs(((press - 1013f)/50f).coerceIn(-1f,1f)))
    val hrvCap   = soft01(hrv / 80f)

    val motionStability = knee(1f - nGyro)
    val movement        = knee(nAccel)
    val recovery        = knee(hrvCap)
    val hrCentered      = knee(hrBand)
    val envCentered     = knee(envBal)

    val readiness = (0.4f*recovery + 0.25f*hrCentered + 0.2f*motionStability + 0.1f*movement + 0.05f*envCentered)
        .coerceIn(0f,1f)
    val readinessLabel = when {
        readiness >= 0.66f -> "GREEN"
        readiness >= 0.40f -> "YELLOW"
        else -> "RED"
    }

    // Notes (session-only memory)
    val noteState = remember { mutableStateOf("") }

    // Tone player state (simple sine)
    val tonePlayer = remember { TonePlayer() }
    var freq by remember { mutableStateOf(432f) }
    var playing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Readiness Compass",
            fontSize = 18.sp,
            color = UiSettings.accentColor,
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
                fun mix(c1: androidx.compose.ui.graphics.Color, c2: androidx.compose.ui.graphics.Color, t: Float) =
                    androidx.compose.ui.graphics.Color(
                        lerp(c1.red, c2.red, t),
                        lerp(c1.green, c2.green, t),
                        lerp(c1.blue, c2.blue, t),
                        1f
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

        // --- Signals tile ---
        InfoTile(title = "Signals") {
            LabeledBar("Recovery (HRV)", recovery)
            LabeledBar("HR Centering",   hrCentered)
            LabeledBar("Motion Stability", motionStability)
            LabeledBar("Movement",       movement)
            LabeledBar("Env Balance",    envCentered)
        }

        Spacer(Modifier.height(10.dp))

        // --- Steps tile ---
        InfoTile(title = "Today’s Steps") {
            val norm = (stepsSession / 12_000f).coerceIn(0f,1f)
            Text("${stepsSession.toInt()} session", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            SimpleBar(norm)
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        // --- Notes tile ---
        NotesCard(state = noteState)

        Spacer(Modifier.height(10.dp))

        // --- Frequency generator tile ---
        InfoTile(title = "Frequency Generator") {
            Text("Sine tone • ${freq.roundToInt()} Hz", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionChip("−10 Hz") {
                    freq = (freq - 10f).coerceIn(100f, 1000f)
                    if (playing) tonePlayer.setFrequency(freq)
                }
                ActionChip(if (playing) "Stop" else "Start") {
                    if (playing) { tonePlayer.stop(); playing = false }
                    else { tonePlayer.start(freq); playing = true }
                }
                ActionChip("+10 Hz") {
                    freq = (freq + 10f).coerceIn(100f, 1000f)
                    if (playing) tonePlayer.setFrequency(freq)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionChip("100 Hz") { freq = 100f; if (playing) tonePlayer.setFrequency(freq) }
                ActionChip("432 Hz") { freq = 432f; if (playing) tonePlayer.setFrequency(freq) }
                ActionChip("528 Hz") { freq = 528f; if (playing) tonePlayer.setFrequency(freq) }
                ActionChip("1000 Hz"){ freq = 1000f; if (playing) tonePlayer.setFrequency(freq) }
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Next steps tile ---
        val tipsBase = buildList {
            if (recovery < 0.45f) add("Keep it easy: prioritize sleep & light activity.")
            if (hrCentered < 0.5f) add("HR drifting: 5–7 min nasal breathing / easy walk.")
            if (motionStability < 0.55f) add("High jitter: 1–2 sets balance/mobility.")
            if (movement < 0.25f) add("Low movement: take a 10–15 min walk to prime.")
            if (envCentered < 0.45f) add("Pressure shift: ease into intensity; hydrate.")
        }
        val tips = if (tipsBase.isEmpty()) listOf("You’re in a good zone — green light for planned work.") else tipsBase

        InfoTile(title = "Next Steps") {
            tips.forEach { line -> Text("• $line", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF)) }
        }

        Spacer(Modifier.height(12.dp))
    }

    DisposableEffect(Unit) { onDispose { tonePlayer.stop() } }
}

/* ---------- small UI bits local to this page ---------- */

@Composable
private fun InfoTile(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(UiSettings.bubbleBgColor).padding(10.dp)
    ) {
        Text(title, fontSize = 12.sp, color = UiSettings.accentColor)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun LabeledBar(label: String, score: Float) {
    Column {
        Text("$label  ${fmtPct(score)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
        SimpleBar(score.coerceIn(0f,1f))
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SimpleBar(normIn: Float) {
    val norm = normIn.coerceIn(0f,1f)
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x33,0xFF,0xFF))) {
        Box(Modifier.fillMaxWidth(norm).fillMaxHeight().background(UiSettings.glowColor.copy(alpha=0.6f)))
        Box(
            Modifier.fillMaxWidth((norm * 0.98f).coerceAtLeast(0.02f)).height(6.dp).padding(vertical = 2.dp)
                .clip(RoundedCornerShape(3.dp)).background(UiSettings.accentColor)
        )
    }
}

@Composable
private fun NotesCard(state: MutableState<String>) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(UiSettings.bubbleBgColor)
            .padding(10.dp)
    ) {
        Text("Notes", fontSize = 12.sp, color = UiSettings.accentColor)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x11,0xFF,0xFF))
                .padding(8.dp)
        ) {
            if (state.value.isEmpty()) {
                Text("Add a quick note…", fontSize = 11.sp, color = Color(0x77,0xFF,0xFF))
            }
            BasicTextField(
                value = state.value,
                onValueChange = { state.value = it },
                textStyle = TextStyle(color = Color(0xEE,0xFF,0xFF), fontSize = 12.sp),
                cursorBrush = SolidColor(UiSettings.accentColor),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(UiSettings.bubbleBgColor)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xEE,0xFF,0xFF))
    }
}

/* ---------- tiny tone player (looped static buffer) ---------- */

private class TonePlayer {
    private var track: AudioTrack? = null

    fun start(freq: Float) {
        stop()
        track = buildSineTrack(freq)
        track?.play()
    }

    fun setFrequency(freq: Float) {
        if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) start(freq)
    }

    fun stop() {
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    private fun buildSineTrack(freq: Float): AudioTrack {
        val sampleRate = 22050
        val durationSec = 0.25f
        val samples = (sampleRate * durationSec).toInt().coerceAtLeast(256)
        val buf = ShortArray(samples)
        val twoPiOverFs = (2.0 * PI / sampleRate).toFloat()
        for (i in 0 until samples) {
            val s = sin(twoPiOverFs * freq * i).toFloat()
            buf[i] = (s * 32767f).toInt().toShort()
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(buf.size * 2)
            .build().apply {
                write(buf, 0, buf.size)
                setLoopPoints(0, buf.size, -1)
            }
    }
}
