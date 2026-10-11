package com.paperknifeplus.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LockedFilePromptTest {
    @get:Rule
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

        rule.onNodeWithText("UNLOCK FILE").performClick()
        rule.onNodeWithText("Incorrect password, try again.").assertExists()

        rule.onNode(hasSetTextAction()).performTextInput("x")
        rule.onNodeWithText("Incorrect password, try again.").assertDoesNotExist()

        rule.onNodeWithText("UNLOCK FILE").performClick()
        rule.onNodeWithText("Incorrect password, try again.").assertExists()
    }
}
