package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.folder.FolderBlockedDialog
import com.haziaferi.scanknifeplus.ui.scan.FilterDialog
import com.haziaferi.scanknifeplus.ui.scan.LeaveDialog
import com.haziaferi.scanknifeplus.ui.scan.SaveDialog
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.assertCommitFilled
import com.paperknifeplus.app.testing.assertTargetsFit
import com.paperknifeplus.app.testing.assertTextOnTheScale
import com.paperknifeplus.app.testing.assertTitleFits
import com.paperknifeplus.app.testing.assertWordsWhole
import com.paperknifeplus.app.testing.dialogButton
import com.paperknifeplus.app.testing.dialogTextLayouts
import com.paperknifeplus.app.ui.theme.DocumentContent
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.Typography
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every dialog of the app, opened: R5's filled commit (the only filled button, a pixel check), R14's hit areas (none under its step, none
 * overlapping), and the title's words whole at no less than the body text's size (decision 6 of 2026-11-24). At the default text size the
 * dialogs' text is also held to the type scale, with Page numbers' sheets. Four sizes: [DialogTextScaleTest] at the default text size on the
 * 6T's 384 dp, then twice it on 320 dp in English ([DialogLargeTextSmallPhoneTest]) and Italian ([DialogLargeTextSmallPhoneItalianTest]) and
 * on 384 dp in Italian ([DialogLargeTextItalianTest]). Watermark's focus bar, whose buttons share one row while their words fit, too. The
 * folder check's dialog is left out: it has no title and only Cancel, and its spinner never lets the test go idle.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
@Config(qualifiers = "w384dp-h2400dp")
abstract class DialogWalk(private val fontScale: Float) {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()

    /** The theme's primary, which a commit fills with. */
    private var accent = Color.Unspecified

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    protected fun show(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            PaperKnifePlusTheme {
                accent = MaterialTheme.colorScheme.primary
                content()
            }
        }
    }

    /** The tool [id] with a 3-page file loaded. */
    protected fun open(id: String) {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "scale-$id.pdf")
        show { ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> })) }
        rule.waitUntil("$id shows its pages", 60_000) { rule.onAllNodes(hasClickAction() and hasContentDescription(firstPage())).fetchSemanticsNodes().isNotEmpty() }
    }

    protected fun tap(matcher: SemanticsMatcher) {
        rule.onAllNodes(matcher)[0].performClick()
        rule.waitForIdle()
    }

    protected fun text(@StringRes id: Int) = context.getString(id)

    /** The first page's thumbnail, as the test's language reads it out. */
    private fun firstPage() = context.getString(R.string.tool_page_n, 1)

    protected fun tapFirstPage() = tap(hasClickAction() and hasContentDescription(firstPage()))

    /**
     * The open dialog: titled [title] (null: none), its [buttons] given start to end with the [commit] (null: none) among them. Checks the
     * commit's fill, the title and every hit area, and at the default text size the type scale.
     */
    private fun check(where: String, @StringRes title: Int?, buttons: List<Int>, @StringRes commit: Int?) {
        rule.waitForIdle()
        title?.let { rule.assertTitleFits(text(it), Typography.bodyMedium.fontSize) }
        rule.assertCommitFilled(buttons.map(::text), commit?.let(::text), accent, rule.density, where)
        rule.assertTargetsFit(rule.density, "$where at ${fontScale}x")
        if (fontScale == 1f) rule.assertTextOnTheScale(where)
    }

    @Test
    fun `the Save PDF dialog`() {
        show { SaveDialog(onSave = {}, onDismiss = {}) }
        check("the Save PDF dialog", R.string.scan_save_title, listOf(R.string.scan_cancel, R.string.scan_save_choose), R.string.scan_save_choose)
    }

    @Test
    fun `the Save PDF dialog into the folder`() {
        show { SaveDialog(onSave = {}, onDismiss = {}, folder = "Documents/Scans") }
        check("the Save PDF dialog into the folder", R.string.scan_save_title, listOf(R.string.scan_cancel, R.string.scan_save_folder), R.string.scan_save_folder)
    }

    @Test
    fun `leaving the scanner`() {
        show { LeaveDialog(1, onKeep = {}, onDiscard = {}, onStay = {}) }
        check(
            "leaving the scanner", R.string.scan_leave_title, listOf(R.string.scan_leave_stay, R.string.scan_discard, R.string.scan_leave_save),
            R.string.scan_leave_save
        )
    }

    @Test
    fun `the scanner's filters`() {
        show { FilterDialog(null, onPick = {}, onDismiss = {}) }
        check("the scanner's filters", R.string.scan_filter, listOf(R.string.scan_cancel), commit = null)
    }

    @Test
    fun `the folder can't be reached`() {
        show { FolderBlockedDialog("Documents/Scans", onChooseAgain = {}, onPicker = {}, onDismiss = {}) }
        val buttons = listOf(R.string.scan_folder_blocked_later, R.string.scan_folder_blocked_picker, R.string.scan_folder_blocked_choose)
        check("the folder can't be reached", R.string.scan_folder_blocked_title, buttons, R.string.scan_folder_blocked_choose)
    }

    @Test
    fun `a web link's prompt`() {
        show { WebLinkPrompt("https://example.org/a/long/path/to/a/page", onDismiss = {}) }
        check("a web link's prompt", R.string.link_confirm_title, listOf(R.string.action_cancel, R.string.history_open), R.string.history_open)
    }

    /**
     * Twice the text size is left out here and for Move to: a text field's floating label that wraps ("Numero di pagina (1-3)") makes the
     * dialog lay out again on every frame, so the test never goes idle. A one-line label does not; this PR does not change the fields.
     */
    private fun assumeOneLineLabels() =
        assumeTrue("L4: a wrapping field label lays the dialog out again on every frame at twice the text size", fontScale == 1f)

    @Test
    fun `Go to page`() {
        assumeOneLineLabels()
        show { JumpToPageDialog("2", 3, onJump = {}, onDismiss = {}) }
        check("Go to page", R.string.page_dialog_go_to_page, listOf(R.string.action_cancel, R.string.action_go), R.string.action_go)
    }

    @Test
    fun `Bookmarks' Add bookmark`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "scale-bookmarks.pdf")
        show { ToolCatalog.byId("bookmarks")!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> })) }
        val add = hasText(text(R.string.action_add)) and hasClickAction()
        rule.waitUntil("Bookmarks shows its file", 60_000) { rule.onAllNodes(add).fetchSemanticsNodes().isNotEmpty() }
        tap(add)
        check("Bookmarks' Add bookmark", R.string.bookmarks_add_bookmark, listOf(R.string.action_add), R.string.action_add)
    }

    @Test
    fun `Rearrange's Move to dialog`() {
        assumeOneLineLabels()
        open("rearrange")
        tapFirstPage()
        tap(hasText(text(R.string.rearrange_move_to)) and hasClickAction())
        check("Rearrange's Move to dialog", R.string.rearrange_move_page_to_position, listOf(R.string.action_cancel, R.string.action_move), R.string.action_move)
    }

    @Test
    fun `Sign's options`() {
        open("sign")
        tapFirstPage()
        tap(hasText(text(R.string.sign_add_sign)) and hasClickAction())
        check("Sign's options", R.string.sign_add_signature, buttons = emptyList(), commit = null)
    }

    /** Adopt is enabled once something is drawn: strokes across the 300 dp pad, which sits over the Save signature line. */
    @Test
    fun `Sign's signature pad`() {
        show { SignaturePadDialog(onDismiss = {}, onSave = { _, _ -> }, accentColor = MaterialTheme.colorScheme.primary, initialColor = Color.Black) }
        rule.waitForIdle()
        val dialog = rule.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val save = rule.onNode(hasText(text(R.string.sign_save_signature)) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNode()
        val above = save.boundsInRoot.top - dialog.top
        for (up in listOf(60, 120, 180, 240)) {
            val y = above - with(rule.density) { up.dp.toPx() }
            if (y > 0) rule.onNode(isDialog()).performTouchInput { swipe(Offset(width * 0.3f, y), Offset(width * 0.7f, y)) }
        }
        rule.waitForIdle()
        rule.dialogButton(text(R.string.sign_adopt)).assertIsEnabled()
        check("Sign's signature pad", R.string.sign_draw_signature, listOf(R.string.sign_adopt), R.string.sign_adopt)
    }

    @Test
    fun `Watermark's options`() {
        open("watermark")
        tapFirstPage()
        tap(hasText(text(R.string.watermark_add_watermark)) and hasClickAction())
        check("Watermark's options", R.string.watermark_select_watermark, buttons = emptyList(), commit = null)
    }

    @Test
    fun `Watermark's text dialog`() {
        open("watermark")
        tapFirstPage()
        tap(hasText(text(R.string.watermark_add_watermark)) and hasClickAction())
        tap(hasText(text(R.string.watermark_text_watermark)) and hasClickAction())
        check("Watermark's text dialog", R.string.watermark_text_watermark, listOf(R.string.action_continue), R.string.action_continue)
    }

    /**
     * [DialogTitle] alone, in the dialogs' title style, at the narrowest a dialog's text gets (its 280 dp less 24 dp a side). Here the text
     * size is the test's own: a dialog's window takes the system's, which Robolectric scales below twice at 24 sp, so the notes' longest
     * Italian words fit there even unshrunk. A word too long even at the body text's size stops at that size.
     */
    @Test
    fun `a dialog title shrinks only to keep its longest word whole, never under the body text`() {
        val titles = listOf("Tenere le pagine scansionate?", "Impossibile raggiungere la cartella", "Aggiungi un segnalibro")
        // Too wide at the title's size and, at twice the text size, at the body text's too; then twice as long.
        val long = "Precipitevolissimevolmente"
        val endless = long + long.lowercase()
        show {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineSmall) {
                Column(Modifier.width(232.dp)) { (titles + long + endless).forEach { DialogTitle(it) } }
            }
        }
        rule.waitForIdle()
        val floor = Typography.bodyMedium.fontSize
        fun layout(title: String): TextLayoutResult {
            val node = rule.onNode(hasText(title), useUnmergedTree = true).fetchSemanticsNode()
            return mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it) }.single()
        }
        for (title in titles) {
            val layout = layout(title)
            assertWordsWhole(layout)
            assertTrue("\"$title\" is drawn at ${layout.layoutInput.style.fontSize}", layout.layoutInput.style.fontSize.value >= floor.value)
            if (fontScale == 1f) assertEquals("\"$title\" keeps the title's size", Typography.headlineSmall.fontSize, layout.layoutInput.style.fontSize)
        }
        val shrunk = layout(long).layoutInput.style.fontSize
        if (fontScale == 1f) {
            assertWordsWhole(layout(long))
            assertTrue("$long shrinks to fit, above the body text: $shrunk", shrunk.value > floor.value && shrunk < Typography.headlineSmall.fontSize)
        } else {
            assertEquals("$long stops at the body text's size", floor, shrunk)
        }
        assertEquals("the endless word stops at the body text's size", floor, layout(endless).layoutInput.style.fontSize)
    }

    /** Watermark's focus bar: Annulla, Applica a tutte and Applica (and the English) break only between words, in one row or stacked. */
    @Test
    fun `Watermark's focus bar keeps every word whole`() {
        open("watermark")
        tapFirstPage()
        tap(hasText(text(R.string.watermark_add_watermark)) and hasClickAction())
        tap(hasText(text(R.string.watermark_draft)) and hasClickAction())
        for (id in listOf(R.string.action_cancel, R.string.action_apply_to_all, R.string.action_apply)) {
            rule.dialogTextLayouts(text(id)).forEach(::assertWordsWhole)
        }
        rule.assertTargetsFit(rule.density, "Watermark's focus bar at ${fontScale}x")
    }
}

@RunWith(RobolectricTestRunner::class)
class DialogTextScaleTest : DialogWalk(fontScale = 1f) {
    /** Rule 5: Keep scanning (stay), Discard, then Save scan (keep), start to end in a row, or stacked with Save scan on top. */
    @Test
    fun `leaving the scanner offers Keep scanning, Discard and Save scan in that order`() {
        var kept = 0
        var stayed = 0
        var discarded = 0
        show { LeaveDialog(2, onKeep = { kept++ }, onDiscard = { discarded++ }, onStay = { stayed++ }) }
        rule.onNode(hasText("Save the scan?")).assertExists()
        rule.onNode(hasText("2 pages aren't a PDF yet. Save the scan to finish it later, or discard it.")).assertExists()
        val bounds = listOf("Keep scanning", "Discard", "Save scan").map { rule.dialogButton(it).fetchSemanticsNode().boundsInRoot }
        if (bounds.map { it.top }.distinct().size == 1) {
            assertEquals("start to end in a row", bounds.map { it.left }.sorted(), bounds.map { it.left })
        } else {
            assertEquals("stacked, Save scan on top", bounds.map { it.top }.sortedDescending(), bounds.map { it.top })
        }
        rule.dialogButton("Save scan").performClick()
        assertEquals(listOf(1, 0, 0), listOf(kept, stayed, discarded))
        rule.dialogButton("Keep scanning").performClick()
        assertEquals(listOf(1, 1, 0), listOf(kept, stayed, discarded))
    }

    /** Back from an edited Single page (owner, 2026-11-25): Keep editing stays, Retake discards for the camera, Save scan keeps. */
    @Test
    fun `the retake prompt offers Keep editing, Retake and Save scan`() {
        var kept = 0
        var stayed = 0
        var discarded = 0
        show { LeaveDialog(1, onKeep = { kept++ }, onDiscard = { discarded++ }, onStay = { stayed++ }, retake = true) }
        rule.onNode(hasText("Save the scan?")).assertExists()
        rule.onAllNodes(hasText("Keep scanning")).assertCountEquals(0)
        rule.onAllNodes(hasText("Discard")).assertCountEquals(0)
        val bounds = listOf("Keep editing", "Retake", "Save scan").map { rule.dialogButton(it).fetchSemanticsNode().boundsInRoot }
        if (bounds.map { it.top }.distinct().size == 1) {
            assertEquals("start to end in a row", bounds.map { it.left }.sorted(), bounds.map { it.left })
        } else {
            assertEquals("stacked, Save scan on top", bounds.map { it.top }.sortedDescending(), bounds.map { it.top })
        }
        rule.dialogButton("Retake").performClick()
        assertEquals(listOf(0, 0, 1), listOf(kept, stayed, discarded))
        rule.dialogButton("Keep editing").performClick()
        assertEquals(listOf(0, 1, 1), listOf(kept, stayed, discarded))
    }

    @Test
    fun `Page numbers' position sheet`() {
        open("page-numbers")
        tap(hasContentDescription("Numbering options") and hasClickAction())
        tap(hasText("Position", substring = true) and hasClickAction())
        rule.assertTextOnTheScale("Page numbers' position sheet")
    }

    @Test
    fun `Page numbers' colour sheet`() {
        open("page-numbers")
        tap(hasContentDescription("Numbering options") and hasClickAction())
        tap(hasText("Color") and hasClickAction())
        rule.assertTextOnTheScale("Page numbers' colour sheet")
    }

    /** The preview's number sits on the font's own line, as near the page's edge as it will print: no line height pads it. */
    @Test
    fun `the page number on a page's preview takes no more than its own line`() {
        open("page-numbers")
        val numbers = rule.onAllNodes(SemanticsMatcher.keyIsDefined(DocumentContent), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the preview shows its numbers", numbers.isNotEmpty())
        val layouts = mutableListOf<TextLayoutResult>()
        numbers.first().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        // 14 pt prints as a 7 sp number on the preview; its own line is about 1.3 times that.
        val most = with(rule.density) { (7 * 1.5).sp.toPx() }
        assertTrue("the number's line is ${layout.size.height} px, past $most", layout.size.height <= most)
    }

    /** On the 6T's width in Italian the focus bar's buttons share one row (#82), each word whole. */
    @Test
    @Config(qualifiers = "it-w384dp-h2400dp")
    fun `in Italian on the 6T Watermark's focus bar keeps its buttons in one row`() {
        `Watermark's focus bar keeps every word whole`()
        val tops = listOf(R.string.action_cancel, R.string.action_apply_to_all, R.string.action_apply).map {
            rule.dialogButton(text(it)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("the buttons share one row: $tops", 1, tops.distinct().size)
    }

    /** On 320 dp Annulla is wider than its share of the row, so the buttons stack, Applica on top. */
    @Test
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on 320 dp Watermark's focus bar stacks with the commit on top`() {
        `Watermark's focus bar keeps every word whole`()
        val tops = listOf(R.string.action_apply, R.string.action_apply_to_all, R.string.action_cancel).map {
            rule.dialogButton(text(it)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("Applica, Applica a tutte, then Annulla, top to bottom: $tops", tops.sorted(), tops)
        assertEquals("one button a row: $tops", 3, tops.distinct().size)
    }
}

/** Twice the text size on a 320 dp phone, in English. */
@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "w320dp-h2400dp")
class DialogLargeTextSmallPhoneTest : DialogWalk(fontScale = 2f)

/** Twice the text size on a 320 dp phone, in Italian, whose words run longest. */
@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "it-w320dp-h2400dp")
class DialogLargeTextSmallPhoneItalianTest : DialogWalk(fontScale = 2f)

/** Twice the text size on the 6T's 384 dp, in Italian. */
@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "it-w384dp-h2400dp")
class DialogLargeTextItalianTest : DialogWalk(fontScale = 2f)
