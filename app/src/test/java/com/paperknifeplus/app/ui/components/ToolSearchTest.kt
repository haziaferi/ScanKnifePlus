package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rule 15 and decisions 3-4: the search field over the tool catalog, in both tool sheets, and the matching behind it. */
@RunWith(RobolectricTestRunner::class)
class ToolSearchTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a typed word matches the start of any word of a tool's name or description`() {
        assertTrue(toolMatches("rot", "Rotate", "Fix orientation"))
        assertTrue("description words count", toolMatches("orient", "Rotate", "Fix orientation"))
        assertTrue("description hits alone", toolMatches("signature", "Sign", "Add a signature"))
        assertFalse("only the start of a word", toolMatches("tate", "Rotate", "Fix orientation"))
        assertFalse(toolMatches("zip", "Rotate", "Fix orientation"))
    }

    @Test
    fun `matching ignores case and accents`() {
        assertTrue(toolMatches("ROT", "Rotate", "Fix orientation"))
        assertTrue(toolMatches("ruota", "Ruota", "Correggi l'orientamento"))
        assertTrue("after an apostrophe", toolMatches("orientamento", "Ruota", "Correggi l'orientamento"))
        assertTrue("typed without the accent", toolMatches("proprieta", "Metadati", "Modifica le proprietà"))
        assertTrue("typed with it", toolMatches("PROPRIETÀ", "Metadati", "Modifica le proprietà"))
        assertTrue(toolMatches("piu", "Unisci", "Combina più PDF"))
    }

    @Test
    fun `a word of five letters or more may differ in its last letter`() {
        assertTrue(toolMatches("pagin", "Elimina pagine", "Rimuovi pagine"))
        assertTrue("singular finds plural", toolMatches("pagina", "Elimina pagine", "Rimuovi pagine"))
        assertTrue(toolMatches("Pagina", "Numeri di pagina", "Numera le pagine"))
        assertTrue(toolMatches("immagine", "Estrai immagini", "Salva le immagini contenute"))
        assertTrue("and plural finds singular", toolMatches("pages", "Page numbers", "Add page numbers"))
        assertFalse("only the last letter", toolMatches("paguna", "Elimina pagine", "Rimuovi pagine"))
        assertFalse("not below five letters", toolMatches("file", "Filigrana", "Aggiungi una sovrimpressione"))
        assertTrue(toolMatches("file", "Ripara", "Correggi un file danneggiato"))
    }

    @Test
    fun `several typed words must all match`() {
        assertTrue(toolMatches("add pass", "Protect", "Add a password"))
        assertTrue("in any order, across name and description", toolMatches("password protect", "Protect", "Add a password"))
        assertFalse(toolMatches("add zip", "Protect", "Add a password"))
    }

    @Test
    fun `a blank query matches every tool`() {
        for (query in listOf("", "   ", "-")) assertTrue("\"$query\"", toolMatches(query, "Rotate", "Fix orientation"))
    }

    private val field get() = rule.onNode(hasSetTextAction())

    private fun shows(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun heading(text: String) = rule.onAllNodes(hasText(text) and isHeading()).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the field shows its placeholder and does not take focus on open`() {
        rule.setContent { ToolPickerContent(onToolClick = {}) }
        rule.onNode(hasText("Search tools", substring = true), useUnmergedTree = true).assertExists()
        field.assertIsNotFocused()
        rule.onNode(hasContentDescription("Clear search")).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w384dp-h2400dp") // Tall, so every section of the lazy list is laid out.
    fun `typing hides the sections with no match and keeps the headings of the rest`() {
        var picked: String? = null
        rule.setContent { ToolPickerContent(onToolClick = { picked = it }) }
        for (section in listOf("Edit", "Optimize", "Secure", "Convert")) assertTrue(section, heading(section))
        field.performTextInput("password")
        rule.waitForIdle()
        assertTrue(heading("Secure"))
        for (section in listOf("Edit", "Optimize", "Convert")) assertFalse("$section is hidden", heading(section))
        assertTrue(shows("Protect"))
        assertTrue(shows("Unlock"))
        assertFalse(shows("Merge"))
        rule.onNode(hasText("Protect") and hasClickAction()).performClick()
        assertEquals("protect", picked)
    }

    @Test
    fun `nothing found says so, and clear brings the whole list back`() {
        rule.setContent { ToolPickerContent(onToolClick = {}) }
        field.performTextInput("zzz")
        rule.waitForIdle()
        assertTrue(shows("No tool matches “zzz”."))
        for (section in listOf("Edit", "Optimize", "Secure", "Convert")) assertFalse(section, heading(section))
        rule.onNode(hasContentDescription("Clear search") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertFalse(shows("No tool matches “zzz”."))
        assertTrue(heading("Edit"))
        assertTrue(shows("Merge"))
        assertEquals("", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.onNode(hasContentDescription("Clear search")).assertDoesNotExist()
    }

    /**
     * Robolectric's window is not in touch mode, so a cleared focus comes straight back to the field, the first focusable; on a phone it
     * stays cleared. The keyboard is asked to hide either way, which is what this checks.
     */
    @Test
    fun `the keyboard's Search key puts the keyboard away`() {
        var hidden = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() {}
            override fun hide() {
                hidden++
            }
        }
        rule.setContent { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { ToolPickerContent(onToolClick = {}) } }
        field.performClick()
        field.assertIsFocused()
        field.performTextInput("crop")
        assertEquals(0, hidden)
        field.performImeAction()
        assertEquals(1, hidden)
        assertTrue(shows("Crop pages"))
    }

    @Test
    @Config(qualifiers = "w384dp-h640dp")
    fun `the field stays in place while the list scrolls`() {
        rule.setContent { ToolPickerContent(onToolClick = {}, fileName = "report.pdf", onRemoveFile = {}) }
        val before = field.fetchSemanticsNode().boundsInRoot
        val edit = rule.onNode(hasText("Edit") and isHeading()).fetchSemanticsNode().boundsInRoot
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(ToolCatalog.tools.size)
        rule.waitForIdle()
        assertEquals(before, field.fetchSemanticsNode().boundsInRoot)
        assertTrue("the list scrolled", rule.onAllNodes(hasText("Edit") and isHeading()).fetchSemanticsNodes().none { it.boundsInRoot == edit })
        assertTrue("the last tool shows", shows(context.getString(ToolCatalog.tools.last().name)))
    }

    /** A sheet is as tall as its content: the field must not slide down the screen as the matches narrow (6T, 2026-11-25: 117 to 647 dp). */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `in the sheet the field stays in place as the matches narrow`() {
        rule.setContent {
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberToolSheetState()) { ToolPickerContent(onToolClick = {}) }
        }
        rule.waitForIdle()
        val before = field.fetchSemanticsNode().boundsInRoot
        for (typed in listOf("pass", "zzz")) {
            field.performTextInput(typed)
            rule.waitForIdle()
            assertEquals("after \"$typed\"", before.top, field.fetchSemanticsNode().boundsInRoot.top, 0.5f)
        }
        assertTrue(shows("No tool matches “passzzz”."))
    }

    @Test
    fun `the file chip sits under the field, at the top of the list`() {
        rule.setContent { ToolPickerContent(onToolClick = {}, fileName = "report.pdf", onRemoveFile = {}) }
        val chip = rule.onNode(hasText("Opens with")).fetchSemanticsNode().boundsInRoot
        assertTrue(chip.top >= field.fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(chip.bottom <= rule.onNode(hasText("Edit") and isHeading()).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    @Config(qualifiers = "it")
    fun `in Italian the search reads the Italian names, ignoring accents`() {
        rule.setContent { ToolPickerContent(onToolClick = {}) }
        rule.onNode(hasText("Cerca uno strumento", substring = true), useUnmergedTree = true).assertExists()
        field.performTextInput("ruota")
        rule.waitForIdle()
        assertTrue(shows("Ruota"))
        assertTrue(heading("Modifica"))
        assertFalse(heading("Sicurezza"))
        clear()
        field.performTextInput("pagina")
        rule.waitForIdle()
        assertTrue(shows("Numeri di pagina"))
        assertTrue("the singular finds the plural", shows("Elimina pagine"))
        assertFalse(shows("Ruota"))
        clear()
        field.performTextInput("proprieta")
        rule.waitForIdle()
        assertTrue(shows("Metadati"))
        clear()
        field.performTextInput("zzz")
        rule.waitForIdle()
        assertTrue(shows("Nessuno strumento corrisponde a “zzz”."))
    }

    private fun clear() {
        rule.onNode(hasContentDescription("Cancella la ricerca") and hasClickAction()).performClick()
        rule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `the reader's tool sheet has the same search`() {
        PDFBoxResourceLoader.init(context)
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "search-reader.pdf")
        var opened: String? = null
        rule.setContent { UltraPreview(uri, "search-reader.pdf", 1, onDismiss = {}, onOpenInTool = { tool, _, _ -> opened = tool }) }
        rule.waitUntil(60_000) { rule.onAllNodes(hasContentDescription("Tools for this file")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Tools for this file")).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        field.assertIsNotFocused()
        field.performTextInput("grayscale")
        rule.waitForIdle()
        assertTrue(heading("Optimize"))
        assertFalse(heading("Edit"))
        rule.onNode(hasText("Grayscale") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { opened != null }
        assertEquals("grayscale", opened)
    }
}
