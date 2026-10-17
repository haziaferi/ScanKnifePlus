package com.paperknifeplus.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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

    private val fakeNotices = listOf(NoticeSection("Fake notice", "fake notice body"), NoticeSection("Other notice", "other body"))

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    private fun scrollTo(text: String, substring: Boolean = false) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = substring))
        rule.onAllNodesWithText(text, substring = substring).onFirst().assertExists()
    }

    /** Brings every item of the lazy list into composition in turn, since a lazy list only composes the visible items. */
    private fun assertNowhereOnPage(vararg texts: String) {
        val list = rule.onNode(hasScrollAction())
        var index = 0
        while (true) {
            list.performScrollToIndex(index++)
            for (text in texts) rule.onAllNodes(hasText(text, substring = true, ignoreCase = true)).assertCountEquals(0)
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue()) break
        }
    }

    private fun openLibraries() {
        scrollTo("Open Source Libraries")
        rule.onNodeWithText("Open Source Libraries").performClick()
        rule.onNodeWithText("Open Source").assertExists()
    }

    @Test
    fun `system Back and the arrow on a sub-page return to the About page`() {
        var backCalled = false
        rule.setContent { AboutView(onBack = { backCalled = true }, loadNotices = { fakeNotices }) }

        openLibraries()
        pressBack()
        rule.onNodeWithText("Open Source").assertDoesNotExist()
        rule.onNodeWithText("How it works").assertExists()

        openLibraries()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("How it works").assertExists()
        assertFalse(backCalled)
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun `the arrow on the About page leaves About`() {
        var backCalled = false
        rule.setContent { AboutView(onBack = { backCalled = true }, loadNotices = { fakeNotices }) }

        rule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backCalled)
    }

    @Test
    fun `the About page credits both upstream projects and shows the app name and version`() {
        rule.setContent { AboutView(onBack = {}, loadNotices = { fakeNotices }) }
        scrollTo("By potatameister; contributors are listed on the project's GitHub page", substring = true)
        scrollTo("[GPL-3.0-or-later]")
        scrollTo("By Vijay T S and Vikram H (Ethereal Developers); contributors are listed on the project's GitHub page", substring = true)
        scrollTo("not affiliated with or endorsed by the PaperKnife+ or OpenScan projects", substring = true)
        scrollTo("ScanKnife+ V", substring = true)
        rule.onNodeWithText("PaperKnife+ V", substring = true).assertDoesNotExist()
        assertNowhereOnPage("supporter", "Gabbar-v7", "yk1234f", "phyrz91", "meskobalazs", "IzzySoft", "PatrykMis", "VasilisKos")
    }

    @Test
    fun `the libraries page credits OpenScan and CameraX and shows one row per notice`() {
        rule.setContent { AboutView(onBack = {}, loadNotices = { fakeNotices }) }
        openLibraries()
        for (text in listOf("OpenScan", "[BSD-3-Clause]", "CameraX", "Notices", "Fake notice", "fake notice body", "Other notice")) scrollTo(text)
    }
}
