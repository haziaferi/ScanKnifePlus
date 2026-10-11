package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.mainThreadComposeRule
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompareViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = mainThreadComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun emptyCards() = rule.onAllNodes(hasText("Select File")).fetchSemanticsNodes().size

    @Test
    fun `a locked file whose prompt is cancelled is not kept as file A`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "locked-compare.pdf")
        rule.setContent { CompareView(initialUri = locked, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("UNLOCK FILE")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("CANCEL").performClick()
        rule.waitForIdle()
        assertEquals(2, emptyCards())
    }

    @Test
    fun `a readable file becomes file A`() {
        val plain = TestPdfs.write(context.cacheDir, TestPdfs.plain(), "plain-compare.pdf")
        rule.setContent { CompareView(initialUri = plain, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { emptyCards() == 1 }
    }
}
