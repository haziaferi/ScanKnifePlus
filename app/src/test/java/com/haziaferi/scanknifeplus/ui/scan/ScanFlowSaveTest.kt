package com.haziaferi.scanknifeplus.ui.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.export.PdfAssembler
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.scan.export.ScanPdfExporter
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.session.ScanSession
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.TestDocumentsProvider
import com.haziaferi.scanknifeplus.ui.folder.AppFolder
import com.haziaferi.scanknifeplus.ui.folder.pdfMaker
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Where ScanFlow's Done saves: into the user's folder (a DocumentsProvider serving a folder named "Scans"), with the picker when none is
 * chosen, after the first check when the folder has not been checked yet, and after choosing it again when it cannot be reached. The flow
 * runs on a real session and library with text pages and a text "PDF" that joins them.
 */
@RunWith(RobolectricTestRunner::class)
class ScanFlowSaveTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = rule.activity
    private val root: File = Files.createTempDirectory("flow-save").toFile()
    private val folderRoot = File(root, "Scans").apply { mkdirs() }
    private val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)
    private val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private val writer = CaptureWriter { source, _, page, original ->
        val text = source.open().use { it.readBytes().decodeToString() }
        page.writeText(text)
        original?.writeText("original $text")
        StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
    }
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File) = true.also { source.copyTo(dest, overwrite = true) }
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = true.also { source.copyTo(dest, overwrite = true) }
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = false
        override fun rotate(source: File, quarterTurns: Int, dest: File) = true.also { source.copyTo(dest, overwrite = true) }
    }

    // A "PDF" of the pages' texts; one holding "fail" cannot be made.
    private val assembler = PdfAssembler { pages, _, _, dest, _ ->
        val text = pages.joinToString("|") { it.readText() }
        dest.writeText(text)
        "fail" !in text
    }
    private lateinit var library: ScanLibrary
    private lateinit var exporter: ScanPdfExporter
    private val models = mutableListOf<ScanFlowModel>()
    private var saved = 0

    @Before
    fun setUp() {
        TestDocumentsProvider.install(folderRoot)
        library = ScanLibrary(ScanFiles.libraryDir(context), capture = writer, images = images)
        exporter = ScanPdfExporter(library, File(root, "export"), images = images, assembler = assembler)
    }

    @After
    fun cleanUp() {
        models.forEach { it.close() }
        forgetFolder()
        ScanFiles.libraryDir(context).deleteRecursively()
        root.deleteRecursively()
    }

    /**
     * Forgets the folder this test chose: Robolectric can hand a later test's activity the preferences instance this one wrote, so they are
     * emptied through it before the test ends.
     */
    private fun forgetFolder() {
        for (name in listOf("scan_folder", "folder_setup")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "scan-folder").deleteRecursively()
    }

    /** Runs its work at once, except while [held]; [release] then runs what waited, in order. */
    private class HeldDispatcher(@Volatile var held: Boolean = false) : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) queue += block else block.run()
        }

        fun release() {
            held = false
            while (true) (queue.poll() ?: break).run()
        }
    }

    private fun appFolder(io: CoroutineContext = Dispatchers.Unconfined) =
        AppFolder(context, CoroutineScope(Dispatchers.Unconfined), io, FolderAccess(context), library, exporter.pdfMaker())

    /** A scan named [name] already on the phone, not in any folder. */
    private fun onPhone(name: String) {
        val doc = library.create(name)
        library.addCapture(doc.id, ImageSource { name.byteInputStream() }, null, keepOriginal = true)
    }

    /** The flow in Review of one page with [text], shown with [folder]. */
    private fun flowInReview(folder: AppFolder?, text: String = "page", io: CoroutineContext = Dispatchers.Unconfined): ScanFlowModel {
        val model = ScanFlowModel(
            context,
            library = library,
            startSession = { ScanSession(library, File(root, "staging/s${models.size}")) },
            exporter = exporter,
            export = ScanExport(context, library, exporter, onSaved = {}),
            main = Dispatchers.Unconfined,
            io = io,
        ).also { models += it }
        model.chooseMode(CaptureMode.MULTI)
        val shots = File(root, "shots").apply { mkdirs() }
        rule.waitUntil(10_000) { model.add(File(shots, "shot${models.size}.jpg").apply { writeText(text) }, null, 0) }
        rule.waitUntil(10_000) { model.pages.size == 1 && model.canSave }
        model.review()
        rule.runOnUiThread { ViewModelProvider(rule.activity)[ScanFlowHolder::class.java].model = model }
        rule.setContent { ScanFlow(onClose = {}, onSaved = { saved++ }, folder = folder) }
        return model
    }

    /** The actions of every activity started for a result so far (the camera permission request among them), taking them off the list. */
    private fun startedActions(): List<String?> =
        generateSequence { shadowOf(rule.activity).nextStartedActivityForResult }.map { it.intent.action }.toList()

    /** The request of the folder picker among the activities started for a result. */
    private fun folderPickerRequest(): Intent = generateSequence { shadowOf(rule.activity).nextStartedActivityForResult }
        .map { it.intent }.first { it.action == Intent.ACTION_OPEN_DOCUMENT_TREE }

    private fun pdfs() = folderRoot.list().orEmpty().filter { it.endsWith(".pdf") }.toSet()

    @Test
    fun `with a folder Done saves into it, without the picker`() {
        FolderAccess(context).adopt(treeUri)
        val folder = appFolder().also { it.start() }
        flowInReview(folder)
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("The PDF goes into Scans, its pages into Scan sources there.").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { saved == 1 }
        assertEquals(1, pdfs().size)
        assertEquals("page", File(folderRoot, pdfs().single()).readText())
    }

    @Test
    fun `without a folder Done asks the system picker where to save`() {
        flowInReview(appFolder().also { it.start() })
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Choose where to save").performClick()
        assertTrue(startedActions().contains(Intent.ACTION_CREATE_DOCUMENT))
    }

    @Test
    fun `before the first folder check Done waits for it instead of guessing`() {
        FolderAccess(context).adopt(treeUri)
        val io = HeldDispatcher(held = true)
        val folder = appFolder(io)
        flowInReview(folder)
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Checking your folder…").assertExists()
        rule.onNodeWithText("Choose where to save").assertDoesNotExist()
        assertFalse("the picker did not open", startedActions().contains(Intent.ACTION_CREATE_DOCUMENT))

        rule.runOnUiThread { io.release() }
        rule.onNodeWithText("Checking your folder…").assertDoesNotExist()
        rule.onNodeWithText("Save").assertExists()
    }

    /** A folder chosen before whose grant is gone, so saving waits until it is chosen again; Other is a scan already on the phone. */
    private fun unreachable(): AppFolder {
        onPhone("Other")
        FolderAccess(context).adopt(treeUri)
        context.contentResolver.releasePersistableUriPermission(treeUri, readWrite)
        return appFolder().also { it.start() }
    }

    /** Done, then Save, then "Choose the folder again" from the dialog, and the picker answering with the folder. */
    private fun saveAndChooseAgain() {
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Can't reach your folder").assertExists()
        rule.onNodeWithText("Choose the folder again").performClick()
        val request = folderPickerRequest()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, request.action)
        rule.runOnUiThread { shadowOf(rule.activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(treeUri)) }
        rule.waitForIdle()
    }

    @Test
    fun `a folder out of reach holds the save until it is chosen again, then saves and copies the other scans in`() {
        flowInReview(unreachable())
        saveAndChooseAgain()
        rule.waitUntil(10_000) { saved == 1 }
        assertEquals(2, pdfs().size)
        assertTrue("Other.pdf" in pdfs())
        val sources = File(folderRoot, "Scan sources").list().orEmpty().toSet()
        assertFalse("the open scan is written once, by its own save: ${pdfs()} $sources", pdfs().any { it.endsWith("-2.pdf") })
    }

    @Test
    fun `the other scans are copied in even when the save then fails`() {
        flowInReview(unreachable(), text = "fail")
        saveAndChooseAgain()
        rule.waitUntil(10_000) { "Other.pdf" in pdfs() }
        assertEquals(setOf("Other.pdf"), pdfs())
        assertEquals(0, saved)
        rule.onNodeWithText("The PDF couldn't be saved. Try again.").assertExists()
    }

    @Test
    fun `the other scans are copied in even when the save never runs`() {
        val io = HeldDispatcher()
        val model = flowInReview(unreachable(), io = io)
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Save").performClick()
        io.held = true
        rule.onNodeWithText("Choose the folder again").performClick()
        val request = folderPickerRequest()
        rule.runOnUiThread { shadowOf(rule.activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(treeUri)) }
        rule.waitUntil(10_000) { "Other.pdf" in pdfs() }
        assertEquals("the open scan's save is still waiting", setOf("Other.pdf"), pdfs())
        assertTrue(model.busy)
    }
}
