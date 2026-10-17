package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.mainThreadComposeRule
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfToImageTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = mainThreadComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun `a document opens with every page selected`() {
        assertEquals(setOf(0, 1, 2), initialSelection(3))
        assertEquals(emptySet<Int>(), initialSelection(0))
    }

    @Test
    fun `the default format is one of the chips`() {
        assertEquals("JPG", IMAGE_FORMATS.first())
        assertTrue(IMAGE_FORMATS.containsAll(listOf("JPG", "PNG", "WebP")))
    }

    @Test
    fun `opened with a file, every page is selected and the format is JPG`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "three-images.pdf")
        rule.setContent { PdfToImageView(initialUri = uri, onBack = {}) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Convert", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Convert 3 Pages to JPG")).assertExists()
    }

    @Test
    fun `each page tile is a checkbox that says whether the page is exported`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "three-tiles.pdf")
        rule.setContent { PdfToImageView(initialUri = uri, onBack = {}) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Convert 3 Pages to JPG")).fetchSemanticsNodes().isNotEmpty() }
        val tile = rule.onNode(isToggleable() and hasContentDescription("Page 1"))
        tile.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOn()
        tile.performClick()
        tile.assertIsOff()
        rule.onNode(hasText("Convert 2 Pages to JPG")).assertExists()
    }
}
