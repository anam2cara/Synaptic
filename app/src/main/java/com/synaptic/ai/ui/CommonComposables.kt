package com.synaptic.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IconBubble(symbol: String, background: Color = SynapticColors.Surface2, foreground: Color = SynapticColors.Text2) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = foreground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AvatarLetter(
    text: String,
    bg: Color = SynapticColors.Surface3,
    fg: Color = SynapticColors.Text1
) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        color = SynapticColors.Text3,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
fun Badge(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("•", color = color, fontSize = 14.sp)
        Text(text, color = color, fontSize = 12.sp)
    }
}
