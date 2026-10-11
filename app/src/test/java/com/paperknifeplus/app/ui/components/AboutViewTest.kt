package com.paperknifeplus.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AboutViewTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `system Back on a sub-page returns to the About page like the arrow`() {
        var backCalled = false
        rule.setContent { AboutView(initialPage = "libraries", onBack = { backCalled = true }) }
        rule.onNodeWithText("Open Source").assertExists()

        pressBack()
        rule.onNodeWithText("Open Source").assertDoesNotExist()
        rule.onNodeWithText("How it works").assertExists()
        assertFalse(backCalled)
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun `system Back on a page opened from Settings leaves About`() {
        var backCalled = false
        rule.setContent { AboutView(initialPage = "support", isFromSettings = true, onBack = { backCalled = true }) }

        pressBack()
        assertTrue(backCalled)
    }

    @Test
    fun `the libraries page credits OpenScan and CameraX`() {
        rule.setContent { AboutView(initialPage = "libraries", onBack = {}) }
        for (text in listOf("OpenScan", "[BSD-3-Clause]", "CameraX")) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            rule.onNodeWithText(text).assertExists()
        }
    }
}
