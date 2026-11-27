package com.haziaferi.scanknifeplus.ui.folder

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.assertTargetsFit
import com.paperknifeplus.app.testing.assertWordsWhole
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
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
 * Home's folder bar (and History's line, the same bar) at twice the text size, in English and Italian on 384 and 320 dp, with files kept in
 * the app (Move to folder) and without (Choose folder): when the line and its action do not both fit on one line the action goes under the
 * line, so the line keeps at least half the bar and breaks only between words (the 6T showed it squeezed into a column 90 dp wide).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
abstract class FolderBarLayout {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun layoutOf(text: String): TextLayoutResult = mutableListOf<TextLayoutResult>().also {
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it)
    }.single()

    private fun check(appFiles: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                PaperKnifePlusTheme { FolderBar(FolderStatus.NotChosen, onClick = {}, appFiles = appFiles) }
            }
        }
        val line = context.getString(R.string.folder_bar_in_app)
        val action = context.getString(if (appFiles) R.string.folder_bar_move else R.string.folder_setup_choose)
        val bar = rule.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithText(line, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithText(action, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the line is ${text.width} px of a ${bar.width} px bar", text.width >= bar.width / 2)
        assertTrue("$action overlaps the line", text.right <= label.left || text.bottom <= label.top)
        for (shown in listOf(line, action)) {
            val layout = layoutOf(shown)
            assertWordsWhole(layout)
            assertFalse("$shown is cut", layout.didOverflowHeight)
            assertEquals("$shown loses its end", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
        rule.assertTargetsFit(rule.density, "the folder bar at 2.0x")
    }

    @Test fun `with files kept in the app, Move to folder`() = check(appFiles = true)
    @Test fun `without files, Choose folder`() = check(appFiles = false)
}

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "w384dp-h832dp")
class FolderBarLayoutTest : FolderBarLayout()

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "w320dp-h640dp")
class FolderBarLayoutSmallPhoneTest : FolderBarLayout()

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "it-w384dp-h832dp")
class FolderBarLayoutItalianTest : FolderBarLayout()

@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "it-w320dp-h640dp")
class FolderBarLayoutItalianSmallPhoneTest : FolderBarLayout()
