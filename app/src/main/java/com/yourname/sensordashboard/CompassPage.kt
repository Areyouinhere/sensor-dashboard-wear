package com.yourname.sensordashboard
}
}


@Composable fun Sparkline(values: List<Float>, confidence: List<Float>, heightDp: Int = 36) {
Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
if (values.size < 2) return@Canvas
val w = size.width; val h = size.height
val step = w / (values.size - 1)
val base = Color(0x22,0xFF,0xFF)
// grid
drawLine(base, Offset(0f, h*0.5f), Offset(w, h*0.5f), 1f)
var prev = Offset(0f, h * (1f - values[0]))
for (i in 1 until values.size) {
val y = h * (1f - values[i])
val x = step * i
val conf = confidence.getOrNull(i) ?: 0.5f
val col = Color(1f, 0.84f, 0f, alpha = 0.3f + 0.6f*conf)
drawLine(col, prev, Offset(x,y), 3f)
prev = Offset(x,y)
}
}
}
