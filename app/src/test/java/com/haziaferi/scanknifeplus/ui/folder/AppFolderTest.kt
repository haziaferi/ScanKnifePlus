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
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.PdfMaker
import com.haziaferi.scanknifeplus.storage.TestDocumentsProvider
import com.haziaferi.scanknifeplus.ui.scan.ScanFlowModel
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
        forgetFolder()
        base.deleteRecursively()
        ScanFiles.libraryDir(context).deleteRecursively()
    }

    private val sweeps = mutableListOf<String>()

    /** The app's folder over this test's library, with its launch work done unless [started] is false. */
    /**
     * Forgets the folder this test chose: Robolectric can hand a later test's activity the preferences instance this one wrote, so they are
     * emptied through it before the test ends.
     */
    private fun forgetFolder() {
        for (name in listOf("scan_folder", "folder_setup")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "scan-folder").deleteRecursively()
    }

    private fun appFolder(started: Boolean = true, pdf: PdfMaker = pdfs, events: MutableList<String> = sweeps) = AppFolder(
        context, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, FolderAccess(context), library, pdf, sweep = { events += "sweep" }
    ).also { if (started) it.start() }

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
        assertEquals(listOf("sweep"), sweeps)
        File(folderRoot, "a.pdf").apply { writeText("mine") }.setLastModified(1_000_000)
        File(folderRoot, "a.sync-conflict-20261010-101010-ABCDEFG.pdf").apply { writeText("theirs") }.setLastModified(2_000_000)

        folder.start()
        assertEquals("the sweep runs at each launch", listOf("sweep", "sweep"), sweeps)
        assertEquals(FolderNotice.Conflicts(count = 1, keptCopies = 1), folder.notice)
        folder.noticeShown()
        val conflict = folder.history!!.items!!.single { it.kind == FolderKind.CONFLICT }
        assertEquals("a.pdf", conflict.originalName)
        assertEquals("a.pdf", conflict.original!!.name)

        folder.refresh()
        assertNull("reported once", folder.notice)
    }

    @Test
    fun `the launch sweep runs once at start, and a scan session waits for it`() {
        val events = mutableListOf<String>()
        val folder = appFolder(started = false, events = events)
        val model = ScanFlowModel(
            context, library = library, startSession = { null.also { events += "session" } }, main = Dispatchers.Unconfined,
            io = Dispatchers.Unconfined, beforeSession = folder::awaitSwept
        )
        try {
            assertEquals("the session waits for the sweep", emptyList<String>(), events)
            folder.start()
            assertEquals(listOf("sweep", "session"), events)
        } finally {
            model.close()
        }
    }

    @Test
    fun `a ready folder that cannot be listed shows History's warning, never an empty list`() {
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        TestDocumentsProvider.crash = true
        folder.refresh()
        assertEquals(FolderStatus.Ready("Scans"), folder.status)
        assertEquals(HistoryFolder("Scans", null, unreadable = true), folder.history)
    }

    @Test
    fun `a copy that loses the folder halfway says it stopped and how many scans stay on the phone`() {
        scan("Lease")
        scan("Receipt")
        val losing = PdfMaker { id, deliver ->
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            TestDocumentsProvider.crash = true
            deliver(File(base, "out.pdf").apply { writeText("pdf of $id") })
        }
        val folder = appFolder(pdf = losing)
        assertTrue(folder.chooseNow())
        assertEquals(FolderNotice.CopyStopped(left = 2), folder.notice)
        assertFalse(File(folderRoot, "Lease.pdf").exists())

        // The next launch, with the folder back, runs the copy again; the one after has nothing left to do.
        TestDocumentsProvider.crash = false
        assertTrue(FolderAccess(context).adopt(treeUri))
        val relaunched = appFolder()
        assertEquals(FolderNotice.Copied(written = 2, failed = 0), relaunched.notice)
        assertTrue(File(folderRoot, "Lease.pdf").isFile)
        assertNull("copied once", appFolder().notice)
    }

    @Test
    fun `a copy the app was closed during runs again at the next launch`() {
        scan("Lease")
        assertTrue(FolderAccess(context).adopt(treeUri))
        PrefsCopyPending(context).set(true) // as left by a copy that never returned
        assertEquals(FolderNotice.Copied(written = 1, failed = 0), appFolder().notice)
        assertFalse(PrefsCopyPending(context).get())
    }

    @Test
    fun `a launch with no copy left unfinished copies nothing`() {
        scan("Lease")
        assertTrue(FolderAccess(context).adopt(treeUri))
        assertNull(appFolder().notice)
        assertFalse(File(folderRoot, "Lease.pdf").exists())
    }

    @Test
    fun `a copy leaves out the scans it is told to skip`() {
        scan("Lease")
        scan("Open")
        val folder = appFolder()
        var adopted: Boolean? = null
        folder.choose(treeUri, copyScans = false) { adopted = it }
        assertEquals(true, adopted)
        assertNull("nothing copied yet", folder.notice)
        val open = library.documents().single { it.displayName == "Open" }.id
        folder.copyScansIn(skip = setOf(open))
        assertEquals(FolderNotice.Copied(written = 1, failed = 0), folder.notice)
        assertTrue(File(folderRoot, "Lease.pdf").isFile)
        assertFalse(File(folderRoot, "Open.pdf").exists())
    }

    @Test
    fun `Not now stops setup from opening by itself`() {
        val folder = appFolder()
        assertTrue(folder.offersSetup)
        folder.dismissSetup()
        assertFalse(appFolder(started = false).offersSetup)
    }

    /** [AppFolder.copyOutput] for a tool output the picker saved as [name] in the cache, with its result. */
    private fun AppFolder.copyNow(name: String, content: String? = "pdf $name"): OutputCopy {
        val saved = File(context.cacheDir, name).apply { delete() }
        content?.let { saved.writeText(it) }
        var result: OutputCopy? = null
        copyOutput(Uri.fromFile(saved)) { result = it }
        return result!!
    }

    @Test
    fun `a tool output goes to the folder's top level under its own name, never over a file, and History lists it`() {
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        assertEquals(OutputCopy.COPIED, folder.copyNow("merged.pdf"))
        assertEquals(OutputCopy.COPIED, folder.copyNow("merged.pdf", "second"))
        assertEquals("pdf merged.pdf", File(folderRoot, "merged.pdf").readText())
        assertEquals("second", File(folderRoot, "merged-2.pdf").readText())
        assertEquals(setOf("merged.pdf", "merged-2.pdf"), folder.history!!.items!!.filter { it.kind == FolderKind.TOOL }.map { it.name }.toSet())
    }

    @Test
    fun `with no folder chosen a tool output is not copied`() {
        val folder = appFolder()
        assertEquals(OutputCopy.NO_FOLDER, folder.copyNow("merged.pdf"))
        assertEquals(emptyList<String>(), folderRoot.list()!!.toList())
    }

    @Test
    fun `a folder that cannot be reached asks to be chosen again and gets no copy`() {
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(OutputCopy.CHOOSE_AGAIN, folder.copyNow("merged.pdf"))
        assertFalse(File(folderRoot, "merged.pdf").exists())
        assertEquals(FolderStatus.ChooseAgain("Scans"), folder.status)
    }

    @Test
    fun `an output that cannot be read fails the copy and leaves the folder as it was`() {
        val folder = appFolder()
        assertTrue(folder.chooseNow())
        assertEquals(OutputCopy.FAILED, folder.copyNow("gone.pdf", content = null))
        assertFalse(File(folderRoot, "gone.pdf").exists())
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
            "Copying stopped: your folder can't be reached. 2 scans stay on this phone; the copy tries again at the next launch.",
            noticeText(res, FolderNotice.CopyStopped(2))
        )
    }
}
