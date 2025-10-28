package com.yourname.sensordashboard
lerp(c1.red, c2.red, t), lerp(c1.green, c2.green, t), lerp(c1.blue, c2.blue, t), 1f
)
val red = Color(0xFF,0x44,0x44)
val yel = Color(0xFF,0xD7,0x00)
val grn = Color(0x44,0xFF,0x88)
val mid = mix(red, yel, comp.composite)
val valColor = mix(mid, grn, comp.composite)
val satAlpha = 0.4f + 0.6f*comp.confidence // confidence gates intensity


drawArc(
color = valColor.copy(alpha = satAlpha),
startAngle = 135f,
sweepAngle = max(6f, 270f * comp.composite),
useCenter = false,
topLeft = Offset(cx - r, cy - r),
size = Size(r*2, r*2),
style = Stroke(width = 14f, cap = StrokeCap.Round)
)
}
Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
val pct = fmtPct(comp.composite)
val label = when {
comp.composite >= 0.66f -> "GREEN"
comp.composite >= 0.40f -> "YELLOW"
else -> "RED"
}
Text("$pct • $label", fontSize = 14.sp,
color = when(label){
"GREEN"-> Color(0x88,0xFF,0xAA)
"YELLOW"-> Color(0xFF,0xD7,0x00)
else-> Color(0xFF,0x66,0x66)
}
)
Spacer(Modifier.height(2.dp))
Text("Confidence ${fmtPct(comp.confidence)}", fontSize = 11.sp, color = Color(0xCC,0xFF,0xFF))
}
}


Spacer(Modifier.height(8.dp))
if (showHistory) {
val (s, c) = remember { CoherenceHistory.snapshot() }
Text("Recent Micro‑Trend", fontSize = 12.sp, color = Color(0xEE,0xFF,0xFF))
Sparkline(values = s, confidence = c, heightDp = 42)
Spacer(Modifier.height(8.dp))
}


DividerLine(); Spacer(Modifier.height(8.dp))


// Notes
Column(
Modifier.fillMaxWidth()
.clip(RoundedCornerShape(10.dp))
.background(Color(0f,0f,0f,0.80f))
.padding(10.dp)
) {
Text("Notes (today)", fontSize = 12.sp, color = Color(0xFF,0xD7,0x00))
Spacer(Modifier.height(6.dp))
BasicTextField(
value = notes,
onValueChange = { notes = it },
textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
maxLines = 6,
cursorBrush = SolidColor(Color(0xFF,0xD7,0x00)),
modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
)
}
Spacer(Modifier.height(10.dp))
Text("Swipe up/down to toggle trend", fontSize = 10.sp, color = Color.Gray,
modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
}
}
