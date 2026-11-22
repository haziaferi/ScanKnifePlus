package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PdfToTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun withText(text: String): ByteArray = PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use {
            it.beginText()
            it.setFont(PDType1Font.HELVETICA, 12f)
            it.newLineAtOffset(72f, 700f)
            it.showText(text)
            it.endText()
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `a one-page PDF gives its text under a page header`() {
        val text = PDDocument.load(withText("Hello text layer")).use(::extractPdfText)
        assertTrue(text, text.startsWith("--- PAGE 1 ---\n"))
        assertTrue(text, text.contains("Hello text layer"))
    }

    @Test
    fun `a protected file gives its text through the opener with its password`() = runBlocking {
        val bytes = TestPdfs.protectedCopy(withText("Locked text layer"))
        val locked = TestPdfs.write(context.cacheDir, bytes, "locked-text.pdf")
        val expected = PDDocument.load(bytes, TestPdfs.USER_PASSWORD).use(::extractPdfText)
        assertTrue(expected, expected.contains("Locked text layer"))
        assertEquals(expected, processText(context, locked, TestPdfs.USER_PASSWORD).getOrThrow().first)
        assertTrue(processText(context, locked, null).isFailure)
    }

    @Test
    fun `running out of memory while opening the file is a failure result, not a crash`() = runBlocking {
        val huge = TestPdfs.OutOfMemorySource(context, withText("Too much"), "huge-text.pdf").apply { runOutOfMemory() }
        assertTrue(processText(context, huge.uri, null).exceptionOrNull() is OutOfMemoryError)
    }

    @Test
    fun `a failed extraction is a failure result, not text`() = runBlocking {
        val garbage = TestPdfs.write(context.cacheDir, TestPdfs.garbage, "garbage.pdf")
        assertTrue(processText(context, garbage, null).isFailure)
        assertTrue(processText(context, TestPdfs.nullStreamUri(), null).exceptionOrNull() is IOException)
        val good = TestPdfs.write(context.cacheDir, withText("Readable"), "good.pdf")
        assertTrue(processText(context, good, null).getOrThrow().first.contains("Readable"))
    }

    @Test
    fun `the chunks joined give the text back`() {
        val text = (1..95).joinToString("\n") { "line $it" } + "\n\n--- PAGE 2 ---\nend"
        val chunks = textChunks(text, linesPerChunk = 10)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(10, chunks.size)
        assertTrue(chunks.dropLast(1).all { it.count { c -> c == '\n' } == 10 && it.endsWith("\n") })
        assertEquals(emptyList<String>(), textChunks("", 10))
        assertEquals(listOf("no break"), textChunks("no break", 10))
    }
}

/** PDF to text on its screen: running out of memory while saving the text. */
@RunWith(RobolectricTestRunner::class)
class PdfToTextViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `running out of memory while saving the text shows the failure and removes the file the save created`() {
        val (output, created) = TestPdfs.createdDocument(context, "huge-text.txt")
        // Writing the text runs out of memory, as encoding a very large text can.
        Shadows.shadowOf(context.contentResolver).registerOutputStreamSupplier(output) { throw OutOfMemoryError("test") }
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "text-saved.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output)) {
                PdfToTextView(initialUri = uri, onBack = {})
            }
        }
        rule.waitUntil(10_000) { shown("Extract text") }
        rule.onNodeWithText("Extract text").performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasContentDescription("Save as TXT")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNode(hasContentDescription("Save as TXT")).performClick()
        // Reading the screen runs the main thread's queue, where the failure handler shows its message.
        rule.waitUntil(30_000) { shown("Extracted content") && ShadowToast.getTextOfLatestToast() == TestPdfs.OUT_OF_MEMORY }
        assertFalse("the created file is removed", created.exists())
    }
}
