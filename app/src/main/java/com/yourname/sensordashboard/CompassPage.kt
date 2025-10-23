package com.yourname.sensordashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.roundToInt

@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    val hr: Float = readings["Heart Rate"]?.getOrNull(0) ?: 0f
    val hrv: Float = HRVHistory.rmssd()
    val stepsSession: Int = (readings["Step Counter"]?.getOrNull(1) ?: 0f).toInt()

    // readiness + label from the model (uses rolling HR/HRV + ACWR-lite)
    val (readiness, label) = CompassModel.readiness()

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())
    ) {
        Text(
            "Coherence Compass",
            fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(6.dp)); DividerLine(); Spacer(Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0x14,0xFF,0xFF)).padding(12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text("Status", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("$label  (${(readiness*100).roundToInt()}%)", fontSize = 16.sp, color = Color(0xFF,0xD7,0x00))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Guide: Green → Strength/Power • Yellow → Volume/Technique or Flow • Red → Tissue care + early bed",
                    fontSize = 11.sp, color = Color(0xAA,0xFF,0xFF), lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        MetricRow("HR", if (hr>0f) "${hr.roundToInt()} bpm" else "—", readinessHint = hr/100f)
        Spacer(Modifier.height(6.dp))
        MetricRow("HRV", "${hrv.roundToInt()} ms", readinessHint = (hrv/80f).coerceIn(0f,1f))
        Spacer(Modifier.height(6.dp))
        MetricRow("Steps (session)", stepsSession.toString(), readinessHint = (stepsSession/12_000f).coerceIn(0f,1f))

        Spacer(Modifier.height(12.dp)); DividerLine(); Spacer(Modifier.height(8.dp))
        Text("How it learns", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Rolling baselines for HR/HRV update passively; movement adds to a simple load signal (ACWR-lite). " +
            "We’ll fold in sleep density later—no dependency on Samsung Health is required.",
            fontSize = 11.sp, color = Color(0x99,0xFF,0xFF), lineHeight = 14.sp
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String, readinessHint: Float) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0x10,0xFF,0xFF)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(value, fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF))
        }
        Box(Modifier.width(66.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22,0xFF,0xFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(readinessHint.coerceIn(0f,1f))
                .clip(RoundedCornerShape(4.dp)).background(Color(0xFF,0xD7,0x00)))
        }
    }
}
