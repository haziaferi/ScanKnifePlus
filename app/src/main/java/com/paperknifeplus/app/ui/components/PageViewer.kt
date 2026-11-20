package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest

/**
 * The shared page viewer (R3): "Page N of M" with 48 dp arrows ([onPageChange]) over [page] of [uri], opened with [password]. The page
 * keeps its displayed shape [aspect] (width over height), at most [pageHeight] high and as wide as the viewer, inside [pagePadding];
 * [overlay] draws over the page's own area, so fractions of its size are fractions of the page.
 */
@Composable
fun PageViewer(
    uri: Uri,
    password: String?,
    page: Int,
    pageCount: Int,
    aspect: Float,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageHeight: Dp = 226.dp,
    pagePadding: PaddingValues = PaddingValues(vertical = 8.dp),
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.scan_page_of, page + 1, pageCount),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
            )
            val previous = stringResource(R.string.lightbox_previous)
            val next = stringResource(R.string.lightbox_next)
            PageArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, previous, page > 0) { onPageChange(page - 1) }
            PageArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, next, page < pageCount - 1) { onPageChange(page + 1) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(pagePadding), contentAlignment = Alignment.Center) {
            val width = min(maxWidth, pageHeight * aspect)
            Box(Modifier.size(width, width / aspect)) {
                PdfPageImage(uri, password, page, width, priority = 1, modifier = Modifier.fillMaxSize())
                overlay()
            }
        }
    }
}

/** One of the viewer's arrows: an outlined 48 dp button named [label]; faded and inert when not [enabled]. */
@Composable
private fun PageArrow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.outline.copy(alpha = 0.4f))
    ) {
        Icon(icon, label, tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
    }
}

/**
 * Page [index] of [uri], opened with [password], rendered off the main thread at the [width] it is shown, never larger, on the surface
 * colour until it arrives. [priority] 1 is the page in view, 0 a miniature that can wait.
 */
@Composable
internal fun PdfPageImage(uri: Uri, password: String?, index: Int, width: Dp, priority: Int, modifier: Modifier = Modifier) {
    val widthPx = with(LocalDensity.current) { width.roundToPx() }.coerceAtLeast(1)
    // A large scale, capped by the shown width: the page renders at the size it is drawn.
    val request = remember(uri, password, index, widthPx, priority) {
        PdfPageRequest(uri, index, password, 4f, priority = priority, maxWidthPx = widthPx)
    }
    Image(
        painter = rememberAsyncImagePainter(request, PdfImageLoader.get(LocalContext.current)),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.background(MaterialTheme.colorScheme.surface)
    )
}
