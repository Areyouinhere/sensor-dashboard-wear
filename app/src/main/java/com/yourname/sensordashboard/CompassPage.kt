package com.yourname.sensordashboard

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    val composite = CompassModel.composite.value
    val grounded  = CompassModel.grounded.value
    val conf      = CompassModel.hrConfidence()

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Coherence Compass", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        // Center dial with composite + grounded halo
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val cx = size.width/2f
            val cy = size.height/2f
            val r = min(size.width, size.height)*0.34f

            // grounded halo
            if (grounded) {
                drawCircle(Color(0x44,0xE0,0xFF), radius = r*1.1f, center = Offset(cx,cy))
            }

            // pulse ring from model’s moment signature
            val pulse = CompassModel.momentPulse.value // 0..1
            val ringR = r * (0.85f + 0.1f*pulse)
            drawCircle(Color(0x22,0xFF,0xFF), radius = ringR+6f, center = Offset(cx,cy))
            drawCircle(Color(0xFF,0xD7,0x00), radius = ringR, center = Offset(cx,cy))
        }

        // Center text (restored)
        Text("Composite ${fmtPct(composite)}", fontSize = 14.sp, color = Color(0xEE,0xFF,0xFF))
        val trendSpark = CompassModel.sparkline() // 0..1 list last few minutes
        if (trendSpark.isNotEmpty()) {
            SparklineRow(trendSpark, label = "Trend (3–5 min)")
        }

        Spacer(Modifier.height(8.dp))
        DividerLine()
        Spacer(Modifier.height(8.dp))

        // Quick readouts (restored)
        val hrv = HRVHistory.rmssd()
        val hr  = readings["Heart Rate"]?.getOrNull(0) ?: 0f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("HRV ${fmtMs(hrv)}", fontSize = 12.sp)
            Text("HR ${hr.toInt()} bpm", fontSize = 12.sp)
            Text("Conf ${fmtPct(conf)}", fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        // Notes (restored)
        var notes by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Color(0x14,0xFF,0xFF)).padding(8.dp)
        ) {
            Text("Notes", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = notes,
                onValueChange = { notes = it },
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Tone Generator (restored lightweight)
        ToneBox()
    }
}

@Composable
private fun SparklineRow(points01: List<Float>, label: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0x10,0xFF,0xFF)).padding(8.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            if (points01.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val step = w / (points01.size - 1)
            var prev = Offset(0f, h*(1f - points01.first().coerceIn(0f,1f)))
            for (i in 1 until points01.size) {
                val x = step * i
                val y = h*(1f - points01[i].coerceIn(0f,1f))
                drawLine(Color(0xFF,0xD7,0x00), prev, Offset(x,y), 2f)
                prev = Offset(x,y)
            }
        }
    }
}

@Composable
private fun ToneBox() {
    var on by remember { mutableStateOf(false) }
    var hz by remember { mutableStateOf(180f) }
    val tg = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    DisposableEffect(Unit) {
        onDispose { runCatching { tg.release() } }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0x10,0xFF,0xFF)).padding(8.dp)
    ) {
        Text("Tone", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (on) "On" else "Off", fontSize = 12.sp)
            Text("${hz.toInt()} Hz", fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0x18,0xFF,0xFF))
                .pointerInput(on, hz) {
                    detectTapGestures(
                        onTap = {
                            on = !on
                            if (on) tg.startTone(ToneGenerator.TONE_DTMF_5, 150)
                            else tg.stopTone()
                        },
                        onPress = {
                            // press/hold gently nudges frequency for fun
                            val start = hz
                            try {
                                while (currentEvent?.pressed == true && isActive) {
                                    hz = (start + ((System.currentTimeMillis() % 1000) / 1000f - 0.5f)*40f)
                                        .coerceIn(120f, 600f)
                                    delay(60)
                                }
                            } finally {}
                        }
                    )
                }
        )
    }
}
