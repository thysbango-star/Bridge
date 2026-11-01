package com.flowshare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object Flow {
    val Black = Color(0xFF000000)
    val Ink = Color(0xFF07060B)
    val Surface = Color(0xFF111017)
    val SurfaceHi = Color(0xFF1A1822)
    val Stroke = Color(0x1AFFFFFF)
    val StrokeHi = Color(0x33FFFFFF)
    val White = Color(0xFFFFFFFF)
    val Mist = Color(0xFFA7A1B6)
    val Dim = Color(0xFF6E6880)

    val Violet = Color(0xFF8B5CF6)
    val VioletBright = Color(0xFFA78BFA)
    val VioletDeep = Color(0xFF4C1D95)
    val VioletInk = Color(0xFF1C0B3A)
    val Lavender = Color(0xFFDDD2FF)
    val Orchid = Color(0xFFE48CFF)
    /** A small warm note in the iridescent rim, echoing the reference animation's amber edge. */
    val Ember = Color(0xFFFFB98F)
    val Danger = Color(0xFFFF6B8A)
}

private val scheme = darkColorScheme(
    primary = Flow.Violet,
    onPrimary = Flow.White,
    secondary = Flow.Lavender,
    onSecondary = Flow.Black,
    background = Flow.Black,
    onBackground = Flow.White,
    surface = Flow.Surface,
    onSurface = Flow.White,
    surfaceVariant = Flow.SurfaceHi,
    onSurfaceVariant = Flow.Mist,
    outline = Flow.Stroke,
    error = Flow.Danger,
)

private val Sans = FontFamily.SansSerif

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 88.sp, letterSpacing = (-0.04).em),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 56.sp, letterSpacing = (-0.03).em),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.06.em),
)

@Composable
fun FlowShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
