package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.theme.AppPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings (4c): each one-line row shows its value and opens a sheet whose choice applies and closes it. */
@RunWith(RobolectricTestRunner::class)
class SettingsViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val palette = mutableStateOf(AppPalette.STILL_WATER)

    private fun show(
        themes: MutableList<Int> = mutableListOf(),
        folder: FolderStatus = FolderStatus.NotChosen,
        onFolder: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
        onBack: () -> Unit = {}
    ) = rule.setContent {
        SettingsView(
            onThemeChange = { themes += it }, palette = palette.value, onPaletteChange = { palette.value = it }, onOpenAbout = onOpenAbout,
            onBack = onBack, folder = folder, onFolder = onFolder
        )
    }

    private fun scrollTo(text: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    @Test
    fun `the theme row shows the theme and its sheet applies OLED`() {
        val themes = mutableListOf<Int>()
        show(themes)
        rule.onNodeWithText("System").assertExists()
        rule.onNodeWithText("Theme").performClick()
        rule.onNodeWithText("Dark").assertDoesNotExist()
        rule.onNode(hasText("System") and isSelected()).assertExists()
        rule.onNodeWithText("OLED").performClick()
        assertEquals(listOf(2), themes)
        rule.onNodeWithText("Light").assertDoesNotExist() // the sheet closed
        rule.onNodeWithText("OLED").assertExists() // the row's value
    }

    @Test
    fun `the palette row shows the palette and its sheet applies the choice`() {
        show()
        rule.onNodeWithText("Still Water").assertExists()
        rule.onNodeWithText("Colour palette").performClick()
        rule.onNodeWithText("Graphite Scholastic").performClick()
        assertEquals(AppPalette.GRAPHITE_SCHOLASTIC, palette.value)
        rule.onNodeWithText("Rose").assertDoesNotExist()
        rule.onNodeWithText("Graphite Scholastic").assertExists()
    }

    @Test
    fun `the folder row shows the folder and its sheet opens Folder setup`() {
        var opened = 0
        show(folder = FolderStatus.Ready("Documents/Scans"), onFolder = { opened++ })
        rule.onNodeWithText("Documents/Scans").performClick()
        rule.onNodeWithText("Saving to Documents/Scans").assertExists()
        rule.onNodeWithText("Change").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Saving to Documents/Scans").assertDoesNotExist()
    }

    @Test
    fun `without a folder the folder sheet says so and offers Choose folder`() {
        var opened = 0
        show(onFolder = { opened++ })
        rule.onNodeWithText("Not chosen").performClick()
        rule.onNodeWithText("Not chosen · each file is saved with the picker").assertExists()
        rule.onNodeWithText("Choose folder").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `an unreachable folder says so on its row and in its sheet`() {
        show(folder = FolderStatus.ChooseAgain("Documents/Scans"))
        rule.onNodeWithText("Choose again").performClick()
        rule.onNodeWithText("Can't reach Documents/Scans · choose it again").assertExists()
    }

    @Test
    fun `the author sheet saves the name and the row shows it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferencesManager.setDefaultAuthor(context, "")
        show()
        rule.onNodeWithText("Not set").performClick()
        rule.onNodeWithText("e.g. John Doe").performTextInput("Ana Rossi")
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Ana Rossi").assertExists()
        assertEquals("Ana Rossi", PreferencesManager.getDefaultAuthor(context))
    }

    @Test
    fun `clear history asks in a sheet, and Cancel keeps the history`() {
        rule.runOnUiThread { SessionManager.addEntry("kept.pdf", "compress", "1 KB", Icons.Filled.Info) }
        show()
        scrollTo("Clear history")
        rule.onNodeWithText("Clear history").performClick()
        rule.onNodeWithText("Clear history?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(SessionManager.history.isNotEmpty())

        rule.onNodeWithText("Clear history").performClick()
        rule.onNodeWithText("Wipe everything").performClick()
        assertTrue(SessionManager.history.isEmpty())
        rule.onNodeWithText("Clear history?").assertDoesNotExist()
    }

    @Test
    fun `the About row opens About under the app name and the footer shows the app name`() {
        var opened = 0
        show(onOpenAbout = { opened++ })
        scrollTo("About ScanKnife+")
        rule.onNodeWithText("About ScanKnife+").performClick()
        assertEquals(1, opened)

        scrollTo("ScanKnife+ V")
        rule.onNodeWithText("Support Us").assertDoesNotExist()
        rule.onNodeWithText("Hall of Fame").assertDoesNotExist()
        rule.onNodeWithText("PaperKnife", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the top bar's back button leaves Settings`() {
        var back = 0
        show(onBack = { back++ })
        rule.onNodeWithContentDescription("Back to Home").performClick()
        assertEquals(1, back)
    }
}
