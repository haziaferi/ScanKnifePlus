package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredBook
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.session.ScanSession
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Check crop mark: a camera page stored whole (no outline, or the warp failed) is marked until the user crops it. The fake capture writer
 * crops only when given a quad, and fails the warp of a photo whose text is "warp fails".
 */
@RunWith(RobolectricTestRunner::class)
class CheckCropTest {
    private val root: File = Files.createTempDirectory("check-crop").toFile()
    private val capture = CaptureWriter { source, quad, page, original ->
        val text = source.open().use { it.readBytes().decodeToString() }
        page.writeText(text)
        original?.writeText("original")
        StoredCapture(page, original, cropped = quad != null && text != "warp fails", pageWidth = 1, pageHeight = 1)
    }
    private val book = BookCaptureWriter { _, spread, _, _, pages, originals ->
        val stored = pages.toList().zip(originals!!.toList()) { page, original ->
            page.writeText("half")
            original.writeText("original")
            StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
        }
        StoredBook(stored, spread ?: whole)
    }
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File) = true.also { dest.writeText("filtered") }
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = true.also { dest.writeText("cropped") }
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = true.also { dest.writeText("normalized") }
        override fun rotate(source: File, quarterTurns: Int, dest: File) = true.also { dest.writeText("turned") }
    }
    private val library = ScanLibrary(root, capture = capture, images = images, bookCapture = book)
    private val detected = Quad(Pt(0.1, 0.2), Pt(0.9, 0.15), Pt(0.85, 0.9), Pt(0.12, 0.88))
    private val whole = Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun source(text: String) = ImageSource { text.byteInputStream() }

    private fun add(id: String, text: String, quad: Quad?, camera: Boolean = true) =
        library.addCapture(id, source(text), quad, keepOriginal = true, markUncropped = camera)!!.document.pages.last()

    @Test
    fun `a camera page without an outline or with a failed warp is marked, a cropped one and a picked image are not`() {
        val id = library.create().id
        assertTrue("no outline", add(id, "page", null).checkCrop)
        assertTrue("the warp failed", add(id, "warp fails", detected).checkCrop)
        assertFalse("cropped", add(id, "page", detected).checkCrop)
        assertFalse("a picked image is whole on purpose", add(id, "page", null, camera = false).checkCrop)
    }

    @Test
    fun `both pages of a book shot without an outline are marked`() {
        val id = library.create().id
        val whole = library.addBookCapture(id, source("book"), null, 0.5, 0, keepOriginal = true, markUncropped = true)!!.document.pages
        assertEquals(listOf(true, true), whole.map { it.checkCrop })
        val found = library.addBookCapture(id, source("book"), detected, 0.5, 0, keepOriginal = true, markUncropped = true)!!.document.pages
        assertEquals(listOf(false, false), found.drop(2).map { it.checkCrop })
    }

    @Test
    fun `the mark survives a fresh read, a rotation and a filter, and goes with a crop`() {
        val id = library.create().id
        val page = add(id, "page", null)
        assertTrue(ScanLibrary(root).document(id)!!.pages.single().checkCrop)
        library.rotatePage(id, page.id, 1)
        library.applyFilter(id, page.id, "Grayscale")
        assertTrue(library.document(id)!!.pages.single().checkCrop)
        library.recropPage(id, page.id, detected)
        assertFalse(library.document(id)!!.pages.single().checkCrop)
        assertFalse("written only when set", File(File(root, id), ScanLibrary.RECORD).readText().contains("checkCrop"))
    }

    @Test
    fun `a record without the field reads unmarked, so older records stay as they were`() {
        val id = library.create().id
        add(id, "page", detected)
        val record = File(File(root, id), ScanLibrary.RECORD)
        val json = JSONObject(record.readText())
        assertFalse(json.getJSONArray("pages").getJSONObject(0).has("checkCrop"))
        assertFalse(library.document(id)!!.pages.single().checkCrop)
    }

    @Test
    fun `the session marks camera shots only`() {
        val session = ScanSession(library, File(root, "staging").apply { mkdirs() })
        try {
            val shot = File(root, "shot.jpg").apply { writeText("page") }
            assertTrue(session.capture(shot, null))
            assertTrue(session.import(source("picked")))
            val deadline = System.currentTimeMillis() + 10_000
            while (session.state.value.pages.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            val pages = library.document(session.state.value.documentId!!)!!.pages
            assertEquals(listOf(true, false), pages.map { it.checkCrop })
        } finally {
            session.finish()
        }
    }
}
