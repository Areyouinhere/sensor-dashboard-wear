package com.yourname.sensordashboard

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text

object AppSettings {
    var showCenterGlow by mutableStateOf(true)
    var enableAura by mutableStateOf(true)
    var enableTone by mutableStateOf(false)
    var fontScale by mutableStateOf(1f)
}

@Composable
fun SettingsPage() {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Settings", color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp)); DividerLine(); Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Center Glow", color = Color(0xCC,0xFF,0xFF), fontSize = 12.sp)
            Switch(checked = AppSettings.showCenterGlow, onCheckedChange = { AppSettings.showCenterGlow = it })
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Micro-Weather Aura", color = Color(0xCC,0xFF,0xFF), fontSize = 12.sp)
            Switch(checked = AppSettings.enableAura, onCheckedChange = { AppSettings.enableAura = it })
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tone Hook (future)", color = Color(0xCC,0xFF,0xFF), fontSize = 12.sp)
            Switch(checked = AppSettings.enableTone, onCheckedChange = { AppSettings.enableTone = it })
        }
    }
}
