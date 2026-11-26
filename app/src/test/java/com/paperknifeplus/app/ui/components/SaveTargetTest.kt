package com.paperknifeplus.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.haziaferi.scanknifeplus.storage.TestDocumentsProvider
import com.haziaferi.scanknifeplus.ui.folder.AppFolder
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.SavedFilesProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * Ledger L8: the tools' one save path. With no folder an output is made in the app, with a folder at its top level, with a folder out of
 * reach in the app with a notice, and Save elsewhere asks the picker; no save writes over a file, and a failed save removes what it made.
 */
@RunWith(RobolectricTestRunner::class)
class SaveTargetTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = rule.activity.applicationContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)
    private val base: File = Files.createTempDirectory("save-target").toFile()
    private val folderRoot = File(base, "Scans").apply { mkdirs() }
    private val got = mutableListOf<Uri?>()
    private var pickerLaunches = 0
    private lateinit var target: SaveTarget

    @Before
    fun setUp() {
        TestDocumentsProvider.install(folderRoot)
        SavedFilesProvider.install(context)
        PDFBoxResourceLoader.init(context)
        ShadowToast.reset()
    }

    @After
    fun cleanUp() {
        for (name in listOf("scan_folder", "folder_setup")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "scan-folder").deleteRecursively()
        base.deleteRecursively()
        SavedFilesProvider.forget(context)
    }

    private val files get() = SavedFiles.of(context)

    private fun appFolder() = AppFolder(context, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, FolderAccess(context))

    /** A system picker that answers every launch with [answer], counting the launches. */
    private fun picker(answer: Uri?) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                pickerLaunches++
                dispatchResult(requestCode, answer)
            }
        }
    }

    private fun show(places: SavePlaces, answer: Uri? = null) = rule.setContent {
        CompositionLocalProvider(LocalSavePlaces provides places, LocalActivityResultRegistryOwner provides picker(answer)) {
            target = rememberSaveTarget("application/pdf") { got += it }
        }
    }

    /** Saves an output named [name] as a tool's button would, and returns the URI its callback got. */
    private fun save(name: String, how: SaveTarget.(String) -> Unit = SaveTarget::launch): Uri {
        val before = got.size
        rule.runOnUiThread { target.how(name) }
        rule.waitUntil(10_000) { got.size > before }
        return got.last()!!
    }

    @Test
    fun `with no folder an output is made in the app, a second of the same name gets -2, and no picker opens`() {
        show(appFolder())
        val first = save("out.pdf")
        assertEquals(files.uriOf(File(files.dir, "out.pdf")), first)
        files.fileOf(first)!!.writeText("first")
        val second = save("out.pdf")
        assertEquals("out-2.pdf", files.fileOf(second)!!.name)
        assertEquals("first", File(files.dir, "out.pdf").readText())
        assertEquals(0, pickerLaunches)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertTrue("nothing went to a folder", folderRoot.list().orEmpty().isEmpty())
    }

    @Test
    fun `with a folder an output is made at its top level under a new name, never over a file, and not in the app`() {
        File(folderRoot, "out.pdf").writeText("the user's")
        assertTrue(FolderAccess(context).adopt(treeUri))
        show(appFolder())
        val uri = save("out.pdf")
        assertTrue(DocumentsContract.isTreeUri(uri))
        assertEquals("${TestDocumentsProvider.ROOT}/out-2.pdf", DocumentsContract.getDocumentId(uri))
        assertTrue(File(folderRoot, "out-2.pdf").isFile)
        assertEquals("the user's", File(folderRoot, "out.pdf").readText())
        assertEquals("out-3.pdf", DocumentsContract.getDocumentId(save("out.pdf")).substringAfterLast('/'))
        assertEquals(0, pickerLaunches)
        assertFalse(files.dir.list().orEmpty().any())
    }

    @Test
    fun `with a folder out of reach an output is made in the app, and the notice says it moves there later`() {
        assertTrue(FolderAccess(context).adopt(treeUri))
        context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        show(appFolder())
        val uri = save("out.pdf")
        assertEquals(File(files.dir, "out.pdf"), files.fileOf(uri))
        assertEquals(context.getString(R.string.tool_saved_in_app), ShadowToast.getTextOfLatestToast())
        assertEquals(0, pickerLaunches)
    }

    @Test
    fun `Save elsewhere asks the picker and makes nothing in the app or the folder`() {
        assertTrue(FolderAccess(context).adopt(treeUri))
        val picked = Uri.fromFile(File(base, "picked.pdf"))
        show(appFolder(), answer = picked)
        assertEquals(picked, save("out.pdf", SaveTarget::launchElsewhere))
        // A tool's own action, run through Save elsewhere…, calls launch: it asks the picker too.
        assertEquals(picked, save("out.pdf") { name -> elsewhere { launch(name) } })
        assertEquals(2, pickerLaunches)
        assertFalse(files.dir.list().orEmpty().any())
        assertTrue(folderRoot.list().orEmpty().isEmpty())
    }

    @Test
    fun `a host without the app's places asks the picker`() {
        val picked = Uri.fromFile(File(base, "picked.pdf"))
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker(picked)) {
                target = rememberSaveTarget("application/pdf") { got += it }
            }
        }
        assertEquals(picked, save("out.pdf"))
        assertEquals(1, pickerLaunches)
    }

    @Test
    fun `a failed save removes the file it made, in the app and in the folder`() {
        show(appFolder())
        val inApp = save("failed.pdf")
        deleteCreatedDocument(context, inApp)
        assertFalse(File(files.dir, "failed.pdf").exists())

        assertTrue(FolderAccess(context).adopt(treeUri))
        val inFolder = save("failed.pdf")
        assertTrue(File(folderRoot, "failed.pdf").isFile)
        deleteCreatedDocument(context, inFolder)
        assertFalse(File(folderRoot, "failed.pdf").exists())
    }

    @Test
    fun `leaving the screen while its file is made opens no picker, keeps no file and does not crash`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val made = java.util.Collections.synchronizedList(mutableListOf<File>())
        val saved = files // read here: the rule's activity is reachable from the main thread only
        val slow = SavePlaces { name ->
            entered.countDown()
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
            saved.create(name)?.also { made += it }?.let { SaveSpot(saved.uriOf(it)) }
        }
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker(null)) {
                if (shown.value) {
                    CompositionLocalProvider(LocalSavePlaces provides slow) { target = rememberSaveTarget("application/pdf") { got += it } }
                }
            }
        }
        rule.runOnUiThread { target.launch("gone.pdf") }
        rule.waitUntil(10_000) { entered.count == 0L } // the file is being made
        rule.runOnUiThread { shown.value = false }
        rule.waitForIdle()
        gate.countDown()
        rule.waitUntil(10_000) { made.isNotEmpty() && !made.single().exists() }
        rule.waitForIdle()
        assertEquals(0, pickerLaunches)
        assertTrue(got.isEmpty())
        assertTrue("no row left", files.entries().isEmpty())
    }

    @Test
    fun `a folder that could not make the file has the save kept in the app, with its own notice`() {
        assertTrue(FolderAccess(context).adopt(treeUri))
        show(appFolder())
        // A name the folder will not take (hidden there), which the app keeps under a plain name.
        val uri = save(".hidden.pdf")
        assertEquals(File(files.dir, "hidden.pdf"), files.fileOf(uri))
        assertEquals(context.getString(R.string.tool_saved_in_app_later), ShadowToast.getTextOfLatestToast())
        assertTrue(folderRoot.list().orEmpty().isEmpty())
    }

    @Test
    fun `a save listed before its tool's clean-up ran is kept, in the folder as in the app`() {
        assertTrue(FolderAccess(context).adopt(treeUri))
        show(appFolder())
        val inFolder = save("done.pdf")
        files.record(inFolder, "done.pdf", "merge")
        deleteCreatedDocument(context, inFolder)
        assertTrue(File(folderRoot, "done.pdf").isFile)
    }

    @Test
    fun `a tool's saved file is listed, grant and all, before saved returns`() {
        val picked = Uri.fromFile(File(base, "picked.pdf").apply { writeText("picked") })
        rule.runOnUiThread { ToolOutputs("merge", context).saved(picked, "picked.pdf", "", Icons.Filled.Done) }
        assertEquals(picked, files.entries().single().uri)
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == picked })
    }

    @Test
    fun `a failed save with the picker removes the document the picker made`() {
        val (uri, file) = TestPdfs.createdDocument(context, "failed.pdf")
        deleteCreatedDocument(context, uri)
        assertFalse(file.exists())
    }

    @Test
    fun `a file kept in the app and listed is never removed as a failed save`() {
        show(appFolder())
        val uri = save("kept.pdf")
        files.fileOf(uri)!!.writeText("kept")
        files.record(uri, "kept.pdf", "merge")
        deleteCreatedDocument(context, uri)
        assertEquals("kept", File(files.dir, "kept.pdf").readText())
    }

    @Test
    fun `a tool's primary save with no folder writes its output into the app and lists it there`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "to-repair.pdf")
        val folder = appFolder()
        rule.setContent {
            CompositionLocalProvider(LocalSavePlaces provides folder, LocalActivityResultRegistryOwner provides picker(null)) {
                RepairView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Repair PDF")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save elsewhere…").assertExists()
        rule.onNodeWithText("Repair PDF").performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Repair complete")).fetchSemanticsNodes().isNotEmpty() }

        val entry = files.entries().single()
        assertEquals(SavedPlace.APP, entry.place)
        assertEquals("repair", entry.tool)
        PDDocument.load(File(files.dir, entry.file!!)).use { assertEquals(2, it.numberOfPages) }
        assertEquals(0, pickerLaunches)
        rule.onNodeWithText("In the app", substring = true).assertExists()
    }

    /** The guard: every tool view saves through rememberSaveTarget, so none can bypass where saves go. */
    @Test
    fun `no view under ui components launches CreateDocument itself`() {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve("com/paperknifeplus/app/ui/components")
        val sources = dir.listFiles().orEmpty().filter { it.extension == "kt" }
        assertTrue(sources.size > 20)
        val direct = sources.filter { it.name != "SaveTarget.kt" && "CreateDocument" in it.readText() }.map { it.name }
        assertEquals(emptyList<String>(), direct)
        // The views themselves: SaveTarget.kt, which defines it, does not count.
        val views = sources.filter { it.name != "SaveTarget.kt" && "rememberSaveTarget(" in it.readText() }
        assertEquals(23, views.size)
        // Save elsewhere under every primary save button: each launch of a view's saveLauncher has a launchElsewhere beside it, or is the
        // action of a ToolConfigLayout that adds one (save = saveLauncher), as in Sign's and Watermark's page view.
        for (view in views) {
            val text = view.readText()
            val launches = Regex("""saveLauncher\.launch\(""").findAll(text).count()
            val elsewhere = Regex("""saveLauncher\.launchElsewhere\(|save = saveLauncher""").findAll(text).count()
            assertTrue("${view.name}: $launches saves, $elsewhere ways elsewhere", elsewhere >= launches)
        }
    }
}
