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
import com.haziaferi.scanknifeplus.storage.TestDocumentsProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app's folder state over FolderAccess and a DocumentsProvider serving a folder named "Scans", with all work run inline. */
@RunWith(RobolectricTestRunner::class)
class AppFolderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)
    private val base: File = Files.createTempDirectory("app-folder").toFile()
    private val folderRoot = File(base, "Scans").apply { mkdirs() }
    private var shots = 0
    private val library = ScanLibrary(ScanFiles.libraryDir(context), capture = CaptureWriter { _, _, page, original ->
        shots++
        page.writeText("page $shots")
        original?.writeText("original $shots")
        StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
    })
    private val pdfs = PdfMaker { id, deliver -> deliver(File(base, "out.pdf").apply { writeText("pdf of $id") }) }

    @Before
    fun setUp() = TestDocumentsProvider.install(folderRoot)

    @After
    fun cleanUp() {
        base.deleteRecursively()
        ScanFiles.libraryDir(context).deleteRecursively()
    }

    private fun appFolder() = AppFolder(context, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, FolderAccess(context), library, pdfs)

    private fun scan(name: String) {
        val doc = library.create(name)
        library.addCapture(doc.id, ImageSource { ByteArray(0).inputStream() }, null, keepOriginal = true)
    }

    private fun AppFolder.chooseNow(): Boolean {
        var adopted: Boolean? = null
        choose(treeUri) { adopted = it }
        return adopted!!
    }

    @Test
    fun `choosing a folder copies the scans on this phone into it, once, and History lists them`() {
        scan("Lease")
        scan("Receipt")
        val folder = appFolder()
        assertTrue(folder.offersSetup)
        assertEquals(FolderStatus.NotChosen, folder.status)
        assertNull("no folder: History shows this session's outputs", folder.history)
        folder.refresh()
        assertEquals(2, folder.scansOnPhone)

        assertTrue(folder.chooseNow())
        assertFalse(folder.offersSetup)
        assertEquals(FolderStatus.Ready("Scans"), folder.status)
        assertEquals(FolderNotice.Copied(written = 2, failed = 0), folder.notice)
        assertTrue(File(folderRoot, "Lease.pdf").isFile)
        assertTrue(File(folderRoot, "Scan sources/Receipt/document.json").isFile)
        val items = folder.history!!.items!!
        assertEquals(setOf("Lease.pdf", "Receipt.pdf"), items.map { it.name }.toSet())
        assertTrue(items.all { it.kind == FolderKind.SCAN && it.uri != null })

        // Chosen again, the folder already holds them: nothing is written twice and nothing is said.
        folder.noticeShown()
        val before = folderRoot.walkTopDown().map { it.relativeTo(folderRoot).path }.toSet()
        assertTrue(folder.chooseNow())
        assertEquals(FolderNotice.Copied(written = 0, failed = 0), folder.notice)
        assertNull(noticeText(context.resources, folder.notice!!))
        assertEquals(before, folderRoot.walkTopDown().map { it.relativeTo(folderRoot).path }.toSet())
    }

    @Test
    fun `a folder whose grant is gone must be chosen again, and History shows no list`() {
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        folder.refresh()
        assertEquals(FolderStatus.ChooseAgain("Scans"), folder.status)
        assertEquals(HistoryFolder("Scans", null, unreadable = true), folder.history)
    }

    @Test
    fun `a picked folder that cannot be written to is refused and setup is still offered`() {
        TestDocumentsProvider.readOnly = true
        val folder = appFolder()
        assertFalse(folder.chooseNow())
        assertEquals(FolderStatus.NotChosen, folder.status)
        assertTrue(folder.offersSetup)
    }

    @Test
    fun `the launch work settles the folder's sync conflicts and reports them once`() {
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        File(folderRoot, "a.pdf").apply { writeText("mine") }.setLastModified(1_000_000)
        File(folderRoot, "a.sync-conflict-20261010-101010-ABCDEFG.pdf").apply { writeText("theirs") }.setLastModified(2_000_000)

        folder.start()
        assertEquals(FolderNotice.Conflicts(count = 1, keptCopies = 1), folder.notice)
        folder.noticeShown()
        val conflict = folder.history!!.items!!.single { it.kind == FolderKind.CONFLICT }
        assertEquals("a.pdf", conflict.originalName)
        assertEquals("a.pdf", conflict.original!!.name)

        folder.refresh()
        assertNull("reported once", folder.notice)
    }

    @Test
    fun `Not now stops setup from opening by itself`() {
        val folder = appFolder()
        assertTrue(folder.offersSetup)
        folder.dismissSetup()
        assertFalse(appFolder().offersSetup)
    }

    @Test
    fun `the notices say what happened in words`() {
        val res = context.resources
        assertEquals(
            "Settled 1 sync clash in your folder. The other version is kept as a conflict copy in History.",
            noticeText(res, FolderNotice.Conflicts(1, 1))
        )
        assertEquals("Settled 2 sync clashes in your folder; the versions were merged.", noticeText(res, FolderNotice.Conflicts(2, 0)))
        assertEquals("3 scans copied into your folder.", noticeText(res, FolderNotice.Copied(3, 0)))
        assertEquals(
            "1 scan couldn't be copied into your folder. It stays on this phone; choose the folder again to retry.",
            noticeText(res, FolderNotice.Copied(2, 1))
        )
        assertEquals(
            "Copying stopped: your folder can't be reached. 2 scans stay on this phone.",
            noticeText(res, FolderNotice.CopyStopped(2))
        )
    }
}
