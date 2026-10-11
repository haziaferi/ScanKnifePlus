package com.paperknifeplus.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PaperPink,
    secondary = PaperBlue,
    tertiary = PaperAccent,
    background = Color.Black,
    surface = PaperSurfaceDark,
    onBackground = PaperTextDark,
    onSurface = PaperTextDark
)

private val LightColorScheme = lightColorScheme(
    primary = PaperPink,
    secondary = PaperBlue,
    tertiary = PaperAccent,
    background = PaperSurfaceLight,
    surface = Color.White,
    onBackground = PaperTextLight,
    onSurface = PaperTextLight
)

/** Whether [PaperKnifePlusTheme] is dark; false outside it. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun PaperKnifePlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
