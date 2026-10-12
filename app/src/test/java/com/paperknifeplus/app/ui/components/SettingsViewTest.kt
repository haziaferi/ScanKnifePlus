package com.paperknifeplus.app.ui.components

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.paperknifeplus.app.testing.ComposeSnapshotPump
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
        val opened = mutableListOf<String>()
        rule.setContent { SettingsView(isDarkMode = false, onThemeChange = {}, onNavigateToAbout = { opened += it }) }
        val list = rule.onNode(hasScrollAction())

        list.performScrollToNode(hasText("About ScanKnife+"))
        rule.onNodeWithText("About ScanKnife+").performClick()
        assertEquals(listOf("main"), opened)

        list.performScrollToNode(hasText("ScanKnife+ V", substring = true))
        rule.onNodeWithText("Support Us").assertDoesNotExist()
        rule.onNodeWithText("Hall of Fame").assertDoesNotExist()
        rule.onNodeWithText("PaperKnife", substring = true).assertDoesNotExist()
    }
}
