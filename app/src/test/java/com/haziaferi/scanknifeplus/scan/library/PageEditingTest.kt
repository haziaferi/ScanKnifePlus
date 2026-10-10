package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Undoable filters and re-crops (OpenScan directory_cubit.dart _applyFilter and cropImage). The fake image work writes what it was computed
 * from, e.g. `Grayscale(page)`, so each test can see which source every edit used.
 */
@RunWith(RobolectricTestRunner::class)
class PageEditingTest {
    private val root: File = Files.createTempDirectory("editing").toFile()
    private var now = 1_791_590_400_000L

    private inner class FakeImages : PageImages {
        var failNext = false
        var meanwhile: (() -> Unit)? = null
        val crops = mutableListOf<Quad>()

        override fun filter(source: File, filter: Filter, dest: File) = op(dest) { "${filter.name}(${source.readText()})" }

        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = op(dest) {
            crops += quad
            "crop$quarterTurns(${source.readText()})"
        }

        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = op(dest) { "norm$maxEdge@$quality(${source.readText()})" }

        private fun op(dest: File, content: () -> String): Boolean {
            // Cleared before it runs, so a hook can arm the next operation.
            val hook = meanwhile
            meanwhile = null
            hook?.invoke()
            if (failNext) {
                failNext = false
                return false
            }
            dest.writeText(content())
            return true
        }
    }

    private val images = FakeImages()
    private val capture = CaptureWriter { _, _, page, original ->
        page.writeText("page")
        original?.writeText("original")
        StoredCapture(page, original, cropped = false, pageWidth = 1, pageHeight = 1)
    }
    private val library = ScanLibrary(root, clock = { now }, capture = capture, images = images)
    private val anySource = ImageSource { ByteArray(0).inputStream() }
    private val quad = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun newPage(keepOriginal: Boolean = true): Pair<String, ScanPage> {
        val doc = library.create()
        return doc.id to library.addCapture(doc.id, anySource, null, keepOriginal)!!.pages.single()
    }

    private fun text(id: String, name: String?) = library.file(id, name!!).readText()

    private fun folderFiles(id: String) = File(root, id).list()!!.filter { it != "document.json" }.toSet()

    @Test
    fun `the first filter keeps the page as its unfiltered copy`() {
        val (id, page) = newPage()
        val edited = library.applyFilter(id, page.id, "B&W")!!.pages.single()
        assertEquals("B&W", edited.filter)
        assertEquals("B&W(page)", text(id, edited.image))
        assertEquals("page", text(id, edited.unfiltered))
        assertEquals(page.original, edited.original)
        assertEquals(edited.files.toSet(), folderFiles(id)) // the old page file is gone, its content lives on as the copy
    }

    @Test
    fun `later filters are computed from the unfiltered copy, never compounded`() {
        val (id, page) = newPage()
        val first = library.applyFilter(id, page.id, "B&W")!!.pages.single()
        val second = library.applyFilter(id, page.id, "Grayscale")!!.pages.single()
        assertEquals("Grayscale(page)", text(id, second.image))
        assertEquals(first.unfiltered, second.unfiltered)
        assertEquals(second.files.toSet(), folderFiles(id))
    }

    @Test
    fun `Original restores the unfiltered copy`() {
        val (id, page) = newPage()
        library.applyFilter(id, page.id, "Lighten")
        val restored = library.applyFilter(id, page.id, "Original")!!.pages.single()
        assertEquals("page", text(id, restored.image))
        assertNull(restored.unfiltered)
        assertNull(restored.filter)
        assertEquals(restored.files.toSet(), folderFiles(id))
    }

    @Test
    fun `nothing to do returns the document unchanged`() {
        val (id, page) = newPage()
        now += 1000
        val before = library.document(id)!!
        assertEquals(before, library.applyFilter(id, page.id, null))
        assertEquals(before, library.applyFilter(id, page.id, "Original"))
        library.applyFilter(id, page.id, "Auto")
        val filtered = library.document(id)!!
        assertEquals(filtered, library.applyFilter(id, page.id, "Auto"))
    }

    @Test
    fun `a filter that fails leaves the page and the folder as they were`() {
        val (id, page) = newPage()
        val before = folderFiles(id)
        images.failNext = true
        assertNull(library.applyFilter(id, page.id, "Whiteboard"))
        assertEquals(page, library.document(id)!!.pages.single())
        assertEquals(before, folderFiles(id))
    }

    @Test
    fun `an edit whose page was deleted meanwhile is discarded`() {
        val (id, page) = newPage()
        library.addCapture(id, anySource, null, keepOriginal = false) // a second page, so the document survives
        images.meanwhile = { library.deletePage(id, page.id) }
        assertNull(library.applyFilter(id, page.id, "Auto"))
        val remaining = library.document(id)!!.pages.single()
        assertEquals(remaining.files.toSet(), folderFiles(id))
    }

    @Test
    fun `a re-crop starts from the original and clears the filter`() {
        val (id, page) = newPage()
        library.applyFilter(id, page.id, "B&W")
        val recropped = library.recropPage(id, page.id, quad, quarterTurns = 1)!!.pages.single()
        assertEquals("crop1(original)", text(id, recropped.image))
        assertEquals(page.original, recropped.original)
        assertNull(recropped.unfiltered)
        assertNull(recropped.filter)
        assertEquals(listOf(quad), images.crops)
        assertEquals(recropped.files.toSet(), folderFiles(id))
    }

    @Test
    fun `a page without an original gets one from its unfiltered image before the crop`() {
        val (id, page) = newPage(keepOriginal = false)
        library.applyFilter(id, page.id, "Grayscale")
        val recropped = library.recropPage(id, page.id, quad)!!.pages.single()
        assertEquals("norm3200@80(page)", text(id, recropped.original))
        assertEquals("crop0(page)", text(id, recropped.image))
        // A second crop works off that original, not off the first crop.
        val again = library.recropPage(id, page.id, quad)!!.pages.single()
        assertEquals("crop0(norm3200@80(page))", text(id, again.image))
        assertEquals(again.files.toSet(), folderFiles(id))
    }

    @Test
    fun `a crop that fails leaves the page and the folder as they were`() {
        val (id, page) = newPage(keepOriginal = false)
        val before = folderFiles(id)
        images.meanwhile = { images.meanwhile = { images.failNext = true } } // the promotion succeeds, then the crop fails
        assertNull(library.recropPage(id, page.id, quad))
        assertEquals(page, library.document(id)!!.pages.single())
        assertEquals(before, folderFiles(id))
    }

    @Test
    fun `new pages get the default filter, and Original means none`() {
        val doc = library.create()
        val filtered = library.addCapture(doc.id, anySource, null, keepOriginal = false, filter = "Auto")!!.pages.single()
        assertEquals("Auto", filtered.filter)
        assertEquals("Auto(page)", text(doc.id, filtered.image))
        val plain = library.addCapture(doc.id, anySource, null, keepOriginal = false, filter = "Original")!!.pages.last()
        assertNull(plain.filter)
        assertNull(plain.unfiltered)
    }

    @Test
    fun `a default filter that fails leaves the new page unfiltered`() {
        val doc = library.create()
        images.failNext = true
        val page = library.addCapture(doc.id, anySource, null, keepOriginal = false, filter = "B&W")!!.pages.single()
        assertNull(page.filter)
        assertEquals("page", text(doc.id, page.image))
        assertEquals(page.files.toSet(), folderFiles(doc.id))
    }

    @Test
    fun `a page with neither an original nor a filter is cropped from itself`() {
        val (id, page) = newPage(keepOriginal = false)
        val recropped = library.recropPage(id, page.id, quad)!!.pages.single()
        assertEquals("norm3200@80(page)", text(id, recropped.original))
        assertEquals("crop0(page)", text(id, recropped.image))
        assertEquals(recropped.files.toSet(), folderFiles(id))
    }
}
