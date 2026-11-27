package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.haziaferi.scanknifeplus.ui.scan.SaveDialog
import com.paperknifeplus.app.MainBottomBar
import com.paperknifeplus.app.HOME_ROUTE
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.assertTargetsFit
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.TargetStep
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ledger L8's new controls at twice the text size, on the 6T's 384 dp and on 320 dp, in English and Italian: "Save elsewhere…" sits
 * directly under each tool's save button (a page grid's under its file card, at this text size) and under the Save dialog's commit, and
 * History's Move to folder line, Home's folder bar and the
 * Delete dialog show whole. Each check holds every target to its step with no overlap ([assertTargetsFit]) and finds its text uncut.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
abstract class StorageLayout {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private val elsewhere get() = text(R.string.tool_save_elsewhere)

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { PaperKnifePlusTheme { content() } }
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Fails when [label] is cut: its layout, in the space its container gave it, overflows or loses its end. */
    private fun assertWhole(label: String) {
        val nodes = rule.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$label shows", nodes.isNotEmpty())
        for (node in nodes) {
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            assertFalse("$label is cut off at the bottom", layout.didOverflowHeight)
            assertEquals("$label loses its end", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
    }

    private val SemanticsNode.hit: Rect get() = touchBoundsInRoot

    /** The one clickable node showing [label]. */
    private fun target(label: String): SemanticsNode {
        val nodes = rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
        assertEquals("one \"$label\" button", 1, nodes.size)
        return nodes.single()
    }

    /**
     * "Save elsewhere…" is a whole T3 target directly under the save button above it: the nearest target above it, overlapping it across,
     * ends where its hit area starts or within T3's gap, and is a T1 (or its peer in one row).
     */
    private fun assertElsewhereUnderSave(where: String) {
        rule.waitForIdle()
        val button = target(elsewhere)
        val least = with(rule.density) { TargetStep.ACT.hit.toPx() } - 0.5f
        assertTrue("$where: Save elsewhere is ${button.hit}", button.hit.height >= least && button.hit.width >= least)
        // A page grid at large text keeps it under its file card, so a whole page always fits in view (rule 10): it scrolls with the pages.
        if (generateSequence(button.parent) { it.parent }.any { SemanticsActions.ScrollBy in it.config }) {
            assertWhole(elsewhere)
            rule.assertTargetsFit(rule.density, "$where at 2.0x")
            return
        }
        val above = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .filter { it.id != button.id && it.hit.bottom <= button.hit.top + 0.5f && it.hit.right > button.hit.left && it.hit.left < button.hit.right }
            .maxByOrNull { it.hit.bottom }
        assertNotNull("$where: a save button above Save elsewhere", above)
        val gap = button.hit.top - above!!.hit.bottom
        assertTrue("$where: Save elsewhere is $gap px under the button above", gap <= with(rule.density) { TargetStep.INLINE_GAP.toPx() })
        assertWhole(elsewhere)
        rule.assertTargetsFit(rule.density, "$where at 2.0x")
    }

    @Composable
    private fun ToolScreen(id: String, uri: Uri?) = ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> }))

    /** Tool [id] with a 3-page file ([pdf]) loaded, once its Save elsewhere shows. */
    private fun configuring(id: String, pdf: ByteArray = TestPdfs.plain(3), unlock: Boolean = false) {
        val uri = TestPdfs.write(context.cacheDir, pdf, "layout-$id.pdf")
        show { ToolScreen(id, uri) }
        if (unlock) {
            rule.waitUntil(30_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
            rule.onNode(hasText(text(R.string.action_unlock)) and hasClickAction()).performClick()
        }
        rule.waitUntil(30_000) { shown(elsewhere) }
        assertElsewhereUnderSave(id)
    }

    @Test fun `Merge`() = configuring("merge")
    @Test fun `Split`() = configuring("split")
    @Test fun `Delete`() = configuring("delete")
    @Test fun `Rearrange`() = configuring("rearrange")
    @Test fun `Rotate`() = configuring("rotate")
    @Test fun `Sign`() = configuring("sign")
    @Test fun `Watermark`() = configuring("watermark")
    @Test fun `Page numbers`() = configuring("page-numbers")
    @Test fun `Bookmarks`() = configuring("bookmarks")
    @Test fun `Links`() = configuring("links")
    @Test fun `Crop`() = configuring("crop")
    @Test fun `Compress`() = configuring("compress")
    @Test fun `Grayscale`() = configuring("grayscale")
    @Test fun `Repair`() = configuring("repair")
    @Test fun `Protect`() = configuring("protect")
    @Test fun `Unlock`() = configuring("unlock", TestPdfs.userPassword(3), unlock = true)
    @Test fun `Metadata`() = configuring("metadata")
    @Test fun `PDF to images`() = configuring("pdf2img")
    @Test fun `PDF to ZIP`() = configuring("pdf2zip")
    @Test fun `Extract images`() = configuring("extract-images")
    @Test fun `Make searchable`() = configuring("searchable")

    @Test
    fun `Images to PDF`() {
        val bitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val images = (1..2).map { TestPdfs.write(context.cacheDir, png, "layout-photo-$it.png") }
        val output = Uri.fromFile(File(context.cacheDir, "layout-images.pdf"))
        show { CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output, picked = images)) { ToolScreen("img2pdf", null) } }
        rule.onNode(hasText(text(R.string.tool_step_choose_images)) and hasClickAction()).performClick()
        rule.waitUntil(60_000) { shown(elsewhere) }
        assertElsewhereUnderSave("img2pdf")
    }

    @Test
    fun `the Save dialog without a folder has Save elsewhere under its commit`() {
        show { SaveDialog(onSave = {}, onDismiss = {}, onSaveElsewhere = {}) }
        rule.waitForIdle()
        val commit = target(text(R.string.scan_leave_save))
        val button = target(elsewhere)
        assertTrue("Save elsewhere is under the commit", button.hit.top >= commit.hit.bottom - 0.5f)
        assertWhole(elsewhere)
        assertWhole(text(R.string.scan_leave_save))
        rule.assertTargetsFit(rule.density, "the Save dialog at 2.0x")
    }

    private val kept = SavedEntry("1", "Lease agreement 2026 signed and scanned.pdf", "merge", 1_200_000, 3, 3_000, SavedPlace.APP, file = "kept.pdf")

    @Test
    fun `History's Move to folder line and a file kept in the app with its Delete`() {
        show { HistoryView(onItemClick = { _, _, _ -> }, saved = listOf(kept), onMoveToFolder = {}) }
        rule.waitForIdle()
        assertWhole(text(R.string.folder_bar_in_app))
        assertWhole(text(R.string.folder_bar_move))
        assertWhole(kept.name)
        assertWhole(text(R.string.history_delete))
        target(text(R.string.folder_bar_in_app))
        rule.assertTargetsFit(rule.density, "History at 2.0x")
    }

    @Test
    fun `Home's folder bar without a folder, with Move to folder`() {
        show {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                    HomeView(
                        onAllTools = {}, onSettings = {}, onScan = {}, onImportPhotos = {}, onSeeAll = {}, onOpenPreview = { _, _, _ -> },
                        folder = FolderStatus.NotChosen, saved = listOf(kept), appFiles = true
                    )
                }
                MainBottomBar(HOME_ROUTE, onNavigate = {}, onScan = {})
            }
        }
        rule.waitForIdle()
        assertWhole(text(R.string.folder_bar_in_app))
        assertWhole(text(R.string.folder_bar_move))
        rule.assertTargetsFit(rule.density, "Home at 2.0x")
    }

    @Test
    fun `the Delete dialog`() {
        show { DeleteSavedDialog(kept.name, onDelete = {}, onDismiss = {}) }
        rule.waitForIdle()
        assertWhole(text(R.string.history_delete_title))
        assertWhole(text(R.string.history_delete_body, kept.name))
        target(text(R.string.history_delete))
        target(text(R.string.action_cancel))
        rule.assertTargetsFit(rule.density, "the Delete dialog at 2.0x")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "w384dp-h2400dp")
class StorageLayoutTest : StorageLayout()

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "w320dp-h2400dp")
class StorageLayoutSmallPhoneTest : StorageLayout()

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "it-w384dp-h2400dp")
class StorageLayoutItalianTest : StorageLayout()

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "it-w320dp-h2400dp")
class StorageLayoutItalianSmallPhoneTest : StorageLayout()

/**
 * At twice the text size on a short 320 x 640 dp phone, in Italian, pinning Save elsewhere would squeeze the screen's content under half
 * the window, so it waits at the end of the scrolling content (Crop) or under the page grid's file card (Rearrange): swipes bring it into view,
 * a whole T3 target, and the pinned bar keeps only the save button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(fontScale = 2f, qualifiers = "it-w320dp-h640dp")
class StorageLayoutShortPhoneTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun reachable(id: String) {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "short-$id.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                PaperKnifePlusTheme { ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> })) }
            }
        }
        val label = context.getString(R.string.tool_save_elsewhere)
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Document", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        repeat(30) {
            val node = rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().firstOrNull()
            val view = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxByOrNull { it.boundsInRoot.height }!!.boundsInRoot
            if (node != null && node.boundsInRoot.height >= node.size.height - 0.5f && node.boundsInRoot.bottom <= view.bottom + 0.5f &&
                node.touchBoundsInRoot.bottom <= window.bottom + 0.5f
            ) {
                val least = with(rule.density) { TargetStep.ACT.hit.toPx() } - 0.5f
                assertTrue("$id: Save elsewhere is ${node.touchBoundsInRoot}", node.touchBoundsInRoot.height >= least)
                assertTrue("$id: it scrolls with the content", generateSequence(node.parent) { it.parent }.any { SemanticsActions.ScrollBy in it.config })
                rule.assertTargetsFit(rule.density, "$id on a short phone")
                return
            }
            // Short swipes, so none passes it by.
            rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxByOrNull { it.boundsInRoot.height }!!.let { scroll ->
                rule.onNode(SemanticsMatcher("the screen's scroll") { it.id == scroll.id }).performTouchInput { swipeUp(startY = centerY + 60f, endY = centerY - 60f) }
            }
            rule.waitForIdle()
        }
        fail("$id: Save elsewhere stays out of reach")
    }

    @Test fun `Crop's Save elsewhere is at the end of its content`() = reachable("crop")
    @Test fun `Rearrange's Save elsewhere is under its file card`() = reachable("rearrange")
}
