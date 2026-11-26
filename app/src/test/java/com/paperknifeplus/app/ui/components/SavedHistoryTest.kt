package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.haziaferi.scanknifeplus.ui.folder.AppFolder
import com.haziaferi.scanknifeplus.ui.folder.FolderBar
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.SavedFilesProvider
import com.paperknifeplus.app.testing.dialogButton
import com.paperknifeplus.app.ui.theme.AppPalette
import java.io.File
import java.nio.file.Files
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

/**
 * Ledger L8 in History, Home and Settings: the saved files are listed across restarts, Move to folder shows only while files wait in the app
 * with no folder, a file kept in the app is deleted only after its confirmation, and Clear history keeps every file kept in the app.
 */
@RunWith(RobolectricTestRunner::class)
class SavedHistoryTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = rule.activity.applicationContext
    private val base: File = Files.createTempDirectory("saved-history").toFile()
    private val files get() = SavedFiles.of(context)

    @Before
    fun setUp() {
        SavedFilesProvider.install(context)
        SessionManager.clearHistory()
    }

    @After
    fun cleanUp() {
        SessionManager.clearHistory()
        base.deleteRecursively()
        SavedFilesProvider.forget(context)
    }

    private fun keep(name: String, tool: String = "merge"): SavedEntry {
        val file = files.create(name)!!.apply { writeText("pdf $name") }
        return files.record(files.uriOf(file), name, tool, 1)
    }

    private fun elsewhere(name: String): SavedEntry =
        files.record(Uri.fromFile(File(base, name).apply { writeText(name) }), name, "compress", 1)

    private fun appFolder(saved: SavedFiles = files) =
        AppFolder(context, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, FolderAccess(context), sweep = {}, files = saved).also { it.start() }

    @Test
    fun `after a restart History lists the files kept in the app and saved elsewhere, saying which are in the app`() {
        keep("kept.pdf")
        elsewhere("picked.pdf")
        // A new index over the same files, as the next launch reads them.
        val restarted = SavedFiles(files.dir, File(context.filesDir, "saved-index.json"), ScanFiles.authority(context), context.contentResolver)
        val folder = appFolder(restarted)
        assertEquals(setOf("kept.pdf", "picked.pdf"), folder.saved.map { it.name }.toSet())

        rule.setContent { HistoryView(onItemClick = { _, _, _ -> }, saved = folder.saved) }
        rule.onNodeWithText("Your saved files").assertExists()
        rule.onNodeWithText("kept.pdf").assertExists()
        rule.onNodeWithText("picked.pdf").assertExists()
        rule.onNodeWithText("In the app", substring = true).assertExists()
        rule.onNodeWithText("Merge").assertExists()
        rule.onAllNodes(hasText("Delete") and hasClickAction()).fetchSemanticsNodes().let { assertEquals("only the file in the app", 1, it.size) }
    }

    @Test
    fun `a PDF kept in the app opens in the reader from History`() {
        val kept = keep("kept.pdf")
        var opened: Uri? = null
        rule.setContent { HistoryView(onItemClick = { uri, _, _ -> opened = uri }, saved = files.entries()) }
        rule.onNodeWithText("kept.pdf").performClick()
        assertEquals(files.uriOf(File(files.dir, kept.file!!)), opened)
    }

    @Test
    fun `Move to folder is offered only while files wait in the app and no folder is chosen`() {
        val empty = appFolder()
        assertFalse(empty.offersMove)
        keep("kept.pdf")
        val waiting = appFolder()
        assertTrue(waiting.offersMove)
        assertEquals(1, waiting.appFiles)
        // A folder was chosen, and its grant is gone: it must be chosen again, which is not "no folder".
        val prefs = context.getSharedPreferences("scan_folder", Context.MODE_PRIVATE)
        prefs.edit().putString("tree_uri", "content://gone.provider/tree/root").commit()
        try {
            val chosen = appFolder()
            assertEquals(FolderStatus.ChooseAgain::class, chosen.status::class)
            assertFalse(chosen.offersMove)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun `History shows the folder bar's line with Move to folder only when asked, and it opens Folder setup`() {
        keep("kept.pdf")
        var opened = 0
        rule.setContent { HistoryView(onItemClick = { _, _, _ -> }, saved = files.entries(), onMoveToFolder = { opened++ }) }
        rule.onNodeWithText("Saved in the app until you choose a folder").assertExists()
        rule.onNodeWithText("Move to folder").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `without files waiting History has no Move to folder line`() {
        rule.setContent { HistoryView(onItemClick = { _, _, _ -> }, saved = files.entries(), onMoveToFolder = null) }
        rule.onNodeWithText("Move to folder").assertDoesNotExist()
        rule.onNodeWithText("Saved in the app until you choose a folder").assertDoesNotExist()
    }

    @Test
    fun `Home's folder bar says files are saved in the app, with Move to folder or Choose folder`() {
        val waiting = mutableStateOf(true)
        rule.setContent { FolderBar(FolderStatus.NotChosen, onClick = {}, appFiles = waiting.value) }
        rule.onNodeWithText("Saved in the app until you choose a folder").assertExists()
        rule.onNodeWithText("Move to folder").assertExists()
        waiting.value = false
        rule.onNodeWithText("Choose folder").assertExists()
        rule.onNodeWithText("Move to folder").assertDoesNotExist()
    }

    @Test
    fun `Delete asks first, Cancel keeps the file, and Delete removes it and its row`() {
        val kept = keep("kept.pdf")
        val folder = appFolder()
        rule.setContent { HistoryView(onItemClick = { _, _, _ -> }, saved = folder.saved, onDelete = { folder.deleteSaved(it) }) }
        rule.onNodeWithContentDescription("Delete kept.pdf").performClick()
        rule.onNodeWithText("Delete this file?").assertExists()
        rule.onNodeWithText("“kept.pdf” is kept only in the app. This can't be undone.").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Delete this file?").assertDoesNotExist()
        assertTrue(File(files.dir, kept.file!!).isFile)
        assertEquals(1, files.entries().size)

        rule.onNodeWithContentDescription("Delete kept.pdf").performClick()
        rule.dialogButton("Delete").performClick()
        rule.waitUntil(10_000) { files.entries().isEmpty() }
        assertFalse(File(files.dir, kept.file!!).exists())
        rule.onNodeWithText("kept.pdf").assertDoesNotExist()
    }

    @Test
    fun `Clear history drops the files saved elsewhere from the list and keeps every file kept in the app`() {
        val kept = keep("kept.pdf")
        val picked = elsewhere("picked.pdf")
        rule.setContent { SettingsView(onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = {}, onBack = {}) }
        rule.onNodeWithText("Clear history").performScrollTo().performClick()
        rule.onNodeWithText("Removes files saved elsewhere from this list", substring = true).assertExists()
        rule.onNodeWithText("Clear list").performClick()
        rule.waitUntil(10_000) { files.entries().none { it.place == SavedPlace.ELSEWHERE } }
        assertEquals(listOf(kept.id), files.entries().map { it.id })
        assertTrue(File(files.dir, kept.file!!).isFile)
        assertTrue("the file saved elsewhere stays where it is", File(picked.uri!!.path!!).isFile)
    }
}
