package com.paperknifeplus.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AboutViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    // Not mainThreadAndroidComposeRule: under its standard test dispatcher the lazy list never scrolls, so performScrollToNode loops until
    // the heap runs out.
    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    private fun scrollTo(text: String, substring: Boolean = false) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = substring))
        rule.onAllNodesWithText(text, substring = substring).onFirst().assertExists()
    }

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
        rule.setContent { AboutView(initialPage = "libraries", isFromSettings = true, onBack = { backCalled = true }) }

        pressBack()
        assertTrue(backCalled)
    }

    @Test
    fun `the About page credits both upstream projects and shows the app name and version`() {
        rule.setContent { AboutView(onBack = {}) }
        scrollTo("fork of PaperKnife+ by potatameister", substring = true)
        scrollTo("[GPL-3.0-or-later]")
        scrollTo("OpenScan by Vijay T S and Vikram H", substring = true)
        scrollTo("ScanKnife+ V", substring = true)
        rule.onNodeWithText("PaperKnife+ V", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the libraries page credits OpenScan and CameraX and shows the bundled notices`() {
        rule.setContent { AboutView(initialPage = "libraries", onBack = {}) }
        for (text in listOf("OpenScan", "[BSD-3-Clause]", "CameraX", "NOTICES")) scrollTo(text)
        // The notices load on an IO thread. A bounded loop, not waitUntil: under Robolectric's paused looper its clock may never time out.
        val loaded = (1..25).any {
            Thread.sleep(40)
            rule.waitForIdle()
            runCatching { scrollTo("OpenScan license") }.isSuccess
        }
        assertTrue("the notices never loaded", loaded)
        scrollTo("LiberationSans-OFL-1.1.txt")
    }
}
