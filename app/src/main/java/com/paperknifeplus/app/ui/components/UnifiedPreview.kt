package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.ui.theme.DenseTargets
import com.paperknifeplus.app.ui.theme.TargetStep

enum class PreviewMode {
    GRID,   // Scrollable grid (Split, etc.)
    COVER,  // Single high-res page (Compress, Protect, etc.)
    REORDER, // Draggable grid (Rearrange)
    ROTATE  // Tap to rotate (Rotate)
}

/**
 * The pages of [uri] for a tool screen: as a grid of tiles in [mode] GRID, ROTATE and REORDER, under the screen's [header] (its file card
 * and choices), which scrolls with the tiles; or as the first page large ([PreviewMode.COVER]).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UnifiedPdfPreview(
    uri: Uri,
    pageCount: Int,
    mode: PreviewMode = PreviewMode.GRID,
    password: String? = null,
    isGrayscale: Boolean = false,
    showIndexNumbers: Boolean = true,
    showSelectionIcon: Boolean = true,
    showZoomIcon: Boolean = true,
    disableLightbox: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    selectedPages: Set<Int>? = null,
    onToggleSelection: ((Int) -> Unit)? = null,
    pageOrder: List<Int>? = null,
    onOrderChange: ((List<Int>) -> Unit)? = null,
    pageRotations: Map<Int, Int>? = null,
    onRotatePage: ((Int) -> Unit)? = null,
    itemOverlay: @Composable (BoxScope.(Int) -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit = {}
) {
    var lightboxPage by remember { mutableStateOf<Int?>(null) }
    val imageLoader = PdfImageLoader.get(LocalContext.current)

    if (mode == PreviewMode.COVER) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.707f)
                    .clickable(enabled = !disableLightbox) { lightboxPage = 0 },
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val request = remember(uri, password) { PdfPageRequest(uri, 0, password, 1.2f) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val painter = rememberAsyncImagePainter(request, imageLoader)
                    Image(
                        painter = painter,
                        contentDescription = stringResource(R.string.tool_document_cover),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = if (isGrayscale) GrayscaleColorFilter else null
                    )
                    if (painter.state is AsyncImagePainter.State.Loading) {
                        CircularProgressIndicator(color = accentColor, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    }
                    if (!disableLightbox && showZoomIcon) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                            color = pageBadge,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Filled.ZoomIn, null, tint = pageBadgeText, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                    }
                }
            }
        }
    } else if (mode == PreviewMode.REORDER) {
        PageTileGrid(pageCount, key = { pageOrder?.get(it) ?: it }, top = 12.dp, header = header) { index, place ->
            val isSelected = selectedPages?.contains(index) == true
            val pageIdx = pageOrder?.get(index) ?: index

            PdfPageItem(
                uri = uri,
                index = pageIdx,
                password = password,
                isGrayscale = isGrayscale,
                showIndexNumbers = showIndexNumbers,
                imageLoader = imageLoader,
                accentColor = accentColor,
                onClick = { onToggleSelection?.invoke(index) },
                scale = 0.6f,
                modifier = place.then(if (isSelected) Modifier.border(BorderStroke(3.dp, accentColor), RoundedCornerShape(12.dp)) else Modifier)
            ) {
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(accentColor.copy(alpha = 0.15f)))
                    if (showSelectionIcon) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp),
                            color = accentColor,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
                itemOverlay?.invoke(this, index)
            }
        }
    } else {
        PageTileGrid(pageCount, key = { it }, top = 8.dp, header = header) { index, place ->
            val isSelected = selectedPages?.contains(index) == true
            val rotation = pageRotations?.get(index) ?: 0
            PdfPageItem(
                uri = uri, index = index, password = password, isGrayscale = isGrayscale,
                showIndexNumbers = showIndexNumbers, imageLoader = imageLoader, accentColor = accentColor, rotation = rotation,
                onClick = {
                    if (mode == PreviewMode.ROTATE && onRotatePage != null) onRotatePage(index)
                    else if (onToggleSelection != null) onToggleSelection(index)
                    else if (!disableLightbox) lightboxPage = index
                },
                scale = 0.6f,
                modifier = place.then(if (isSelected) Modifier.border(BorderStroke(3.dp, accentColor), RoundedCornerShape(12.dp)) else Modifier)
            ) {
                if (mode == PreviewMode.ROTATE) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp),
                        color = pageBadge, shape = CircleShape
                    ) { Icon(Icons.AutoMirrored.Filled.RotateRight, null, tint = pageBadgeText, modifier = Modifier.padding(6.dp)) }
                } else if (onToggleSelection != null) {
                    Box(modifier = Modifier.fillMaxSize().background(if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent))
                    if (showSelectionIcon) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(TargetStep.DENSE.drawn),
                            color = if (isSelected) accentColor else pageBadge, shape = CircleShape
                        ) { Icon(Icons.Filled.Check, null, tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else pageBadgeText, modifier = Modifier.padding(4.dp)) }
                    }
                    if (!disableLightbox && showZoomIcon) {
                        DenseTargets { dense ->
                            Surface(
                                onClick = { lightboxPage = index },
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(TargetStep.DENSE.drawn).then(dense),
                                color = pageBadge, shape = CircleShape
                            ) { Icon(Icons.Filled.Fullscreen, null, tint = pageBadgeText, modifier = Modifier.padding(4.dp)) }
                        }
                    }
                }
                itemOverlay?.invoke(this, index)
            }
        }
    }

    if (lightboxPage != null) {
        PageLightbox(
            uri = uri, initialPage = lightboxPage!!, totalCount = pageCount, password = password,
            onDismiss = { lightboxPage = null }, selectedPages = selectedPages, onToggleSelection = onToggleSelection,
            isGrayscale = isGrayscale,
            itemOverlay = itemOverlay
        )
    }
}

/** Margin of the page tiles at the grid's sides, and the gap between rows and columns. */
private val TILE_SIDE = 20.dp
private val TILE_GAP = 16.dp

/**
 * The page tiles, two a row, as one lazy grid under the screen's [header] (full width, padded by the screen): the file card and choices
 * scroll with the pages, so at large text the grid is never squeezed out of reach and long files stay lazy (rule 10). [count] tiles keyed by
 * [key]; each [tile] gets its place in the grid as a modifier, which keeps the tiles [TILE_SIDE] from the sides however the header is
 * padded. [top] is the room above the first row.
 */
@Composable
private fun PageTileGrid(
    count: Int,
    key: (Int) -> Any,
    top: Dp,
    header: @Composable ColumnScope.() -> Unit,
    tile: @Composable (index: Int, place: Modifier) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        contentPadding = PaddingValues(bottom = TILE_GAP)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { Column(content = header) }
        items(count, key = key) { index ->
            val start = index % 2 == 0
            tile(index, Modifier.padding(start = if (start) TILE_SIDE else 0.dp, end = if (start) 0.dp else TILE_SIDE, top = if (index < 2) top else TILE_GAP))
        }
    }
}

@Composable
fun PdfPageItem(
    uri: Uri,
    index: Int,
    password: String?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    scale: Float = 0.6f,
    rotation: Int = 0,
    isGrayscale: Boolean = false,
    showIndexNumbers: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val request = remember(uri, index, password, scale, rotation) { PdfPageRequest(uri, index, password, scale, rotation) }
    Box(
        modifier = modifier.aspectRatio(if (rotation % 180 != 0) 1.414f else 0.707f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val painter = rememberAsyncImagePainter(model = request, imageLoader = imageLoader)
        Image(
            painter = painter, contentDescription = stringResource(R.string.tool_page_n, index + 1),
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            colorFilter = if (isGrayscale) GrayscaleColorFilter else null
        )
        if (painter.state is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(color = accentColor, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (painter.state is AsyncImagePainter.State.Error && password != null) {
             Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        
        if (showIndexNumbers) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                color = pageBadge, shape = RoundedCornerShape(8.dp)
            ) {
                Text("${index + 1}", color = pageBadgeText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        content()
    }
}
