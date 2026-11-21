package com.paperknifeplus.app.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class LockedFilePromptTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun shows(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    @Test
    fun `the password is masked until the toggle reveals it`() {
        rule.setContent { LockedFilePrompt(fileName = "a.pdf", onDismiss = {}, onUnlocked = {}) }
        rule.onNode(hasSetTextAction()).performTextInput("secret")
        rule.onNode(hasSetTextAction()).assert(shows("•".repeat(6)))

        rule.onNodeWithContentDescription("Show password").performClick()
        rule.onNode(hasSetTextAction()).assert(shows("secret"))
        rule.onNodeWithContentDescription("Hide password").performClick()
        rule.onNode(hasSetTextAction()).assert(shows("•".repeat(6)))
    }

    @Test
    fun `IME Done submits the password`() {
        var submitted: String? = null
        rule.setContent { LockedFilePrompt(fileName = "a.pdf", onDismiss = {}, onUnlocked = { submitted = it }) }
        rule.onNode(hasSetTextAction()).performTextInput("pw")
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals("pw", submitted)
    }

    @Test
    fun `the error shows when the caller reports it and clears on edit`() {
        var isError by mutableStateOf(false)
        rule.setContent { LockedFilePrompt(fileName = "a.pdf", onDismiss = {}, onUnlocked = { isError = true }, isError = isError) }
        rule.onNodeWithText("Incorrect password, try again.").assertDoesNotExist()

        rule.onNodeWithText("Unlock").performClick()
        rule.onNodeWithText("Incorrect password, try again.").assertExists()

        rule.onNode(hasSetTextAction()).performTextInput("x")
        rule.onNodeWithText("Incorrect password, try again.").assertDoesNotExist()

        rule.onNodeWithText("Unlock").performClick()
        rule.onNodeWithText("Incorrect password, try again.").assertExists()
    }

    /** Back, as the system hands it to the prompt's window. */
    private fun pressBack() {
        rule.waitForIdle()
        rule.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** A tap at the window's top-left corner, outside the prompt's card, as the system hands it to the dialog. */
    private fun tapOutside() {
        rule.waitForIdle()
        rule.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(time, time, action, 1f, 1f, 0).also { dialog.onTouchEvent(it) }.recycle()
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `while the password is checked, Cancel and Back still close the prompt, and a tap outside does not`() {
        var dismissed = 0
        var loading by mutableStateOf(true)
        rule.setContent { LockedFilePrompt(fileName = "a.pdf", onDismiss = { dismissed++ }, onUnlocked = {}, isLoading = loading) }

        rule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        assertEquals(1, dismissed)
        pressBack()
        assertEquals(2, dismissed)
        tapOutside()
        assertEquals(2, dismissed)

        // Not checking, a tap outside closes it too.
        loading = false
        tapOutside()
        assertEquals(3, dismissed)
    }
}
