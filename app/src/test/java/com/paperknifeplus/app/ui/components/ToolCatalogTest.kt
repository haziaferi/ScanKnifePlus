package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
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

    @Test
    fun `the shell's files take colours from the theme only`() {
        val shell = listOf(File(sourceDir("com/paperknifeplus/app"), "MainActivity.kt")) +
            listOf("HomeView.kt", "HistoryView.kt", "ToolPicker.kt", "ToolsView.kt", "Tool.kt", "ToolCatalog.kt").map { File(components, it) }
        val hardCoded = Regex("""Color\(0x|Color\.(White|Black|Gray|DarkGray|LightGray)|PaperPink""")
        for (file in shell) assertEquals(file.name, null, hardCoded.find(file.readText())?.value)
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
            "merge" to "JOIN MULTIPLE DOCUMENTS", "split" to "EXTRACT PAGES FROM PDF", "delete" to "DELETE PAGES INSTANTLY",
            "rearrange" to "MOVE PAGES TO NEW SLOTS", "rotate" to "FIX DOCUMENT ORIENTATION", "sign" to "SECURE SIGNATURE",
            "watermark" to "CUSTOM OVERLAY", "page-numbers" to "CUSTOM PAGINATION", "bookmarks" to "EDIT DOCUMENT NAVIGATION",
            "compress" to "OPTIMIZE PDF SIZE", "grayscale" to "CONVERT PDF TO B&W", "repair" to "RECOVER CORRUPT DOCUMENTS",
            "compare" to "VISUAL DIFFERENCE TOOL", "protect" to "ENCRYPT YOUR DOCUMENT", "unlock" to "REMOVE PDF RESTRICTIONS",
            "metadata" to "EDIT DOCUMENT PROPERTIES", "pdf2img" to "NATIVE EXPORT ENGINE", "img2pdf" to "JPG, PNG, OR WEBP",
            "pdf2zip" to "PAGES TO ARCHIVE", "extract-images" to "EXTRACT ASSETS FROM PDFS", "pdf2text" to "EXTRACT RAW CONTENT"
        )

        /** The *View composables that are not tools: the shell's own screens and the tools' shared success screen. */
        val NOT_TOOLS = setOf("HomeView", "HistoryView", "ToolsView", "SettingsView", "AboutView", "SuccessView")
    }
}
