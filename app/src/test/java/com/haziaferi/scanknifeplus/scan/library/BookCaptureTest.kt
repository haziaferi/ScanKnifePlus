package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredBook
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.BookSplit
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Book mode in the library: one photo stored as two pages, both or neither, each with its own original, and their split moved later. The fake
 * book writer stores "left" and "right" as the pages and "original left" and "original right" as their originals, and reports [photoSpread] as
 * the spread in the original's coordinates, unless [failing] (it then writes the left page and gives up) or [noSecondOriginal]. The fake crop
 * fails once [cropsLeft] reaches 0.
 */
@RunWith(RobolectricTestRunner::class)
class BookCaptureTest {
    private val root: File = Files.createTempDirectory("book").toFile()
    private var now = 1_791_590_400_000L
    private var failing = false
    private var noSecondOriginal = false
    private val calls = mutableListOf<Triple<Quad?, Double, Int>>()
    private val photoSpread = Quad(Pt(0.2, 0.1), Pt(0.8, 0.12), Pt(0.82, 0.9), Pt(0.18, 0.88))
    private val crops = mutableListOf<Quad>()
    private var cropsLeft = Int.MAX_VALUE

    private val capture = CaptureWriter { _, _, page, original ->
        page.writeText("single")
        original?.writeText("single original")
        StoredCapture(page, original, cropped = false, pageWidth = 1, pageHeight = 1)
    }
    private val book = BookCaptureWriter { _, spread, split, quarterTurns, pages, originals ->
        calls += Triple(spread, split, quarterTurns)
        pages.first.writeText("left")
        if (failing) return@BookCaptureWriter null
        pages.second.writeText("right")
        originals?.first?.writeText("original left")
        if (!noSecondOriginal) originals?.second?.writeText("original right")
        val stored = listOf(
            StoredCapture(pages.first, originals?.first, cropped = true, pageWidth = 1, pageHeight = 1),
            StoredCapture(pages.second, originals?.second?.takeIf { !noSecondOriginal }, cropped = true, pageWidth = 1, pageHeight = 1),
        )
        StoredBook(stored, photoSpread)
    }
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File) = write(dest, "${filter.name}(${source.readText()})")
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File): Boolean {
            if (cropsLeft-- <= 0) return false
            crops += quad
            return write(dest, "crop$quarterTurns(${source.readText()})")
        }
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = false
        override fun rotate(source: File, quarterTurns: Int, dest: File) = write(dest, "turn$quarterTurns(${source.readText()})")

        private fun write(dest: File, text: String): Boolean {
            dest.writeText(text)
            return true
        }
    }
    private val library = ScanLibrary(root, clock = { now }, capture = capture, images = images, bookCapture = book)
    private val anySource = ImageSource { ByteArray(0).inputStream() }
    private val spread = Quad(Pt(0.1, 0.2), Pt(0.9, 0.2), Pt(0.9, 0.8), Pt(0.1, 0.8))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun text(id: String, name: String?) = library.file(id, name!!).readText()

    private fun folderFiles(id: String) = File(root, id).list()!!.filter { it != ScanLibrary.RECORD }.toSet()

    @Test
    fun `a spread becomes two last pages, left then right, each with its own original and the turn`() {
        val id = library.create().id
        library.addCapture(id, anySource, null, keepOriginal = true)
        val added = library.addBookCapture(id, anySource, spread, 0.4, 3, keepOriginal = true)!!
        assertEquals(listOf(Triple(spread, 0.4, 3)), calls)

        val pages = added.document.pages
        assertEquals(3, pages.size)
        assertEquals(pages.drop(1).map { it.id }, added.pageIds)
        val (left, right) = pages.drop(1)
        assertEquals("left", text(id, left.image))
        assertEquals("right", text(id, right.image))
        assertNotEquals(left.original, right.original)
        assertEquals("original left", text(id, left.original))
        assertEquals("original right", text(id, right.original))
        assertEquals(listOf(3, 3), listOf(left.quarterTurns, right.quarterTurns))
        assertEquals(BookCrop(photoSpread, 0.4, BookPage.LEFT, 3), left.book)
        assertEquals(BookCrop(photoSpread, 0.4, BookPage.RIGHT, 3), right.book)
        assertNull(pages[0].book)
        assertEquals(pages.flatMap { it.files }.toSet(), folderFiles(id))
        assertEquals(added.document, library.document(id))
    }

    @Test
    fun `negative turns are recorded as the same clockwise turn`() {
        val id = library.create().id
        val pages = library.addBookCapture(id, anySource, spread, 0.5, -1, keepOriginal = true)!!.document.pages
        assertEquals(listOf(3, 3), pages.map { it.quarterTurns })
    }

    @Test
    fun `without originals the pages already show the turn and record none`() {
        val id = library.create().id
        val pages = library.addBookCapture(id, anySource, null, 0.5, 1, keepOriginal = false)!!.document.pages
        assertEquals(listOf(null, null), pages.map { it.original })
        assertEquals(listOf(0, 0), pages.map { it.quarterTurns })
        assertEquals(listOf(null, null), pages.map { it.book })
        assertEquals(setOf(pages[0].image, pages[1].image), folderFiles(id))
    }

    @Test
    fun `a page whose original could not be copied records no turn`() {
        noSecondOriginal = true
        val id = library.create().id
        val (left, right) = library.addBookCapture(id, anySource, spread, 0.5, 1, keepOriginal = true)!!.document.pages
        assertEquals(1, left.quarterTurns)
        assertNull(right.original)
        assertEquals(0, right.quarterTurns)
        assertNull(right.book)
    }

    @Test
    fun `a spread that cannot be stored adds neither page and leaves no files`() {
        val id = library.create().id
        library.addCapture(id, anySource, null, keepOriginal = true)
        val before = library.document(id)!!
        failing = true
        assertNull(library.addBookCapture(id, anySource, spread, 0.5, 0, keepOriginal = true))
        assertEquals(before, library.document(id))
        assertEquals(before.pages.flatMap { it.files }.toSet(), folderFiles(id))
    }

    @Test
    fun `the default filter is applied to both pages`() {
        val id = library.create().id
        val added = library.addBookCapture(id, anySource, spread, 0.5, 0, keepOriginal = true, filter = "Grayscale")!!
        val (left, right) = added.document.pages
        assertEquals("Grayscale(left)", text(id, left.image))
        assertEquals("Grayscale(right)", text(id, right.image))
        assertEquals(added.document, library.document(id))
    }

    @Test
    fun `editing or deleting one page never touches the other`() {
        val id = library.create().id
        val (left, right) = library.addBookCapture(id, anySource, spread, 0.5, 1, keepOriginal = true)!!.document.pages

        val turned = library.rotatePage(id, right.id, 1)!!.pages[1]
        assertEquals(2, turned.quarterTurns)
        assertEquals(left, library.document(id)!!.pages[0])

        // A re-crop starts from the page's own original with its own turn.
        val recropped = library.recropPage(id, left.id, spread)!!.pages[0]
        assertEquals("crop1(original left)", text(id, recropped.image))
        assertEquals(left.original, recropped.original)
        assertNull(recropped.book) // cropped by hand, it is no longer half of a spread

        library.deletePage(id, left.id)
        val remaining = library.document(id)!!.pages.single()
        assertEquals(turned, remaining)
        assertEquals("original right", text(id, remaining.original))
        assertEquals(remaining.files.toSet(), folderFiles(id))
        assertTrue(File(root, id).isDirectory)
    }

    @Test
    fun `moving the split re-crops both pages from their own originals, each keeping its own turn`() {
        val id = library.create().id
        val (left, right) = library.addBookCapture(id, anySource, spread, 0.5, 1, keepOriginal = true, filter = "Grayscale")!!.document.pages
        library.rotatePage(id, right.id, 1)

        val (newLeft, newRight) = library.resplitBook(id, left.id, right.id, 0.3)!!.pages
        assertEquals(listOf(BookSplit.page(photoSpread, BookPage.LEFT, 0.3, 1), BookSplit.page(photoSpread, BookPage.RIGHT, 0.3, 1)), crops)
        assertEquals("crop1(original left)", text(id, newLeft.image))
        assertEquals("crop2(original right)", text(id, newRight.image))
        assertEquals(listOf(left.id, right.id), listOf(newLeft.id, newRight.id))
        assertEquals(listOf(1, 2), listOf(newLeft.quarterTurns, newRight.quarterTurns))
        assertEquals(listOf(0.3, 0.3), listOf(newLeft.book!!.split, newRight.book!!.split))
        assertEquals(listOf(null, null), listOf(newLeft.filter, newRight.filter))
        assertEquals(listOf(left.original, right.original), listOf(newLeft.original, newRight.original))
        assertEquals(listOf(newLeft, newRight).flatMap { it.files }.toSet(), folderFiles(id))
    }

    @Test
    fun `a re-split whose second crop fails changes neither page and leaves no files`() {
        val id = library.create().id
        val (left, right) = library.addBookCapture(id, anySource, spread, 0.5, 0, keepOriginal = true)!!.document.pages
        val before = library.document(id)!!
        cropsLeft = 1
        assertNull(library.resplitBook(id, left.id, right.id, 0.3))
        assertEquals(before, library.document(id))
        assertEquals(before.pages.flatMap { it.files }.toSet(), folderFiles(id))
    }

    @Test
    fun `a re-split is refused for pages that are not the two halves of one spread`() {
        val id = library.create().id
        val single = library.addCapture(id, anySource, null, keepOriginal = true)!!.pageId
        val (left, right) = library.addBookCapture(id, anySource, spread, 0.5, 0, keepOriginal = true)!!.document.pages
        val (otherLeft, _) = library.addBookCapture(id, anySource, null, 0.5, 1, keepOriginal = true)!!.document.pages.drop(3)
        val before = library.document(id)!!
        assertNull(library.resplitBook(id, right.id, left.id, 0.3)) // swapped
        assertNull(library.resplitBook(id, single, right.id, 0.3)) // a plain page
        assertNull(library.resplitBook(id, otherLeft.id, right.id, 0.3)) // turned differently
        for (split in listOf(-0.1, 1.1, Double.NaN)) assertNull(library.resplitBook(id, left.id, right.id, split))
        assertNull(library.resplitBook(id, left.id, "p0", 0.3))
        assertTrue(crops.isEmpty())
        assertEquals(before, library.document(id))

        library.recropPage(id, left.id, spread)
        assertNull(library.resplitBook(id, left.id, right.id, 0.3)) // the left page was cropped by hand
    }

    @Test
    fun `the book crop survives a fresh read of the record`() {
        val id = library.create().id
        val pages = library.addBookCapture(id, anySource, spread, 0.25, 3, keepOriginal = true)!!.document.pages
        val record = File(File(root, id), ScanLibrary.RECORD).readText()
        assertTrue(record.contains("\"book\""))
        assertEquals(pages, ScanLibrary(root).document(id)!!.pages)
    }

    @Test
    fun `plain pages write no book field, and records without it or with a damaged one still read`() {
        val id = library.create().id
        library.addCapture(id, anySource, null, keepOriginal = true)
        val record = File(File(root, id), ScanLibrary.RECORD)
        assertFalse(record.readText().contains("book"))

        library.addBookCapture(id, anySource, spread, 0.5, 0, keepOriginal = true)
        val json = JSONObject(record.readText())
        val pages = json.getJSONArray("pages")
        pages.getJSONObject(1).getJSONObject("book").put("side", "MIDDLE")
        pages.getJSONObject(2).getJSONObject("book").put("spread", JSONArray(listOf(1, 2)))
        record.writeText(json.toString())
        val read = ScanLibrary(root).document(id)!!.pages
        assertEquals(3, read.size)
        assertEquals(listOf(null, null, null), read.map { it.book })
    }
}
