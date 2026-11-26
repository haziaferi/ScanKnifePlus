package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Decision 37: one tool catalog that every tool is in and every tool list reads from. */
@RunWith(RobolectricTestRunner::class)
class ToolCatalogTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val components = sourceDir("com/paperknifeplus/app/ui/components")

    @Test
    fun `tool ids are unique and every category has tools`() {
        val ids = ToolCatalog.tools.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        for (category in ToolCategory.entries) assertTrue(category.name, ToolCatalog.tools.any { it.category == category })
        for (id in ids) assertEquals(id, ToolCatalog.byId(id)?.id)
    }

    @Test
    fun `Links sits in Edit right after Bookmarks, to remove or add links`() {
        val links = ToolCatalog.byId("links")!!
        assertEquals("links", links.id)
        assertEquals(ToolCategory.EDIT, links.category)
        val edit = ToolCatalog.tools.filter { it.category == ToolCategory.EDIT }.map { it.id }
        assertEquals("links", edit[edit.indexOf("bookmarks") + 1])
        assertEquals("Links", context.getString(links.name))
        assertEquals("Remove or add links", context.getString(links.description))
    }

    @Test
    fun `every tool screen is in the catalog`() {
        val catalog = File(components, "ToolCatalog.kt").readText()
        val screens = components.listFiles()!!.map { it.name.removeSuffix(".kt") }.filter { it.endsWith("View") && it !in NOT_TOOLS }
        assertEquals(ToolCatalog.tools.size, screens.size)
        for (screen in screens) assertTrue("$screen is missing from ToolCatalog", catalog.contains("$screen("))
    }

    @Test
    fun `no other file keeps its own tool list`() {
        val toolConstructor = Regex("""(?<![A-Za-z.])Tool\(""")
        val offenders = sourceDir("").walkTopDown().filter { it.extension == "kt" && it.name !in setOf("Tool.kt", "ToolCatalog.kt") }
            .filter { toolConstructor.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /** The shell, every screen in the components (tools, Settings, About, the result screen) and the tools' shared scaffold. */
    private fun screenFiles(): List<File> = listOf(File(sourceDir("com/paperknifeplus/app"), "MainActivity.kt")) +
        components.listFiles()!!.filter { it.name.endsWith("View.kt") && !it.name.endsWith("Preview.kt") } +
        listOf("ToolPicker.kt", "Tool.kt", "ToolCatalog.kt").map { File(components, it) }

    @Test
    fun `the screens take colours from the theme only`() {
        val hardCoded = Regex("""Color\(0x|Color\.(White|Black|Gray|DarkGray|LightGray|Red|Blue)|PaperPink""")
        for (file in screenFiles()) assertEquals(file.name, null, hardCoded.find(file.readText())?.value)
    }

    @Test
    fun `every tool records its outputs under its own catalog id, and nothing else writes History`() {
        val catalog = File(components, "ToolCatalog.kt").readText()
        val screenOf = Regex("""Tool\("([^"]+)"[^{]*\{\s*(\w+View)\(""").findAll(catalog).associate { it.groupValues[2] to it.groupValues[1] }
        assertEquals(ToolCatalog.tools.size, screenOf.size)
        val recorders = Regex("""rememberToolOutputs\("([^"]+)"\)""")
        for ((screen, id) in screenOf) {
            val ids = recorders.findAll(File(components, "$screen.kt").readText()).map { it.groupValues[1] }.toList()
            // Compare shows two files side by side and saves nothing.
            val expected = if (screen == "CompareView") emptyList() else listOf(id)
            assertEquals(screen, expected, ids)
            for (used in ids) assertEquals(used, ToolCatalog.byId(used)?.id)
        }
        val sources = sourceDir("").walkTopDown().filter { it.extension == "kt" }.toList()
        val writers = sources.filter { it.readText().contains("SessionManager.addEntry(") }.map { it.name }
        // Ledger L8: this session's list holds only what a tool read on screen; saved files go into the index.
        assertEquals(setOf("Tool.kt"), writers.toSet())
        val listing = Regex("""\.record(Later)?\(""")
        val indexWriters = sources.filter { it.name != "SavedFiles.kt" && "SavedFiles" in it.readText() && listing.containsMatchIn(it.readText()) }
        assertEquals(setOf("Tool.kt", "ScanExport.kt", "ScanFlow.kt"), indexWriters.map { it.name }.toSet())
    }

    @Test
    fun `History's tag is the catalog name of the tool that made the output`() {
        fun entry(toolId: String) = ActivityEntry("1", "out.pdf", toolId, "", Icons.Filled.Description)
        for (tool in ToolCatalog.tools) assertEquals(tool.id, tool.name, entry(tool.id).tag)
        assertEquals(R.string.history_tag_scan, entry(SCAN_ENTRY_TOOL).tag)
        assertEquals(R.string.history_tag_tool, entry("Merge").tag)
    }

    @Test
    fun `the tool list shows every catalog tool and reports its id`() {
        var picked: String? = null
        rule.setContent { ToolPickerContent(onToolClick = { picked = it }) }
        for (tool in ToolCatalog.tools) {
            val name = context.getString(tool.name)
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            assertEquals(tool.id, picked)
        }
    }

    @Test
    fun `the pre-loaded file shows as a chip that can be removed`() {
        var removed = 0
        var fileName by mutableStateOf<String?>("report.pdf")
        rule.setContent { ToolPickerContent(onToolClick = {}, fileName = fileName, onRemoveFile = { removed++; fileName = null }) }

        rule.onNodeWithText("report.pdf").assertExists()
        rule.onNodeWithContentDescription("Remove report.pdf").performClick()
        assertEquals(1, removed)
        rule.onNodeWithText("report.pdf").assertDoesNotExist()
        rule.onNodeWithText("Opens with").assertDoesNotExist()
    }

    @Test
    fun `every catalog tool opens its own screen`() {
        PDFBoxResourceLoader.init(context)
        assertEquals(ToolCatalog.tools.map { it.id }.toSet(), SCREEN_TEXT.keys)
        var id by mutableStateOf(ToolCatalog.tools.first().id)
        rule.setContent { key(id) { ToolCatalog.byId(id)!!.screen(ToolLaunch(null, null, onBack = {}, onOpenPreview = { _, _, _ -> })) } }
        for (tool in ToolCatalog.tools) {
            id = tool.id
            rule.waitForIdle()
            val text = SCREEN_TEXT.getValue(tool.id)
            assertTrue("${tool.id} does not show $text", rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private fun sourceDir(pkg: String): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve(pkg)

    private companion object {
        /** A text only that tool's empty screen shows. */
        val SCREEN_TEXT = mapOf(
            "merge" to "Join multiple documents", "split" to "Extract pages from PDF", "delete" to "Delete pages instantly",
            "rearrange" to "Move pages to new slots", "rotate" to "Fix document orientation", "sign" to "Secure signature",
            "watermark" to "Custom overlay", "page-numbers" to "Custom pagination", "bookmarks" to "Edit document navigation",
            "links" to "Remove links from a PDF", "crop" to "Crop the pages of a PDF",
            "compress" to "Optimize PDF size", "grayscale" to "Convert PDF to B&W", "repair" to "Recover corrupt documents",
            "compare" to "Visual difference tool", "protect" to "Encrypt your document", "unlock" to "Remove PDF restrictions",
            "metadata" to "Edit document properties", "pdf2img" to "Native export engine", "img2pdf" to "JPG, PNG or WebP",
            "pdf2zip" to "Pages to archive", "extract-images" to "Extract assets from PDFs", "pdf2text" to "Extract raw content",
            "searchable" to "Searchable text for scanned PDFs and photos"
        )

        /** The *View composables that are not tools: the shell's own screens and the tools' shared success screen. */
        val NOT_TOOLS = setOf("HomeView", "HistoryView", "ToolsView", "SettingsView", "AboutView", "SuccessView")
    }
}
