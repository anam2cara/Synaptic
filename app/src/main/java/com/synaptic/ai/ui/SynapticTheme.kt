package com.synaptic.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SynapticColors {
    val Background = Color(0xFF0F0F12) // --bg
    val Surface1 = Color(0xFF17171D)   // --s1
    val Surface2 = Color(0xFF1F1F28)   // --s2
    val Surface3 = Color(0xFF272733)   // --s3
    val Accent = Color(0xFF7C6AF7)     // --acc
    val Accent2 = Color(0xFF5B4FE0)    // --acc2
    val AccentDim = Color(0x227C6AF7)  // --acc-dim
    val Text1 = Color(0xFFEEEEF4)      // --t1
    val Text2 = Color(0xFF9898AA)      // --t2
    val Text3 = Color(0xFF52526A)      // --t3
    val Danger = Color(0xFFE05A5A)     // --danger
    val Warning = Color(0xFFE09A3A)    // --warn
    val Success = Color(0xFF3AB87A)    // --ok
    val Info = Color(0xFF4AB4E0)       // --info
    val Border = Color(0x10FFFFFF)     // --border
    val Border2 = Color(0x18FFFFFF)    // --border2
}

val SynapticTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )
)

@Composable
fun SynapticTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SynapticColors.Accent,
            background = SynapticColors.Background,
            surface = SynapticColors.Surface1,
            onBackground = SynapticColors.Text1,
            onSurface = SynapticColors.Text1,
            surfaceVariant = SynapticColors.Surface2,
            error = SynapticColors.Danger
        ),
        typography = SynapticTypography,
        content = content
    )
}
