package com.haziaferi.scanknifeplus.ui.folder

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ledger L8: choosing a folder moves everything kept in the app into it. Each file is copied first and leaves the app only once the folder
 * holds it whole; a scan's PDF goes in as its scan's PDF; a move the folder stops keeps every file and its row, and finishes at a later launch.
 */
@RunWith(RobolectricTestRunner::class)
class AppFolderMoveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)
    private val base: File = Files.createTempDirectory("app-folder-move").toFile()
    private val folderRoot = File(base, "Scans").apply { mkdirs() }
    private val library = ScanLibrary(ScanFiles.libraryDir(context), capture = CaptureWriter { _, _, page, original ->
        page.writeText("page")
        original?.writeText("original")
        StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
    })
    private val made = mutableListOf<String>()
    private val pdfs = PdfMaker { id, deliver ->
        made += id
        deliver(File(base, "out.pdf").apply { writeText("made for $id") })
    }
    private val files get() = SavedFiles.of(context)

    @Before
    fun setUp() = TestDocumentsProvider.install(folderRoot)

    @After
    fun cleanUp() {
        for (name in listOf("scan_folder", "folder_setup")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "scan-folder").deleteRecursively()
        ScanFiles.libraryDir(context).deleteRecursively()
        base.deleteRecursively()
        SavedFilesProvider.forget(context)
    }

    private fun appFolder(pdf: PdfMaker = pdfs) = AppFolder(
        context, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, FolderAccess(context), library, pdf, sweep = {}
    ).also { it.start() }

    private fun AppFolder.chooseNow(): Boolean {
        var adopted: Boolean? = null
        choose(treeUri) { adopted = it }
        return adopted!!
    }

    /** A tool's output kept in the app as [name], holding [content]. */
    private fun keep(name: String, content: String = "pdf $name"): SavedEntry {
        val file = files.create(name)!!.apply { writeText(content) }
        return files.record(files.uriOf(file), name, "merge", 1)
    }

    private fun fileInApp(entry: SavedEntry) = File(files.dir, entry.file!!)

    @Test
    fun `choosing a folder moves the files kept in the app into it, then empties the app, and says so once`() {
        val merged = keep("merged.pdf")
        val split = keep("split.pdf")
        val folder = appFolder()
        assertEquals(2, folder.appFiles)

        assertTrue(folder.chooseNow())
        assertEquals("pdf merged.pdf", File(folderRoot, "merged.pdf").readText())
        assertEquals("pdf split.pdf", File(folderRoot, "split.pdf").readText())
        assertFalse(fileInApp(merged).exists())
        assertFalse(fileInApp(split).exists())
        val rows = files.entries()
        assertEquals(listOf(SavedPlace.FOLDER, SavedPlace.FOLDER), rows.map { it.place })
        assertTrue(rows.all { it.uri != null && DocumentsContract.isTreeUri(it.uri!!) })
        assertEquals(0, folder.appFiles)
        assertEquals(FolderNotice.Copied(written = 0, failed = 0, moved = 2, unmoved = 0), folder.notice)
        assertEquals("Moved 2 files to your folder.", noticeText(context.resources, folder.notice!!))
    }

    @Test
    fun `a file whose name the folder has already goes in under a new name, and the folder's file stays`() {
        File(folderRoot, "merged.pdf").writeText("the user's")
        keep("merged.pdf")
        assertTrue(appFolder().chooseNow())
        assertEquals("the user's", File(folderRoot, "merged.pdf").readText())
        assertEquals("pdf merged.pdf", File(folderRoot, "merged-2.pdf").readText())
    }

    @Test
    fun `a file that fails to copy stays in the app with its row, the others move, and the next launch moves it`() {
        val merged = keep("merged.pdf")
        val split = keep("split.pdf")
        // A folder in the way of merged.pdf's .tmp makes its copy fail while the folder itself still answers.
        val blocker = File(folderRoot, "merged.pdf.tmp").apply { mkdirs() }
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        assertTrue("kept in the app", fileInApp(merged).isFile)
        assertEquals(SavedPlace.APP, files.entries().single { it.id == merged.id }.place)
        assertFalse(File(folderRoot, "merged.pdf").exists())
        assertFalse(fileInApp(split).exists())
        assertEquals("pdf split.pdf", File(folderRoot, "split.pdf").readText())
        assertEquals(FolderNotice.Copied(written = 0, failed = 0, moved = 1, unmoved = 1), folder.notice)
        assertEquals(
            "1 file couldn't be moved to your folder. It stays in the app; the move tries again at the next launch.",
            noticeText(context.resources, folder.notice!!)
        )

        blocker.delete()
        val relaunched = appFolder()
        assertEquals(FolderNotice.Copied(written = 0, failed = 0, moved = 1, unmoved = 0), relaunched.notice)
        assertFalse(fileInApp(merged).exists())
        assertEquals("pdf merged.pdf", File(folderRoot, "merged.pdf").readText())
        assertTrue(files.entries().all { it.place == SavedPlace.FOLDER })
    }

    @Test
    fun `a folder lost halfway leaves the rest in the app and says how many stay`() {
        keep("a.pdf")
        keep("b.pdf")
        // Released while the scans are written, before any file is copied; the scan keeps the move from starting on the files.
        val doc = library.create("Lease")
        library.addCapture(doc.id, ImageSource { ByteArray(0).inputStream() }, null, keepOriginal = true)
        val losing = PdfMaker { id, deliver ->
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            TestDocumentsProvider.crash = true
            deliver(File(base, "out.pdf").apply { writeText("pdf of $id") })
        }
        val folder = appFolder(losing)
        assertTrue(folder.chooseNow())
        assertEquals(FolderNotice.CopyStopped(left = 1, files = 2), folder.notice)
        assertEquals(
            "Moving stopped: your folder can't be reached. 3 files stay in the app; the move tries again at the next launch.",
            noticeText(context.resources, folder.notice!!)
        )
        assertEquals(2, files.entries().count { it.place == SavedPlace.APP && fileInApp(it).isFile })

        // The next launch, with the folder back, finishes the move.
        TestDocumentsProvider.crash = false
        assertTrue(FolderAccess(context).adopt(treeUri))
        appFolder()
        assertEquals("pdf a.pdf", File(folderRoot, "a.pdf").readText())
        assertEquals("pdf b.pdf", File(folderRoot, "b.pdf").readText())
        assertEquals(0, files.entries().count { it.place == SavedPlace.APP })
    }

    @Test
    fun `a scan's PDF kept in the app goes in as its scan's PDF, as saved, and leaves the app once written back`() {
        val doc = library.create("Lease")
        library.addCapture(doc.id, ImageSource { ByteArray(0).inputStream() }, null, keepOriginal = true)
        val pdf = files.create("Lease.pdf")!!.apply { writeText("saved with a password and its text") }
        val row = files.record(files.uriOf(pdf), "Lease.pdf", SCAN_ENTRY_TOOL, 1, scan = doc.id)

        assertTrue(appFolder().chooseNow())
        assertEquals("saved with a password and its text", File(folderRoot, "Lease.pdf").readText())
        assertTrue(File(folderRoot, "Scan sources/Lease/document.json").isFile)
        assertEquals("the saved PDF was used, not one made again", emptyList<String>(), made)
        assertFalse(pdf.exists())
        assertTrue(files.entries().none { it.id == row.id })
    }

    @Test
    fun `nothing kept in the app is lost when the folder cannot be reached as the move starts`() {
        val kept = keep("merged.pdf")
        assertTrue(FolderAccess(context).adopt(treeUri))
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val folder = appFolder()
        assertEquals(FolderStatus.ChooseAgain("Scans"), folder.status)
        folder.copyScansIn()
        assertTrue(fileInApp(kept).isFile)
        assertEquals(listOf(SavedPlace.APP), files.entries().map { it.place })
        assertEquals(1, folder.appFiles)
    }

    @Test
    fun `files saved elsewhere are not moved`() {
        val picked = File(base, "picked.pdf").apply { writeText("picked") }
        files.record(android.net.Uri.fromFile(picked), "picked.pdf", "merge")
        assertTrue(appFolder().chooseNow())
        assertFalse(File(folderRoot, "picked.pdf").exists())
        assertEquals(listOf(SavedPlace.ELSEWHERE), files.entries().map { it.place })
    }
}
