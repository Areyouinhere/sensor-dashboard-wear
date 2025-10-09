package com.yourname.sensordashboard

import kotlin.math.max
import kotlin.math.sqrt

data class DaySnapshot(
    val hrNow: Int = 0,
    val hrvNow: Int = 0,      // RMSSD, ms
    val stepsToday: Int = 0
)

class Rolling(val max: Int = 21) {
    private val hr = ArrayDeque<Int>()
    private val hrv = ArrayDeque<Int>()

    fun pushHR(v: Int) { hr += v; while (hr.size > max) hr.removeFirst() }
    fun pushHRV(v: Int) { hrv += v; while (hrv.size > max) hrv.removeFirst() }

    fun muHR(): Double = if (hr.isEmpty()) 0.0 else hr.map { it.toDouble() }.average()
    fun sdHR(): Double = stdev(hr.map { it.toDouble() })
    fun muHRV(): Double = if (hrv.isEmpty()) 0.0 else hrv.map { it.toDouble() }.average()
    fun sdHRV(): Double = stdev(hrv.map { it.toDouble() })

    private fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        val v = xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
        return sqrt(v)
    }
}

object Compass {
    data class Result(val state: State, val notes: List<String>)
    enum class State { GREEN, YELLOW, RED }

    fun readiness(day: DaySnapshot, roll: Rolling): Result {
        val muHr = roll.muHR(); val sdHr = max(1e-6, roll.sdHR())
        val muHrv = roll.muHRV(); val sdHrv = max(1e-6, roll.sdHRV())

        val zHr = ((day.hrNow - muHr) / sdHr).toFloat()
        val zHrv = ((day.hrvNow - muHrv) / sdHrv).toFloat()

        var pts = 0
        val notes = mutableListOf<String>()

        if (day.hrvNow >= 0.8 * muHrv && zHrv > -0.5f) { pts++; notes += "HRV holding" }
        else notes += "HRV downshift"

        if (zHr < 0.5f) { pts++; notes += "HR centered" }
        else notes += "HR elevated"

        when {
            day.stepsToday < 4000 -> notes += "Low movement → recovery bias"
            day.stepsToday > 14000 -> notes += "High load → groove/technique"
            else -> notes += "Movement in sweet spot"
        }

        val state = when {
            pts >= 2 -> State.GREEN
            pts == 1 -> State.YELLOW
            else -> State.RED
        }
        return Result(state, notes)
    }
}

package com.yourname.sensordashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import kotlin.math.roundToInt

@Composable
fun CompassPage(readings: Map<String, FloatArray>) {
    // Live signals from your existing map
    val hr = readings["Heart Rate"]?.getOrNull(0)?.roundToInt() ?: 0
    val hrv = HRVHistory.rmssd().roundToInt().coerceAtLeast(0)
    val steps = readings["Step Counter"]?.getOrNull(1)?.roundToInt() ?: 0 // session delta

    // Rolling baselines (in-memory)
    val rolling = remember { Rolling(max = 21) }
    LaunchedEffect(hr) { if (hr > 0) rolling.pushHR(hr) }
    LaunchedEffect(hrv) { if (hrv > 0) rolling.pushHRV(hrv) }

    var result by remember { mutableStateOf<Compass.Result?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Resonance Compass",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        DividerLine()

        // Live tiles
        DataTile("HR", if (hr == 0) "—" else "$hr bpm")
        DataTile("HRV", if (hrv == 0) "—" else "$hrv ms")
        DataTile("Steps", "$steps")

        Spacer(Modifier.height(8.dp))

        Button(onClick = {
            val day = DaySnapshot(hrNow = hr, hrvNow = hrv, stepsToday = steps)
            result = Compass.readiness(day, rolling)
        }) {
            Text("Compute Readiness", fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        result?.let { r ->
            val (bg, fg) = when (r.state) {
                Compass.State.GREEN -> Color(0x20,0xB2,0x22) to Color(0x98,0xFB,0x98)
                Compass.State.YELLOW -> Color(0x44,0x44,0x00) to Color(0xFF,0xEE,0x99)
                Compass.State.RED -> Color(0x33,0x00,0x00) to Color(0xFF,0xA0,0xA0)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .padding(10.dp)
            ) {
                Column {
                    Text("Result: ${r.state}", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    r.notes.forEach { n ->
                        Text("• $n", color = fg.copy(alpha = 0.9f), fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: run this a few times/day; baselines learn you over ~21 days.",
            fontSize = 10.sp,
            color = Color(0xAA,0xFF,0xFF)
        )
    }
}

@Composable
private fun DataTile(label: String, value: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x10, 0xFF, 0xFF))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = Color(0xCC,0xFF,0xFF))
            Text(value, fontSize = 12.sp, color = Color(0xFF,0xD7,0x00), fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x22, 0xFF, 0xFF))
    )
}
