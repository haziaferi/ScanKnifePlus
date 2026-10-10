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
import org.junit.Assert.fail
import org.json.JSONObject
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

    /** Writes small placeholder files, like a successful CaptureStore.store; when told to fail, it writes them anyway and then reports failure. */
    private val fakeCapture = CaptureWriter { _, quad, page, original ->
        page.writeText("page")
        original?.writeText("original")
        if (failNextCapture) {
            failNextCapture = false
            null
        } else {
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
    fun `keepDocument leaves a document empty rather than deleting it`() {
        val doc = library.create()
        val page = library.addCapture(doc.id, anySource, null, keepOriginal = true)!!.pages.single()
        assertEquals(emptyList<ScanPage>(), library.deletePage(doc.id, page.id, keepDocument = true)!!.pages)
        assertEquals(listOf(ScanLibrary.RECORD), File(root, doc.id).list()!!.toList())
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

    @Test
    fun `records with page files outside their folder are refused`() {
        val doc = library.create()
        library.addCapture(doc.id, anySource, null, keepOriginal = false)
        val record = File(root, "${doc.id}/document.json")
        for (bad in listOf("../../x.xml", "document.json", "..", "a/b.jpg")) {
            val json = JSONObject(record.readText())
            json.getJSONArray("pages").getJSONObject(0).put("image", bad)
            File(root, "tampered").apply { deleteRecursively(); mkdirs() }
            File(root, "tampered/document.json").writeText(json.toString())
            assertNull(bad, library.document("tampered"))
            assertNull(bad, library.deletePage("tampered", "anything"))
        }
        for ((id, name) in listOf(".." to "x", doc.id to "..", doc.id to "../x", "a/b" to "x")) {
            try {
                library.file(id, name)
                fail("$id/$name must be refused")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `records with missing or mistyped fields are skipped`() {
        val good = library.create()
        val bodies = listOf(
            """{"id":"x","created":1,"modified":1}""",
            """{"id":"x","name":null,"created":"soon","modified":1,"pages":[]}""",
            """{"id":"x","name":null,"created":1,"modified":1,"pages":[{"id":"p"}]}""",
            """[]""",
        )
        bodies.forEachIndexed { i, body ->
            File(root, "bad$i").mkdirs()
            File(root, "bad$i/document.json").writeText(body)
        }
        assertEquals(listOf(good.id), library.documents().map { it.id })
    }

    @Test
    fun `a record left only as its synced temp file is recovered`() {
        val doc = library.rename(library.create().id, "kept")!!
        val folder = File(root, doc.id)
        File(folder, "document.json").renameTo(File(folder, "document.json.tmp"))
        assertEquals("kept", library.document(doc.id)!!.name)
        assertTrue(File(folder, "document.json").isFile)
    }

    @Test
    fun `deleted documents leave no tombstone behind`() {
        val doc = library.create()
        assertTrue(library.delete(doc.id))
        assertFalse(File(root, doc.id).exists())
        File(root, ".trash-leftover").mkdirs()
        library.documents()
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun `two instances adding pages at once neither lose pages nor share files`() {
        val doc = library.create()
        val other = ScanLibrary(root, clock = { now }, timeZone = utc, capture = fakeCapture)
        val threads = listOf(library, other).map { lib ->
            Thread { repeat(20) { lib.addCapture(doc.id, anySource, null, keepOriginal = true) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val pages = library.document(doc.id)!!.pages
        assertEquals(40, pages.size)
        assertEquals(80, pages.flatMap { it.files }.toSet().size)
    }
}
