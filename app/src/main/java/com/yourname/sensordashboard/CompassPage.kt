package com.yourname.sensordashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

/**
 * Temporary placeholder screen so MainActivity's pager compiles.
 * We'll wire this to CompassModel in a later pass.
 */
@Composable
fun CompassPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            "Compass",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF, 0xFF, 0xFF)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x22, 0xFF, 0xFF), RoundedCornerShape(0.dp))
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Coming soon…",
            fontSize = 14.sp,
            color = Color(0xAA, 0xFF, 0xFF)
        )
    }
}
