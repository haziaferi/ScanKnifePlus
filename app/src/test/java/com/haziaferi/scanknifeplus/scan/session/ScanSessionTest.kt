package com.haziaferi.scanknifeplus.scan.session

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The scan session (OpenScan live_scan_screen.dart and directory_cubit.dart createImage). The fake capture writer stores a shot's own text as
 * the page, so each test can read back which shot became which page; a shot whose text starts with "bad" cannot be stored, and one whose text
 * starts with "slow" waits for [gate] first.
 */
@RunWith(RobolectricTestRunner::class)
class ScanSessionTest {
    private val root: File = Files.createTempDirectory("session").toFile()
    private val library = root.resolve("library")
    private val staging = root.resolve("staging").apply { mkdirs() }
    private var now = 1_791_590_400_000L
    private val gate = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val quads = Collections.synchronizedList(mutableListOf<Quad?>())
    private val originals = Collections.synchronizedList(mutableListOf<Boolean>())
    private val clears = AtomicInteger()

    private val writer = CaptureWriter { source, quad, page, original ->
        val text = source.open().use { it.readBytes().decodeToString() }
        quads += quad
        originals += original != null
        if (text.startsWith("slow")) {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        if (text.startsWith("bad")) {
            null
        } else {
            page.writeText(text)
            original?.writeText("original $text")
            StoredCapture(page, original, cropped = quad != null, pageWidth = 1, pageHeight = 1)
        }
    }
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File) = true.also { dest.writeText("${filter.name}(${source.readText()})") }
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = false
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = false
    }
    private val scans = ScanLibrary(library, clock = { now++ }, capture = writer, images = images)
    private val quad = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))
    private val shots = AtomicInteger()

    @After
    fun cleanUp() {
        gate.countDown()
        root.deleteRecursively()
    }

    private fun session(documentId: String? = null, keepOriginal: Boolean = true, filter: String? = null) =
        ScanSession(scans, documentId, keepOriginal, filter, clearStaging = { clears.incrementAndGet(); true })

    private fun shot(text: String): File = staging.resolve("shot${shots.getAndIncrement()}.jpg").apply { writeText(text) }

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

    @Test
    fun `pages are stored in shutter order`() {
        val s = session()
        val texts = (1..8).map { "page $it" }
        texts.forEach { s.capture(shot(it), quad) }
        val result = s.finish()
        assertEquals(texts, pages(result.documentId))
        assertEquals(8, result.pagesAdded)
        assertEquals(List(8) { quad }, quads)
        assertEquals(result.documentId, s.state.value.documentId)
        assertEquals(scans.document(result.documentId!!)!!.pages.map { it.id }, s.state.value.pages)
        assertTrue(s.state.value.finished)
    }

    @Test
    fun `the shutter never waits for a page to be stored`() {
        val s = session()
        val start = System.nanoTime()
        s.capture(shot("slow 1"), quad)
        assertTrue(entered.await(10, TimeUnit.SECONDS)) // the first shot is being stored, and stays so until the gate opens
        s.capture(shot("page 2"), quad)
        s.capture(shot("page 3"), null)
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
        s.capture(shot("page 1"), null)
        s.idle()
        assertEquals(listOf(s.state.value.documentId), documents().map { it.id })
    }

    @Test
    fun `a session with no pages leaves no document behind`() {
        val result = session().finish()
        assertNull(result.documentId)
        assertEquals(0, result.pagesAdded)
        assertTrue(documents().isEmpty())
        assertEquals(1, clears.get())
    }

    @Test
    fun `a session whose every shot failed leaves no document behind`() {
        val s = session()
        s.capture(shot("bad 1"), quad)
        val result = s.finish()
        assertNull(result.documentId)
        assertTrue(documents().isEmpty())
        assertEquals(listOf(ShotFailure(0, ShotOrigin.CAMERA)), s.state.value.failures)
    }

    @Test
    fun `undo drops a shot still waiting`() {
        val s = session()
        s.capture(shot("slow 1"), quad)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val waiting = shot("page 2")
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
        s.capture(shot("slow 1"), quad)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertTrue(s.undoLast())
        assertEquals(0, s.state.value.count)
        gate.countDown()
        val result = s.finish()
        assertNull(result.documentId)
        assertTrue(documents().isEmpty())
    }

    @Test
    fun `undo deletes a stored page and its files`() {
        val s = session()
        s.capture(shot("page 1"), quad)
        s.capture(shot("page 2"), quad)
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
    fun `undo skips failed shots and stops at the session's own pages`() {
        val existing = scans.create().id
        scans.addCapture(existing, { "before".byteInputStream() }, null, keepOriginal = false)
        val s = session(existing)
        assertFalse(s.undoLast())
        s.capture(shot("page 1"), quad)
        s.capture(shot("bad 2"), quad)
        s.idle()
        assertTrue(s.undoLast()) // takes page 1, the failed shot is no page
        assertFalse(s.undoLast())
        val result = s.finish()
        assertEquals(existing, result.documentId)
        assertEquals(0, result.pagesAdded)
        assertEquals(listOf("before"), pages(existing))
    }

    @Test
    fun `undoing every page of a new document and shooting again starts a fresh one`() {
        val s = session()
        s.capture(shot("page 1"), quad)
        s.idle()
        assertTrue(s.undoLast())
        s.capture(shot("page 2"), quad)
        val result = s.finish()
        assertEquals(listOf("page 2"), pages(result.documentId))
        assertEquals(listOf(result.documentId), documents().map { it.id })
    }

    @Test
    fun `pages are added after an existing document's pages`() {
        val existing = scans.create().id
        scans.addCapture(existing, { "before".byteInputStream() }, null, keepOriginal = false)
        val s = session(existing)
        assertEquals(existing, s.state.value.documentId)
        s.capture(shot("page 1"), quad)
        val result = s.finish()
        assertEquals(ScanSessionResult(existing, 1), result)
        assertEquals(listOf("before", "page 1"), pages(existing))
    }

    @Test
    fun `failures are reported and the other shots still stored`() {
        val s = session()
        s.capture(shot("page 1"), quad)
        s.capture(shot("bad 2"), quad)
        s.import { "bad 3".byteInputStream() }
        s.capture(shot("page 4"), quad)
        val result = s.finish()
        assertEquals(listOf("page 1", "page 4"), pages(result.documentId))
        assertEquals(listOf(ShotFailure(1, ShotOrigin.CAMERA), ShotFailure(2, ShotOrigin.IMPORT)), s.state.value.failures)
    }

    @Test
    fun `camera shots are deleted once handled, whatever the outcome`() {
        val s = session()
        val handled = listOf(shot("page 1"), shot("bad 2"), shot("page 3"))
        handled.forEach { s.capture(it, quad) }
        s.undoLast()
        s.finish()
        handled.forEach { assertFalse(it.name, it.exists()) }
    }

    @Test
    fun `imports go through the same queue, whole and in order`() {
        val picked = root.resolve("picked.jpg").apply { writeText("picked") }
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        val s = session()
        s.capture(shot("page 1"), quad)
        assertTrue(s.import(resolver, listOf(Uri.fromFile(picked))))
        s.capture(shot("page 3"), quad)
        assertEquals(listOf("page 1", "picked", "page 3"), pages(s.finish().documentId))
        assertEquals(listOf(quad, null, quad), quads)
        assertTrue(picked.exists()) // a picked image is only read
    }

    @Test
    fun `keepOriginal and the default filter are passed to every page`() {
        val s = session(keepOriginal = true, filter = "Auto")
        s.capture(shot("page 1"), quad)
        val id = s.finish().documentId!!
        val page = scans.document(id)!!.pages.single()
        assertEquals("Auto", page.filter)
        assertEquals("Auto(page 1)", scans.file(id, page.image).readText())
        assertEquals("original page 1", scans.file(id, page.original!!).readText())

        val plain = session(id, keepOriginal = false)
        plain.capture(shot("page 2"), quad)
        plain.finish()
        val second = scans.document(id)!!.pages.last()
        assertNull(second.original)
        assertNull(second.filter)
        assertEquals(listOf(true, false), originals)
    }

    @Test
    fun `the default for keepOriginal is true`() {
        val s = ScanSession(scans)
        s.capture(shot("page 1"), quad)
        s.finish()
        assertEquals(listOf(true), originals)
    }

    @Test
    fun `finish waits for the queue, and nothing is taken after it`() {
        val s = session()
        s.capture(shot("slow 1"), quad)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        var result: ScanSessionResult? = null
        val done = thread { result = s.finish() }
        // Once finish has closed the session it waits on the stuck shot; the gate is still shut, so it cannot have returned.
        val until = System.currentTimeMillis() + 10_000
        while (true) {
            val late = shot("late")
            if (!s.capture(late, quad)) {
                assertFalse(late.exists()) // refused, and its file deleted
                break
            }
            s.undoLast() // accepted before finish closed the session: take it back again
            check(System.currentTimeMillis() < until)
            Thread.sleep(1)
        }
        assertTrue(done.isAlive)
        assertFalse(s.undoLast())
        gate.countDown()
        done.join(10_000)
        assertEquals(listOf("slow 1"), pages(result!!.documentId))
        assertEquals(1, clears.get())
    }

    @Test
    fun `shots racing with finish are either stored or refused, never lost`() {
        repeat(20) { round ->
            val s = session()
            val accepted = Collections.synchronizedList(mutableListOf<String>())
            val shooters = (0 until 4).map { t ->
                thread {
                    for (i in 0 until 25) {
                        val text = "r$round t$t i$i"
                        val file = shot(text)
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
        s.capture(shot("page 1"), quad)
        s.idle()
        s.capture(shot("slow 2"), quad)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        s.capture(shot("page 3"), quad)
        var result: ScanSessionResult? = null
        val cancelling = thread { result = s.cancel() }
        val until = System.currentTimeMillis() + 10_000
        while (s.capture(shot("late"), quad)) {
            s.undoLast()
            check(System.currentTimeMillis() < until)
            Thread.sleep(1)
        }
        gate.countDown()
        cancelling.join(10_000)
        assertEquals(ScanSessionResult(null, 0), result)
        assertTrue(documents().isEmpty())
        assertEquals(2, quads.size) // page 3 was still waiting, and was never stored
        assertNull(s.state.value.documentId)
        assertEquals(1, clears.get())
    }

    @Test
    fun `cancel keeps an existing document's earlier pages`() {
        val existing = scans.create().id
        scans.addCapture(existing, { "before".byteInputStream() }, null, keepOriginal = false)
        val s = session(existing)
        s.capture(shot("page 1"), quad)
        s.idle()
        s.capture(shot("page 2"), quad)
        assertEquals(ScanSessionResult(existing, 0), s.cancel())
        assertEquals(listOf("before"), pages(existing))
    }

    @Test
    fun `start clears only the staging folder when the session ends`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staged = File(ScanFiles.stagingDir(context), "shot.jpg").apply { writeText("x") }
        val other = File(ScanFiles.shareDir(context), "keep.pdf").apply { writeText("x") }
        val result = ScanSession.start(context).finish()
        assertNull(result.documentId)
        assertFalse(staged.exists())
        assertTrue(other.exists())
        assertNotNull(ScanFiles.stagingDir(context).list())
    }
}
