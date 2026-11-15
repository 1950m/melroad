package com.studio1950.melroad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 현대자동차 CI 색 기반 팔레트(Hyundai Blue / Active Blue / Sky Blue / 흰색 / 회색).
 * 저시력 사용자를 위해 글자는 흰 바탕 위 짙은 남색(대비 13:1 이상)으로만 쓰고,
 * Active Blue·Sky Blue는 글자가 아닌 장식·배지 배경에만 쓴다.
 */
val HyundaiBlue = Color(0xFF002C5F)
val HyundaiBlueLight = Color(0xFF0B4A8F)
val ActiveBlue = Color(0xFF00AAD2)
val SkyBlue = Color(0xFFAACAE6)
val ActiveRed = Color(0xFFE63312)
val LightSand = Color(0xFFF6F3F2)
val Ink = Color(0xFF0B1B2E)
val Subtle = Color(0xFF3A4553)
val Gray100 = Color(0xFFEEF1F5)
val Gray300 = Color(0xFFC9D1DB)
val Gray500 = Color(0xFF8A94A3)

val AppBackground = Brush.verticalGradient(listOf(Color(0xFFFAFBFD), Color(0xFFE6EBF2)))

private val colors = lightColorScheme(
    primary = HyundaiBlue,
    onPrimary = Color.White,
    secondary = ActiveBlue,
    onSecondary = Ink,
    background = Color(0xFFF3F5F8),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Gray100,
    onSurfaceVariant = Subtle,
    error = Color(0xFFB3261E),
    onError = Color.White,
    outline = Gray300,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 46.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold, color = HyundaiBlue, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, color = HyundaiBlue, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, color = Ink),
    bodyMedium = TextStyle(fontSize = 21.sp, lineHeight = 30.sp, color = Subtle),
    labelLarge = TextStyle(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun MelroadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
