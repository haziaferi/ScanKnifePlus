package com.paperknifeplus.app.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.paperknifeplus.app.ui.theme.DenseTarget
import com.paperknifeplus.app.ui.theme.DenseTargets
import com.paperknifeplus.app.ui.theme.TargetStep
import org.junit.Assert.fail

/**
 * R14's checks over every target on screen, in every window (a dialog or sheet is its own root): each hit area is at least its step's,
 * 48 x 48 dp, or 24 x 24 dp for a T4 control (marked by [DenseTargets]), and no two hit areas overlap, touching edges aside. A hit area is
 * the node's touch bounds, which include Compose's minimum touch target, cut to
 * its window and, for a node scrolled partly out of view, to what shows of it; a node out of its window is not on screen. A target inside
 * another (a tile's zoom in its tile) overlaps it by design and takes the touches it covers, and a backdrop as large as its window (a
 * sheet's scrim) lies under everything in it, so neither pair is compared.
 */
fun SemanticsNodeInteractionsProvider.assertTargetsFit(density: Density, where: String) {
    val problems = mutableListOf<String>()
    val targets = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        .map(::Target)
        .filter { !it.hit.isEmpty && !it.backdrop }
    for (target in targets.filter { it.whole }) {
        val hit = target.hit
        val least = with(density) { (if (target.dense) TargetStep.DENSE.hit else TargetStep.ACT.hit).toPx() } - TOLERANCE
        if (hit.width < least || hit.height < least) problems += "${target.name} is ${dp(hit.width, density)} x ${dp(hit.height, density)} dp"
    }
    for ((i, a) in targets.withIndex()) {
        for (b in targets.drop(i + 1)) {
            if (a.root != b.root || a.node.contains(b.node) || b.node.contains(a.node)) continue
            val overlap = a.hit.intersect(b.hit)
            if (overlap.width > TOLERANCE && overlap.height > TOLERANCE) {
                problems += "${a.name} and ${b.name} overlap by ${dp(overlap.width, density)} x ${dp(overlap.height, density)} dp"
            }
        }
    }
    if (problems.isNotEmpty()) fail("$where:\n" + problems.joinToString("\n"))
}

/** Half a pixel, for rounding in layout. */
private const val TOLERANCE = 0.5f

private class Target(val node: SemanticsNode) {
    private val window: SemanticsNode = generateSequence(node) { it.parent }.last()
    val root: Int = window.id

    /** A T4 control, held to 24 dp. */
    val dense: Boolean = DenseTarget in node.config

    private val bounds = Rect(node.positionInRoot, node.size.toSize())

    /** Cut by an ancestor's clip, such as a scrolled list's edge: the hit area is then what shows. */
    private val clipped: Boolean = node.boundsInRoot.let { it.width < bounds.width - TOLERANCE || it.height < bounds.height - TOLERANCE }

    val hit: Rect = (if (clipped) node.boundsInRoot else node.touchBoundsInRoot).intersect(window.boundsInRoot).let { if (it.isEmpty) Rect.Zero else it }

    /** Shown whole, so its size counts. */
    val whole: Boolean = !clipped && window.boundsInRoot.intersect(node.touchBoundsInRoot) == node.touchBoundsInRoot

    val backdrop: Boolean = window.boundsInRoot.let { bounds.width >= it.width - TOLERANCE && bounds.height >= it.height - TOLERANCE }

    val name: String = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text?.let { "field \"$it\"" }
        ?: "node ${node.id}"
}

private fun SemanticsNode.contains(other: SemanticsNode): Boolean = generateSequence(other.parent) { it.parent }.any { it.id == id }

private fun dp(px: Float, density: Density): String = "%.1f".format(px / density.density)
