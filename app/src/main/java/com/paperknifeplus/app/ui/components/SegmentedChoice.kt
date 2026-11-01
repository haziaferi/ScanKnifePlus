package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SEGMENT_STYLE = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
private val SEGMENT_PAD = 10.dp
private val STACKED_PAD = 16.dp
private val MARK = 18.dp
private val MARK_GAP = 4.dp

/**
 * The one control for a small single choice (R2): [options] as segments of one line and one height (48 dp at least), the [selected] one
 * tonal with a check. When the labels, measured at the current text size, do not fit one row, the segments stack as full-width rows; a
 * row never wraps. Each segment is a radio button of one group.
 */
@Composable
fun SegmentedChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = LocalTextStyle.current.merge(SEGMENT_STYLE)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labels = options.sumOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        // The labels with their padding, the check of the selected one, the border and a little air (as the spec measures).
        val needed = with(density) { labels.toDp() } + SEGMENT_PAD * 2 * options.size + MARK + MARK_GAP + 10.dp
        val stacked = needed > maxWidth
        val shape = RoundedCornerShape(if (stacked) 16.dp else 24.dp)
        val frame = Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.outline, shape).selectableGroup()
        val segment: @Composable (Int) -> Unit = { index ->
            val on = index == selected
            Row(
                Modifier
                    .then(if (stacked) Modifier.fillMaxWidth() else Modifier)
                    .heightIn(min = 48.dp)
                    .then(if (on) Modifier.background(colors.primaryContainer) else Modifier)
                    .drawBehind {
                        // A divider drawn over the start of every segment but the first, so stacked rows gain no height from it.
                        val end = if (stacked) Offset(size.width, 0f) else Offset(0f, size.height)
                        if (index > 0) drawLine(colors.outline, Offset.Zero, end, 1.dp.toPx())
                    }
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = if (stacked) STACKED_PAD else SEGMENT_PAD, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (stacked) Arrangement.Start else Arrangement.Center
            ) {
                if (on) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(MARK))
                    Spacer(Modifier.size(MARK_GAP))
                } else if (stacked) {
                    Spacer(Modifier.size(MARK + MARK_GAP))
                }
                Text(options[index], style = style, color = if (on) colors.onPrimaryContainer else colors.onSurface, maxLines = 1, softWrap = false)
            }
        }
        if (stacked) {
            Column(frame) { options.indices.forEach { segment(it) } }
        } else {
            SegmentRow(frame) { options.indices.forEach { segment(it) } }
        }
    }
}

/** Segments side by side, each its own width plus an equal share of the room left, all as tall as the tallest. */
@Composable
private fun SegmentRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val room = (constraints.maxWidth - natural.sum()).coerceAtLeast(0)
        val widths = natural.mapIndexed { i, w -> w + room / natural.size + if (i == natural.lastIndex) room % natural.size else 0 }
        val height = measurables.indices.maxOf { measurables[it].minIntrinsicHeight(widths[it]) }.coerceAtLeast(constraints.minHeight)
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
        layout(widths.sum(), height) {
            var x = 0
            placeables.forEach {
                it.place(x, 0)
                x += it.width
            }
        }
    }
}
