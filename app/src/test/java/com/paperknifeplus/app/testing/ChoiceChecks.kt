package com.paperknifeplus.app.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** A radio button showing exactly [label]. */
fun radioButton(label: String): SemanticsMatcher = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

/** The segment [label] of a choice on screen: one radio button shows it. */
fun SemanticsNodeInteractionsProvider.segment(label: String): SemanticsNodeInteraction {
    assertEquals("one radio button shows \"$label\"", 1, onAllNodes(radioButton(label)).fetchSemanticsNodes().size)
    return onNode(radioButton(label))
}

/**
 * The segments of one choice, in the order of [labels]: each one radio button, all of them children of one selectable group, exactly one
 * of them selected. Fails when a label shows no segment, so the check never passes on nothing.
 */
fun SemanticsNodeInteractionsProvider.assertOneChoice(labels: List<String>): List<SemanticsNode> {
    val segments = labels.map { segment(it).fetchSemanticsNode() }
    val parents = segments.map { it.parent }.distinctBy { it?.id }
    assertEquals("the segments $labels share one parent", 1, parents.size)
    assertTrue("the segments $labels are one selectable group", parents.single()?.config?.contains(SemanticsProperties.SelectableGroup) == true)
    assertEquals("exactly one of $labels is selected", 1, segments.count { it.config.getOrElse(SemanticsProperties.Selected) { false } })
    return segments
}

/** Whether [segments] stack as rows, each below the one before, rather than sitting side by side in one row. */
fun stacked(segments: List<SemanticsNode>): Boolean {
    val tops = segments.map { it.boundsInRoot.top }
    if (tops.all { abs(it - tops.first()) < 0.5f }) return false
    assertTrue("the segments are either one row or rows one below the other: $tops", tops.zipWithNext().all { (a, b) -> b > a })
    return true
}
