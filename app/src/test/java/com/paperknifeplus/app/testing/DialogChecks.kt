package com.paperknifeplus.app.testing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** The button labelled [label] in an open dialog; fails when there is none, so a check never passes on a dialog that did not open. */
fun SemanticsNodeInteractionsProvider.dialogButton(label: String): SemanticsNodeInteraction {
    val matcher = hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())
    assertEquals("one \"$label\" button shows in a dialog", 1, onAllNodes(matcher).fetchSemanticsNodes().size)
    return onNode(matcher)
}

/**
 * The colour the button draws just inside its start edge, half way down: its fill for a filled button, what lies under it (the dialog's
 * surface) for a text button. 8 dp in is inside a pill's rounded end and short of its label.
 */
fun SemanticsNodeInteraction.startFill(density: Density): Color {
    val pixels = captureToImage().toPixelMap()
    return pixels[with(density) { 8.dp.roundToPx() }, pixels.height / 2]
}

/** Whether [a] and [b] are one colour, within rounding. */
fun sameColour(a: Color, b: Color): Boolean = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) < 0.03f

/**
 * R5 in an open dialog: of its [buttons], given start to end, the [commit] alone is filled with [fill], and the rest are text buttons. A
 * dialog with no commit (null) fills none of them.
 */
fun SemanticsNodeInteractionsProvider.assertCommitFilled(buttons: List<String>, commit: String?, fill: Color, density: Density, where: String) {
    val problems = buttons.mapNotNull { label ->
        val filled = sameColour(dialogButton(label).startFill(density), fill)
        when {
            label == commit && !filled -> "the commit, $label, is not filled"
            label != commit && filled -> "$label is filled, but it is not the commit"
            else -> null
        }
    }
    if (problems.isNotEmpty()) fail("$where:\n" + problems.joinToString("\n"))
}

/** The layouts of every text reading exactly [text] in an open dialog; fails when there is none. */
fun SemanticsNodeInteractionsProvider.dialogTextLayouts(text: String): List<TextLayoutResult> {
    val nodes = onAllNodes(hasText(text) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes()
    assertTrue("\"$text\" shows in a dialog", nodes.isNotEmpty())
    return nodes.map { node -> mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it) }.single() }
}

/** Fails when a line of [layout] ends inside a word: the line breaks only at a space. */
fun assertWordsWhole(layout: TextLayoutResult) {
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        assertTrue("\"$text\" breaks inside a word at line ${line + 1}", text[end - 1].isWhitespace() || text[end].isWhitespace())
    }
}

/** Decision 6 of 2026-11-24 for the dialog [title]: its words whole, at [floor] (the dialog's body text) or larger. */
fun SemanticsNodeInteractionsProvider.assertTitleFits(title: String, floor: TextUnit) {
    for (layout in dialogTextLayouts(title)) {
        assertWordsWhole(layout)
        val size = layout.layoutInput.style.fontSize
        assertTrue("\"$title\" is drawn at $size, under the body text's $floor", size.value >= floor.value)
    }
}
