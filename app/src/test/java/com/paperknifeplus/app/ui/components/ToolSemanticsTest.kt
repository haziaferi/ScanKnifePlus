package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tools' single-choice controls are radio buttons with a selected state, so the choice is never shown by colour alone. */
@RunWith(RobolectricTestRunner::class)
class ToolSemanticsTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun `option rows are radio buttons that say which one is chosen`() {
        rule.setContent {
            Column {
                ToolOptionRow(selected = true, title = "Recommended", description = "Balance", onClick = {})
                ToolOptionRow(selected = false, title = "Extreme", description = "Smallest", onClick = {})
            }
        }
        rule.onNode(isSelectable() and hasText("Recommended")).assert(radio).assertIsSelected()
        rule.onNode(isSelectable() and hasText("Extreme")).assert(radio).assertIsNotSelected()
    }

    @Test
    fun `ink swatches are named radio buttons`() {
        rule.setContent {
            Column {
                DocumentInk.signature.take(2).forEachIndexed { i, ink -> InkSwatch(ink, selected = i == 0, onClick = {}) }
            }
        }
        rule.onNode(hasContentDescription("Black")).assert(radio).assertIsSelected()
        rule.onNode(hasContentDescription("Blue")).assert(radio).assertIsNotSelected()
    }
}
