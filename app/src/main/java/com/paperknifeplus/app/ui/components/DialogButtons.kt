package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep

/** How a dialog button draws (R5): as text, or as the dialog's one commit, filled with the accent or, when it deletes, the danger colour. */
enum class DialogButtonKind { TEXT, COMMIT, DANGER_COMMIT }

/** A dialog's button (T2): [label] as [kind] says, a text button's label in [color] (the button's own when unspecified). */
@Composable
fun DialogButton(
    label: String,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    kind: DialogButtonKind = DialogButtonKind.TEXT,
    onClick: () -> Unit
) {
    val modifier = Modifier.heightIn(min = TargetStep.ACT.drawn)
    when (kind) {
        DialogButtonKind.TEXT -> TextButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label, color = color) }
        DialogButtonKind.COMMIT -> Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
        DialogButtonKind.DANGER_COMMIT -> {
            // The background on danger: 5.85:1 at the least, on Rose's OLED theme.
            val palette = LocalScanPalette.current
            val colors = ButtonDefaults.buttonColors(containerColor = palette.danger, contentColor = palette.bg)
            Button(onClick = onClick, enabled = enabled, modifier = modifier, colors = colors) { Text(label) }
        }
    }
}

/**
 * A dialog's buttons, given start to end with the commit last, for its confirm slot. They sit in one row at the end when they fit, sharing
 * one height (R14 rule 1), else stack with the commit on top. Material's own button row places a commit taller than the button under it over
 * that button, as a wrapped label at large text makes it (the Save PDF dialog at twice the text size); this never overlaps.
 */
@Composable
fun DialogButtons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val rowGap = 8.dp.roundToPx()
        val stackGap = 12.dp.roundToPx()
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val rowWidth = widths.sum() + rowGap * (measurables.size - 1).coerceAtLeast(0)
        if (!constraints.hasBoundedWidth || rowWidth <= constraints.maxWidth) {
            val height = measurables.indices.maxOfOrNull { measurables[it].minIntrinsicHeight(widths[it]) } ?: 0
            val placeables = measurables.mapIndexed { i, it -> it.measure(Constraints.fixed(widths[i], height)) }
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else rowWidth
            layout(width, height) {
                var x = width - rowWidth
                for (placeable in placeables) {
                    placeable.place(x, 0)
                    x += placeable.width + rowGap
                }
            }
        } else {
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(loose) }.asReversed()
            val height = placeables.sumOf { it.height } + stackGap * (placeables.size - 1)
            layout(constraints.maxWidth, height) {
                var y = 0
                for (placeable in placeables) {
                    placeable.place(constraints.maxWidth - placeable.width, y)
                    y += placeable.height + stackGap
                }
            }
        }
    }
}
