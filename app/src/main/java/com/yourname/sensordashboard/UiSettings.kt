package com.yourname.sensordashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** App-wide, lightweight UI settings (state in memory only). */
object UiSettings {
    // Colors
    var gridColor by mutableStateOf(Color(0x13, 0xFF, 0xFF))          // subtle cyan lines
    var accentColor by mutableStateOf(Color(0xFF, 0xD7, 0x00))        // gold
    var glowColor by mutableStateOf(Color(0x66, 0x00, 0xEA))          // violet
    var bubbleBgColor by mutableStateOf(Color(0f, 0f, 0f, 0.80f))     // black @ 80% opacity

    // Grid behavior
    var gridSpeed by mutableStateOf(0.6f)   // px per tick
    var gridSpacing by mutableStateOf(20f)  // px between lines
    var isometric by mutableStateOf(false)  // diagonal iso grid
}
