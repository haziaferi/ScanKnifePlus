package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.haziaferi.scanknifeplus.ui.scan.SaveDialog
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.assertTargetsFit
import com.paperknifeplus.app.testing.drawnTop
import com.paperknifeplus.app.testing.radioButton
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.Typography
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At twice the text size, Settings rows and the tools' shared scaffold grow with their text instead of clipping it, in English and, for the
 * segmented control, in Italian.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
// Twice the text size for every window, the sheets' dialogs too; LocalDensity alone would reach only the content window.
@Config(fontScale = 2f)
class LargeTextLayoutTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    /** [content] at twice the text size, in the app's type scale. */
    private fun atDoubleText(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            MaterialTheme(typography = Typography) { content() }
        }
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun text(text: String): Rect = rule.onNodeWithText(text, useUnmergedTree = true).bounds()

    private fun layoutOf(text: String): TextLayoutResult = layoutOf(rule.onNodeWithText(text, useUnmergedTree = true))

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** Fails when [text] is cut: its own layout, measured in the space its container gave it, overflows (bounds alone cannot show this). */
    private fun assertNotClipped(text: String) = assertNotClipped(text, rule.onNodeWithText(text, useUnmergedTree = true))

    /** [assertNotClipped] for [node], which shows [text]. */
    private fun assertNotClipped(text: String, node: SemanticsNodeInteraction) {
        val layout = layoutOf(node)
        // Height overflow is a cut line; a visible end short of the text is a line dropped by maxLines or an ellipsis.
        assertFalse("$text is cut off at the bottom", layout.didOverflowHeight)
        assertEquals("$text loses its end", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    @Test
    fun `Settings rows and palette chips show their text uncut`() {
        atDoubleText {
            SettingsView(
                onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = {}, onBack = {},
                folder = FolderStatus.ChooseAgain("Documents/Scans")
            )
        }
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Choose again"))
        assertNotClipped("Folder")
        assertNotClipped("Choose again")
        assertNotClipped("Colour palette")
        assertNotClipped("Still Water")
        assertTrue("the value is beside the name", text("Choose again").left >= text("Folder").right)

        rule.onNodeWithText("Colour palette").performClick()
        assertEquals("the sheet measures at twice the text size", 2f, layoutOf("Graphite Scholastic").layoutInput.density.fontScale)
        assertNotClipped("Graphite Scholastic")
    }

    /**
     * The longest screen title in Italian on a 320 dp phone stays one whole word with room to spare: at 22 sp it fitted by about 19 dp here,
     * and the 6T's font draws wider than Robolectric's, so the title's 21 sp keeps at least 24 dp.
     */
    @Test
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on a small phone Settings' title Impostazioni keeps one line with room to spare`() {
        atDoubleText { SettingsView(onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = {}, onBack = {}) }
        val layout = layoutOf("Impostazioni")
        assertEquals("Impostazioni wraps", 1, layout.lineCount)
        val room = with(rule.density) { (layout.layoutInput.constraints.maxWidth - layout.getLineRight(0)).toDp() }
        assertTrue("Impostazioni has only $room spare", room >= 24.dp)
    }

    @Test
    fun `the tool scaffold grows with its text`() {
        val label = "Compress and save PDF with a longer label"
        val name = "a-rather-long-file-name-for-the-test.pdf"
        atDoubleText {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolTopBar(title = "Compress", subtitle = "Optimize PDF size", showChange = true, onBack = {}, onChange = {})
                ToolFileCard(Uri.parse("content://test/a.pdf"), null, name, "1.2 MB • 3 pages", onChange = {})
                ToolOptionRow(selected = true, title = "Recommended", description = "Great balance of size and quality", onClick = {})
                ToolPrimaryButton(label, onClick = {})
            }
        }
        // The top bar's Change comes first in the tree, the file card's second.
        val changes = rule.onAllNodes(hasClickAction() and hasText("Change"))
        val barChange = changes[0].bounds()
        // Beside the title, or under it when the bar stacks.
        for (line in listOf("Compress", "Optimize PDF size")) {
            assertTrue("$line keeps clear of Change", text(line).right <= barChange.left || text("Optimize PDF size").bottom <= barChange.top)
        }
        assertTrue("the file name runs beside the card's Change", text(name).right <= changes[1].bounds().left)

        val shown = listOf("Compress", "Optimize PDF size", name, "1.2 MB • 3 pages", "Recommended", "Great balance of size and quality", label)
        for (text in shown) assertNotClipped(text)
    }

    @Test
    fun `the empty tool screen's steps show their text uncut`() {
        atDoubleText { ToolEmptyState(Icons.Outlined.Bolt, "Optimize any PDF document", "Make the file smaller", onChoose = {}, onOpenRecent = null) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Your original file stays as it is"))
        // The step numbers too: their circles grow with the text instead of cutting it.
        val shown = listOf("1", "2", "3", "Optimize any PDF document", "From your folder or anywhere on this phone", "Make the file smaller",
            "Your original file stays as it is")
        for (text in shown) assertNotClipped(text)
    }

    @Test
    fun `Links' file card and counts show their text uncut`() {
        atDoubleText {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolFileCard(Uri.parse("content://test/a.pdf"), null, "Insurance letters.pdf", "2.4 MB • 12 pages", onChange = {}, keepsPassword = true)
                LinkCountsCard(FoundLinks(inside = listOf(0), leaving = listOf(14), automatic = LinkRemoval(0, 1)), LinkOptions())
            }
        }
        val shown = listOf(PASSWORD_LINE, "Found in this file", "14", "links", "1", "script that runs on its own",
            "Nothing changes until you save. Form fields, comments and bookmarks stay.")
        for (text in shown) assertNotClipped(text)
        assertEquals("the counts sit side by side", text("14").top, text("1").top, 0.5f)
    }

    @Test
    fun `Links' counts with nothing to remove show their text uncut`() {
        atDoubleText { LinkCountsCard(FoundLinks(inside = listOf(0), leaving = listOf(0), automatic = LinkRemoval(0, 0)), LinkOptions()) }
        for (text in listOf("Found in this file", "No links or scripts in this file", "There is nothing to remove, so nothing is saved.")) {
            assertNotClipped(text)
        }
    }

    @Test
    // A small phone, and tall, so the whole screen is laid out and touches reach it.
    @Config(qualifiers = "w320dp-h2400dp")
    fun `Links' Nothing to remove shows uncut on its button`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        // Three links, all to pages of the file, and no script: External links removes nothing.
        val input = TestPdfs.write(context.cacheDir, TestPdfs.linked(3), "large-inside.pdf")
        atDoubleText { LinksView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Remove links")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("External links").performClick()
        rule.waitForIdle()
        assertNotClipped("Nothing to remove")
    }

    @Test
    // A small phone, and tall, so the whole screen is laid out and touches reach it.
    @Config(qualifiers = "w320dp-h2400dp")
    fun `Links with its choices, disabled buttons and a range error shows its text uncut, and nothing sideways`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.linksAndScript(), "large-links.pdf")
        atDoubleText { LinksView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Remove links")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Page range").performClick()
        // The disabled button for an untouched range, then for a range with an error, which shows under the field.
        assertNotClipped("Type the pages to remove links from")
        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("3")
        assertNotClipped("Check the page range")
        assertNotClipped("“3”: page 3 doesn’t exist. This file has 2 pages.")
        field.performTextReplacement("2")
        rule.onNodeWithText("External links").performClick()
        rule.waitForIdle()

        // "Pages" labels the section and the field.
        val pages = rule.onAllNodesWithText("Pages", useUnmergedTree = true)
        val pageLabels = pages.fetchSemanticsNodes().size
        assertTrue("the section's and the field's Pages", pageLabels >= 2)
        for (i in 0 until pageLabels) assertNotClipped("Pages", pages[i])

        val segments = listOf("All pages", "Page range", "All links", "External links")
        val shown = segments + listOf(
            "Found on these pages", "external link", "script that runs on its own",
            "Scripts and actions that run on their own go from the whole file.",
            "Nothing changes until you save. Form fields, comments and bookmarks stay.", "For example 1-3, 5, 8-", "Links to remove",
            "Links to pages of this file stay, such as a table of contents.", "Remove links", "Saves a new PDF. The original stays as it is."
        )
        for (text in shown) assertNotClipped(text)
        // These labels fit their rows: each is one line, the whole of it shown.
        for (label in segments) {
            val layout = layoutOf(label)
            assertTrue("$label is cut at its end", layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.size.width + 0.5f)
        }
        assertNothingSideways()
    }

    @Test
    fun `the result screen's password line and report rows show their text uncut`() {
        atDoubleText {
            SuccessView(
                message = "Link removal complete",
                subMessage = "12 pages",
                result = ToolResult(Uri.parse("content://test/a-no-links.pdf"), "Insurance letters-no-links.pdf"),
                onDone = {},
                onProcessMore = {},
                onOpen = {},
                keepsPassword = true,
                report = listOf("Links" to 14, "Scripts that run on their own" to 1)
            )
        }
        val shown = listOf("Link removal complete", "Insurance letters-no-links.pdf", "12 pages", PASSWORD_LINE, "What was removed", "Links", "14",
            "Scripts that run on their own", "1")
        for (text in shown) assertNotClipped(text)
        assertTrue("a count is beside its label", text("1").left >= text("Scripts that run on their own").right)
    }

    @Test
    fun `Compare's file cards grow with their text`() {
        atDoubleText {
            Row(Modifier.width(320.dp)) {
                CompareFileCard("ORIGINAL (A)", "a-long-contract-name-2026.pdf", isSelected = true, modifier = Modifier.weight(1f)) {}
                CompareFileCard("REVISED (B)", "", isSelected = false, modifier = Modifier.weight(1f)) {}
            }
        }
        for (shown in listOf("ORIGINAL (A)", "a-long-contract-name-2026.pdf", "REVISED (B)", "Select file")) assertNotClipped(shown)
    }

    /** [assertNotClipped], and every line within the width the text was given: a label that wraps is whole, one cut at its end is not. */
    private fun assertFullyVisible(text: String) {
        assertNotClipped(text)
        val layout = layoutOf(text)
        for (line in 0 until layout.lineCount) {
            assertTrue("$text runs past the end of its room", layout.getLineRight(line) <= layout.size.width + 0.5f)
        }
    }

    /** Fails when any node runs sideways out of the window. */
    private fun assertNothingSideways() {
        val width = rule.onRoot().bounds().width
        for (node in rule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()) {
            assertTrue("$node runs sideways", node.boundsInRoot.left >= -0.5f && node.boundsInRoot.right <= width + 0.5f)
        }
    }

    @Test
    // An Italian phone, small, and tall, so the whole screen is laid out.
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on a small phone Links' segments show whole, a stacked one wrapping rather than cut, and nothing sideways`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.linksAndScript(), "large-links-it.pdf")
        // In the app's font, whose "Intervallo di pagine" is wider than its stacked row.
        atDoubleText { LinksView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Rimuovi i link")).fetchSemanticsNodes().isNotEmpty() }
        for (label in listOf("Tutte le pagine", "Intervallo di pagine", "Tutti i link", "Link esterni")) assertFullyVisible(label)
        assertTrue("the long label wraps onto a second line", layoutOf("Intervallo di pagine").lineCount > 1)
        assertNothingSideways()
    }

    @Test
    // An Italian phone, small, and tall, so the whole screen is laid out.
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on a small phone Crop's Pages segments show whole, a stacked one wrapping rather than cut, and nothing sideways`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "large-crop-it.pdf")
        // In the app's font, whose "Intervallo di pagine" is wider than its stacked row.
        atDoubleText { CropView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Sposta un angolo per ritagliare")).fetchSemanticsNodes().isNotEmpty() }
        for (label in listOf("Tutte le pagine", "Intervallo di pagine")) assertFullyVisible(label)
        assertTrue("the long label wraps onto a second line", layoutOf("Intervallo di pagine").lineCount > 1)
        assertNothingSideways()
    }

    @Test
    fun `the segmented control stacks into full-width rows of one height while their labels fit them`() {
        // As wide as on a 320 dp phone; the segments measure their labels in the font they are drawn in.
        atDoubleText {
            Column(Modifier.width(288.dp)) { SegmentedChoice(listOf("All pages", "Page range"), 1, onSelect = {}) }
        }
        for (label in listOf("All pages", "Page range")) assertNotClipped(label)
        val all = rule.onNode(hasText("All pages")).bounds()
        val range = rule.onNode(hasText("Page range")).bounds()
        assertTrue("the segments stack", range.top >= all.bottom)
        assertEquals("each row is full width", all.width, range.width, 0.5f)
        assertEquals("every row is one height", all.height, range.height, 0.5f)
        val width = with(rule.density) { 288.dp.toPx() }
        assertEquals(width, all.width, 1f)
        assertTrue("a row is one line, at least 48 dp", all.height >= with(rule.density) { 48.dp.toPx() } && layoutOf("All pages").lineCount == 1)
    }

    @Test
    @Config(qualifiers = "w600dp-h2400dp")
    fun `Home's Import photos and Open PDF grow to one height on every width`() {
        // Phone widths in steps, so the two labels wrap onto different numbers of lines at some of them, as on the 6T.
        var width by mutableStateOf(300.dp)
        atDoubleText {
            Column(Modifier.width(width)) {
                HomeView(onAllTools = {}, onSettings = {}, onScan = {}, onImportPhotos = {}, onSeeAll = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        var differentLines = false
        for (w in 300..440 step 10) {
            width = w.dp
            rule.waitForIdle()
            differentLines = differentLines || layoutOf("Import photos").lineCount != layoutOf("Open PDF").lineCount
            val photos = rule.onNode(hasText("Import photos") and hasClickAction()).bounds()
            val pdf = rule.onNode(hasText("Open PDF") and hasClickAction()).bounds()
            assertEquals("the peers are one height at $w dp", photos.height, pdf.height, 0.5f)
            assertEquals("side by side at $w dp", photos.top, pdf.top, 0.5f)
        }
        assertTrue("at some width one label wraps more than the other", differentLines)
    }

    @Test
    fun `the top bar's Back grows with the text action beside it`() {
        // A short title, so the bar stays on one line at twice the text size.
        atDoubleText { ToolTopBar(title = "Sign", subtitle = null, showChange = true, onBack = {}, onShowTip = {}) }
        val back = rule.onNode(hasContentDescription("Back") and hasClickAction()).bounds()
        val change = rule.onNode(hasText("Change") and hasClickAction()).bounds()
        val tip = rule.onNode(hasContentDescription("Show the tip") and hasClickAction()).bounds()
        assertTrue("Change has grown past 48 dp", change.height > with(rule.density) { 48.dp.toPx() } + 0.5f)
        assertEquals("Back is as high as Change", change.height, back.height, 0.5f)
        assertEquals("the (i) is as high as Change", change.height, tip.height, 0.5f)
    }

    /** Watermark's options at twice the text size on a short small phone: the dialog scrolls, so its last choice comes whole into view. */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `Watermark's options keep Upload image in reach on a short phone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "large-watermark.pdf")
        atDoubleText { WatermarkView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil("the file shows", 60_000) { rule.onAllNodes(hasText("Document", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // The file card fills the first screen here; the pages follow it in the one scroll.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        val page = hasClickAction() and hasContentDescription("Page 1")
        rule.waitUntil("the pages show", 60_000) { rule.onAllNodes(page).fetchSemanticsNodes().isNotEmpty() }
        // The page and its Add watermark are opened by their click actions: this test is about the dialog, not reaching them.
        rule.onAllNodes(page)[0].performSemanticsAction(SemanticsActions.OnClick)
        val add = hasText("Add watermark") and hasClickAction()
        rule.waitUntil("the page opens", 30_000) { rule.onAllNodes(add).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(add).performSemanticsAction(SemanticsActions.OnClick)
        val card = rule.onNode(hasText("Upload image") and hasClickAction())
        card.performScrollTo()
        val node = card.fetchSemanticsNode()
        assertEquals("Upload image shows whole", node.size.height.toFloat(), node.boundsInRoot.height, 0.5f)
    }

    @Test
    // A small phone, where Move to wraps at twice the text size (on the 6T it now fits one 48 dp line), as tall as the 6T: the file card
    // scrolls with the grid, so the bar keeps its room.
    @Config(qualifiers = "w320dp-h832dp")
    fun `Rearrange's arrows grow with Move to`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "large-rearrange.pdf")
        atDoubleText { RearrangeView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil("Move to shows", 60_000) { rule.onAllNodes(hasText("Move to…")).fetchSemanticsNodes().isNotEmpty() }
        val moveTo = rule.onNode(hasText("Move to…") and hasClickAction()).bounds()
        assertTrue("Move to has grown past 48 dp", moveTo.height > with(rule.density) { 48.dp.toPx() } + 0.5f)
        for (arrow in listOf("Move back", "Move forward")) {
            assertEquals("$arrow is as high as Move to", moveTo.height, rule.onNode(hasContentDescription(arrow) and hasClickAction()).bounds().height, 0.5f)
        }
    }

    @Test
    // The 6T's height, so the numbering sheet shows whole.
    @Config(qualifiers = "w384dp-h832dp")
    fun `Page numbers' colour button grows with the format field beside it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "large-numbers.pdf")
        atDoubleText { PageNumbersView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil("the options button shows", 60_000) {
            rule.onAllNodes(hasContentDescription("Numbering options")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasContentDescription("Numbering options") and hasClickAction()).performClick()
        rule.waitForIdle()
        val fieldNode = rule.onNode(hasSetTextAction() and hasText("Format ({n})"))
        val field = fieldNode.bounds()
        val colour = rule.onNode(hasText("Color") and hasClickAction()).bounds()
        assertTrue("the field has grown past 56 dp", field.height > with(rule.density) { 56.dp.toPx() } + 0.5f)
        assertNotClipped("Format ({n})", rule.onNode(hasText("Format ({n})") and hasSetTextAction().not(), useUnmergedTree = true))
        // The outline, where the field draws its border: right of the label's gap (the 13 sp label's reaches past 22 dp from the end), past
        // the 12 dp rounded corner.
        val outlineTop = field.top + fieldNode.drawnTop(listOf(14, 16, 18, 20).map { (field.width - with(rule.density) { it.dp.toPx() }).toInt() })
        val dp = with(rule.density) { 1.dp.toPx() }
        assertEquals("the colour button starts at the field's outline", outlineTop, colour.top, dp)
        assertEquals("the colour button ends with the field", field.bottom, colour.bottom, dp)
    }

    @Test
    // An Italian phone, small: Move to's label is longest there. As tall as the 6T, where the bar was squeezed with the grid before the
    // file card scrolled with it.
    @Config(qualifiers = "it-w320dp-h832dp")
    fun `in Italian on a small phone Rearrange's Move to shows uncut beside its arrows`() = rearrangeMoveToUncut()

    @Test
    @Config(qualifiers = "it-w384dp-h832dp")
    fun `in Italian on the 6T Rearrange's Move to shows uncut beside its arrows`() = rearrangeMoveToUncut()

    private fun rearrangeMoveToUncut() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "large-rearrange-it.pdf")
        atDoubleText { RearrangeView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil("Move to shows", 60_000) { rule.onAllNodes(hasText("Sposta in…")).fetchSemanticsNodes().isNotEmpty() }
        assertNotClipped("Sposta in…", rule.onNode(hasText("Sposta in…"), useUnmergedTree = true))
        val moveTo = rule.onNode(hasText("Sposta in…") and hasClickAction()).bounds()
        for (arrow in listOf("Sposta indietro", "Sposta avanti")) {
            assertEquals("$arrow is as high as Move to", moveTo.height, rule.onNode(hasContentDescription(arrow) and hasClickAction()).bounds().height, 0.5f)
        }
    }

    /** Fails when [node], which shows [text], breaks a line inside a word: each line but the last ends at a space. */
    private fun assertWholeWords(text: String, node: SemanticsNodeInteraction) {
        val layout = layoutOf(node)
        val s = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("$text breaks inside a word at line ${line + 1}", s[end - 1].isWhitespace() || s[end].isWhitespace())
        }
    }

    /** The topmost node showing [text], as the bar's copy of a line the screen also shows lower down. */
    private fun topmost(text: String): SemanticsNodeInteraction {
        val nodes = rule.onAllNodesWithText(text, useUnmergedTree = true)
        val tops = nodes.fetchSemanticsNodes().map { it.boundsInRoot.top }
        return nodes[tops.indexOf(tops.min())]
    }

    /**
     * Make searchable's empty screen at twice the text size: the bar's [title], [subtitle] and [label] whole and uncut, and nothing
     * overlapping. Returns whether the bar stacked its action under the title; it is then at the end edge, and Back ([back]) level with the
     * title's top.
     */
    private fun searchableBar(title: String, subtitle: String, label: String, back: String): Boolean {
        atDoubleText { SearchableView(onBack = {}, onOpenPreview = { _, _, _ -> }) }
        for (shown in listOf(title, subtitle, label)) {
            assertWholeWords(shown, topmost(shown))
            assertNotClipped(shown, topmost(shown))
        }
        rule.assertTargetsFit(rule.density, "Make searchable's bar")
        val backBounds = rule.onNode(hasContentDescription(back) and hasClickAction()).bounds()
        val choose = rule.onNode(hasText(label) and hasClickAction()).bounds()
        val stacked = choose.top >= topmost(subtitle).bounds().bottom - 0.5f
        if (stacked) {
            assertEquals("Back is level with the title's top", topmost(title).bounds().top, backBounds.top, 0.5f)
            val line = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }
                .filter { it.top >= choose.top - 0.5f && it.bottom <= choose.bottom + 0.5f }
            val end = rule.onRoot().bounds().width - with(rule.density) { 12.dp.toPx() }
            assertEquals("the stacked action ends at the bar's end edge", end, line.maxOf { it.right }, 0.5f)
        } else {
            assertEquals("on one line, Back is as high as the action", choose.height, backBounds.height, 0.5f)
        }
        return stacked
    }

    @Test
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on a small phone Make searchable's bar stacks Choose images under a whole title`() {
        val stacked = searchableBar("Rendi ricercabile un PDF", "Aggiunge il testo riconosciuto, così puoi cercarlo e copiarlo", "Scegli le immagini", "Indietro")
        assertTrue("the action moves under the title", stacked)
    }

    @Test
    @Config(qualifiers = "it-w384dp-h2400dp")
    fun `in Italian on the 6T Make searchable's bar keeps every word whole`() {
        searchableBar("Rendi ricercabile un PDF", "Aggiunge il testo riconosciuto, così puoi cercarlo e copiarlo", "Scegli le immagini", "Indietro")
    }

    @Test
    @Config(qualifiers = "w384dp-h2400dp")
    fun `in English on the 6T Make searchable's bar keeps every word whole`() {
        searchableBar("Make PDF searchable", "Adds the recognised text so you can search and copy it", "Choose images", "Back")
    }

    @Test
    fun `the top bar mirrors right to left`() {
        atDoubleText {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ToolTopBar(title = "Sign", subtitle = null, showChange = true, onBack = {}, onShowTip = {})
            }
        }
        val width = rule.onRoot().bounds().width
        val back = rule.onNode(hasContentDescription("Back") and hasClickAction()).bounds()
        val change = rule.onNode(hasText("Change") and hasClickAction()).bounds()
        val tip = rule.onNode(hasContentDescription("Show the tip") and hasClickAction()).bounds()
        val title = text("Sign")
        assertTrue("Back sits at the right edge", back.right >= width - with(rule.density) { 4.dp.toPx() } - 0.5f)
        assertTrue("the title is left of Back", title.right <= back.left + 0.5f)
        assertTrue("the actions are left of the title", change.right <= title.left + 0.5f && tip.right <= change.left + 0.5f)
        assertTrue("the (i) sits at the left edge", tip.left <= with(rule.density) { 12.dp.toPx() } + 0.5f)
    }

    @Test
    fun `a stacked top bar mirrors right to left`() {
        atDoubleText {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ToolTopBar(title = "Compress", subtitle = "Optimize PDF size", showChange = true, onBack = {}, onShowTip = {})
            }
        }
        val width = rule.onRoot().bounds().width
        val back = rule.onNode(hasContentDescription("Back") and hasClickAction()).bounds()
        val change = rule.onNode(hasText("Change") and hasClickAction()).bounds()
        val tip = rule.onNode(hasContentDescription("Show the tip") and hasClickAction()).bounds()
        assertTrue("the actions sit under the title", change.top >= text("Optimize PDF size").bottom - 0.5f)
        assertTrue("Back sits at the right edge", back.right >= width - with(rule.density) { 4.dp.toPx() } - 0.5f)
        assertTrue("the actions end at the left edge, the (i) last", tip.right <= change.left + 0.5f && tip.left <= with(rule.density) { 12.dp.toPx() } + 0.5f)
        assertTrue("Change has grown past 48 dp", change.height > with(rule.density) { 48.dp.toPx() } + 0.5f)
        assertEquals("Change and the (i) are one height", change.height, tip.height, 0.5f)
    }

    @Test
    // The 6T's width, where a short title leaves the actions one line beside it, but too little room for the label on one line.
    @Config(qualifiers = "w384dp-h2400dp")
    fun `a wrapping action label leaves the (i) beside it 48 dp`() {
        atDoubleText {
            ToolTopBar(title = "Sign", subtitle = null, showChange = true, onBack = {}, changeLabel = "Choose two images", onShowTip = {})
        }
        val back = rule.onNode(hasContentDescription("Back") and hasClickAction()).bounds()
        val change = rule.onNode(hasText("Choose two images") and hasClickAction()).bounds()
        val tip = rule.onNode(hasContentDescription("Show the tip") and hasClickAction()).bounds()
        assertTrue("one line", change.top < back.bottom && tip.top < back.bottom)
        assertTrue("the label wraps", layoutOf("Choose two images").lineCount > 1)
        assertNotClipped("Choose two images")
        val step = with(rule.density) { 48.dp.toPx() } - 0.5f
        assertTrue("the (i) is at least 48 x 48 dp: ${tip.width} x ${tip.height} px", tip.width >= step && tip.height >= step)
        assertEquals("the (i) is as high as the label", change.height, tip.height, 0.5f)
    }

    /** The segments [labels] stack as rows of one width, one below the other, each label whole and uncut. */
    private fun assertStackedWhole(labels: List<String>) {
        val rows = labels.map { rule.onNode(radioButton(it)).bounds() }
        assertTrue("the segments $labels stack", rows.zipWithNext().all { (a, b) -> b.top >= a.bottom - 0.5f })
        assertTrue("the rows are one width", rows.all { abs(it.width - rows.first().width) < 0.5f })
        for (label in labels) {
            assertFullyVisible(label)
            assertWholeWords(label, rule.onNodeWithText(label, useUnmergedTree = true))
        }
    }

    @Test
    // Robolectric's dialog window has no margins of its own, so a 280 dp window gives the dialog about the 232 dp it has on a 320 dp phone.
    @Config(qualifiers = "it-w280dp-h2400dp")
    fun `in Italian on a small phone the Save dialog's choices stack with their labels whole`() {
        atDoubleText { SaveDialog(onSave = {}, onDismiss = {}) }
        assertStackedWhole(listOf("A4", "Letter", "Legal", "Adatta"))
        assertStackedWhole(listOf("Inglese", "Italiano"))
        assertStackedWhole(listOf("Automatica", "Tabelle", "Scontrini"))
    }

    @Test
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on a small phone Make searchable's Reading stacks with its labels whole`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "large-searchable.pdf")
        atDoubleText { SearchableView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil("the reading choice shows", 60_000) { rule.onAllNodes(hasText("Scontrini")).fetchSemanticsNodes().isNotEmpty() }
        // Language (286 dp in the 6T's font, of 280) draws one row in Robolectric's narrower font, so only Reading is held here.
        assertStackedWhole(listOf("Automatica", "Tabelle", "Scontrini"))
        assertNothingSideways()
    }

    /** PDF to images on a small phone at twice the text size: its format and quality stack, and the page grid scrolls with them (rule 10). */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `PDF to images' last page is in reach on a small phone`() {
        showTool("pdf2img")
        assertLastTileInReach("pdf2img")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `Images to PDF's last image is in reach under the page size on a small phone`() {
        // Pinned above the grid, the page size left it about 30 dp here.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val images = (1..3).map { TestPdfs.write(context.cacheDir, png, "large-photo-$it.png") }
        val output = Uri.fromFile(File(context.cacheDir, "large-images.pdf"))
        atDoubleText {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output, picked = images)) {
                ImageToPdfView(onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.onNode(hasText("Choose images") and hasClickAction()).performClick()
        rule.waitUntil("the page size shows", 60_000) { rule.onAllNodes(radioButton("A4 paper")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the page size stacks here", rule.onNode(radioButton("A4 paper")).bounds().top >= rule.onNode(radioButton("Original")).bounds().bottom - 0.5f)
        assertLastTileInReach("img2pdf", "Image 3")
    }

    @Test
    // The 6T's height, where the grid shares the screen with the options button.
    @Config(qualifiers = "w384dp-h832dp")
    fun `Page numbers' options button takes a tap over the tiles scrolled under it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(12), "large-numbers-tap.pdf")
        atDoubleText { PageNumbersView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil("the options button shows", 60_000) {
            rule.onAllNodes(hasContentDescription("Numbering options")).fetchSemanticsNodes().isNotEmpty()
        }
        val grid = rule.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("Page 1"))).bounds()
        val options = rule.onNode(hasContentDescription("Numbering options") and hasClickAction())
        // The grid clips its tiles at its bottom, above the button, so no tile can take a touch there.
        assertTrue("the grid ends above the options button", grid.bottom <= options.bounds().top + 0.5f)
        options.performTouchInput { click(Offset(centerX, 1f)) }
        rule.waitForIdle()
        assertTrue("the numbering sheet opens", rule.onAllNodes(hasText("Color")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the tip card grows with its text beside its close button`() {
        atDoubleText { ToolTipCard(TIP, onClose = {}) }
        assertNotClipped(TIP)
        val closeNode = rule.onNodeWithContentDescription("Close the tip")
        val close = closeNode.bounds()
        // T3: drawn 40 dp, answering touches across 48.
        assertTrue("the close button takes 48 dp of touch", closeNode.fetchSemanticsNode().touchBoundsInRoot.width >= with(rule.density) { 48.dp.toPx() } - 0.5f)
        assertTrue("the text runs beside the close button", text(TIP).right <= close.left)
    }

    @Test
    fun `the page grid keeps five a row and nothing sideways at twice the text size`() {
        val width = 358.dp
        atDoubleText {
            Column(Modifier.width(width)) {
                PageGrid(16, isSelected = { it == 0 }, tapLabel = { "Show page ${it + 1}" }, onTap = {}, onRead = {}) {
                    Box(Modifier.size(48.dp, 64.dp))
                }
            }
        }
        assertNotClipped("Show all 16 pages")
        val first = rule.onNodeWithContentDescription("Show page 1").bounds()
        val fifth = rule.onNodeWithContentDescription("Show page 5").bounds()
        val sixth = rule.onNodeWithContentDescription("Show page 6").bounds()
        assertEquals("five pages a row", first.top, fifth.top, 0.5f)
        assertTrue("the sixth starts the next row", sixth.top >= first.bottom)
        assertTrue("nothing sideways", fifth.right <= with(rule.density) { width.toPx() } + 0.5f)
        // The tile holds the page and its number at twice the size: 10 + 64 + 6 + 32 (16 sp line, doubled) + 4 dp.
        assertTrue("the number fits under the page", first.height >= with(rule.density) { 116.dp.toPx() } - 0.5f)
    }

    /**
     * The tool sheet as the shell and the reader open it, on a 320 x 640 dp phone, with the last-viewed file's chip and a search typed: the
     * field, its clear button and the first match show whole in the window, each taking touches across at least 48 dp. Half open, as the
     * sheets were before, the first match ended 119 dp below the window.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun assertSheetSearchInReach(query: String, firstMatch: String, clear: String) {
        atDoubleText {
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberToolSheetState()) {
                ToolPickerContent(onToolClick = {}, fileName = "report.pdf", onRemoveFile = {})
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput(query)
        rule.waitForIdle()
        val least = with(rule.density) { TargetStep.ACT.hit.toPx() } - 0.5f
        val window = with(rule.density) { 640.dp.toPx() }
        for ((what, matcher) in listOf("the field" to hasSetTextAction(), "clear" to hasContentDescription(clear), firstMatch to hasText(firstMatch))) {
            val node = rule.onNode(matcher and hasClickAction()).fetchSemanticsNode()
            assertTrue("$what ends at ${node.boundsInRoot.bottom} px, past the window's $window", node.boundsInRoot.bottom <= window + 0.5f)
            assertEquals("$what shows whole", node.size.height.toFloat(), node.boundsInRoot.height, 0.5f)
            assertTrue("$what takes touches across ${node.touchBoundsInRoot}", node.touchBoundsInRoot.height >= least && node.touchBoundsInRoot.width >= least)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `on a small phone the tool sheet's search and its first match are in reach`() = assertSheetSearchInReach("pages", "Split", "Clear search")

    @Test
    @Config(qualifiers = "it-w320dp-h640dp")
    fun `in Italian on a small phone the tool sheet's search and its first match are in reach`() =
        assertSheetSearchInReach("pagine", "Dividi", "Cancella la ricerca")

    /** Whether [inner] lies within [outer], to half a pixel. */
    private fun within(inner: Rect, outer: Rect) =
        inner.left >= outer.left - 0.5f && inner.top >= outer.top - 0.5f && inner.right <= outer.right + 0.5f && inner.bottom <= outer.bottom + 0.5f

    /** The tool [id] with a 3-page file at twice the text size, once its file card shows (a file:// URI is named Document.pdf). */
    private fun showTool(id: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "large-grid-$id.pdf")
        atDoubleText { ToolCatalog.byId(id)!!.screen(ToolLaunch(input, null, onBack = {}, onOpenPreview = { _, _, _ -> })) }
        rule.waitUntil("$id shows its file", 60_000) { rule.onAllNodes(hasText("Document", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /**
     * The tool [id]'s page grid at twice the text size (rule 10): swipes on the screen's one scroll bring the last page's tile whole into view,
     * its touch area inside the window and the scroll's view, so clear of the pinned button, while the first-time hold hint shows under the
     * grid. On main the grid got no height here.
     */
    private fun assertLastPageInReach(id: String) {
        showTool(id)
        rule.onNodeWithText("Touch and hold a page to read it.").assertIsDisplayed()
        assertLastTileInReach(id)
    }

    /** Swipes on the tool [id]'s one scroll bring its last tile, read out as [lastTile], whole into view, inside the window and the scroll's view. */
    private fun assertLastTileInReach(id: String, lastTile: String = "Page 3") {
        val window = rule.onRoot().bounds()
        val last = hasClickAction() and hasContentDescription(lastTile)
        repeat(10) {
            val scroll = rule.onNode(hasScrollAction())
            val view = scroll.bounds()
            val tile = rule.onAllNodes(last).fetchSemanticsNodes().firstOrNull()
            if (tile != null && tile.boundsInRoot.height >= tile.size.height - 0.5f && within(tile.touchBoundsInRoot, view) &&
                within(tile.touchBoundsInRoot, window)
            ) return
            scroll.performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        fail("$id's last page stays out of reach")
    }

    @Test @Config(qualifiers = "w320dp-h640dp") fun `Split's last page is in reach on a small phone`() = assertLastPageInReach("split")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Split's last page is in reach on the 6T`() = assertLastPageInReach("split")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Delete's last page is in reach on a small phone`() = assertLastPageInReach("delete")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Delete's last page is in reach on the 6T`() = assertLastPageInReach("delete")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Rotate's last page is in reach on a small phone`() = assertLastPageInReach("rotate")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Rotate's last page is in reach on the 6T`() = assertLastPageInReach("rotate")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Watermark's last page is in reach on a small phone`() = assertLastPageInReach("watermark")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Watermark's last page is in reach on the 6T`() = assertLastPageInReach("watermark")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Sign's last page is in reach on a small phone`() = assertLastPageInReach("sign")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Sign's last page is in reach on the 6T`() = assertLastPageInReach("sign")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Grayscale's last page is in reach on a small phone`() = assertLastPageInReach("grayscale")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Grayscale's last page is in reach on the 6T`() = assertLastPageInReach("grayscale")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Page numbers' last page is in reach on a small phone`() = assertLastPageInReach("page-numbers")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Page numbers' last page is in reach on the 6T`() = assertLastPageInReach("page-numbers")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Rearrange's last page is in reach on a small phone`() = assertLastPageInReach("rearrange")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Rearrange's last page is in reach on the 6T`() = assertLastPageInReach("rearrange")

    /**
     * [label], the text action beside the status line over the tool [id]'s page grid, at twice the text size: it breaks only between words
     * and shows whole, and its control takes touches across at least T3's 48 dp.
     */
    private fun assertStatusActionWhole(id: String, label: String) {
        showTool(id)
        // On a small phone the file card above it fills the first screen.
        rule.onNode(hasText(label) and hasClickAction()).performScrollTo()
        val text = rule.onNodeWithText(label, useUnmergedTree = true)
        assertWholeWords(label, text)
        assertNotClipped(label, text)
        val control = rule.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
        val hit = with(rule.density) { TargetStep.INLINE.hit.toPx() } - 0.5f
        assertTrue("$label takes touches across less than 48 dp: ${control.touchBoundsInRoot}",
            control.touchBoundsInRoot.height >= hit && control.touchBoundsInRoot.width >= hit)
    }

    @Test @Config(qualifiers = "w320dp-h640dp") fun `Split's Enter range keeps whole words on a small phone`() = assertStatusActionWhole("split", "Enter range")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Split's Enter range keeps whole words on the 6T`() = assertStatusActionWhole("split", "Enter range")
    @Test @Config(qualifiers = "it-w320dp-h640dp")
    fun `in Italian Split's Enter range keeps whole words on a small phone`() = assertStatusActionWhole("split", "Inserisci un intervallo")
    @Test @Config(qualifiers = "it-w384dp-h832dp")
    fun `in Italian Split's Enter range keeps whole words on the 6T`() = assertStatusActionWhole("split", "Inserisci un intervallo")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Delete's Enter range keeps whole words on a small phone`() = assertStatusActionWhole("delete", "Enter range")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Delete's Enter range keeps whole words on the 6T`() = assertStatusActionWhole("delete", "Enter range")
    @Test @Config(qualifiers = "it-w320dp-h640dp")
    fun `in Italian Delete's Enter range keeps whole words on a small phone`() = assertStatusActionWhole("delete", "Inserisci un intervallo")
    @Test @Config(qualifiers = "it-w384dp-h832dp")
    fun `in Italian Delete's Enter range keeps whole words on the 6T`() = assertStatusActionWhole("delete", "Inserisci un intervallo")
    @Test @Config(qualifiers = "w320dp-h640dp") fun `Rotate all keeps whole words on a small phone`() = assertStatusActionWhole("rotate", "Rotate all 90°")
    @Test @Config(qualifiers = "w384dp-h832dp") fun `Rotate all keeps whole words on the 6T`() = assertStatusActionWhole("rotate", "Rotate all 90°")
    @Test @Config(qualifiers = "it-w320dp-h640dp")
    fun `in Italian Rotate all keeps whole words on a small phone`() = assertStatusActionWhole("rotate", "Ruota tutte di 90°")
    @Test @Config(qualifiers = "it-w384dp-h832dp")
    fun `in Italian Rotate all keeps whole words on the 6T`() = assertStatusActionWhole("rotate", "Ruota tutte di 90°")

    private companion object {
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
        const val TIP = "Cropping hides the edges; it doesn’t delete them. To remove content for good, use Redact."
    }
}
