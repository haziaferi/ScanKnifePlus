package com.paperknifeplus.app.ui.components

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.FolderKind
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class HomeViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Reports ZIP for *.zip paths and PDF otherwise, as a document provider would. */
    class TypedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = if (uri.path.orEmpty().endsWith(".zip")) "application/zip" else "application/pdf"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @After
    fun tearDown() = rule.runOnUiThread { SessionManager.clearHistory() }

    private fun home(
        onAllTools: () -> Unit = {},
        onSettings: () -> Unit = {},
        onSeeAll: () -> Unit = {},
        folder: FolderStatus = FolderStatus.NotChosen,
        onFolder: () -> Unit = {},
        recentFolder: List<FolderItem>? = null,
        onOpen: (Uri) -> Unit = {}
    ) = rule.setContent {
        HomeView(
            onAllTools = onAllTools, onSettings = onSettings, onImportPhotos = {}, onSeeAll = onSeeAll, onOpenPreview = { uri, _, _ -> onOpen(uri) },
            folder = folder, onFolder = onFolder, recentFolder = recentFolder
        )
    }

    @Test
    fun `the header shows the app name, All tools and Settings`() {
        var tools = 0
        var settings = 0
        home(onAllTools = { tools++ }, onSettings = { settings++ })
        rule.onNodeWithText("ScanKnife+").assertExists()
        rule.onNodeWithText("PaperKnife", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("All tools").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, tools)
        assertEquals(1, settings)
    }

    @Test
    fun `the scan card has Import photos, which asks the system picker for several images, but no Scan button of its own`() {
        home()
        rule.onNodeWithText("Scan a document").assertHasNoClickAction()
        rule.onNodeWithText("Scan").assertDoesNotExist()
        rule.onNodeWithText("Import photos").performClick()
        val request = shadowOf(rule.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_GET_CONTENT, request.action)
        assertEquals("image/*", request.type)
        assertTrue(request.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun `the folder bar sits above the scan card and opens Folder setup`() {
        var opened = 0
        home(folder = FolderStatus.Ready("Documents/Scans"), onFolder = { opened++ })
        val bar = rule.onNodeWithText("Saving to Documents/Scans").fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithText("Scan a document").fetchSemanticsNode().boundsInRoot
        assertTrue(bar.bottom <= card.top)
        rule.onNodeWithText("Saving to Documents/Scans").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `with a folder Recent shows its newest files, PDFs opening in the reader and others in History`() {
        val pdf = Uri.parse("content://folder/new.pdf")
        val items = listOf(
            FolderItem("1", "new.pdf", FolderKind.SCAN, 10, 4, pdf),
            FolderItem("2", "pages.zip", FolderKind.TOOL, 10, 3, Uri.parse("content://folder/pages.zip")),
            FolderItem("3", "a.pdf", FolderKind.TOOL, 10, 2, Uri.parse("content://folder/a.pdf")),
            FolderItem("4", "old.pdf", FolderKind.TOOL, 10, 1, Uri.parse("content://folder/old.pdf")),
        )
        rule.runOnUiThread { SessionManager.addEntry("session.pdf", "merge", "2 files", Icons.Filled.Layers) }
        val opened = mutableListOf<Uri>()
        var seeAll = 0
        home(folder = FolderStatus.Ready("Documents/Scans"), recentFolder = items, onOpen = { opened += it }, onSeeAll = { seeAll++ })
        rule.onNodeWithText("Recent in your folder").assertExists()
        rule.onNodeWithText("session.pdf").assertDoesNotExist()
        rule.onNodeWithText("old.pdf").assertDoesNotExist()
        rule.onNodeWithText("new.pdf").performScrollTo().performClick()
        rule.onNodeWithText("pages.zip").performScrollTo().performClick()
        assertEquals(listOf(pdf), opened)
        assertEquals(1, seeAll)
    }

    @Test
    fun `Open PDF asks the system picker for a PDF`() {
        home()
        rule.onNodeWithText("Open PDF").performClick()
        val request = shadowOf(rule.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_GET_CONTENT, request.action)
        assertEquals("application/pdf", request.type)
    }

    @Test
    fun `Recent shows the newest three outputs with their type tags`() {
        rule.runOnUiThread {
            SessionManager.addEntry("old.pdf", "merge", "2 files joined", Icons.Filled.Layers)
            SessionManager.addEntry("signed.pdf", "sign", "Document signed locally", Icons.Filled.Description)
            SessionManager.addEntry("scan.pdf", SCAN_ENTRY_TOOL, "3 pages", Icons.Filled.DocumentScanner)
            SessionManager.addEntry("small.pdf", "compress", "Optimized", Icons.Filled.Description)
        }
        var seeAll = 0
        home(onSeeAll = { seeAll++ })
        for (text in listOf("small.pdf", "Compress", "scan.pdf", "Scan", "3 pages", "signed.pdf", "Sign")) rule.onNodeWithText(text).assertExists()
        rule.onNodeWithText("old.pdf").assertDoesNotExist()
        rule.onNodeWithText("See all").performClick()
        assertEquals(1, seeAll)
    }

    @Test
    fun `an empty Recent list says what will appear`() {
        home()
        rule.onNodeWithText("Your scans and tool outputs will show here.").assertExists()
    }

    @Test
    fun `recent PDFs open in the reader and ZIP outputs open History`() {
        Robolectric.setupContentProvider(TypedProvider::class.java, "com.paperknifeplus.app.test.typed")
        val pdf = Uri.parse("content://com.paperknifeplus.app.test.typed/merged.pdf")
        val zip = Uri.parse("content://com.paperknifeplus.app.test.typed/pages.zip")
        val history = listOf(
            ActivityEntry("1", "pages.zip", "pdf2zip", "3 pages", Icons.Filled.FolderZip, zip, 3),
            ActivityEntry("2", "merged.pdf", "merge", "2 files", Icons.Filled.Layers, pdf, 4),
            ActivityEntry("3", "text.txt", "pdf2text", "5 chars", Icons.Filled.Description),
        )
        var opened: Uri? = null
        var historyOpened = 0
        rule.setContent { RecentList(history, onSeeAll = { historyOpened++ }, onOpenPreview = { uri, _, _ -> opened = uri }) }

        rule.onNodeWithText("pages.zip").performClick()
        assertNull(opened)
        assertEquals(1, historyOpened)

        rule.onNodeWithText("merged.pdf").performClick()
        assertEquals(pdf, opened)
        assertEquals(1, historyOpened)

        rule.onNodeWithText("text.txt").assertHasNoClickAction()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so long names wrap.
    fun `at 2x text a recent row grows and keeps its name clear of the tag`() {
        val long = "scanknife-scan-2026-10-15-143205-with-a-long-name.pdf"
        val history = listOf(ActivityEntry("1", long, SCAN_ENTRY_TOOL, "3 pages", Icons.Filled.DocumentScanner))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                RecentList(history, onSeeAll = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        val name = rule.onNodeWithText(long).fetchSemanticsNode().boundsInRoot
        val tag = rule.onNodeWithText("Scan").fetchSemanticsNode().boundsInRoot
        val meta = rule.onNodeWithText("3 pages").fetchSemanticsNode().boundsInRoot
        assertTrue("name overlaps the tag", name.right <= tag.left)
        assertTrue("meta is drawn over the name", meta.top >= name.bottom)
        assertTrue("the name wraps instead of clipping", name.height > tag.height)
    }
}
