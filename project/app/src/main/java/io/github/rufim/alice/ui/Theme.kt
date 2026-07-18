package io.github.rufim.alice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра приложения (исторически — цвета View-оверлеев поверх игры).
object AliceColors {
    val Background = Color(0xFF181B26)      // rgb(24,27,38)
    val Surface = Color(0xFF1E2230)         // rgb(30,34,48)
    val Accent = Color(0xFF4CC878)          // rgb(76,200,120) — зелёный
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFF96A0B4)   // rgb(150,160,180)
    val ValueGreen = Color(0xFF96C8A0)      // rgb(150,200,160) — значения читов
    val Scrim = Color(0x3C000000)
    val Indicator = Color(0xFFFFEB3B)       // жёлтый индикатор «стр·окно»
}

private val DarkScheme = darkColorScheme(
    primary = AliceColors.Accent,
    onPrimary = Color.Black,
    secondary = AliceColors.TextSecondary,
    background = AliceColors.Background,
    onBackground = AliceColors.TextPrimary,
    surface = AliceColors.Surface,
    onSurface = AliceColors.TextPrimary,
    surfaceVariant = AliceColors.Surface,
    onSurfaceVariant = AliceColors.TextSecondary,
)

/** Единая (тёмная) тема всего приложения. */
@Composable
fun AliceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
