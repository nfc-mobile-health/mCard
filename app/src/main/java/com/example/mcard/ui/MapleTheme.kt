package com.example.mcard.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MapleColors {
    val Pine = Color(0xFF14503F)
    val PineDeep = Color(0xFF0E3A2D)
    val Sage = Color(0xFFF2F6F1)
    val White = Color(0xFFFFFFFF)
    val Mist = Color(0xFFDCE9DE)
    val Mist2 = Color(0xFFC7DCCB)
    val Line = Color(0xFFD3E0D5)
    val Amber = Color(0xFFF2A33A)
    val AmberTint = Color(0xFFFBE3BC)
    val Red = Color(0xFFB3321F)
    val RedTint = Color(0xFFF6D9D3)
    val Ink = Color(0xFF14231C)
    val Moss = Color(0xFF4B5E55)
}

private val MapleScheme = lightColorScheme(
    primary = MapleColors.Pine,
    onPrimary = MapleColors.White,
    primaryContainer = MapleColors.Mist2,
    onPrimaryContainer = MapleColors.PineDeep,
    surfaceVariant = MapleColors.Mist,
    onSurfaceVariant = MapleColors.Moss,
    tertiary = MapleColors.Amber,
    onTertiary = MapleColors.Ink,
    tertiaryContainer = MapleColors.AmberTint,
    onTertiaryContainer = MapleColors.Ink,
    error = MapleColors.Red,
    onError = MapleColors.White,
    errorContainer = MapleColors.RedTint,
    onErrorContainer = MapleColors.Ink,
    background = MapleColors.Sage,
    onBackground = MapleColors.Ink,
    surface = MapleColors.White,
    onSurface = MapleColors.Ink,
    outline = MapleColors.Line,
)

private val MapleTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 32.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 30.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
)

private val MapleShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MapleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MapleScheme,
        typography = MapleTypography,
        shapes = MapleShapes,
        content = content,
    )
}
