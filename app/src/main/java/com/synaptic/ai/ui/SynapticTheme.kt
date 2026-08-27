package com.synaptic.ai.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SynapticColors {
    val Background = Color(0xFF0B0B0E) // Deeper Dark
    val Surface1 = Color(0xFF14141A)   // Claude-like surface
    val Surface2 = Color(0xFF1C1C24)
    val Surface3 = Color(0xFF252530)
    val Accent = Color(0xFF8B7AF9)     // Slightly softer violet
    val Accent2 = Color(0xFF6E5DF0)
    val AccentDim = Color(0x1A8B7AF9)
    val Text1 = Color(0xFFF0F0F5)      // Off-white
    val Text2 = Color(0xFFA0A0B8)      // Muted
    val Text3 = Color(0xFF60607A)      // Muted more
    val Danger = Color(0xFFFF5C5C)
    val Warning = Color(0xFFFFB347)
    val Success = Color(0xFF4ADE80)
    val Info = Color(0xFF60A5FA)
    val Border = Color(0x0FFFFFFF)
    val Border2 = Color(0x1AFFFFFF)
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
fun SynapticTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(context)
        }
        else -> darkColorScheme(
            primary = SynapticColors.Accent,
            onPrimary = Color.White,
            primaryContainer = SynapticColors.Accent2,
            onPrimaryContainer = Color.White,
            background = SynapticColors.Background,
            onBackground = SynapticColors.Text1,
            surface = SynapticColors.Surface1,
            onSurface = SynapticColors.Text1,
            surfaceVariant = SynapticColors.Surface2,
            onSurfaceVariant = SynapticColors.Text2,
            outline = SynapticColors.Border,
            error = SynapticColors.Danger,
            onError = Color.White
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SynapticTypography,
        content = content
    )
}
