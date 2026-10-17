package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette

/** A file a tool saved, as its result screen shows it: [uri], the [name] the picker gave it, and its [mime] type. */
data class ToolResult(val uri: Uri, val name: String, val mime: String = PDF_MIME)

internal const val PDF_MIME = "application/pdf"
internal const val ZIP_MIME = "application/zip"

/**
 * The tools' shared result screen (3b), under [ToolScreenBar]: a check and [message], then a card with the saved [result] (its first page,
 * name and [subMessage]) whose rank-1 action is Open ([onOpen], the reader) with Share beside it, or Share alone when the result does not
 * open in the reader; below, Process more files and Back to tools ([onDone]). It scrolls, so nothing clips at large text sizes.
 */
@Composable
fun SuccessView(
    message: String,
    subMessage: String,
    result: ToolResult?,
    onDone: () -> Unit,
    onProcessMore: () -> Unit,
    onOpen: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalScanPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(40.dp).background(palette.toolBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = palette.toolFg, modifier = Modifier.size(24.dp))
            }
            Text(
                message,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 26.sp,
                color = colors.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
        }

        ToolCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val thumb = Modifier.size(width = 56.dp, height = 76.dp)
                    if (result != null && result.mime == PDF_MIME) {
                        PdfThumbnail(result.uri, null, thumb)
                    } else {
                        Box(thumb.background(colors.surfaceVariant, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (result != null) Text(result.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        if (subMessage.isNotBlank()) Text(subMessage, fontSize = 13.sp, color = palette.meta)
                    }
                }
                if (result != null) {
                    val share = { shareFile(context, result.uri, result.name, result.mime) }
                    val shareLabel = stringResource(R.string.action_share)
                    if (onOpen != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolPrimaryButton(
                                stringResource(R.string.history_open),
                                onClick = onOpen,
                                modifier = Modifier.weight(1f),
                                icon = Icons.AutoMirrored.Outlined.OpenInNew
                            )
                            ToolSecondaryButton(shareLabel, onClick = share, modifier = Modifier.weight(1f).heightIn(min = 56.dp), icon = Icons.Outlined.Share)
                        }
                    } else {
                        ToolPrimaryButton(shareLabel, onClick = share, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Share)
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolSecondaryButton(stringResource(R.string.tool_process_more), onClick = onProcessMore, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tool_back_to_tools), fontWeight = FontWeight.SemiBold, color = colors.onBackground, fontSize = 15.sp)
            }
        }
    }
}
