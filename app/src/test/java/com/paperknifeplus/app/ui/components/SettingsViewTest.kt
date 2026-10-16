package com.paperknifeplus.app.ui.components

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.theme.AppPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun `the About group opens About under the app name and the footer shows the app name`() {
        var opened = 0
        rule.setContent {
            SettingsView(isDarkMode = false, onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = { opened++ })
        }
        val list = rule.onNode(hasScrollAction())

        list.performScrollToNode(hasText("About ScanKnife+"))
        rule.onNodeWithText("About ScanKnife+").performClick()
        assertEquals(1, opened)

        list.performScrollToNode(hasText("ScanKnife+ V", substring = true))
        rule.onNodeWithText("Support Us").assertDoesNotExist()
        rule.onNodeWithText("Hall of Fame").assertDoesNotExist()
        rule.onNodeWithText("PaperKnife", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the theme row offers OLED and the palette row reports the chosen palette`() {
        val chosen = mutableListOf<AppPalette>()
        val themes = mutableListOf<Int>()
        rule.setContent {
            SettingsView(
                isDarkMode = false, onThemeChange = { themes += it }, palette = AppPalette.STILL_WATER, onPaletteChange = { chosen += it },
                onOpenAbout = {}
            )
        }
        rule.onNodeWithText("Dark").assertDoesNotExist()
        rule.onNodeWithText("OLED").performClick()
        assertEquals(listOf(2), themes)

        rule.onNodeWithText("COLOUR PALETTE").assertExists()
        rule.onNodeWithText("Rose").performClick()
        rule.onNodeWithText("Graphite Scholastic").performClick()
        rule.onNodeWithText("Still Water").performClick()
        assertEquals(listOf(AppPalette.ROSE, AppPalette.GRAPHITE_SCHOLASTIC, AppPalette.STILL_WATER), chosen)
    }
}
