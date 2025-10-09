package com.yourname.sensordashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.roundToInt

@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    // Keep a rolling baseline of your HR/HRV so Compass can compute z-scores
    val rolling = remember { Rolling(21) }

    // Ingest the latest snapshot each recomposition (guarded for zeros)
    val day = readingsToDayMetricsCompat(readings)
    LaunchedEffect(day.hrNow, day.hrvNow) {
        if (day.hrNow > 0)  rolling.pushHR(day.hrNow)
        if (day.hrvNow > 0) rolling.pushHRV(day.hrvNow)
    }

    val result = remember(day, rolling.muHR(), rolling.muHRV()) {
        // Convert to the Compass DaySnapshot
        val snap = DaySnapshot(
            hrNow = day.hrNow,
            hrvNow = day.hrvNow,
            stepsToday = day.stepsToday
        )
        Compass.readiness(snap, rolling)
    }

    val stateColor = when (result.state) {
        Compass.State.GREEN  -> Color(0x55, 0xFF, 0x99)
        Compass.State.YELLOW -> Color(0xFF, 0xD7, 0x00)
        Compass.State.RED    -> Color(0xFF, 0x66, 0x66)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Compass",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x22, 0xFF, 0xFF))
        )
        Spacer(Modifier.height(10.dp))

        // Big status
        Text(
            text = when (result.state) {
                Compass.State.GREEN  -> "GREEN — Build / Explore"
                Compass.State.YELLOW -> "YELLOW — Groove / Technique"
                Compass.State.RED    -> "RED — Heal / Restore"
            },
            fontSize = 16.sp,
            color = stateColor
        )

        Spacer(Modifier.height(10.dp))
        // Current vitals row
        Text(
            text = "HR ${day.hrNow} bpm • HRV ${day.hrvNow} ms • Steps ${day.stepsToday}",
            fontSize = 12.sp,
            color = Color(0xCC, 0xFF, 0xFF)
        )

        Spacer(Modifier.height(8.dp))
        result.notes.forEach { line ->
            Text("• $line", fontSize = 12.sp, color = Color(0xAA, 0xFF, 0xFF))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Coming soon: one-tap “Daily Check” + session selector.",
            fontSize = 11.sp,
            color = Color(0x88, 0xFF, 0xFF)
        )
    }
}

/**
 * Small adapter so CompassPage can be dropped in without touching MainActivity code.
 * Uses session steps if present.
 */
private fun readingsToDayMetricsCompat(readings: Map<String, FloatArray>): DayMetrics {
    val hr   = readings["Heart Rate"]?.getOrNull(0)?.roundToInt() ?: 0
    val hrv  = HRVHistory.rmssd().roundToInt()
    val step = readings["Step Counter"]?.let { arr ->
        // Use session steps if we stored them at index 1 (MainActivity does this)
        (arr.getOrNull(1) ?: arr.getOrNull(0) ?: 0f).roundToInt()
    } ?: 0
    return DayMetrics(hrNow = hr, hrvNow = hrv, stepsToday = step)
}
