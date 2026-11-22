package com.paperknifeplus.app.ui.theme

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The target-size ladder (R14): every control takes one step, and one role takes the same step on every screen. A step is a minimum: a
 * control grows with its text, and controls in one row grow together to the tallest. Material's own buttons and icon buttons come as T3:
 * drawn 40 dp in a 48 dp interactive size.
 */
enum class TargetStep(val drawn: Dp, val hit: Dp) {
    /** T1 Commit: the screen's one primary action, such as the bottom primary button and the bar's Scan; the shutter draws larger. */
    COMMIT(56.dp, 56.dp),

    /** T2 Act: navigation and secondary actions, such as top-bar icons, tabs, segments, list and option rows and dialog buttons. */
    ACT(48.dp, 48.dp),

    /** T3 Inline: a tertiary action in a card or line, such as the file card's Change, See all, text links, chips and a tip's close. */
    INLINE(40.dp, 48.dp),

    /** T4 Dense: a control inside a page tile or a dense grid, such as the tile's zoom, held in [DenseTargets]. */
    DENSE(24.dp, 24.dp);

    companion object {
        /** The least gap between a T3 control and the next target, drawn edge to drawn edge, so that their hit areas at most touch. */
        val INLINE_GAP: Dp = 8.dp
    }
}

/**
 * T3 for a hand-made control: lays out [TargetStep.INLINE]'s hit area and draws the rest of the chain at least [TargetStep.INLINE]'s drawn
 * height in its middle, so the control's shape, clickable and padding come after it. Compose's minimum touch target makes the drawn part
 * answer touches across the whole hit area.
 */
fun Modifier.inlineTarget(): Modifier =
    sizeIn(minWidth = TargetStep.INLINE.hit, minHeight = TargetStep.INLINE.hit).wrapContentSize().heightIn(min = TargetStep.INLINE.drawn)

/**
 * Holds T4 controls: each answers touches only where it is drawn ([TargetStep.DENSE]), not across Compose's 48 dp minimum touch target, so
 * the controls of one page tile never take each other's touches or the tile's.
 */
@Composable
fun DenseTargets(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val dense = remember(base) {
        object : ViewConfiguration by base {
            override val minimumTouchTargetSize = DpSize(TargetStep.DENSE.hit, TargetStep.DENSE.hit)
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides dense, content = content)
}
