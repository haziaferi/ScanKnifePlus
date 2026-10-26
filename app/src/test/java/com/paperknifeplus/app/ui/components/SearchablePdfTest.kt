package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ocr.OcrImages
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.TesseractOcrEngine
import com.haziaferi.scanknifeplus.scan.export.FakeOcrEngine
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A [PageRenders] that writes "page n" as the page image, which [FakeOcrEngine] reads back as one word across the page. */
internal class FakeRenders : PageRenders {
    val sizes = mutableListOf<Triple<Int, Int, Int>>()
    var password: String? = null
    var closes = 0

    override fun write(index: Int, width: Int, height: Int, dest: File): Boolean {
        sizes += Triple(index, width, height)
        dest.writeText("page ${index + 1}")
        return true
    }

    override fun close() {
        closes++
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchablePdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "searchable-test").apply { deleteRecursively(); mkdirs() }
    private val options = SearchableOptions(OcrLanguage.ITALIAN, OcrReading.TABLES)
    private val engine = FakeOcrEngine()
    private val renders = FakeRenders()
    private var opens = 0
    private val running = MutableStateFlow(true)

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun openEngine() = engine.also { opens++ }

    private fun run(
        bytes: ByteArray,
        password: String = "",
        searchable: SearchableOptions = options,
        onPage: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<SearchableResult, File> {
        val out = File(dir, "out.pdf").apply { delete() }
        val result = runBlocking {
            makeSearchable(context, TestPdfs.write(dir, bytes), password, Uri.fromFile(out), searchable, ::openEngine, running,
                renders = { _, pass -> renders.also { it.password = pass } }, onPage = onPage)
        }
        return result to out
    }

    private fun text(file: File, password: String = ""): String = PDDocument.load(file, password).use { PDFTextStripper().getText(it) }

    /** The glyphs of the first page of [file] as PDFBox extracts them. */
    private fun glyphs(file: File): List<TextPosition> = PDDocument.load(file).use { doc ->
        val found = mutableListOf<TextPosition>()
        object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: List<TextPosition>) {
                found += textPositions
            }
        }.getText(doc)
        found
    }

    @Test
    fun `the display matrix maps the displayed page as displayToPdf does`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val box = PageBox(40f, 60f, 400f, 560f, rotation)
            val m = box.displayMatrix()
            for ((px, py) in listOf(0f to 0f, box.displayWidth to 0f, 0f to box.displayHeight, 13f to 71f)) {
                val expected = box.displayToPdf(px, py)
                val actual = m.transformPoint(px, py)
                assertEquals("$rotation x", expected.x, actual.x, 0.001f)
                assertEquals("$rotation y", expected.y, actual.y, 0.001f)
            }
        }
    }

    @Test
    fun `pages are rendered at the size the engine reads them at, so its scaling is a no-op`() {
        for (box in listOf(PageBox(0f, 0f, 595f, 842f, 0), PageBox(0f, 0f, 612f, 792f, 90), PageBox(10f, 20f, 300f, 200f, 0))) {
            val mm = box.longEdgeMm()
            val size = readingSize(box, mm)
            assertEquals(size, OcrImages.targetSize(size.width, size.height, mm, TesseractOcrEngine.DPI))
            assertEquals((maxOf(box.displayWidth, box.displayHeight) * 200 / 72.0).roundToInt(), maxOf(size.width, size.height))
            assertEquals(box.displayWidth > box.displayHeight, size.width > size.height)
        }
    }

    @Test
    fun `each page is read at its real size with the chosen language and reading, and gets a text layer`() {
        val (result, out) = run(TestPdfs.plain(2))
        assertEquals(SearchableResult(2, 2, 0, emptyList()), result)
        assertEquals(listOf("page 1", "page 2"), engine.reads.map { it.text })
        val letterMm = 792 / 72.0 * 25.4
        for (read in engine.reads) {
            assertEquals(letterMm, read.longEdgeMm, 1e-9)
            assertEquals(OcrLanguage.ITALIAN, read.language)
            assertEquals(OcrReading.TABLES, read.reading)
        }
        assertEquals(listOf(Triple(0, 1700, 2200), Triple(1, 1700, 2200)), renders.sizes)
        val text = text(out)
        assertTrue(text, "page 1" in text && "page 2" in text)
        assertEquals(1, opens)
        assertEquals(1, engine.closes)
        assertEquals(1, renders.closes)
        assertEquals(emptyList<String>(), searchableWorkDir(context).list()!!.toList())
    }

    @Test
    fun `the words land on the page as displayed, on rotated pages and with a cropBox away from the origin`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            engine.reads.clear()
            val (_, out) = run(TestPdfs.cropped(rotation))
            val box = PageBox(TestPdfs.CROP_BOX.lowerLeftX, TestPdfs.CROP_BOX.lowerLeftY, TestPdfs.CROP_BOX.width, TestPdfs.CROP_BOX.height, rotation)
            val glyphs = glyphs(out)
            assertEquals("$rotation", "page 1", glyphs.joinToString("") { it.unicode })
            // The fake's word spans 0.1 to 0.9 across and 0.1 to 0.2 down the page as displayed.
            val shown = glyphs.map { g ->
                val m = g.textMatrix
                val x = m.translateX + box.left
                val y = m.translateY + box.bottom
                val at = box.pdfToDisplay(x, y)
                val ahead = box.pdfToDisplay(x + m.scaleX, y + m.shearY)
                Triple(at, ahead.x - at.x, ahead.y - at.y)
            }
            val first = shown.first().first
            assertEquals("$rotation start", 0.1f * box.displayWidth, first.x, 0.5f)
            for ((at, dx, dy) in shown) {
                assertTrue("$rotation baseline ${at.y}", at.y > 0.1f * box.displayHeight && at.y < 0.2f * box.displayHeight)
                assertEquals("$rotation baseline level", first.y, at.y, 0.01f)
                assertTrue("$rotation reads left to right", dx > 0f && abs(dy) < 0.001f)
                assertTrue("$rotation inside the word", at.x < 0.9f * box.displayWidth)
            }
            assertTrue("$rotation glyphs advance", shown.zipWithNext().all { (a, b) -> b.first.x > a.first.x })
        }
    }

    @Test
    fun `pages that already have text are copied unchanged, and read again with redo`() {
        val source = TestPdfs.someWithText(3, setOf(1))
        val (skipped, out) = run(source)
        assertEquals(SearchableResult(3, 2, 1, emptyList()), skipped)
        assertEquals(listOf("page 1", "page 3"), engine.reads.map { it.text })
        val before = PDDocument.load(source).use { it.getPage(1).contents.readBytes() }
        PDDocument.load(out).use { doc ->
            assertTrue(before.contentEquals(doc.getPage(1).contents.readBytes()))
            val stripper = PDFTextStripper().apply { startPage = 2; endPage = 2 }
            assertEquals(TestPdfs.textMarker(2), stripper.getText(doc).trim())
        }

        engine.reads.clear()
        val (redone, again) = run(source, searchable = options.copy(redo = true))
        assertEquals(SearchableResult(3, 3, 0, emptyList()), redone)
        assertEquals(listOf("page 1", "page 2", "page 3"), engine.reads.map { it.text })
        val second = PDDocument.load(again).use { PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(it) }
        assertTrue(second, TestPdfs.textMarker(2) in second && "page 2" in second)
    }

    @Test
    fun `redoing a page replaces its old invisible text, keeping its image and visible text, also when redone twice`() {
        val source = TestPdfs.layered("q 100 0 0 100 72 72 cm /Im1 Do Q", "BT /F1 12 Tf 72 700 Td (VISIBLE) Tj ET",
            "BT 3 Tr /F1 12 Tf 72 650 Td (OLDOCR) Tj ET")
        val redo = options.copy(redo = true)
        val (_, once) = run(source, searchable = redo)
        val first = File(dir, "once.pdf").apply { writeBytes(once.readBytes()) }
        val (_, twice) = run(first.readBytes(), searchable = redo)
        for ((name, bytes) in listOf("once" to first.readBytes(), "twice" to twice.readBytes())) {
            PDDocument.load(bytes).use { doc ->
                val words = PDFTextStripper().getText(doc).split(Regex("""\s+""")).filter { it.isNotEmpty() }
                assertEquals(name, listOf("VISIBLE", "page", "1"), words)
                val content = String(doc.getPage(0).contents.readBytes(), Charsets.ISO_8859_1)
                assertTrue(content, "/Im1 Do" in content)
            }
        }
    }

    @Test
    fun `a redone page whose content cannot be read keeps it and still gets the new layer`() {
        val broken = PDDocument.load(TestPdfs.layered("BT 3 Tr /F1 12 Tf 72 650 Td (OLDOCR) Tj ET")).use { doc ->
            val contents = doc.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS) as COSArray
            (contents.getObject(0) as COSStream).setItem(COSName.FILTER, COSName.getPDFName("Bogus"))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val (result, out) = run(broken, searchable = options.copy(redo = true))
        assertEquals(1, result.read)
        PDDocument.load(out).use { doc ->
            val contents = doc.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS) as COSArray
            val kept = (0 until contents.size()).filter {
                (contents.getObject(it) as COSStream).getDictionaryObject(COSName.FILTER) == COSName.getPDFName("Bogus")
            }
            assertEquals(1, kept.size)
            // Without the unreadable stream, the rest of the page reads as the new layer.
            contents.remove(kept.single())
            assertEquals("page 1", PDFTextStripper().getText(doc).trim())
        }
    }

    @Test
    fun `a document whose pages all have text never opens an engine`() {
        val (result, out) = run(TestPdfs.someWithText(2, setOf(0, 1)))
        assertEquals(SearchableResult(2, 0, 2, emptyList()), result)
        assertEquals(0, opens)
        assertTrue(out.length() > 0)
    }

    @Test
    fun `handwritten-looking pages are reported by number`() {
        renders.sizes.clear()
        val scribbles = object : PageRenders by renders {
            override fun write(index: Int, width: Int, height: Int, dest: File): Boolean {
                dest.writeText(if (index == 1) "scribble" else "page ${index + 1}")
                return true
            }
        }
        val out = File(dir, "out.pdf")
        val result = runBlocking {
            makeSearchable(context, TestPdfs.write(dir, TestPdfs.plain(3)), "", Uri.fromFile(out), options, ::openEngine, running, renders = { _, _ -> scribbles })
        }
        assertEquals(listOf(2), result.handwritten)
    }

    @Test
    fun `a protected PDF is rendered with its password and stays protected with it`() {
        val (result, out) = run(TestPdfs.userPassword(2), TestPdfs.USER_PASSWORD)
        assertEquals(2, result.read)
        assertEquals(TestPdfs.USER_PASSWORD, renders.password)
        PDDocument.load(out, TestPdfs.USER_PASSWORD).use { assertTrue(it.isEncrypted) }
        assertTrue(runCatching { PDDocument.load(out).close() }.isFailure)
        assertTrue("page 2" in text(out, TestPdfs.USER_PASSWORD))
    }

    @Test
    fun `an unprotected PDF is rendered without a password`() {
        run(TestPdfs.plain(1))
        assertEquals(null, renders.password)
    }

    private fun photo(name: String, width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun `photos become one page each, read as pages with A4's long edge`() {
        val out = File(dir, "photos.pdf")
        val photos = listOf(photo("a.png", 600, 800), photo("b.png", 900, 600))
        val result = runBlocking {
            makePhotosSearchable(context, photos, Uri.fromFile(out), options, ::openEngine, running, renders = { _, _ -> renders })
        }
        assertEquals(SearchableResult(2, 2, 0, emptyList()), result)
        assertEquals(listOf(PHOTO_LONG_EDGE_MM, PHOTO_LONG_EDGE_MM), engine.reads.map { it.longEdgeMm })
        assertEquals(listOf(Triple(0, 1754, 2339), Triple(1, 2339, 1559)), renders.sizes)
        PDDocument.load(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            // Placed as Images to PDF places them: the page is the photo at 300 DPI.
            assertEquals(600 * 72f / 300, doc.getPage(0).mediaBox.width, 0.01f)
        }
        assertTrue("page 2" in text(out))
        assertEquals(emptyList<String>(), searchableWorkDir(context).list()!!.toList())
    }

    @Test
    fun `progress reports each page read as it starts`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        run(TestPdfs.someWithText(3, setOf(1)), onPage = { page, total -> seen += page to total })
        assertEquals(listOf(1 to 3, 3 to 3), seen)
    }

    @Test
    fun `a cancel stops the page being read, closes the engine and writes nothing`() {
        val out = File(dir, "cancelled.pdf").apply { delete() }
        lateinit var job: Job
        engine.onRead = { text ->
            if (text == "page 2") {
                job.cancel()
                val end = System.currentTimeMillis() + 5_000
                while (engine.cancels == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
            }
        }
        var failure: Throwable? = null
        job = CoroutineScope(Dispatchers.IO).launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                makeSearchable(context, TestPdfs.write(dir, TestPdfs.plain(3)), "", Uri.fromFile(out), options, ::openEngine, running,
                    renders = { _, _ -> renders })
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        runBlocking { job.join() }
        assertTrue(failure is CancellationException)
        assertEquals(1, engine.cancels)
        assertEquals(listOf("page 1", "page 2"), engine.reads.map { it.text })
        assertEquals(1, engine.closes)
        assertFalse(out.exists())
        assertEquals(emptyList<String>(), searchableWorkDir(context).list()!!.toList())
    }

    @Test
    fun `in the background reading finishes the current page and waits until the app returns`() {
        engine.onRead = { text -> if (text == "page 1") running.value = false }
        val out = File(dir, "paused.pdf").apply { delete() }
        val job = CoroutineScope(Dispatchers.IO).launch {
            makeSearchable(context, TestPdfs.write(dir, TestPdfs.plain(3)), "", Uri.fromFile(out), options, ::openEngine, running,
                renders = { _, _ -> renders })
        }
        Thread.sleep(500)
        assertEquals(listOf("page 1"), engine.reads.map { it.text })
        assertFalse(job.isCompleted)
        assertEquals(0, engine.cancels)
        running.value = true
        runBlocking { job.join() }
        assertEquals(listOf("page 1", "page 2", "page 3"), engine.reads.map { it.text })
        assertTrue("page 3" in text(out))
    }
}
