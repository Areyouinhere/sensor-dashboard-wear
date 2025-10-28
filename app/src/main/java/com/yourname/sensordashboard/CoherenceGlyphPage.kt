package com.yourname.sensordashboard


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt


@Composable
fun CoherenceGlyphPage(readings: Map<String, FloatArray>) {
val accel = readings["Accelerometer"] ?: floatArrayOf(0f, 0f, 0f)
val gyro = readings["Gyroscope"] ?: floatArrayOf(0f, 0f, 0f)
val hr = readings["Heart Rate"]?.getOrNull(0) ?: 0f
val press = readings["Pressure"]?.getOrNull(0) ?: 1000f
val lux = readings["Light"]?.getOrNull(0) ?: 0f
val hrv = HRVHistory.rmssd()


val c = remember(accel, gyro, hr, press, lux, hrv) {
CompassModel.composite(
accelMag = magnitude(accel), gyroMag = magnitude(gyro), hr = hr,
press = press, humidity = readings["Humidity"]?.getOrNull(0) ?: 45f, light = lux, hrv = hrv
)
}


val accelMag = magnitude(accel)
val gyroMag = magnitude(gyro)
val nAccel = (accelMag / 6f).coerceIn(0f,1f)
val nGyroInv = (1f - (gyroMag / 4f).coerceIn(0f,1f))
val hrCentered = 1f - abs((hr - ((hr + 65f)/2f)) / 25f).coerceIn(0f,1f)
val envBal = 1f - abs((((press - 980f)/70f).coerceIn(0f,1f)) - 0.5f)*2f
val hrvBand = c.composite // already banded in model; use as primary ring


val intensity = 0.4f + 0.6f*c.confidence


Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
Text("Coherence", fontSize = 18.sp, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
Spacer(Modifier.height(6.dp)); DividerLine(); Spacer(Modifier.height(8.dp))


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
drawArc(Color(0x22,0xFF,0xFF), -90f, 360f, false, tl, sz, Stroke(8f, StrokeCap.Round))
drawArc(glow.copy(alpha=intensity*0.7f), -90f, 360f*pct, false, tl, sz, Stroke(10f, StrokeCap.Round))
drawArc(core.copy(alpha=intensity), -90f, (360f*pct).coerceAtLeast(6f), false, tl, sz, Stroke(5f, StrokeCap.Round))
}
ring(0, hrvBand, Color(0x55,0xFF,0xAA), Color(0xFF,0xCC,0x66))
ring(1, hrCentered, Color(0x66,0x80,0xFF), Color(0xFF,0xE0,0x80))
ring(2, nGyroInv, Color(0x55,0xD0,0xFF), Color(0xAA,0xFF,0xFF))
ring(3, nAccel, Color(0x66,0xFF,0xD7), Color(0xFF,0xE6,0x88))
ring(4, envBal, Color(0x55,0xFF,0x99), Color(0xDD,0xFF,0x99))
val coreR = (baseR - 6f) * (0.35f + 0.65f*c.composite)
drawCircle(Color(0x33,0xFF,0xD7,0x00), radius = coreR*1.15f, center = Offset(cx,cy))
drawCircle(Color(0xFF,0xD7,0x00), radius = coreR*0.75f, center = Offset(cx,cy))
}


Spacer(Modifier.height(6.dp))
Text("HRV ${fmtMs(hrv)} • HR ${hr.roundToInt()} bpm • Coherence ${fmtPct(c.composite)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
}
}
