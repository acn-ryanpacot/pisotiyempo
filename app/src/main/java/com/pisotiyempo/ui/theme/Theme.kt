package com.pisotiyempo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0B2545)
val Gold = Color(0xFFE0A526)
val Gain = Color(0xFF1B998B)
val Loss = Color(0xFFE84855)
val GainDark = Color(0xFF4FD1C0)
val LossDark = Color(0xFFFF7B88)

@Immutable
data class MarketColors(val gain: Color, val loss: Color, val neutral: Color)

val LocalMarketColors = staticCompositionLocalOf { MarketColors(Gain, Loss, Color.Gray) }

private val LightScheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F5),
    onPrimaryContainer = Navy,
    secondary = Gold,
    onSecondary = Navy,
    secondaryContainer = Color(0xFFFCEBC4),
    onSecondaryContainer = Color(0xFF3D2C00),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF1F5),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9EC1F0),
    onPrimary = Navy,
    primaryContainer = Color(0xFF1C3A63),
    onPrimaryContainer = Color(0xFFD6E3F5),
    secondary = Gold,
    onSecondary = Navy,
    secondaryContainer = Color(0xFF5A4300),
    onSecondaryContainer = Color(0xFFFCEBC4),
    background = Color(0xFF0E1420),
    surface = Color(0xFF151C2A),
    surfaceVariant = Color(0xFF222B3B),
)

@Composable
fun PisoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val market = if (dark) MarketColors(GainDark, LossDark, Color(0xFF9AA5B5)) else MarketColors(Gain, Loss, Color(0xFF6B7585))
    androidx.compose.runtime.CompositionLocalProvider(LocalMarketColors provides market) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
