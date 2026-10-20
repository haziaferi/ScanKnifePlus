package com.paperknifeplus.app.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.components.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ThemePreferencesTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("pk_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `the default palette is Still Water`() {
        assertEquals(AppPalette.STILL_WATER, AppPalette.DEFAULT)
        assertEquals(AppPalette.STILL_WATER, PreferencesManager.getPalette(context))
    }

    @Test
    fun `the palette round-trips by its stable name`() {
        for (palette in AppPalette.entries) {
            PreferencesManager.setPalette(context, palette)
            assertEquals(palette, PreferencesManager.getPalette(context))
        }
        PreferencesManager.setPalette(context, AppPalette.GRAPHITE_SCHOLASTIC)
        assertEquals("graphite_scholastic", context.getSharedPreferences("pk_prefs", Context.MODE_PRIVATE).getString("palette", null))
    }

    @Test
    fun `an unknown stored palette falls back to Still Water`() {
        context.getSharedPreferences("pk_prefs", Context.MODE_PRIVATE).edit().putString("palette", "neon").commit()
        assertEquals(AppPalette.STILL_WATER, PreferencesManager.getPalette(context))
    }

    @Test
    fun `theme modes keep their stored values, 2 meaning OLED`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(PreferencesManager.getTheme(context)))
        for (mode in ThemeMode.entries) {
            PreferencesManager.setTheme(context, mode.stored)
            assertEquals(mode, ThemeMode.fromStored(PreferencesManager.getTheme(context)))
        }
        assertEquals(ThemeMode.OLED, ThemeMode.fromStored(2))
        assertTrue(ThemeMode.OLED.isOled(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isOled(systemDark = true))
        assertTrue(ThemeMode.SYSTEM.isOled(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isOled(systemDark = false))
    }

    @Test
    fun `the theme provides the chosen palette's OLED tokens`() {
        var tokens: ScanPalette? = null
        var primary = AppPalette.DEFAULT.light.bg
        var dark = false
        rule.setContent {
            PaperKnifePlusTheme(palette = AppPalette.ROSE, themeMode = ThemeMode.OLED) {
                tokens = LocalScanPalette.current
                primary = MaterialTheme.colorScheme.primary
                dark = LocalIsDarkTheme.current
            }
        }
        rule.waitForIdle()
        assertEquals(AppPalette.ROSE.oled, tokens)
        assertEquals(AppPalette.ROSE.oled.acf, primary)
        assertTrue(dark)
    }

    @Test
    @Config(qualifiers = "night")
    fun `System follows the system dark mode with OLED`() {
        var tokens: ScanPalette? = null
        rule.setContent { PaperKnifePlusTheme(themeMode = ThemeMode.SYSTEM) { tokens = LocalScanPalette.current } }
        rule.waitForIdle()
        assertEquals(AppPalette.STILL_WATER.oled, tokens)
    }
}
