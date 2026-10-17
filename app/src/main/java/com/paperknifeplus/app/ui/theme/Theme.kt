package com.paperknifeplus.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat

/** The colour palettes offered in Settings. [storageName] is the stable preference value; never change it. */
enum class AppPalette(val storageName: String, val label: String, val light: ScanPalette, val oled: ScanPalette) {
    STILL_WATER("still_water", "Still Water", StillWaterLight, StillWaterOled),
    ROSE("rose", "Rose", RoseLight, RoseOled),
    GRAPHITE_SCHOLASTIC("graphite_scholastic", "Graphite Scholastic", GraphiteLight, GraphiteOled);

    /** The OLED tokens when [oled], else the Light ones. */
    fun tokens(oled: Boolean): ScanPalette = if (oled) this.oled else light

    companion object {
        val DEFAULT = STILL_WATER

        /** The palette stored as [name]; [DEFAULT] for null or an unknown name. */
        fun fromStorageName(name: String?): AppPalette = entries.firstOrNull { it.storageName == name } ?: DEFAULT
    }
}

/** The theme setting; [stored] is its preference value. The dark theme is always OLED. */
enum class ThemeMode(val stored: Int) {
    SYSTEM(0), LIGHT(1), OLED(2);

    /** Whether this mode shows OLED, given whether the system is in dark mode. */
    fun isOled(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        OLED -> true
    }

    companion object {
        /** The mode stored as [value]; System for an unknown value. */
        fun fromStored(value: Int): ThemeMode = entries.firstOrNull { it.stored == value } ?: SYSTEM
    }
}

/**
 * These tokens as a Material3 scheme. Every slot is set, so no baseline colour leaks in, and light versus dark needs no separate builder:
 * - primary, secondary, tertiary = acf with onAcf; their containers and fixed colours = acSoft with ac; surfaceTint = acf
 * - background = bg, surface = sf, surfaceVariant = sf2; every on-background/surface colour = tx, onSurfaceVariant = mu
 * - surfaceBright and surfaceContainerLowest, Low, (default) and High = sf; surfaceDim and surfaceContainerHighest = sf2
 * - outline and outlineVariant = bd; error = danger with bg; errorContainer = warnBg with warnFg
 * - inverseSurface = selBg, inverseOnSurface and inversePrimary = selFg; scrim = camera.cam
 */
fun ScanPalette.toColorScheme(): ColorScheme = lightColorScheme(
    primary = acf, onPrimary = onAcf, primaryContainer = acSoft, onPrimaryContainer = ac, inversePrimary = selFg,
    secondary = acf, onSecondary = onAcf, secondaryContainer = acSoft, onSecondaryContainer = ac,
    tertiary = acf, onTertiary = onAcf, tertiaryContainer = acSoft, onTertiaryContainer = ac,
    background = bg, onBackground = tx, surface = sf, onSurface = tx, surfaceVariant = sf2, onSurfaceVariant = mu, surfaceTint = acf,
    inverseSurface = selBg, inverseOnSurface = selFg, error = danger, onError = bg, errorContainer = warnBg, onErrorContainer = warnFg,
    outline = bd, outlineVariant = bd, scrim = camera.cam, surfaceBright = sf, surfaceContainer = sf, surfaceContainerHigh = sf,
    surfaceContainerHighest = sf2, surfaceContainerLow = sf, surfaceContainerLowest = sf, surfaceDim = sf2,
    primaryFixed = acSoft, primaryFixedDim = acSoft, onPrimaryFixed = ac, onPrimaryFixedVariant = ac,
    secondaryFixed = acSoft, secondaryFixedDim = acSoft, onSecondaryFixed = ac, onSecondaryFixedVariant = ac,
    tertiaryFixed = acSoft, tertiaryFixedDim = acSoft, onTertiaryFixed = ac, onTertiaryFixedVariant = ac
)

/** Whether [PaperKnifePlusTheme] is dark (OLED); false outside it. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** The active role tokens, including the roles Material3 has no slot for; the default palette's Light tokens outside the theme. */
val LocalScanPalette = staticCompositionLocalOf { AppPalette.DEFAULT.light }

/**
 * The app theme: [palette] in Light or OLED, as [themeMode] and the system setting choose. The window background follows bg at once, so a
 * palette or theme change in Settings needs no recreation; the bars are edge to edge (enableEdgeToEdge), with icons for the theme.
 */
@Composable
fun PaperKnifePlusTheme(
    palette: AppPalette = AppPalette.DEFAULT,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val oled = themeMode.isOled(isSystemInDarkTheme())
    val tokens = palette.tokens(oled)
    val colorScheme = remember(tokens) { tokens.toColorScheme() }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.setBackgroundDrawable(tokens.bg.toArgb().toDrawable())
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !oled
            controller.isAppearanceLightNavigationBars = !oled
            // Below API 29 enableEdgeToEdge leaves a scrim chosen by the system's night mode at launch, which can hide icons drawn for the app's
            // theme; there the bar takes the theme's surface instead.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) window.navigationBarColor = tokens.sf.toArgb()
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides oled, LocalScanPalette provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
