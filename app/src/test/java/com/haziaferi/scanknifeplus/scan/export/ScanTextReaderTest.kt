package com.haziaferi.scanknifeplus.scan.export

import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.OcrWord
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The OCR pass of a save over a real library with text pages: cache, pause, stop and the engine's lifetime, with a fake engine. */
@RunWith(RobolectricTestRunner::class)
class ScanTextReaderTest {
    private val root: File = Files.createTempDirectory("reader").toFile()
    private val writer = CaptureWriter { source, _, page, _ ->
        page.writeText(source.open().use { it.readBytes().decodeToString() })
        StoredCapture(page, null, cropped = true, pageWidth = 1, pageHeight = 1)
    }
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File) = true.also { dest.writeText("${filter.name} ${source.readText()}") }
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = true.also { dest.writeText("cropped ${source.readText()}") }
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = true.also { source.copyTo(dest, overwrite = true) }
        override fun rotate(source: File, quarterTurns: Int, dest: File) = true.also { dest.writeText("turned ${source.readText()}") }
    }
    private val library = ScanLibrary(root, capture = writer, images = images)
    private val running = MutableStateFlow(true)
    private val settings = OcrSettings(OcrLanguage.ITALIAN, OcrReading.TABLES, 297.0)
    private var opened = 0

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun scan(vararg texts: String): String {
        val id = library.create().id
        for (t in texts) library.addCapture(id, ImageSource { t.byteInputStream() }, null, keepOriginal = false)
        return id
    }

    private fun reader(engine: FakeOcrEngine) = ScanTextReader(library, { opened++; engine }, Dispatchers.Unconfined, running)

    private fun pages(id: String) = library.document(id)!!.pages

    private fun readAll(engine: FakeOcrEngine, id: String, s: OcrSettings = settings, progress: MutableList<Pair<Int, Int>>? = null) =
        runBlocking { reader(engine).read(id, pages(id), s) { page, total -> progress?.add(page to total) } }

    @Test
    fun `every page is read once with the settings, reported as it starts, and the engine is closed`() {
        val id = scan("a", "b", "c")
        val engine = FakeOcrEngine()
        val progress = mutableListOf<Pair<Int, Int>>()
        val read = readAll(engine, id, progress = progress)!!
        assertEquals(listOf(FakeRead("a", 297.0, OcrLanguage.ITALIAN, OcrReading.TABLES)), engine.reads.take(1))
        assertEquals(listOf("a", "b", "c"), pages(id).map { read[it.image]!!.words.single().text })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(1, opened)
        assertEquals(1, engine.closes)
    }

    @Test
    fun `after a restart the cached pages are reused and only the rest is read`() {
        val id = scan("a", "b", "c")
        // The first run is stopped (the process dies) while page b is read: a is cached, b is not.
        val first = FakeOcrEngine()
        lateinit var dying: ScanTextReader
        first.onRead = { if (it == "b") dying.stop() }
        dying = reader(first)
        assertNull(runBlocking { dying.read(id, pages(id), settings) { _, _ -> } })

        val second = FakeOcrEngine()
        val progress = mutableListOf<Pair<Int, Int>>()
        val read = readAll(second, id, progress = progress)!!
        assertEquals(listOf("b", "c"), second.reads.map { it.text })
        assertEquals("the count starts at the first page left", listOf(2 to 3, 3 to 3), progress)
        assertEquals("a", read[pages(id)[0].image]!!.words.single().text)

        val third = FakeOcrEngine()
        assertEquals(3, readAll(third, id)!!.size)
        assertTrue(third.reads.isEmpty())
        assertEquals("all cached: no engine opened", 2, opened)
    }

    @Test
    fun `an edited page is read again, the others are not`() {
        val id = scan("a", "b")
        readAll(FakeOcrEngine(), id)
        val edited = pages(id)[1]
        library.rotatePage(id, edited.id, 1)
        val engine = FakeOcrEngine()
        readAll(engine, id)
        assertEquals(listOf("turned b"), engine.reads.map { it.text })
        library.applyFilter(id, edited.id, "Grayscale")
        library.recropPage(id, pages(id)[0].id, Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0)))
        val again = FakeOcrEngine()
        readAll(again, id)
        assertEquals(2, again.reads.size)
    }

    @Test
    fun `the cache key is the image, the language, the reading and the long edge`() {
        val id = scan("a")
        readAll(FakeOcrEngine(), id)
        for (other in listOf(
            settings.copy(language = OcrLanguage.ENGLISH),
            settings.copy(reading = OcrReading.AUTOMATIC),
            settings.copy(longEdgeMm = OcrSettings.longEdgeMm(PdfPageSize.LETTER)),
        )) {
            val engine = FakeOcrEngine()
            readAll(engine, id, other)
            assertEquals("$other", 1, engine.reads.size)
        }
        val engine = FakeOcrEngine()
        readAll(engine, id, settings)
        assertEquals(1, engine.reads.size)
        readAll(engine, id, settings)
        assertEquals("the same settings hit the cache", 1, engine.reads.size)
    }

    @Test
    fun `clear and deleting the scan remove the cache`() {
        val id = scan("a")
        readAll(FakeOcrEngine(), id)
        OcrCache(library, id).clear()
        val engine = FakeOcrEngine()
        readAll(engine, id)
        assertEquals(1, engine.reads.size)
        assertTrue(File(root, id).list()!!.any { it.startsWith("ocr_") })
        assertTrue(library.delete(id))
        assertFalse(File(root, id).exists())
    }

    @Test
    fun `a page read is kept whole, with its words, boxes, confidence, layout and turn`() {
        val id = scan("a")
        val page = OcrPage(listOf(OcrWord("€12,50", 0.125f, 0.3f, 0.75f, 0.35f, 87.5f, 2, 3, 4), OcrWord("x", 0f, 0f, 1f, 1f, 0f, 0, 0, 0)), 90)
        val image = pages(id)[0].image
        OcrCache(library, id).put(image, settings, page)
        assertEquals(page, OcrCache(library, id).get(image, settings))
        assertNull(OcrCache(library, id).get("other.jpg", settings))
    }

    @Test
    fun `in the background the page being read finishes, then reading waits until the app is back`() {
        val id = scan("a", "b", "c")
        val engine = FakeOcrEngine()
        engine.onRead = { if (it == "a") running.value = false }
        runBlocking {
            val result = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { reader(engine).read(id, pages(id), settings) { _, _ -> } }
            assertEquals("page a finished, b waits", listOf("a"), engine.reads.map { it.text })
            assertFalse(result.isCompleted)
            assertEquals(0, engine.cancels)
            running.value = true
            assertEquals(3, result.await()!!.size)
            assertEquals(listOf("a", "b", "c"), engine.reads.map { it.text })
        }
    }

    @Test
    fun `stop cancels the page being read, returns null and still closes the engine`() {
        val id = scan("a", "b")
        val engine = FakeOcrEngine()
        lateinit var reader: ScanTextReader
        engine.onRead = { reader.stop() }
        reader = reader(engine)
        assertNull(runBlocking { reader.read(id, pages(id), settings) { _, _ -> } })
        assertEquals(1, engine.cancels)
        assertEquals(listOf("a"), engine.reads.map { it.text })
        assertEquals(1, engine.closes)
    }

    @Test
    fun `stop while paused ends the wait`() {
        val id = scan("a", "b")
        running.value = false
        val engine = FakeOcrEngine()
        val reader = reader(engine)
        runBlocking {
            val result = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { reader.read(id, pages(id), settings) { _, _ -> } }
            assertFalse(result.isCompleted)
            reader.stop()
            assertNull(result.await())
        }
        assertTrue(engine.reads.isEmpty())
    }

    @Test
    fun `a page the engine cannot read has no text, the others do`() {
        val id = scan("a", "fail", "c")
        val engine = FakeOcrEngine { if (it == "fail") throw IllegalStateException("broken") }
        val read = readAll(engine, id)!!
        assertEquals(setOf(pages(id)[0].image, pages(id)[2].image), read.keys)
    }

    @Test
    fun `the long edge is the save size's, A4 297 mm, Letter 279_4, Legal 355_6, Fit A4's`() {
        assertEquals(297.0, OcrSettings.longEdgeMm(PdfPageSize.A4), 1e-9)
        assertEquals(279.4, OcrSettings.longEdgeMm(PdfPageSize.LETTER), 1e-9)
        assertEquals(355.6, OcrSettings.longEdgeMm(PdfPageSize.LEGAL), 1e-9)
        assertEquals(297.0, OcrSettings.longEdgeMm(PdfPageSize.FIT), 1e-9)
    }

    @Test
    fun `the txt joins the pages in order with a form feed, leaves out unsure words and keeps empty pages`() {
        fun page(vararg words: Pair<String, Float>) = OcrPage(words.mapIndexed { i, (t, c) -> OcrWord(t, 0f, 0f, 1f, 1f, c, 0, 0, i) })
        val text = ScanText.join(listOf(page("one" to 90f, "two" to 20f), null, page(), page("three" to 41f)))
        assertEquals("one\n\u000C\u000C\u000Cthree\n", text)
    }

    @Test
    fun `the handwriting notice gets the numbers of the pages that look handwritten`() {
        val id = scan("a", "scribble", "c", "scribble too")
        val read = readAll(FakeOcrEngine(), id)!!
        assertEquals(listOf(2, 4), ScanText(pages(id), read).handwritten)
    }
}
