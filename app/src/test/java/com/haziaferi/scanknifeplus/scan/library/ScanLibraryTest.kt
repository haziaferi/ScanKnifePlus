package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric only for org.json; the capture step is faked here and exercised for real in ScanLibraryDeviceTest. */
@RunWith(RobolectricTestRunner::class)
class ScanLibraryTest {
    private val root: File = Files.createTempDirectory("library").toFile()
    private var now = 1_791_590_400_000L // 2026-10-10T00:00:00Z
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private var failNextCapture = false

    /** Writes small placeholder files, like a successful CaptureStore.store, unless told to fail. */
    private val fakeCapture = CaptureWriter { _, quad, page, original ->
        if (failNextCapture) {
            failNextCapture = false
            null
        } else {
            page.writeText("page")
            original?.writeText("original")
            StoredCapture(page, original, cropped = quad != null, pageWidth = 1, pageHeight = 1)
        }
    }
    private val library = ScanLibrary(root, clock = { now }, timeZone = utc, capture = fakeCapture)
    private val anySource = ImageSource { ByteArray(0).inputStream() }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `default names follow OpenScan's filesystem-safe scheme`() {
        assertEquals("ScanKnife-2026-10-10-1791590400000", ScanLibrary.defaultName(now, utc))
        assertTrue(ScanLibrary.defaultName(now, utc).matches(Regex("[A-Za-z0-9-]+")))
    }

    @Test
    fun `a created document is persisted and listed`() {
        val doc = library.create()
        assertEquals("ScanKnife-2026-10-10-1791590400000", doc.id)
        assertNull(doc.name)
        assertEquals(doc.id, doc.displayName)
        assertTrue(File(root, "${doc.id}/document.json").isFile)
        assertEquals(listOf(doc), ScanLibrary(root, capture = fakeCapture).documents())
    }

    @Test
    fun `documents created in the same millisecond get distinct folders`() {
        val a = library.create()
        val b = library.create()
        assertNotEquals(a.id, b.id)
        assertEquals("${a.id}-2", b.id)
    }

    @Test
    fun `captures become pages with unique files and optional originals`() {
        val doc = library.create()
        library.addCapture(doc.id, anySource, null, keepOriginal = true)
        val updated = library.addCapture(doc.id, anySource, null, keepOriginal = false)!!
        assertEquals(2, updated.pages.size)
        val (first, second) = updated.pages
        assertNotEquals(first.image, second.image)
        assertEquals("orig_${first.image}", first.original)
        assertNull(second.original)
        updated.pages.flatMap { it.files }.forEach { assertTrue(it, library.file(doc.id, it).isFile) }
        // Persisted: a fresh library over the same folder reads the same pages.
        assertEquals(updated.pages, ScanLibrary(root, capture = fakeCapture).document(doc.id)!!.pages)
    }

    @Test
    fun `a failed capture leaves the document and the folder unchanged`() {
        val doc = library.create()
        failNextCapture = true
        assertNull(library.addCapture(doc.id, anySource, null, keepOriginal = true))
        assertEquals(doc, library.document(doc.id))
        assertEquals(listOf("document.json"), File(root, doc.id).list()!!.toList())
    }

    @Test
    fun `pages can be reordered`() {
        val doc = library.create()
        repeat(3) { library.addCapture(doc.id, anySource, null, keepOriginal = false) }
        val ids = library.document(doc.id)!!.pages.map { it.id }
        assertEquals(listOf(ids[1], ids[2], ids[0]), library.movePage(doc.id, 0, 2)!!.pages.map { it.id })
        assertNull(library.movePage(doc.id, 0, 3))
    }

    @Test
    fun `deleting a page removes its files and the last page removes the document`() {
        val doc = library.create()
        library.addCapture(doc.id, anySource, null, keepOriginal = true)
        library.addCapture(doc.id, anySource, null, keepOriginal = true)
        val (first, second) = library.document(doc.id)!!.pages
        val after = library.deletePage(doc.id, first.id)!!
        assertEquals(listOf(second), after.pages)
        first.files.forEach { assertFalse(it, library.file(doc.id, it).exists()) }
        assertNull(library.deletePage(doc.id, second.id))
        assertFalse(File(root, doc.id).exists())
        assertTrue(library.documents().isEmpty())
    }

    @Test
    fun `renaming keeps the folder and a blank name restores the generated one`() {
        val doc = library.create()
        assertEquals("Tax 2026", library.rename(doc.id, "  Tax 2026 ")!!.displayName)
        assertEquals(doc.id, library.rename(doc.id, " ")!!.displayName)
        assertTrue(File(root, doc.id).isDirectory)
    }

    @Test
    fun `changes update the modified time and documents list newest first`() {
        val older = library.create()
        now += 60_000
        val newer = library.create()
        assertEquals(listOf(newer.id, older.id), library.documents().map { it.id })
        now += 60_000
        assertEquals(now, library.rename(older.id, "x")!!.modified)
    }

    @Test
    fun `unreadable records and unsafe ids are ignored`() {
        val good = library.create()
        File(root, "broken").mkdirs()
        File(root, "broken/document.json").writeText("{not json")
        File(root, "empty").mkdirs()
        assertEquals(listOf(good.id), library.documents().map { it.id })
        for (id in listOf("..", ".", "", "a/b", "a\\b")) {
            assertNull(id, library.document(id))
            assertFalse(id, library.delete(id))
        }
        assertTrue(root.isDirectory)
    }

    @Test
    fun `a record names its own folder even if copied elsewhere`() {
        val doc = library.create()
        File(root, doc.id).copyRecursively(File(root, "copy"))
        assertEquals("copy", library.document("copy")!!.id)
    }

    @Test
    fun `no temp record is left behind`() {
        val doc = library.create()
        library.rename(doc.id, "a")
        assertFalse(File(root, "${doc.id}/document.json.tmp").exists())
    }
}
