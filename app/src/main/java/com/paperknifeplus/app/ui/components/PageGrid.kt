package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R

/** Miniatures per grid row (U1). */
private const val COLUMNS = 5

/** Up to this many pages a grid shows every page; a longer file shows [FIRST_PAGES] (two rows) and "Show all N pages" (U1). */
internal const val GRID_ALL_UP_TO = 15
private const val FIRST_PAGES = 10

/** The long side of a page miniature, as the spec's 48 x 64 tile. */
private val MINIATURE_SIDE = 64.dp

/**
 * The shared page grid (U1): [pageCount] miniatures, five per row, rows wrapping, nothing scrolling sideways. A file of more than
 * [GRID_ALL_UP_TO] pages shows two rows and "Show all N pages", which opens the rest in place.
 *
 * Each tile is the [miniature] of its page (0-based index) with its number under it, the accent ring and a check when [isSelected], and the
 * [badge] in the other corner when there is one. A tap does what the screen needs ([onTap], read out as [tapLabel]); touch and hold, or the
 * TalkBack action "Read page N", opens the readable page card ([onRead]).
 */
@Composable
fun PageGrid(
    pageCount: Int,
    isSelected: (Int) -> Boolean,
    tapLabel: @Composable (Int) -> String,
    onTap: (Int) -> Unit,
    onRead: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badge: ((Int) -> String?)? = null,
    miniature: @Composable (Int) -> Unit
) {
    var expanded by rememberSaveable(pageCount) { mutableStateOf(false) }
    val shown = if (pageCount > GRID_ALL_UP_TO && !expanded) FIRST_PAGES else pageCount
    val rows = (shown + COLUMNS - 1) / COLUMNS
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.semantics { collectionInfo = CollectionInfo(rows, COLUMNS) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    for (column in 0 until COLUMNS) {
                        val index = row * COLUMNS + column
                        if (index < shown) {
                            PageTile(
                                index = index,
                                selected = isSelected(index),
                                tapLabel = tapLabel(index),
                                badge = badge?.invoke(index),
                                onTap = { onTap(index) },
                                onRead = { onRead(index) },
                                modifier = Modifier.weight(1f).semantics { collectionItemInfo = CollectionItemInfo(row, 1, column, 1) }
                            ) { miniature(index) }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (shown < pageCount) {
            ToolSecondaryButton(
                stringResource(R.string.page_grid_show_all, pageCount),
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.KeyboardArrowDown
            )
        }
    }
}

/** One tile of [PageGrid]: the miniature with its ring, check and badge, its number under it, and its tap and touch and hold. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTile(
    index: Int,
    selected: Boolean,
    tapLabel: String,
    badge: String?,
    onTap: () -> Unit,
    onRead: () -> Unit,
    modifier: Modifier,
    miniature: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val read = stringResource(R.string.page_read, index + 1)
    Column(
        modifier
            // TalkBack reads the tile as one button and offers touch and hold as the named action; the gestures' own semantics are replaced.
            .clearAndSetSemantics {
                contentDescription = tapLabel
                role = Role.Button
                this.selected = selected
                onClick { onTap(); true }
                customActions = listOf(CustomAccessibilityAction(read) { onRead(); true })
            }
            .combinedClickable(onClick = onTap, onLongClick = onRead)
            .heightIn(min = 48.dp)
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)
    ) {
        Box(
            Modifier.drawBehind {
                if (selected) {
                    // The ring sits 2 dp outside the page, as the spec's outline offset.
                    val inset = 3.5.dp.toPx()
                    drawRoundRect(
                        colors.primary,
                        topLeft = Offset(-inset, -inset),
                        size = Size(size.width + 2 * inset, size.height + 2 * inset),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                        style = Stroke(3.dp.toPx())
                    )
                }
            }
        ) {
            miniature()
            if (selected) {
                TileDot(colors.primary, Modifier.align(Alignment.TopEnd).offset(9.dp, (-9).dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
            if (badge != null) {
                TileDot(colors.inverseSurface, Modifier.align(Alignment.TopStart).offset((-9).dp, (-9).dp)) {
                    Text(badge, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 16.sp, color = colors.inverseOnSurface)
                }
            }
        }
        Text(
            "${index + 1}",
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
            lineHeight = 16.sp,
            color = colors.onBackground
        )
    }
}

/** A round mark on a miniature's corner: the selection check or a count badge. */
@Composable
private fun TileDot(color: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.defaultMinSize(22.dp, 22.dp).background(color, CircleShape).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Page [index] of [uri], opened with [password], as a grid miniature: its displayed shape [aspect] with the long side 64 dp (narrower when
 * the cell is), rendered at that size once it first shows on screen, so a long file renders only what is looked at. [overlay] draws over
 * the page's own area.
 */
@Composable
fun PdfPageMiniature(uri: Uri, password: String?, index: Int, aspect: Float, overlay: @Composable BoxScope.() -> Unit = {}) {
    var seen by remember(uri, index) { mutableStateOf(false) }
    val width = if (aspect >= 1f) MINIATURE_SIDE else MINIATURE_SIDE * aspect
    Box(
        Modifier
            .width(width)
            .aspectRatio(aspect)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
            .onVisibilityChanged(minFractionVisible = 0.01f) { visible -> if (visible) seen = true }
    ) {
        if (seen) PdfPageImage(uri, password, index, width, priority = 0, modifier = Modifier.fillMaxSize())
        overlay()
    }
}
