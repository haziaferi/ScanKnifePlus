package com.haziaferi.scanknifeplus.scan.session

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The scan session (OpenScan live_scan_screen.dart and directory_cubit.dart createImage). The fake capture writer stores a shot's own text as
 * the page, so each test can read back which shot became which page; a shot whose text contains "bad" cannot be stored, one with "slow" waits
 * for [gate] first, and filtering one with "held" waits for [filterGate].
 */
@RunWith(RobolectricTestRunner::class)
class ScanSessionTest {
    private val root: File = Files.createTempDirectory("session").toFile()
    private val library = root.resolve("library")
    private val staging = root.resolve("staging")
    private var now = 1_791_590_400_000L
    private val gate = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val quads = Collections.synchronizedList(mutableListOf<Quad?>())
    private val originals = Collections.synchronizedList(mutableListOf<Boolean>())

    private val writer = CaptureWriter { source, quad, page, original ->
        val text = source.open().use { it.readBytes().decodeToString() }
        quads += quad
        originals += original != null
        if ("slow" in text) {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        if ("bad" in text) {
            null
        } else {
            page.writeText(text)
            original?.writeText("original $text")
            StoredCapture(page, original, cropped = quad != null, pageWidth = 1, pageHeight = 1)
        }
    }
    private val filtering = CountDownLatch(1)
    private val filterGate = CountDownLatch(1)
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File): Boolean {
            val text = source.readText()
            if ("held" in text) {
                filtering.countDown()
                filterGate.await(10, TimeUnit.SECONDS)
            }
            dest.writeText("${filter.name}($text)")
            return true
        }
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = false
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = false
    }
    private val scans = ScanLibrary(library, clock = { now++ }, capture = writer, images = images)
    private val quad = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))
    private val counter = AtomicInteger()

    @After
    fun cleanUp() {
        gate.countDown()
        filterGate.countDown()
        library.setWritable(true)
        root.deleteRecursively()
    }

    private fun session(documentId: String? = null, keepOriginal: Boolean = true, filter: String? = null) =
        ScanSession(scans, staging.resolve("s${counter.getAndIncrement()}"), documentId, keepOriginal, filter)

    /** A camera shot as the camera writes it: a file in the session's own folder. */
    private fun ScanSession.shot(text: String): File = shotDir.resolve("shot${counter.getAndIncrement()}.jpg").apply { writeText(text) }

    private fun ScanSession.shoot(text: String, q: Quad? = quad) = capture(shot(text), q)

    /** The texts of document [id]'s pages, in order. */
    private fun pages(id: String?): List<String> = scans.document(id!!)!!.pages.map { scans.file(id, it.image).readText() }

    private fun documents() = scans.documents()

    /** Waits until the session has nothing pending. */
    private fun ScanSession.idle() {
        val until = System.currentTimeMillis() + 10_000
        while (state.value.pending > 0) {
            check(System.currentTimeMillis() < until) { "the queue never drained" }
            Thread.sleep(5)
        }
    }

    /** Runs [block] on another thread; [join] waits for it, fails if it did not finish, and rethrows whatever it threw. */
    private class Background<T>(block: () -> T) {
        @Volatile private var result: Result<T>? = null
        private val thread = thread { result = runCatching(block) }
        val isAlive get() = thread.isAlive

        fun join(): T {
            thread.join(10_000)
            assertFalse("still running", thread.isAlive)
            return result!!.getOrThrow()
        }
    }

    /** Waits until [s] refuses shots, i.e. until an ending has closed it; shots taken before that are taken back again. */
    private fun awaitClosed(s: ScanSession) {
        val until = System.currentTimeMillis() + 10_000
        while (true) {
            val late = s.shot("late")
            if (!s.capture(late, quad)) {
                assertFalse(late.exists()) // refused, and its file deleted
                return
            }
            s.undoLast()
            check(System.currentTimeMillis() < until) { "the session never closed" }
            Thread.sleep(1)
        }
    }

    @Test
    fun `pages are stored in shutter order`() {
        val s = session()
        val texts = (1..8).map { "page $it" }
        texts.forEach { s.shoot(it) }
        val result = s.finish()
        assertEquals(texts, pages(result.documentId))
        assertEquals(8, result.pagesAdded)
        assertEquals(SessionEnd.FINISHED, result.end)
        assertEquals(List(8) { quad }, quads)
        assertEquals(result.documentId, s.state.value.documentId)
        assertEquals(scans.document(result.documentId!!)!!.pages.map { it.id }, s.state.value.pages)
        assertTrue(s.state.value.finished)
        assertFalse(s.shotDir.exists())
    }

    @Test
    fun `the shutter never waits for a page to be stored`() {
        val s = session()
        val start = System.nanoTime()
        s.shoot("slow 1")
        assertTrue(entered.await(10, TimeUnit.SECONDS)) // the first shot is being stored, and stays so until the gate opens
        s.shoot("page 2")
        s.shoot("page 3", null)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000)
        assertEquals(3, s.state.value.pending)
        assertEquals(3, s.state.value.count)
        gate.countDown()
        assertEquals(listOf("slow 1", "page 2", "page 3"), pages(s.finish().documentId))
    }

    @Test
    fun `a new document appears with its first page`() {
        val s = session()
        assertNull(s.state.value.documentId)
        assertTrue(documents().isEmpty())
        s.shoot("page 1")
        s.idle()
        assertEquals(listOf(s.state.value.documentId), documents().map { it.id })
        s.finish()
    }

    @Test
    fun `a session with no pages leaves no document and no folder behind`() {
        val s = session()
        assertTrue(s.shotDir.isDirectory)
        val result = s.finish()
        assertEquals(ScanSessionResult(null, 0, SessionEnd.FINISHED), result)
        assertTrue(documents().isEmpty())
        assertFalse(s.shotDir.exists())
    }

    @Test
    fun `a session whose every shot failed leaves no document behind`() {
        val s = session()
        s.shoot("bad 1")
        val result = s.finish()
        assertNull(result.documentId)
        assertTrue(documents().isEmpty())
        assertEquals(listOf(ShotFailure(0, ShotOrigin.CAMERA)), s.state.value.failures)
    }

    @Test
    fun `undo drops a shot still waiting`() {
        val s = session()
        s.shoot("slow 1")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val waiting = s.shot("page 2")
        s.capture(waiting, quad)
        assertTrue(s.undoLast())
        assertEquals(1, s.state.value.pending)
        gate.countDown()
        assertEquals(listOf("slow 1"), pages(s.finish().documentId))
        assertFalse(waiting.exists())
        assertEquals(1, quads.size) // the undone shot was never stored at all
    }

    @Test
    fun `undo of the shot being stored removes its page once stored`() {
        val s = session()
        s.shoot("slow 1")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertTrue(s.undoLast())
        assertEquals(0, s.state.value.count)
        gate.countDown()
        val result = s.finish()
        assertNull(result.documentId)
        assertTrue(documents().isEmpty())
    }

    @Test
    fun `a shot undone while being stored is no failure when it then fails`() {
        val s = session()
        s.shoot("slow bad 1")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertTrue(s.undoLast())
        gate.countDown()
        s.finish()
        assertEquals(emptyList<ShotFailure>(), s.state.value.failures)
    }

    @Test
    fun `undo deletes a stored page and its files`() {
        val s = session()
        s.shoot("page 1")
        s.shoot("page 2")
        s.idle()
        val id = s.state.value.documentId!!
        val second = s.state.value.pages[1]
        assertTrue(s.undoLast())
        assertEquals(1, s.state.value.pages.size)
        val result = s.finish()
        assertEquals(listOf("page 1"), pages(id))
        assertEquals(1, result.pagesAdded)
        val left = File(library, id).list()!!.toSet()
        assertEquals(scans.document(id)!!.pages.flatMap { it.files }.toSet() + ScanLibrary.RECORD, left)
        assertFalse(scans.document(id)!!.pages.any { it.id == second })
    }

    @Test
    fun `undo takes back the session's own page even after another instance moved it`() {
        val existing = scans.create().id
        scans.addCapture(existing, { "before".byteInputStream() }, null, keepOriginal = false)
        val s = session(existing, filter = "Auto")
        s.shoot("held 1")
        assertTrue(filtering.await(10, TimeUnit.SECONDS)) // stored, and now being filtered
        assertEquals(2, ScanLibrary(library).movePage(existing, 1, 0)!!.pages.size) // the new page goes first, "before" last
        filterGate.countDown()
        s.idle()
        assertTrue(s.undoLast())
        s.cancel()
        assertEquals(listOf("before"), pages(existing))
    }

    @Test
    fun `a document that cannot be created fails the shot, and the session still ends`() {
        library.mkdirs()
        assumeTrue("needs a read-only folder: not on Windows, not as root", library.setWritable(false) && !File(library, "probe").mkdir())
        val s = session()
        assertTrue(s.import { "page 1".byteInputStream() })
        val result = Background { s.finish() }.join()
        assertNull(result.documentId)
        assertEquals(listOf(ShotFailure(0, ShotOrigin.IMPORT)), s.state.value.failures)
    }

    @Test
    fun `undo skips failed shots and stops at the session's own pages`() {
        val existing = scans.create().id
        scans.addCapture(existing, { "before".byteInputStream() }, null, keepOriginal = false)
        val s = session(existing)
        assertFalse(s.undoLast())
        s.shoot("page 1")
        s.shoot("bad 2")
        s.idle()
        assertTrue(s.undoLast()) // takes page 1, the failed shot is no page
        assertFalse(s.undoLast())
        val result = s.finish()
        assertEquals(ScanSessionResult(existing, 0, SessionEnd.FINISHED), result)
        assertEquals(listOf("before"), pages(existing))
    }

    @Test
    fun `undoing every page of a new document keeps it for the next shot`() {
        val s = session()
        s.shoot("page 1")
        s.idle()
        val id = s.state.value.documentId!!
        assertTrue(s.undoLast())
        s.shoot("page 2")
        val result = s.finish()
        assertEquals(id, result.documentId)
        assertEquals(listOf("page 2"), pages(id))
        assertEquals(listOf(id), documents().map { it.id })
    }

    @Test
    fun `an existing empty document survives undo and cancel`() {
        val empty = scans.create().id
        val s = session(empty)
        s.shoot("page 1")
        s.idle()
        assertTrue(s.undoLast())
        s.shoot("page 2")
        assertEquals(ScanSessionResult(empty, 1, SessionEnd.FINISHED), s.finish())
        assertEquals(listOf("page 2"), pages(empty))

        val again = scans.create().id
        val c = session(again)
        c.shoot("page 1")
        c.idle()
        assertEquals(ScanSessionResult(again, 0, SessionEnd.CANCELLED), c.cancel())
        assertEquals(emptyList<String>(), pages(again)) // still there, empty, as before the session
    }

    @Test
    fun `a page whose delete fails is kept and still counted`() {
        val existing = scans.create().id
        val s = session(existing)
        s.shoot("page 1")
        s.shoot("page 2")
        s.idle()
        // A folder where the record's temp file goes makes every record write fail.
        val blocker = File(File(library, existing), "document.json.tmp").apply { mkdir() }
        assertTrue(s.undoLast())
        val result = s.finish()
        blocker.delete()
        assertEquals(ScanSessionResult(existing, 2, SessionEnd.FINISHED), result)
        assertEquals(2, s.state.value.pages.size)
        assertEquals(listOf("page 1", "page 2"), pages(existing))
    }

    @Test
    fun `a cancel whose delete fails keeps the document it created with that page`() {
        val s = session()
        s.shoot("page 1")
        s.idle()
        val id = s.state.value.documentId!!
        val blocker = File(File(library, id), "document.json.tmp").apply { mkdir() }
        val result = s.cancel()
        blocker.delete()
        assertEquals(ScanSessionResult(id, 1, SessionEnd.CANCELLED), result)
        assertEquals(listOf("page 1"), pages(id))
    }

    @Test
    fun `pages are added after an existing document's pages`() {
        val existing = scans.create().id
        scans.addCapture(existing, { "before".byteInputStream() }, null, keepOriginal = false)
        val s = session(existing)
        assertEquals(existing, s.state.value.documentId)
        s.shoot("page 1")
        assertEquals(ScanSessionResult(existing, 1, SessionEnd.FINISHED), s.finish())
        assertEquals(listOf("before", "page 1"), pages(existing))
    }

    @Test
    fun `failures are reported and the other shots still stored`() {
        val s = session()
        s.shoot("page 1")
        s.shoot("bad 2")
        s.import { "bad 3".byteInputStream() }
        s.shoot("page 4")
        val result = s.finish()
        assertEquals(listOf("page 1", "page 4"), pages(result.documentId))
        assertEquals(listOf(ShotFailure(1, ShotOrigin.CAMERA), ShotFailure(2, ShotOrigin.IMPORT)), s.state.value.failures)
    }

    @Test
    fun `camera shots are deleted once handled, whatever the outcome`() {
        val s = session()
        // Shots outside the session's folder too, which the folder's removal would not catch.
        val handled = listOf("page 1", "bad 2", "page 3").map { text -> root.resolve("$text.jpg").apply { writeText(text) } }
        handled.forEach { s.capture(it, quad) }
        s.undoLast()
        s.finish()
        handled.forEach { assertFalse(it.name, it.exists()) }
    }

    @Test
    fun `imports go through the same queue, whole and in order`() {
        val picked = root.resolve("picked.jpg").apply { writeText("picked") }
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val s = session()
        s.shoot("page 1")
        assertTrue(s.import(resolver, listOf(Uri.fromFile(picked))))
        s.shoot("page 3")
        assertEquals(listOf("page 1", "picked", "page 3"), pages(s.finish().documentId))
        assertEquals(listOf(quad, null, quad), quads)
        assertTrue(picked.exists()) // a picked image is only read
    }

    @Test
    fun `keepOriginal and the default filter are passed to every page`() {
        val s = session(keepOriginal = true, filter = "Auto")
        s.shoot("page 1")
        val id = s.finish().documentId!!
        val page = scans.document(id)!!.pages.single()
        assertEquals("Auto", page.filter)
        assertEquals("Auto(page 1)", scans.file(id, page.image).readText())
        assertEquals("original page 1", scans.file(id, page.original!!).readText())

        val plain = session(id, keepOriginal = false)
        plain.shoot("page 2")
        plain.finish()
        val second = scans.document(id)!!.pages.last()
        assertNull(second.original)
        assertNull(second.filter)
        assertEquals(listOf(true, false), originals)
    }

    @Test
    fun `the default for keepOriginal is true`() {
        val s = ScanSession(scans, staging.resolve("default"))
        s.shoot("page 1")
        s.finish()
        assertEquals(listOf(true), originals)
    }

    @Test
    fun `finish waits for the queue, and nothing is taken after it`() {
        val s = session()
        s.shoot("slow 1")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val done = Background { s.finish() }
        awaitClosed(s)
        assertTrue(done.isAlive) // the gate is still shut, so finish cannot have returned
        assertFalse(s.undoLast())
        gate.countDown()
        val result = done.join()
        assertEquals(listOf("slow 1"), pages(result.documentId))
        assertFalse(s.shotDir.exists())
    }

    @Test
    fun `shots racing with finish are either stored or refused, never lost`() {
        repeat(20) { round ->
            val s = session()
            val accepted = Collections.synchronizedList(mutableListOf<String>())
            val shooters = (0 until 4).map { t ->
                Background {
                    for (i in 0 until 25) {
                        val text = "r$round t$t i$i"
                        // Not in the session's folder, which goes as soon as finish has waited for the queue.
                        val file = root.resolve("race").apply { mkdirs() }.resolve("$text.jpg").apply { writeText(text) }
                        if (s.capture(file, quad)) accepted += text else assertFalse(file.exists())
                    }
                }
            }
            Thread.sleep(2)
            val result = s.finish()
            shooters.forEach { it.join() }
            val stored = result.documentId?.let { pages(it) }.orEmpty()
            assertEquals(accepted.toSet(), stored.toSet())
            assertEquals(stored.size, result.pagesAdded)
            // Each shooter's own shots keep their order.
            (0 until 4).forEach { t -> assertEquals(accepted.filter { it.contains(" t$t ") }, stored.filter { it.contains(" t$t ") }) }
        }
    }

    @Test
    fun `cancel removes the session's pages and the document it created`() {
        val s = session()
        s.shoot("page 1")
        s.idle()
        s.shoot("slow 2")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        s.shoot("page 3")
        val cancelling = Background { s.cancel() }
        awaitClosed(s)
        gate.countDown()
        assertEquals(ScanSessionResult(null, 0, SessionEnd.CANCELLED), cancelling.join())
        assertTrue(documents().isEmpty())
        assertEquals(2, quads.size) // page 3 was still waiting, and was never stored
        assertNull(s.state.value.documentId)
        assertFalse(s.shotDir.exists())
    }

    @Test
    fun `cancel keeps an existing document's earlier pages`() {
        val existing = scans.create().id
        scans.addCapture(existing, { "before".byteInputStream() }, null, keepOriginal = false)
        val s = session(existing)
        s.shoot("page 1")
        s.idle()
        s.shoot("page 2")
        assertEquals(ScanSessionResult(existing, 0, SessionEnd.CANCELLED), s.cancel())
        assertEquals(listOf("before"), pages(existing))
    }

    @Test
    fun `the first ending stands and says so`() {
        val finished = session()
        finished.shoot("page 1")
        val kept = finished.finish()
        assertEquals(kept, finished.cancel())
        assertEquals(listOf("page 1"), pages(kept.documentId))

        val cancelled = session()
        cancelled.shoot("page 2")
        assertEquals(ScanSessionResult(null, 0, SessionEnd.CANCELLED), cancelled.cancel())
        assertEquals(ScanSessionResult(null, 0, SessionEnd.CANCELLED), cancelled.finish())
        assertEquals(listOf(kept.documentId), documents().map { it.id })
    }

    @Test
    fun `close cancels a session that was not ended, and nothing else`() {
        val dropped = session()
        dropped.use { it.shoot("page 1") }
        assertTrue(documents().isEmpty())
        assertTrue(dropped.state.value.finished)

        val done = session()
        done.use {
            it.shoot("page 2")
            it.finish()
        }
        assertEquals(1, documents().size)
    }

    @Test
    fun `ending one session leaves another's shots alone`() {
        val a = session()
        val b = session()
        val waiting = b.shot("slow b")
        b.capture(waiting, quad)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        a.shoot("page a")
        a.finish()
        assertTrue(waiting.exists())
        assertTrue(b.shotDir.isDirectory)
        gate.countDown()
        assertEquals(listOf("slow b"), pages(b.finish().documentId))
    }

    @Test
    fun `start sweeps only folders of sessions no longer running`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stagingDir = ScanFiles.stagingDir(context)
        val stale = File(stagingDir, "session-stale").apply { mkdirs() }
        File(stale, "lost.jpg").writeText("x")
        val loose = File(stagingDir, "loose.jpg").apply { writeText("x") }
        val other = File(ScanFiles.shareDir(context), "keep.pdf").apply { writeText("x") }

        val first = ScanSession.start(context)!!
        val running = first.shot("still queued")
        assertFalse(stale.exists())
        assertEquals(stagingDir, first.shotDir.parentFile)

        val second = ScanSession.start(context)!!
        assertTrue(running.exists()) // a running session's folder is not stale
        assertEquals(ScanSessionResult(null, 0, SessionEnd.FINISHED), second.finish())
        assertFalse(second.shotDir.exists())
        assertTrue(first.shotDir.exists())
        first.cancel()
        assertFalse(first.shotDir.exists())
        assertTrue(loose.exists())
        assertTrue(other.exists())
    }
}
