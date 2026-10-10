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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The library sweep: what a crash leaves behind goes, while page files reserved by an edit in progress, files that may still be in use by
 * another process, and anything that could be the user's data stay. Ages are set with File.setLastModified relative to the fake clock.
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySweepTest {
    private val root: File = Files.createTempDirectory("sweep").toFile()
    private var now = 1_791_590_400_000L
    private val day = ScanLibrary.STALE_MILLIS

    /** Runs [meanwhile] (once) in the middle of the next image operation, after its reserved files exist. */
    private inner class FakeImages : PageImages {
        var failNext = false
        var throwNext = false
        var meanwhile: (() -> Unit)? = null

        override fun filter(source: File, filter: Filter, dest: File) = op(dest, "${filter.name}(${source.readText()})")

        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = op(dest, "crop(${source.readText()})")

        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = op(dest, "norm(${source.readText()})")

        private fun op(dest: File, content: String): Boolean {
            val hook = meanwhile
            meanwhile = null
            hook?.invoke()
            if (throwNext) {
                throwNext = false
                throw IllegalStateException("image work crashed")
            }
            if (failNext) {
                failNext = false
                return false
            }
            dest.writeText(content)
            return true
        }
    }

    private val images = FakeImages()
    private var captureMeanwhile: (() -> Unit)? = null
    private val capture = CaptureWriter { _, _, page, original ->
        page.writeText("page")
        original?.writeText("original")
        captureMeanwhile?.also { captureMeanwhile = null }?.invoke()
        StoredCapture(page, original, cropped = false, pageWidth = 1, pageHeight = 1)
    }
    private val library = ScanLibrary(root, clock = { now }, capture = capture, images = images)
    private val anySource = ImageSource { ByteArray(0).inputStream() }
    private val quad = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun File.aged(millis: Long): File = apply { assertTrue(path, setLastModified(now - millis)) }

    private fun File.stale(): File = aged(day + 60_000)

    /** Makes everything in document [id] (and the folder) look a day and a minute old. */
    private fun ageAll(id: String) {
        File(root, id).listFiles()!!.forEach { it.stale() }
        File(root, id).stale()
    }

    private fun names(id: String) = File(root, id).list()!!.toSet()

    private fun named(id: String) = library.document(id)!!.pages.flatMap { it.files }.toSet() + "document.json"

    private fun newPage(keepOriginal: Boolean = true): Pair<String, ScanPage> {
        val doc = library.create()
        return doc.id to library.addCapture(doc.id, anySource, null, keepOriginal)!!.pages.single()
    }

    @Test
    fun `stale files no record names are removed and everything else is kept`() {
        val (id, page) = newPage()
        library.addCapture(id, anySource, null, keepOriginal = false)
        library.applyFilter(id, page.id, "B&W")
        val before = library.document(id)!!
        val folder = File(root, id)
        val orphans = listOf("1000.jpg", "orig_1001.jpg", "unfilt_1002.jpg", "1003.jpg.tmp", "orig_1004.jpg.tmp", "document.json.tmp")
        orphans.forEach { File(folder, it).writeText("{\"half") }
        File(folder, "1005.jpg").createNewFile() // a zero-byte reservation
        val unknown = listOf("notes.txt", "1006.jpeg", "page.jpg", "orig_.jpg", "1007.jpg.bak")
        unknown.forEach { File(folder, it).writeText("mine") }
        File(folder, "thumbs").mkdir()
        File(folder, "thumbs/1008.jpg").writeText("x")
        ageAll(id)

        assertEquals(orphans.size + 1, library.sweep())
        assertEquals(named(id) + unknown + "thumbs", names(id))
        assertTrue(File(folder, "thumbs/1008.jpg").exists())
        assertEquals(before, library.document(id))
        assertEquals(0, library.sweep())
    }

    @Test
    fun `fresh orphans are kept until they are a day old`() {
        val (id, _) = newPage()
        val orphan = File(root, "$id/1000.jpg").apply { writeText("half") }.aged(day - 60_000)
        val future = File(root, "$id/1001.jpg").apply { writeText("half") }.aged(-day) // a clock that went back
        assertEquals(0, library.sweep())
        assertTrue(orphan.exists())
        now += 120_000
        assertEquals(1, library.sweep())
        assertFalse(orphan.exists())
        assertTrue(future.exists())
    }

    @Test
    fun `files reserved by a filter in progress survive any sweep, however old`() {
        val (id, page) = newPage()
        var reserved = emptySet<String>()
        images.meanwhile = {
            reserved = names(id) - named(id)
            assertEquals(2, reserved.size) // the filtered page and the unfiltered copy
            File(root, "$id/${reserved.first()}.tmp").writeText("being encoded")
            ageAll(id)
            assertEquals(0, library.sweep())
            assertEquals(0, ScanLibrary(root, clock = { now }).sweep()) // another instance in this process
            library.documents()
            assertTrue(names(id).containsAll(reserved))
        }
        assertNotNull(library.applyFilter(id, page.id, "Grayscale"))
        val edited = library.document(id)!!.pages.single()
        assertEquals(reserved, setOf(edited.image, edited.unfiltered))
        assertEquals("Grayscale(page)", library.file(id, edited.image).readText())
        // The edit is over, so the encoder's leftover temp file is an orphan like any other.
        assertEquals(1, library.sweep())
        assertEquals(named(id), names(id))
    }

    @Test
    fun `files reserved by a re-crop in progress survive a sweep`() {
        val (id, page) = newPage(keepOriginal = false)
        images.meanwhile = {
            assertEquals(2, (names(id) - named(id)).size) // the cropped page and the original made for it
            ageAll(id)
            assertEquals(0, library.sweep())
        }
        val edited = library.recropPage(id, page.id, quad)!!.pages.single()
        assertEquals("norm(page)", library.file(id, edited.original!!).readText())
        assertEquals("crop(page)", library.file(id, edited.image).readText())
        assertEquals(named(id), names(id))
    }

    @Test
    fun `files reserved by a capture in progress survive a sweep`() {
        val doc = library.create()
        captureMeanwhile = {
            assertEquals(2, (names(doc.id) - named(doc.id)).size) // the page and its original
            ageAll(doc.id)
            assertEquals(0, library.sweep())
        }
        val page = library.addCapture(doc.id, anySource, null, keepOriginal = true)!!.pages.single()
        assertEquals("page", library.file(doc.id, page.image).readText())
        assertEquals("original", library.file(doc.id, page.original!!).readText())
    }

    @Test
    fun `holds end with the edit, also when it fails`() {
        val (id, page) = newPage()
        var reserved = emptySet<String>()
        images.meanwhile = { reserved = names(id) - named(id) }
        images.failNext = true
        assertNull(library.applyFilter(id, page.id, "B&W"))
        assertEquals(2, reserved.size)
        // Were the names still held, these stale files would be kept.
        reserved.forEach { File(root, "$id/$it").writeText("half") }
        ageAll(id)
        assertEquals(2, library.sweep())
        assertEquals(named(id), names(id))
    }

    @Test
    fun `holds end with the edit, also when the image work throws`() {
        val (id, page) = newPage(keepOriginal = false)
        var reserved = emptySet<String>()
        images.meanwhile = { reserved = names(id) - named(id) }
        images.throwNext = true
        assertThrows(IllegalStateException::class.java) { library.recropPage(id, page.id, quad) }
        assertEquals(2, reserved.size) // the reserved crop and original, left behind by the throw
        assertTrue(names(id).containsAll(reserved))
        ageAll(id)
        assertEquals(2, library.sweep())
        assertEquals(named(id), names(id))
        assertEquals(page, library.document(id)!!.pages.single())
    }

    @Test
    fun `folders without a record go only when a crash during create plainly left them`() {
        fun folder(name: String, vararg files: Pair<String, String>) = File(root, name).apply {
            mkdirs()
            files.forEach { (n, text) -> File(this, n).apply { writeText(text) }.stale() }
            stale()
        }
        folder("empty")
        folder("zero-record", "document.json" to "")
        folder("blank-temp", "document.json.tmp" to " \n")
        folder("zero-filled", "document.json" to "\u0000\u0000\u0000\u0000") // what a crash can leave in a block never written
        folder("half-temp", "document.json.tmp" to "  {\"id\":\"half") // starts like JSON: may be damage worth keeping
        folder("lost-record", "1000.jpg" to "a page")
        folder("broken-with-page", "document.json" to "{not json", "1000.jpg" to "a page")
        folder("other-files", "document.json" to "", "readme.txt" to "mine")
        folder("tampered", "document.json" to """{"id":"t","name":null,"created":1,"modified":1,"pages":[{"id":"p","image":"../x"}]}""")
        folder("huge", "document.json" to "x".repeat((1 shl 20) + 1))
        folder("record-dir").also { File(it, "document.json").mkdir() }.stale()
        folder(".hidden")
        File(root, "fresh").mkdirs() // created now, for example by another process between its mkdir and its record
        File(root, ".trash-x-1").mkdirs()
        File(root, ".trash-x-1/1000.jpg").writeText("deleted")
        File(root, "stray.txt").writeText("mine")
        val emptyDoc = library.create().also { ageAll(it.id) }

        // empty; zero-record, blank-temp and zero-filled (file and folder each); the tombstone
        assertEquals(8, library.sweep())
        val kept = setOf(
            "half-temp", "lost-record", "broken-with-page", "other-files", "tampered", "huge", "record-dir", ".hidden", "fresh", "stray.txt",
            emptyDoc.id,
        )
        assertEquals(kept, root.list()!!.toSet())
        assertEquals("a page", File(root, "lost-record/1000.jpg").readText())
        assertEquals(listOf(emptyDoc), library.documents())
    }

    @Test
    fun `a record left only as its temp file is recovered, not swept`() {
        val (id, page) = newPage()
        val folder = File(root, id)
        File(folder, "document.json").copyTo(File(folder, "document.json.tmp"))
        assertTrue(File(folder, "document.json").delete())
        ageAll(id)
        assertEquals(0, library.sweep())
        // On disk, before anything else reads the document: the record is back and the page files it names are all there.
        assertEquals(page.files.toSet() + "document.json", names(id))
        assertEquals(page, library.document(id)!!.pages.single())
    }

    @Test
    fun `the first listing in a process sweeps, later ones do not`() {
        val (id, _) = newPage()
        val first = File(root, "$id/1000.jpg").apply { createNewFile() }.stale()
        library.documents()
        assertFalse(first.exists())
        val second = File(root, "$id/1001.jpg").apply { createNewFile() }.stale()
        ScanLibrary(root, clock = { now }).documents()
        assertTrue(second.exists())
        assertEquals(1, library.sweep())
    }

    @Test
    fun `a first sweep that fails does not fail the listing and is tried again`() {
        val (id, _) = newPage()
        var broken = true
        val flaky = ScanLibrary(root, clock = { if (broken) throw IllegalStateException("no clock") else now })
        val orphan = File(root, "$id/1000.jpg").apply { createNewFile() }.stale()
        assertEquals(listOf(id), flaky.documents().map { it.id })
        assertTrue(orphan.exists())
        broken = false
        assertEquals(listOf(id), flaky.documents().map { it.id })
        assertFalse(orphan.exists())
    }
}
