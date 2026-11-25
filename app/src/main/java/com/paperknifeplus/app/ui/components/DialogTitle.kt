package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import com.paperknifeplus.app.ui.theme.resizedTo

/**
 * A dialog's title (decision 6 of 2026-11-24), for the title slot of every AlertDialog, in the slot's style. When its longest word is wider
 * than the line, as Italian's are at large text, the size drops just enough for that word to fit, never below the dialog's body text, so
 * words wrap whole rather than break.
 */
@Composable
fun DialogTitle(text: String) {
    val style = LocalTextStyle.current
    val floor = MaterialTheme.typography.bodyMedium.fontSize
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val width = constraints.maxWidth
        val fitted = remember(text, style, floor, width) { fitLongestWord(measurer, text, style, floor, width) }
        Text(text, style = fitted)
    }
}

/** [style] at the largest size, down to [floor], at which the widest word of [text] fits [width] pixels on one line. */
private fun fitLongestWord(measurer: TextMeasurer, text: String, style: TextStyle, floor: TextUnit, width: Int): TextStyle {
    val words = text.split(' ', '\n').filter { it.isNotEmpty() }
    if (words.isEmpty() || !style.fontSize.isSp || !floor.isSp) return style
    fun widest(s: TextStyle) = words.maxOf { measurer.measure(it, s, softWrap = false, maxLines = 1).size.width }
    var fitted = style
    var size = style.fontSize.value
    while (size > floor.value && widest(fitted) > width) {
        size = maxOf(floor.value, size - STEP_SP)
        fitted = style.resizedTo(size)
    }
    return fitted
}

/** How far the title's size drops at each try: small enough that it stays as near the style as the word allows. */
private const val STEP_SP = 0.5f
