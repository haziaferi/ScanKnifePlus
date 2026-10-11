package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.test.hasText
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
}
