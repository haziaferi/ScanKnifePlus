package com.paperknifeplus.app.ui.components

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.FolderKind
import com.haziaferi.scanknifeplus.ui.folder.folderPdfs
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The tools' shared scaffold: the empty screen's recent list (1b + 1c), the pinned action (2b) and the result screen (3b). */
@RunWith(RobolectricTestRunner::class)
class ToolScaffoldTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun item(name: String, kind: FolderKind = FolderKind.TOOL, uri: Uri? = Uri.parse("content://test/$name"), modified: Long = 1) =
        FolderItem(name, name, kind, 1024, modified, uri)

    private fun emptyScreen(recent: List<FolderItem>?, onOpenRecent: ((Uri) -> Unit)? = {}) = rule.setContent {
        CompositionLocalProvider(LocalFolderPdfs provides { recent }) {
            ToolEmptyState(Icons.Outlined.Bolt, "Optimize any PDF document", "Make the file smaller", onChoose = {}, onOpenRecent = onOpenRecent)
        }
    }

    @Test
    fun `the folder's PDFs are listed under the steps and a tap opens one in the tool`() {
        val opened = mutableListOf<Uri>()
        emptyScreen(listOf(item("Insurance letters.pdf"), item("Tax return.pdf")), onOpenRecent = { opened += it })
        rule.onNodeWithText("Choose a PDF").assertExists()
        rule.onNodeWithText("Recent in your folder").assertExists()
        rule.onNodeWithText("Tax return.pdf").performScrollTo().performClick()
        assertEquals(listOf(Uri.parse("content://test/Tax return.pdf")), opened)
    }

    @Test
    fun `without a listed folder only the steps card shows`() {
        emptyScreen(null)
        rule.onNodeWithText("Optimize any PDF document").assertExists()
        rule.onNodeWithText("Recent in your folder").assertDoesNotExist()
        rule.onNodeWithText("Choose PDF").assertIsDisplayed()
    }

    @Test
    fun `an empty folder, or a tool that takes images, shows no recent list`() {
        emptyScreen(emptyList())
        rule.onNodeWithText("Recent in your folder").assertDoesNotExist()
    }

    @Test
    fun `a tool without a recent action ignores the folder`() {
        emptyScreen(listOf(item("a.pdf")), onOpenRecent = null)
        rule.onNodeWithText("a.pdf").assertDoesNotExist()
    }

    @Test
    fun `the recent list keeps the newest openable PDFs, conflict copies and other files left out`() {
        val items = listOf(
            item("1.pdf"), item("notes.txt"), item("2.pdf", FolderKind.CONFLICT), item("3.pdf", FolderKind.SCAN), item("4.pdf", uri = null),
            item("5.pdf"), item("6.pdf"), item("7.pdf")
        )
        assertEquals(listOf("1.pdf", "3.pdf", "5.pdf", "6.pdf"), folderPdfs(items).map { it.name })
    }

    @Test
    fun `a tap on a recent PDF opens it in Compress`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "recent.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalFolderPdfs provides { listOf(item("recent.pdf", uri = uri)) }) {
                CompressView(onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.onNodeWithText("recent.pdf").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Optimization level")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Compress and save PDF").assertIsDisplayed()
    }

    @Composable
    private fun AtDoubleText(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) { content() }

    @Test
    fun `the primary action stays on screen at twice the text size, however long the options`() {
        val action = "Compress and save PDF"
        rule.setContent {
            AtDoubleText {
                ToolConfigLayout(action = action, onAction = {}, actionEnabled = false) {
                    repeat(12) { ToolOptionRow(selected = it == 0, title = "Option $it", description = "A long description of option $it", onClick = {}) }
                }
            }
        }
        rule.onNodeWithText(action).assertIsDisplayed().assertIsNotEnabled()
        val button = rule.onNodeWithText(action).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the action ends inside the screen", button.bottom <= root.bottom)
    }

    @Test
    fun `the keyboard pushes the primary action up instead of covering it`() {
        val action = "Protect and save"
        var view: View? = null
        rule.setContent {
            view = LocalView.current
            ToolConfigLayout(action = action, onAction = {}) { Text("Password") }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val keyboard = (root.height / 2).toInt()
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        rule.waitForIdle()
        val button = rule.onNodeWithText(action).fetchSemanticsNode().boundsInRoot
        assertTrue("the action ends above the keyboard: ${button.bottom} > ${root.bottom - keyboard}", button.bottom <= root.bottom - keyboard)
    }

    @Test
    fun `Merge opened with one recent file says another PDF is needed`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "only-one.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalFolderPdfs provides { listOf(item("only-one.pdf", uri = uri)) }) {
                MergeView(onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.onNodeWithText("only-one.pdf").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Add at least one more PDF")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add at least one more PDF").assertIsNotEnabled()
        rule.onNodeWithText("Unlock all files first").assertDoesNotExist()
    }

    private fun result(onOpen: (() -> Unit)?, mime: String = PDF_MIME, done: () -> Unit = {}, more: () -> Unit = {}) = rule.setContent {
        SuccessView(
            message = "Compression complete",
            subMessage = "",
            result = ToolResult(Uri.parse("content://test/out.pdf"), "out.pdf", mime),
            onDone = done,
            onProcessMore = more,
            onOpen = onOpen
        )
    }

    @Test
    fun `the result card names the file and opens it in the reader`() {
        var opened = 0
        result(onOpen = { opened++ })
        rule.onNodeWithText("Compression complete").assertExists()
        rule.onNodeWithText("out.pdf").assertExists()
        rule.onNodeWithText("Open").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `Share beside Open hands the file to the share sheet`() {
        result(onOpen = {})
        rule.onNodeWithText("Share").performClick()
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Uri.parse("content://test/out.pdf"), send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test
    fun `a result that does not open in the reader offers Share alone`() {
        result(onOpen = null, mime = ZIP_MIME)
        rule.onNodeWithText("Open").assertDoesNotExist()
        rule.onNodeWithText("Share").assertIsDisplayed()
    }

    @Test
    fun `Process more files and Back to tools leave the result`() {
        var done = 0
        var more = 0
        result(onOpen = {}, done = { done++ }, more = { more++ })
        rule.onNodeWithText("Process more files").performClick()
        rule.onNodeWithText("Back to tools").performClick()
        assertEquals(1, more)
        assertEquals(1, done)
    }

    @Test
    fun `the result's top bar says how long it took and goes back to the tools`() {
        var back = 0
        rule.setContent { ToolScreenBar(ToolState.SUCCESS, "Compress", "Optimize PDF size", "1.8 s", onBack = { back++ }) }
        rule.onNodeWithText("Done in 1.8 s").assertExists()
        rule.onNodeWithText("Optimize PDF size").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back to tools").performClick()
        assertEquals(1, back)
    }
}
