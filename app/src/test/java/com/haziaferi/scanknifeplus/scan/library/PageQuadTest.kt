package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredBook
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The quad a plain page records, so Crop starts from it: the detected one at capture, the new one after a re-crop. The fake capture writer
 * reports a crop only when given a quad; the fake page images always succeed.
 */
@RunWith(RobolectricTestRunner::class)
class PageQuadTest {
    private val root: File = Files.createTempDirectory("quad").toFile()
    private val capture = CaptureWriter { _, quad, page, original ->
        page.writeText("page")
        original?.writeText("original")
        StoredCapture(page, original, cropped = quad != null, pageWidth = 1, pageHeight = 1)
    }
    private val book = BookCaptureWriter { _, spread, _, _, pages, originals ->
        val stored = pages.toList().zip(originals!!.toList()) { page, original ->
            page.writeText("half")
            original.writeText("original")
            StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
        }
        StoredBook(stored, spread!!)
    }
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File) = true.also { dest.writeText("filtered") }
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = true.also { dest.writeText("cropped") }
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = true.also { dest.writeText("normalized") }
        override fun rotate(source: File, quarterTurns: Int, dest: File) = true.also { dest.writeText("turned") }
    }
    private val library = ScanLibrary(root, capture = capture, images = images, bookCapture = book)
    private val anySource = ImageSource { "photo".byteInputStream() }
    private val detected = Quad(Pt(0.1, 0.2), Pt(0.9, 0.15), Pt(0.85, 0.9), Pt(0.12, 0.88))
    private val edge = Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun record(id: String) = File(File(root, id), ScanLibrary.RECORD)

    @Test
    fun `a captured page records its detected quad, which survives a fresh read, a rotation and a filter`() {
        val id = library.create().id
        val page = library.addCapture(id, anySource, detected, keepOriginal = true)!!.document.pages.single()
        assertEquals(detected, page.quad)
        assertTrue(record(id).readText().contains("\"quad\""))
        assertEquals(detected, ScanLibrary(root).document(id)!!.pages.single().quad)
        library.rotatePage(id, page.id, 1)
        library.applyFilter(id, page.id, "Grayscale")
        assertEquals(detected, library.document(id)!!.pages.single().quad)
    }

    @Test
    fun `a re-crop records the new quad`() {
        val id = library.create().id
        val page = library.addCapture(id, anySource, detected, keepOriginal = true)!!.document.pages.single()
        val recropped = library.recropPage(id, page.id, edge)!!.pages.single()
        assertEquals(edge, recropped.quad)
        assertEquals(edge, ScanLibrary(root).document(id)!!.pages.single().quad)
    }

    @Test
    fun `pages without an original, uncropped pages and book pages record no quad, and write no field`() {
        val id = library.create().id
        library.addCapture(id, anySource, detected, keepOriginal = false)
        library.addCapture(id, anySource, null, keepOriginal = true)
        library.addBookCapture(id, anySource, detected, 0.5, 0, keepOriginal = true)
        assertEquals(listOf(null, null, null, null), library.document(id)!!.pages.map { it.quad })
        assertFalse(record(id).readText().contains("\"quad\""))
    }

    @Test
    fun `records without a quad or with a damaged one still read, without it`() {
        val id = library.create().id
        repeat(3) { library.addCapture(id, anySource, detected, keepOriginal = true) }
        val json = JSONObject(record(id).readText())
        val pages = json.getJSONArray("pages")
        pages.getJSONObject(0).remove("quad")
        pages.getJSONObject(1).put("quad", JSONArray(listOf(1, 2)))
        pages.getJSONObject(2).put("quad", "not a quad")
        record(id).writeText(json.toString())
        val read = ScanLibrary(root).document(id)!!.pages
        assertEquals(3, read.size)
        assertEquals(listOf(null, null, null), read.map { it.quad })
    }
}
