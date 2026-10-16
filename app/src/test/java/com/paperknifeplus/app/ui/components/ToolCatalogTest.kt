package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.mainThreadComposeRule
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    val rule = mainThreadComposeRule()

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
        val screens = components.listFiles()!!.map { it.name.removeSuffix(".kt") }.filter { it.endsWith("View") && it !in SHELL_SCREENS }
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
    fun `every catalog tool opens its screen`() {
        PDFBoxResourceLoader.init(context)
        var id by mutableStateOf(ToolCatalog.tools.first().id)
        rule.setContent { key(id) { ToolCatalog.byId(id)!!.screen(ToolLaunch(null, null, onBack = {}, onOpenPreview = { _, _, _ -> })) } }
        for (tool in ToolCatalog.tools) {
            id = tool.id
            rule.waitForIdle()
            assertNotNull(tool.id, rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().firstOrNull())
        }
    }

    private fun sourceDir(pkg: String): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve(pkg)

    private companion object {
        /** The *View screens that are the shell's own, not tools. */
        val SHELL_SCREENS = setOf("HomeView", "HistoryView", "ToolsView", "SettingsView", "AboutView")
    }
}
