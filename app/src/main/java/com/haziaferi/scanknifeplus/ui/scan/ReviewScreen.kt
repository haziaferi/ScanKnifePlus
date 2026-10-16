package com.haziaferi.scanknifeplus.ui.scan

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import java.io.File
import kotlin.math.roundToInt

/** A page as Review shows it: its id and the file of its current image. */
internal data class ReviewPage(val id: String, val image: File)

/** The spread Review shows for a Book mode shot: its two pages, the number of the left one and the split line (0 to 1). */
internal data class ReviewSpread(val left: ReviewPage, val right: ReviewPage, val leftNumber: Int, val split: Float)

/** What Review shows, in plain values. [pages] excludes a page deleted and awaiting Undo, whose number is [deletedNumber]. */
internal data class ReviewUi(
    val mode: CaptureMode,
    val pages: List<ReviewPage>,
    val selectedId: String? = pages.lastOrNull()?.id,
    val spread: ReviewSpread? = null,
    val deletedNumber: Int? = null,
    val storing: Boolean = false,
    val busy: Boolean = false,
    val canSave: Boolean = pages.isNotEmpty(),
    @StringRes val notice: Int? = null,
) {
    val selectedIndex: Int get() = pages.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: pages.lastIndex
}

/** Review's actions. */
internal class ReviewActions(
    val onBack: () -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onMove: (Int) -> Unit = {},
    val onSplit: (Float) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onCrop: () -> Unit = {},
    val onFilter: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onAddPage: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * Review (prototype "Review", one layout per capture mode): the page large (Single page), the selected page with arrows that move it and a page
 * strip (Multi page), or the spread with its labels below it and the split line slider (Book). The page actions keep Delete apart behind a
 * divider, with Undo after a delete (2c); Done sits bottom-right (3a); thumbnails are dimmed in OLED (3g).
 */
@Composable
internal fun ReviewScreen(ui: ReviewUi, actions: ReviewActions) {
    val c = LocalScanPalette.current
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Header(ui, actions.onBack)
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.acf, trackColor = c.sf2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            val spread = ui.spread
            if (ui.mode == CaptureMode.BOOK && spread != null) {
                SpreadView(spread, ui.busy, actions.onSplit)
            } else {
                PageBox {
                    val page = ui.pages.getOrNull(ui.selectedIndex)
                    when {
                        page != null -> AsyncImage(
                            model = page.image, contentDescription = stringResource(R.string.scan_page_label, ui.selectedIndex + 1),
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp)
                        )
                        ui.storing -> CircularProgressIndicator(color = c.acf)
                        else -> EmptyText(if (ui.mode == CaptureMode.SINGLE) R.string.scan_page_deleted else R.string.scan_no_pages)
                    }
                }
                if (ui.mode == CaptureMode.MULTI && ui.pages.isNotEmpty()) MoveRow(ui, actions.onMove)
            }
            PageActions(ui, actions)
        }
        ui.deletedNumber?.let { UndoBar(it, actions.onUndo) }
        ui.notice?.let { NoticeBar(it, actions.onDismissNotice) }
        Footer(ui, actions)
    }
}

@Composable
private fun Header(ui: ReviewUi, onBack: () -> Unit) {
    val c = LocalScanPalette.current
    val n = ui.pages.size
    val subtitle = when (ui.mode) {
        CaptureMode.SINGLE -> stringResource(R.string.scan_mode_single)
        CaptureMode.MULTI -> stringResource(R.string.scan_review_multi, pluralStringResource(R.plurals.scan_pages, n, n))
        CaptureMode.BOOK -> {
            val spreads = (n + 1) / 2
            stringResource(R.string.scan_review_book, pluralStringResource(R.plurals.scan_spreads, spreads, spreads), pluralStringResource(R.plurals.scan_pages, n, n))
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.scan_back_to_scanner), enabled = true, onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.scan_review_title), style = scanText(18, FontWeight.ExtraBold), color = c.tx, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = scanText(13, FontWeight.Medium), color = c.mu)
        }
    }
}

@Composable
private fun PageBox(content: @Composable () -> Unit) {
    Box(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp))
            .background(LocalScanPalette.current.sf2),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun EmptyText(@StringRes text: Int) {
    Text(
        stringResource(text), style = scanText(14, FontWeight.Medium), color = LocalScanPalette.current.mu, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp)
    )
}

@Composable
private fun MoveRow(ui: ReviewUi, onMove: (Int) -> Unit) {
    val i = ui.selectedIndex
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.scan_page_of, i + 1, ui.pages.size), style = scanText(14, FontWeight.Bold), color = LocalScanPalette.current.tx,
            modifier = Modifier.weight(1f)
        )
        OutlinedIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.scan_move_earlier), i > 0 && !ui.busy) { onMove(-1) }
        OutlinedIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.scan_move_later), i < ui.pages.lastIndex && !ui.busy) { onMove(1) }
    }
}

/** The spread with its split line, the page labels below it (3g, so they never cover the pages), and the split slider. */
@Composable
private fun SpreadView(spread: ReviewSpread, busy: Boolean, onSplit: (Float) -> Unit) {
    val c = LocalScanPalette.current
    // Keyed by the pages' images, so a finished re-cut (new files) starts the slider from what was applied.
    var split by remember(spread.left.image, spread.right.image) { mutableFloatStateOf(spread.split.coerceIn(SPLIT_MIN, SPLIT_MAX)) }
    PageBox {
        Row(Modifier.fillMaxSize().padding(16.dp)) {
            AsyncImage(
                model = spread.left.image, contentDescription = stringResource(R.string.scan_page_label, spread.leftNumber),
                contentScale = ContentScale.Fit, alignment = Alignment.CenterEnd, modifier = Modifier.weight(split).fillMaxHeight()
            )
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.acf))
            AsyncImage(
                model = spread.right.image, contentDescription = stringResource(R.string.scan_page_label, spread.leftNumber + 1),
                contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.weight(1f - split).fillMaxHeight()
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
        for (n in listOf(spread.leftNumber, spread.leftNumber + 1)) {
            Text(stringResource(R.string.scan_page_label, n), style = scanText(13, FontWeight.Bold), color = c.mu)
        }
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val label = splitLabel(split)
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.scan_split_line), style = scanText(14, FontWeight.Bold), color = c.tx, modifier = Modifier.weight(1f))
            Text(label, style = scanText(14, FontWeight.Medium), color = c.mu)
        }
        Slider(
            value = split,
            onValueChange = { split = (it * 100).roundToInt() / 100f },
            onValueChangeFinished = { onSplit(split) },
            valueRange = SPLIT_MIN..SPLIT_MAX,
            steps = ((SPLIT_MAX - SPLIT_MIN) * 100).roundToInt() - 1,
            enabled = !busy,
            colors = SliderDefaults.colors(thumbColor = c.acf, activeTrackColor = c.acf, inactiveTrackColor = c.sf2, activeTickColor = c.acf, inactiveTickColor = c.bd),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = label }
        )
        Text(stringResource(R.string.scan_split_hint), style = scanText(13, FontWeight.Medium), color = c.mu)
    }
}

/** "Centre", or how far the line sits from the centre in percent of the spread's width, as the prototype words it. */
@Composable
internal fun splitLabel(split: Float): String {
    val offset = ((split - 0.5f) * 100).roundToInt()
    return when {
        offset == 0 -> stringResource(R.string.scan_split_centre)
        offset < 0 -> stringResource(R.string.scan_split_left, -offset)
        else -> stringResource(R.string.scan_split_right, offset)
    }
}

@Composable
private fun PageActions(ui: ReviewUi, actions: ReviewActions) {
    val c = LocalScanPalette.current
    val enabled = ui.pages.isNotEmpty() && !ui.busy
    val group = stringResource(R.string.scan_page_actions)
    Row(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp).fillMaxWidth().semantics { contentDescription = group },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionButton(Icons.AutoMirrored.Outlined.RotateRight, R.string.scan_rotate, enabled, c.tx, actions.onRotate)
        ActionButton(Icons.Outlined.Crop, R.string.scan_crop, enabled, c.tx, actions.onCrop)
        ActionButton(Icons.Outlined.Contrast, R.string.scan_filter, enabled, c.tx, actions.onFilter)
        Box(Modifier.width(1.dp).height(40.dp).background(c.bd))
        ActionButton(Icons.Outlined.Delete, R.string.scan_delete, enabled, c.danger, actions.onDelete)
    }
}

@Composable
private fun RowScope.ActionButton(
    icon: ImageVector,
    @StringRes label: Int,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(stringResource(label), style = scanText(12, FontWeight.SemiBold), color = color, textAlign = TextAlign.Center)
    }
}

@Composable
private fun UndoBar(number: Int, onUndo: () -> Unit) {
    val c = LocalScanPalette.current
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.selBg)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.scan_page_n_deleted, number), style = scanText(14, FontWeight.SemiBold), color = c.selFg, modifier = Modifier.weight(1f))
        Box(
            Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onUndo)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.scan_undo), style = scanText(15, FontWeight.ExtraBold).copy(textDecoration = TextDecoration.Underline),
                color = c.selFg
            )
        }
    }
}

@Composable
private fun NoticeBar(@StringRes text: Int, onDismiss: () -> Unit) {
    val c = LocalScanPalette.current
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.warnBg)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(text), style = scanText(14, FontWeight.SemiBold), color = c.warnFg, modifier = Modifier.weight(1f))
        Box(
            Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onDismiss)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.scan_dismiss), style = scanText(15, FontWeight.ExtraBold), color = c.warnFg)
        }
    }
}

@Composable
private fun Footer(ui: ReviewUi, actions: ReviewActions) {
    val c = LocalScanPalette.current
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.bd))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ui.mode == CaptureMode.SINGLE) {
                Spacer(Modifier.weight(1f))
            } else {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.pages.forEachIndexed { i, page -> Thumbnail(page, i + 1, page.id == ui.pages.getOrNull(ui.selectedIndex)?.id) { actions.onSelect(page.id) } }
                    AddPageButton(actions.onAddPage)
                }
            }
            FlowButton(stringResource(R.string.scan_done), filled = true, enabled = ui.canSave, onClick = actions.onDone)
        }
    }
}

/** A page in the strip; in OLED the page is dimmed and framed in the surface colour so it does not outweigh Done (3g). */
@Composable
private fun Thumbnail(page: ReviewPage, number: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalScanPalette.current
    val dim = LocalIsDarkTheme.current
    val frame = when {
        selected -> c.ring
        dim -> c.sf2
        else -> null
    }
    val label = stringResource(R.string.scan_page_label, number)
    val state = stringResource(if (selected) R.string.scan_state_selected else R.string.scan_state_not_selected)
    Box(
        Modifier
            .size(56.dp, 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.sf2)
            .then(if (frame != null) Modifier.border(BorderStroke(3.dp, frame), RoundedCornerShape(8.dp)) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                this.selected = selected
                stateDescription = state
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        AsyncImage(
            model = page.image, contentDescription = null, contentScale = ContentScale.Crop,
            colorFilter = if (dim) ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(DIM, DIM, DIM, 1f) }) else null,
            modifier = Modifier.fillMaxSize().padding(3.dp)
        )
        Text(
            "$number", style = scanText(13, FontWeight.ExtraBold), color = c.tx,
            modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(c.sf).padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun AddPageButton(onClick: () -> Unit) {
    val c = LocalScanPalette.current
    val label = stringResource(R.string.scan_add_page)
    Box(
        Modifier
            .size(56.dp, 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .dashedBorder(c.bd)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Add, null, tint = c.mu)
    }
}

@Composable
private fun OutlinedIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalScanPalette.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.dp, c.bd, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = c.tx)
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val stroke = 2.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    )
}

private const val SPLIT_MIN = 0.3f
private const val SPLIT_MAX = 0.7f
private const val DIM = 0.82f
