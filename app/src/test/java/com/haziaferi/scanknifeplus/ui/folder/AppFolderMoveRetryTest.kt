package com.haziaferi.scanknifeplus.ui.folder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.export.ExportNames
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.PdfMaker
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.haziaferi.scanknifeplus.storage.TestDocumentsProvider
import com.paperknifeplus.app.testing.SavedFilesProvider
import com.paperknifeplus.app.ui.components.SCAN_ENTRY_TOOL
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The move into a folder after the L8 audit: a retry takes the copy an earlier attempt left instead of making a `-2`, an app copy that
 * cannot be deleted is never copied again, a copy that does not match stays out, one move runs at a time and Delete waits for it, and a
 * scan's PDFs, a scan the folder holds already and a list rebuilt after a damaged index each keep every file.
 */
@RunWith(RobolectricTestRunner::class)
class AppFolderMoveRetryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)
    private val base: File = Files.createTempDirectory("app-folder-retry").toFile()
    private val folderRoot = File(base, "Scans").apply { mkdirs() }
    private val library = ScanLibrary(ScanFiles.libraryDir(context), capture = CaptureWriter { _, _, page, original ->
        page.writeText("page")
        original?.writeText("original")
        StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
    })
    private val pdfs = PdfMaker { id, deliver -> deliver(File(base, "out.pdf").apply { writeText("made for $id") }) }
    private val files get() = SavedFiles.of(context)

    @Before
    fun setUp() = TestDocumentsProvider.install(folderRoot)

    @After
    fun cleanUp() {
        for (name in listOf("scan_folder", "folder_setup")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "scan-folder").deleteRecursively()
        ScanFiles.libraryDir(context).deleteRecursively()
        files.dir.setWritable(true)
        files.dir.listFiles().orEmpty().forEach { it.setWritable(true) }
        base.deleteRecursively()
        SavedFilesProvider.forget(context)
    }

    private fun appFolder(pdf: PdfMaker = pdfs, saved: SavedFiles = files) = AppFolder(
        context, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, FolderAccess(context), library, pdf, sweep = {}, files = saved
    ).also { it.start() }

    private fun AppFolder.chooseNow(): Boolean {
        var adopted: Boolean? = null
        choose(treeUri) { adopted = it }
        return adopted!!
    }

    private fun keep(name: String, content: String = "pdf $name", scan: String? = null): SavedEntry {
        val file = files.create(name)!!.apply { writeText(content) }
        return files.record(files.uriOf(file), file.name, if (scan != null) SCAN_ENTRY_TOOL else "merge", 1, scan)
    }

    private fun scan(name: String): String {
        val doc = library.create(name)
        library.addCapture(doc.id, ImageSource { ByteArray(0).inputStream() }, null, keepOriginal = true)
        return doc.id
    }

    private fun row(id: String) = files.entries().single { it.id == id }

    private fun top() = folderRoot.list().orEmpty().filter { !it.startsWith(".") && it != "Scan sources" }.toSet()

    @Test
    fun `a copy an attempt left before it was noted is taken, not copied again under -2`() {
        val merged = keep("merged.pdf")
        // As left by a move killed between its copy and its row: the folder has the file whole, the row still says it is in the app.
        File(folderRoot, "merged.pdf").writeText("pdf merged.pdf")
        assertTrue(appFolder().chooseNow())
        assertEquals(setOf("merged.pdf"), top())
        assertFalse(File(files.dir, "merged.pdf").exists())
        assertEquals(SavedPlace.FOLDER, row(merged.id).place)
    }

    @Test
    fun `a copy an attempt noted under -2 is taken, and the user's own file of the name stays as it is`() {
        val merged = keep("merged.pdf")
        File(folderRoot, "merged.pdf").writeText("the user's own")
        File(folderRoot, "merged-2.pdf").writeText("pdf merged.pdf")
        files.wrote(merged.id, "merged-2.pdf")
        assertTrue(appFolder().chooseNow())
        assertEquals(setOf("merged.pdf", "merged-2.pdf"), top())
        assertEquals("the user's own", File(folderRoot, "merged.pdf").readText())
        assertEquals(SavedPlace.FOLDER, row(merged.id).place)
    }

    @Test
    fun `a file of the same name but other content is never taken for the copy`() {
        val merged = keep("merged.pdf")
        File(folderRoot, "merged.pdf").writeText("pdf merged.PDF") // the same size, other bytes
        assertTrue(appFolder().chooseNow())
        assertEquals("pdf merged.PDF", File(folderRoot, "merged.pdf").readText())
        assertEquals("pdf merged.pdf", File(folderRoot, "merged-2.pdf").readText())
        assertEquals(SavedPlace.FOLDER, row(merged.id).place)
    }

    @Test
    fun `a copy whose size the folder changes stays out, and the file and its row stay in the app`() {
        val merged = keep("merged.pdf")
        TestDocumentsProvider.growOnRename = "merged.pdf"
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        assertTrue(File(files.dir, "merged.pdf").isFile)
        assertEquals(SavedPlace.APP, row(merged.id).place)
        assertEquals(FolderNotice.Copied(written = 0, failed = 0, moved = 0, unmoved = 1), folder.notice)
    }

    @Test
    fun `an app copy that cannot be deleted is never copied again, and the next launch deletes it`() {
        val merged = keep("merged.pdf")
        val inApp = File(files.dir, "merged.pdf")
        // An open handle stops the delete on Windows, a folder that cannot be written to on Linux.
        val open = inApp.inputStream()
        files.dir.setWritable(false)
        try {
            assertTrue(appFolder().chooseNow())
            assertTrue("the app copy could not go", inApp.exists())
            assertEquals(SavedPlace.FOLDER, row(merged.id).place)
            appFolder() // the next launch, still unable to delete it
            assertEquals("no second copy", setOf("merged.pdf"), top())
        } finally {
            files.dir.setWritable(true)
            open.close()
        }
        appFolder()
        assertFalse(inApp.exists())
        assertNull(row(merged.id).file)
        assertEquals(setOf("merged.pdf"), top())
    }

    @Test
    fun `every PDF of a scan kept in the app reaches the folder, the newest as its scan's and the other as a file`() {
        val id = scan("Lease")
        val old = keep("Lease.pdf", "the first save", scan = id)
        Thread.sleep(5) // a later time
        val new = keep("Lease.pdf", "the second save", scan = id)
        assertEquals("Lease-2.pdf", new.file)
        assertTrue(appFolder().chooseNow())
        val pdfs = folderRoot.listFiles().orEmpty().filter { it.isFile }.map { it.readText() }.toSet()
        assertTrue(pdfs.toString(), "the second save" in pdfs && "the first save" in pdfs)
        assertTrue(files.entries().none { it.place == SavedPlace.APP })
        assertFalse(File(files.dir, old.file!!).exists())
    }

    @Test
    fun `a scan's PDF whose scan the folder holds already stays in the app, held, and no launch moves it again`() {
        val id = scan("Lease")
        val folder = appFolder()
        assertTrue(folder.chooseNow()) // the scan goes in, confirmed
        val extra = keep("Lease.pdf", "saved in the app", scan = id)
        val relaunched = appFolder()
        assertTrue(File(files.dir, extra.file!!).isFile)
        assertTrue(row(extra.id).held)
        assertNotNull(relaunched.notice)
        assertNull("nothing left to move", appFolder().notice)
    }

    @Test
    fun `after a damaged index, a scan's PDF found in the app stays there instead of going in as a plain file, and grants are kept`() {
        val id = scan("Lease")
        val name = ExportNames.exportFileName("Lease", id) + ".pdf"
        files.dir.mkdirs()
        File(files.dir, name).writeText("saved scan")
        val picked = Uri.fromFile(File(base, "picked.pdf").apply { writeText("picked") })
        resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val index = File(context.filesDir, "saved-index.json").apply { writeText("{ not json") }
        val fresh = SavedFiles(files.dir, index, ScanFiles.authority(context), resolver)
        val folder = appFolder(saved = fresh)
        assertTrue(folder.rebuilt)
        assertTrue(fresh.entries().single { it.file == name }.held)
        assertTrue("the grant of a file saved elsewhere is kept", resolver.persistedUriPermissions.any { it.uri == picked })
        assertTrue(folder.chooseNow())
        assertEquals("only the scan's own PDF", listOf("made for $id"), folderRoot.listFiles().orEmpty().filter { it.isFile }.map { it.readText() })
        assertTrue(File(files.dir, name).isFile)
    }

    /** A real io and a PDF maker that holds the first scan's PDF until [gate] opens, counting how many run at once. */
    private class Held(base: File) {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        private val active = AtomicInteger()
        @Volatile var most = 0
        val maker = PdfMaker { id, deliver ->
            most = maxOf(most, active.incrementAndGet())
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            try {
                deliver(File(base, "held-$id.pdf").apply { writeText("made for $id") })
            } finally {
                active.decrementAndGet()
            }
        }
    }

    /** The copy-pending flag as the moves set it, in order: each move sets it when it starts and again when it ends. */
    private val pending = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

    private val flag = object : CopyPending {
        override fun get() = false
        override fun set(pending: Boolean) {
            this@AppFolderMoveRetryTest.pending += pending
        }
    }

    private fun background(maker: PdfMaker): AppFolder {
        assertTrue(FolderAccess(context).adopt(treeUri))
        val folder = AppFolder(
            context, CoroutineScope(Dispatchers.IO), Dispatchers.IO, FolderAccess(context), library, maker, copyPending = flag, sweep = {}, files = files
        )
        folder.start()
        waitFor { folder.state is com.haziaferi.scanknifeplus.storage.FolderState.Ready && folder.scansOnPhone > 0 }
        return folder
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
    }

    @Test
    fun `one move at a time, so a second one never writes the same scan alongside the first`() {
        scan("Lease")
        val held = Held(base)
        val folder = background(held.maker)
        folder.copyScansIn()
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        folder.copyScansIn()
        Thread.sleep(300)
        held.gate.countDown()
        waitFor { pending.size >= 4 }
        // Started, ended, started, ended: the second move began only once the first was done.
        assertEquals(listOf(true, false, true, false), pending.toList())
        assertEquals(1, held.most)
        assertEquals(setOf("Lease.pdf"), folderRoot.list().orEmpty().filter { it.endsWith(".pdf") }.toSet())
    }

    @Test
    fun `Delete waits for a running move, and a file the move took is not deleted from the folder`() {
        scan("Lease")
        val merged = keep("merged.pdf")
        val held = Held(base)
        val folder = background(held.maker)
        folder.copyScansIn()
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        var deleted: Boolean? = null
        folder.deleteSaved(merged) { deleted = it }
        Thread.sleep(300)
        assertNull("Delete waits", deleted)
        assertTrue(File(files.dir, "merged.pdf").isFile)
        held.gate.countDown()
        waitFor { deleted != null }
        assertEquals(false, deleted)
        assertEquals("pdf merged.pdf", File(folderRoot, "merged.pdf").readText())
        assertEquals(SavedPlace.FOLDER, row(merged.id).place)
    }

    @Test
    fun `a file saved elsewhere into the folder itself is listed once, by the folder's listing`() {
        File(folderRoot, "picked.pdf").writeText("picked")
        val inside = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, "${TestDocumentsProvider.ROOT}/picked.pdf")
        val outside = Uri.fromFile(File(base, "outside.pdf").apply { writeText("outside") })
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        files.record(inside, "picked.pdf", "merge")
        files.record(outside, "outside.pdf", "merge")
        folder.refresh()
        assertEquals(listOf("outside.pdf"), folder.listed.map { it.name })
        assertEquals(1, folder.history!!.items!!.count { it.name == "picked.pdf" })
    }

    @Test
    fun `a file saved elsewhere into a subfolder of the folder stays listed, as the folder's listing does not show it`() {
        File(folderRoot, "Receipts").mkdirs()
        File(folderRoot, "Receipts/x.pdf").writeText("x")
        val sub = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, "${TestDocumentsProvider.ROOT}/Receipts/x.pdf")
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        files.record(sub, "x.pdf", "merge")
        folder.refresh()
        assertEquals(listOf("x.pdf"), folder.listed.map { it.name })
        assertEquals(0, folder.history!!.items!!.count { it.name == "x.pdf" })
    }

    @Test
    fun `a save during a running move is made in the app at once, and moves at the next launch`() {
        scan("Lease")
        val held = Held(base)
        val folder = background(held.maker)
        folder.copyScansIn()
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        val started = System.currentTimeMillis()
        val spot = folder.create("now.pdf")!!
        assertTrue("it did not wait for the move", System.currentTimeMillis() - started < 2_000)
        assertEquals(File(files.dir, "now.pdf"), files.fileOf(spot.uri))
        assertEquals(com.paperknifeplus.app.R.string.tool_saved_in_app_later, spot.notice)
        assertTrue(folder.moveRunning)
        held.gate.countDown()
        waitFor { !folder.moveRunning }
    }

    @Test
    fun `a second holder's move waits for an earlier holder's, and its Delete is held back meanwhile`() {
        scan("Lease")
        val merged = keep("merged.pdf")
        val held = Held(base)
        val first = background(held.maker)
        first.copyScansIn()
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        // The activity came back with a new holder while the first one's move still runs; its launch work waits for the folder.
        val second = AppFolder(
            context, CoroutineScope(Dispatchers.IO), Dispatchers.IO, FolderAccess(context), library, held.maker, copyPending = flag, sweep = {}, files = files
        )
        second.start()
        assertTrue(second.moveRunning)
        second.copyScansIn()
        var deleted: Boolean? = null
        second.deleteSaved(merged) { deleted = it }
        Thread.sleep(300)
        assertNull("Delete waits for the earlier holder's move", deleted)
        held.gate.countDown()
        waitFor { pending.size >= 4 && deleted != null && !second.moveRunning }
        Thread.sleep(300)
        // Each move started only once the one before had ended (the second holder's launch may add one more).
        val flags = pending.toList()
        assertEquals(flags.toString(), List(flags.size) { it % 2 == 0 }, flags)
        assertEquals(1, held.most)
        assertEquals(setOf("Lease.pdf", "merged.pdf"), top())
        assertEquals(false, deleted)
    }

    @Test
    fun `an index that cannot be read keeps a scan's PDF found in the app there, and every grant`() {
        val id = scan("Lease")
        val name = ExportNames.exportFileName("Lease", id) + ".pdf"
        files.dir.mkdirs()
        File(files.dir, name).writeText("saved scan")
        val picked = Uri.fromFile(File(base, "picked.pdf").apply { writeText("picked") })
        resolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val index = File(base, "unreadable-index.json").apply { mkdirs() } // a read fails every time
        val fresh = SavedFiles(files.dir, index, ScanFiles.authority(context), resolver)
        val folder = appFolder(saved = fresh)
        assertFalse(fresh.damaged)
        assertTrue(fresh.partial)
        assertTrue(fresh.entries().single { it.file == name }.held)
        assertTrue(resolver.persistedUriPermissions.any { it.uri == picked })
        assertTrue(folder.chooseNow())
        assertEquals("only the scan's own PDF", listOf("made for $id"), folderRoot.listFiles().orEmpty().filter { it.isFile }.map { it.readText() })
        assertTrue(File(files.dir, name).isFile)
    }
}
