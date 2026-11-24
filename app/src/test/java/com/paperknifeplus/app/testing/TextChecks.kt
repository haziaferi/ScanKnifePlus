package com.paperknifeplus.app.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import com.paperknifeplus.app.ui.theme.DocumentContent
import com.paperknifeplus.app.ui.theme.PlusJakartaSans
import org.junit.Assert.fail

/**
 * Rule 1 of 2026-11-21 over every text on screen, in every window: each draws in Plus Jakarta Sans at 12 sp or more, its spans too. The
 * style checked is the one the text was laid out with, so a Material component's own default counts as much as a style given in the app.
 * Text marked [DocumentContent] shows a document's own content and is left out.
 */
fun SemanticsNodeInteractionsProvider.assertTextOnTheScale(where: String) {
    val problems = mutableListOf<String>()
    val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
    for (node in texts.filter { DocumentContent !in it.config }) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        for (input in layouts.map { it.layoutInput }) {
            val name = "\"${input.text.text.take(40)}\""
            val base = input.style.fontSize
            for (span in listOf(input.style.toSpanStyle()) + input.text.spanStyles.map { it.item }) {
                if (span.fontFamily != null && span.fontFamily != PlusJakartaSans) problems += "$name draws in ${span.fontFamily}"
                val size = sizeOf(span, base)
                if (size == null || size < MIN_SP) problems += "$name is ${span.fontSize}"
            }
            if (input.style.fontFamily == null) problems += "$name has no font family"
        }
    }
    if (problems.isNotEmpty()) fail("$where:\n" + problems.distinct().joinToString("\n"))
}

/** The span's size in sp, [base] for one that sets none, or null when it cannot be told. */
private fun sizeOf(span: SpanStyle, base: TextUnit): Float? = when {
    span.fontSize == TextUnit.Unspecified -> base.takeIf { it.isSp }?.value
    span.fontSize.isSp -> span.fontSize.value
    span.fontSize.isEm && base.isSp -> span.fontSize.value * base.value
    else -> null
}

private const val MIN_SP = 12f
