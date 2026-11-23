package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.haziaferi.scanknifeplus.ui.scan.SaveDialog
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.firstDrawnRow
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.Typography
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun atDoubleText(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) { content() }
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
        assertTrue("the title runs under Change", text("Compress").right <= barChange.left)
        assertTrue("the subtitle runs under Change", text("Optimize PDF size").right <= barChange.left)
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
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Remove links and save PDF")).fetchSemanticsNodes().isNotEmpty() }
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
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Remove links and save PDF")).fetchSemanticsNodes().isNotEmpty() }
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
            "Links to pages of this file stay, such as a table of contents.", "Remove links and save PDF"
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
        atDoubleText { MaterialTheme(typography = Typography) { LinksView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Rimuovi i link e salva il PDF")).fetchSemanticsNodes().isNotEmpty() }
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
        atDoubleText { MaterialTheme(typography = Typography) { CropView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Sposta un angolo per ritagliare")).fetchSemanticsNodes().isNotEmpty() }
        for (label in listOf("Tutte le pagine", "Intervallo di pagine")) assertFullyVisible(label)
        assertTrue("the long label wraps onto a second line", layoutOf("Intervallo di pagine").lineCount > 1)
        assertNothingSideways()
    }

    @Test
    fun `the segmented control stacks into full-width rows of one height while their labels fit them`() {
        // In the app's font, as the segments measure their labels in the font they are drawn in, and as wide as on a 320 dp phone.
        atDoubleText {
            MaterialTheme(typography = Typography) {
                Column(Modifier.width(288.dp)) { SegmentedChoice(listOf("All pages", "Page range"), 1, onSelect = {}) }
            }
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
            MaterialTheme(typography = Typography) {
                Column(Modifier.width(width)) { HomeView(onAllTools = {}, onSettings = {}, onImportPhotos = {}, onSeeAll = {}, onOpenPreview = { _, _, _ -> }) }
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
        atDoubleText { MaterialTheme(typography = Typography) { ToolTopBar(title = "Merge", subtitle = "Combine PDFs", showChange = true, onBack = {}, onShowTip = {}) } }
        val back = rule.onNode(hasContentDescription("Back") and hasClickAction()).bounds()
        val change = rule.onNode(hasText("Change") and hasClickAction()).bounds()
        val tip = rule.onNode(hasContentDescription("Show the tip") and hasClickAction()).bounds()
        assertTrue("Change has grown past 48 dp", change.height > with(rule.density) { 48.dp.toPx() } + 0.5f)
        assertEquals("Back is as high as Change", change.height, back.height, 0.5f)
        assertEquals("the (i) is as high as Change", change.height, tip.height, 0.5f)
    }

    @Test
    // The 6T's height, so the tool's grid and its bar share the screen as on the phone.
    @Config(qualifiers = "w384dp-h832dp")
    fun `Rearrange's arrows grow with Move to`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "large-rearrange.pdf")
        atDoubleText { MaterialTheme(typography = Typography) { RearrangeView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
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
        atDoubleText { MaterialTheme(typography = Typography) { PageNumbersView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        rule.waitUntil("the options button shows", 60_000) {
            rule.onAllNodes(hasContentDescription("Numbering options")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasContentDescription("Numbering options") and hasClickAction()).performClick()
        rule.waitForIdle()
        val fieldNode = rule.onNode(hasSetTextAction() and hasText("Format ({n})"))
        val field = fieldNode.bounds()
        val colour = rule.onNode(hasText("Color") and hasClickAction()).bounds()
        assertTrue("the field has grown past 56 dp", field.height > with(rule.density) { 56.dp.toPx() } + 0.5f)
        // The outline, where the field draws its border: right of the label, past the rounded corner.
        val outlineTop = field.top + fieldNode.firstDrawnRow((field.width - with(rule.density) { 24.dp.toPx() }).toInt())
        val dp = with(rule.density) { 1.dp.toPx() }
        assertEquals("the colour button starts at the field's outline", outlineTop, colour.top, dp)
        assertEquals("the colour button ends with the field", field.bottom, colour.bottom, dp)
    }

    @Test
    // An Italian phone, small: Move to's label is longest there. Tall, so the screen's column has room for the bar (on 832 dp it squeezes
    // the bar with the grid, which is L3's).
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on a small phone Rearrange's Move to shows uncut beside its arrows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "large-rearrange-it.pdf")
        atDoubleText { MaterialTheme(typography = Typography) { RearrangeView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        rule.waitUntil("Move to shows", 60_000) { rule.onAllNodes(hasText("Sposta in…")).fetchSemanticsNodes().isNotEmpty() }
        assertNotClipped("Sposta in…", rule.onNode(hasText("Sposta in…"), useUnmergedTree = true))
        val moveTo = rule.onNode(hasText("Sposta in…") and hasClickAction()).bounds()
        assertEquals("Move back is as high as Move to", moveTo.height, rule.onNode(hasContentDescription("Sposta indietro") and hasClickAction()).bounds().height, 0.5f)
    }

    @Test
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `in Italian on a small phone the top bar's Reset shows uncut and its peers grow with it`() {
        atDoubleText {
            MaterialTheme(typography = Typography) {
                ToolTopBar(title = "Confronta", subtitle = "Trova le differenze", showChange = true, onBack = {}, changeLabel = "Ripristina", onShowTip = {})
            }
        }
        assertNotClipped("Ripristina")
        val change = rule.onNode(hasText("Ripristina") and hasClickAction()).bounds()
        assertEquals("Back is as high as Reset", change.height, rule.onNode(hasContentDescription("Indietro") and hasClickAction()).bounds().height, 0.5f)
    }

    @Test
    fun `the top bar mirrors right to left`() {
        atDoubleText {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ToolTopBar(title = "Merge", subtitle = null, showChange = true, onBack = {}, onShowTip = {})
            }
        }
        val width = rule.onRoot().bounds().width
        val back = rule.onNode(hasContentDescription("Back") and hasClickAction()).bounds()
        val change = rule.onNode(hasText("Change") and hasClickAction()).bounds()
        val tip = rule.onNode(hasContentDescription("Show the tip") and hasClickAction()).bounds()
        val title = text("Merge")
        assertTrue("Back sits at the right edge", back.right >= width - with(rule.density) { 4.dp.toPx() } - 0.5f)
        assertTrue("the title is left of Back", title.right <= back.left + 0.5f)
        assertTrue("the actions are left of the title", change.right <= title.left + 0.5f && tip.right <= change.left + 0.5f)
        assertTrue("the (i) sits at the left edge", tip.left <= with(rule.density) { 12.dp.toPx() } + 0.5f)
    }

    /** The chips [labels], all on screen: those on one line [TargetStep.INLINE_GAP] apart across, the lines that apart down. */
    private fun assertChipGaps(labels: List<String>) {
        val gap = with(rule.density) { TargetStep.INLINE_GAP.toPx() }
        val chips = labels.map { rule.onNode(hasText(it) and hasClickAction()).bounds() }.sortedWith(compareBy({ it.top }, { it.left }))
        var lines = 1
        for ((a, b) in chips.zipWithNext()) {
            if (abs(a.top - b.top) < 0.5f) {
                assertEquals("drawn chips on a line are 8 dp apart", gap, b.left - a.right, 0.5f)
            } else {
                lines++
                assertEquals("lines of drawn chips are 8 dp apart", gap, b.top - a.bottom, 0.5f)
            }
        }
        assertTrue("the chips wrap onto more than one line", lines > 1)
    }

    @Test
    @Config(qualifiers = "w320dp-h2400dp")
    fun `the Save PDF dialog's chips sit 8 dp apart across and down`() {
        atDoubleText { MaterialTheme(typography = Typography) { SaveDialog(onSave = {}, onDismiss = {}) } }
        assertChipGaps(listOf("A4", "Letter", "Legal", "Fit"))
    }

    @Test
    @Config(qualifiers = "w320dp-h2400dp")
    fun `Make searchable's chips sit 8 dp apart across and down`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "large-searchable.pdf")
        atDoubleText { MaterialTheme(typography = Typography) { SearchableView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        rule.waitUntil("the reading chips show", 60_000) { rule.onAllNodes(hasText("Receipts")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Receipts"))
        assertChipGaps(listOf("Automatic", "Tables", "Receipts"))
    }

    @Test
    // The 6T's height, where the grid shares the screen with the options button.
    @Config(qualifiers = "w384dp-h832dp")
    fun `Page numbers' options button takes a tap over the tiles scrolled under it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(12), "large-numbers-tap.pdf")
        atDoubleText { MaterialTheme(typography = Typography) { PageNumbersView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
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

    private companion object {
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
        const val TIP = "Cropping hides the edges; it doesn’t delete them. To remove content for good, use Redact."
    }
}
