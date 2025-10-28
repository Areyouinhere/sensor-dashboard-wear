package com.yourname.sensordashboard
Spacer(Modifier.height(4.dp))
Text("Font Scale: ${"%.2f".format(fontScale)}x")
Spacer(Modifier.height(4.dp))
Text("Tone Preset: ${tonePreset.toInt()} Hz")
Spacer(Modifier.height(8.dp))
Text("(Settings are placeholders; hook into a real store when persisting)", fontSize = 10.sp, color = Color.Gray)
}
}


@Composable fun PagerDots(current: Int, total: Int) {
Row(
Modifier.fillMaxWidth().padding(bottom = 6.dp),
horizontalArrangement = Arrangement.Center
) {
repeat(total) { i ->
val active = current == i
Box(Modifier
.size(if (active) 8.dp else 6.dp)
.clip(RoundedCornerShape(50))
.background(if (active) Color(0xFF, 0xD7, 0x00) else Color(0x44, 0xFF, 0xFF)))
if (i != total-1) Spacer(Modifier.width(6.dp))
}
}
}


/* =============================================
* Coherence history (swipe-up micro-trend)
* ============================================= */
object CoherenceHistory {
private const val CAP = 180 // ~few minutes at ~1s pushes
private val series = FloatArray(CAP) { 0f }
private val conf = FloatArray(CAP) { 0f }
private var idx = 0
private var filled = 0


fun push(value: Float, confidence: Float) {
series[idx] = value.coerceIn(0f,1f)
conf[idx] = confidence.coerceIn(0f,1f)
idx = (idx + 1) % CAP
if (filled < CAP) filled++
}
fun snapshot(): Pair<List<Float>, List<Float>> {
val n = filled
val out = ArrayList<Float>(n)
val outC = ArrayList<Float>(n)
for (i in 0 until n) {
val j = (idx - n + i + CAP) % CAP
out.add(series[j])
outC.add(conf[j])
}
return out to outC
}
}
