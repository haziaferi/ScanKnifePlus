package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.PdfMaker
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.haziaferi.scanknifeplus.storage.TestDocumentsProvider
import com.haziaferi.scanknifeplus.ui.folder.AppFolder
import com.haziaferi.scanknifeplus.ui.folder.HistoryFolder
import com.haziaferi.scanknifeplus.ui.folder.recentPdfs
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.SavedFilesProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The empty tool screens with no folder listed list the newest PDFs kept in the app (ledger 2026-11-27, answer 3); with one, the folder's. */
@RunWith(RobolectricTestRunner::class)
class KeptToolInputTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base: File = Files.createTempDirectory("kept-input").toFile()
    private val folderRoot = File(base, "Scans").apply { mkdirs() }
    private val files get() = SavedFiles.of(context)
    private var folder: AppFolder? = null

    @After
    fun cleanUp() {
        folder?.close()
        for (name in listOf("scan_folder", "folder_setup")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "scan-folder").deleteRecursively()
        base.deleteRecursively()
        SavedFilesProvider.forget(context)
    }

    private fun appFolder() = AppFolder(
        context, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, FolderAccess(context),
        ScanLibrary(ScanFiles.libraryDir(context)), PdfMaker { _, _ -> false }, sweep = {}, files = files,
    ).also { it.start(); folder = it }

    private fun keep(name: String, time: Long): SavedEntry {
        val file = files.create(name)!!.apply { writeText("bytes of $name") }
        Thread.sleep(time) // a later save is newer
        return files.record(files.uriOf(file), file.name, "merge", 1)
    }

    private fun emptyScreen(folder: AppFolder, opened: MutableList<Uri>) = rule.setContent {
        CompositionLocalProvider(LocalFolderPdfs provides { folder.toolPdfs }) {
            ToolEmptyState(Icons.Outlined.Bolt, "Optimize any PDF document", "Make the file smaller", onChoose = {}, onOpenRecent = { opened += it })
        }
    }

    @Test
    fun `with no folder the PDFs kept in the app are listed, and a tap hands the tool the kept file`() {
        keep("Insurance letters.pdf", 2)
        val tax = keep("Tax return.pdf", 2)
        keep("Pages.zip", 2)
        val opened = mutableListOf<Uri>()
        emptyScreen(appFolder(), opened)
        rule.onNodeWithText("Kept in the app").assertExists()
        rule.onNodeWithText("Recent in your folder").assertDoesNotExist()
        rule.onNodeWithText("Insurance letters.pdf").assertExists()
        rule.onNodeWithText("Pages.zip").assertDoesNotExist()
        rule.onNodeWithText("Tax return.pdf").performScrollTo().performClick()
        assertEquals(listOf(files.uriOf(File(files.dir, tax.file!!))), opened)
    }

    @Test
    fun `with a listed folder its PDFs are listed as before, not those kept in the app`() {
        TestDocumentsProvider.install(folderRoot)
        File(folderRoot, "Folder report.pdf").writeText("in the folder")
        val held = keep("Held.pdf", 2)
        files.hold(held.id) // stays in the app when the folder is chosen
        val folder = appFolder()
        var adopted: Boolean? = null
        folder.choose(DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)) { adopted = it }
        assertEquals(true, adopted)
        assertEquals(SavedPlace.APP, files.entries().single { it.id == held.id }.place)
        val opened = mutableListOf<Uri>()
        emptyScreen(folder, opened)
        rule.onNodeWithText("Recent in your folder").assertExists()
        rule.onNodeWithText("Kept in the app").assertDoesNotExist()
        rule.onNodeWithText("Held.pdf").assertDoesNotExist()
        rule.onNodeWithText("Folder report.pdf").performScrollTo().performClick()
        assertEquals(1, opened.size)
        assertTrue(opened.single().toString(), opened.single().authority == TestDocumentsProvider.AUTHORITY)
    }

    @Test
    fun `a folder that can't be reached lists the kept PDFs too, and one being read lists nothing yet`() {
        keep("a.pdf", 1)
        val kept = files.entries()
        val uriOf: (SavedEntry) -> Uri? = { e -> files.fileOf(e)?.let(files::uriOf) }
        val unreachable = recentPdfs(HistoryFolder("Scans", null, unreadable = true), kept, uriOf)!!
        assertTrue(unreachable.kept)
        assertEquals(listOf("a.pdf"), unreachable.items.map { it.name })
        assertNull(recentPdfs(HistoryFolder("Scans", null), kept, uriOf))
    }
}
