package com.paperknifeplus.app.ui.components

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.annotation.WorkerThread
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file a tool saved, as its result screen shows it: [uri], the [name] the picker gave it, its [mime] type, and the [password] it opens with. */
data class ToolResult(val uri: Uri, val name: String, val mime: String = PDF_MIME, val password: String? = null)

internal const val PDF_MIME = "application/pdf"
internal const val ZIP_MIME = "application/zip"

/**
 * The tools' shared result screen (3b), under [ToolScreenBar]: a check and [message], then a card with the saved [result] (its first page,
 * name, size and folder (rule 14), [subMessage] and the [PasswordLine] when it [keepsPassword]) whose rank-1 action is Open ([onOpen], the
 * reader) with Share beside it, or Share alone when the result does not open in the reader; then the [report] of what was removed, when there
 * is one (R8); below, Process more files and Back to tools ([onDone]). It scrolls, so nothing clips at large text sizes.
 */
@Composable
fun SuccessView(
    message: String,
    subMessage: String,
    result: ToolResult?,
    onDone: () -> Unit,
    onProcessMore: () -> Unit,
    onOpen: (() -> Unit)? = null,
    keepsPassword: Boolean = false,
    report: List<Pair<String, Int>> = emptyList()
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
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
        }

        ToolCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val thumb = Modifier.size(width = 56.dp, height = 76.dp)
                    if (result != null && result.mime == PDF_MIME) {
                        PdfThumbnail(result.uri, result.password, thumb)
                    } else {
                        Box(thumb.background(colors.surfaceVariant, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (result != null) {
                            Text(result.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                            ResultPlaceLine(result.uri)
                        }
                        if (subMessage.isNotBlank()) Text(subMessage, style = MaterialTheme.typography.bodySmall, color = palette.meta)
                        if (keepsPassword) PasswordLine()
                    }
                }
                if (result != null) {
                    val share = { shareFile(context, result.uri, result.name, result.mime) }
                    val shareLabel = stringResource(R.string.action_share)
                    if (onOpen != null) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolPrimaryButton(
                                stringResource(R.string.history_open),
                                onClick = onOpen,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                icon = Icons.AutoMirrored.Outlined.OpenInNew
                            )
                            ToolSecondaryButton(shareLabel, onClick = share, modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.Outlined.Share)
                        }
                    } else {
                        ToolPrimaryButton(shareLabel, onClick = share, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Share)
                    }
                }
            }
        }
        if (report.isNotEmpty()) ReportRows(report, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))

        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolSecondaryButton(stringResource(R.string.tool_process_more), onClick = onProcessMore, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = TargetStep.ACT.drawn)) {
                Text(stringResource(R.string.tool_back_to_tools), color = colors.onBackground, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** What Android reports of a saved file: its [size] in bytes and the name of the [folder] it is in, each null when it is not reported. */
internal data class ResultPlace(val size: Long?, val folder: String?)

private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
private const val DOWNLOADS = "com.android.providers.downloads.documents"

/**
 * The name of the folder that holds the document at [uri], where its provider names documents by path: the phone's storage
 * ("primary:Download/a.pdf", which also covers the app's folder there; "home:a.pdf" is in Documents) and a Downloads path
 * ("raw:/storage/emulated/0/Download/a.pdf"). Null for any other id ("msf:123", a cloud provider's), and for a file at the top of a
 * storage, so no id or full path ever shows.
 */
internal fun resultFolder(uri: Uri): String? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val id = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        return null // not a document URI
    }
    val parent = when (uri.authority) {
        // "home:" is the Documents folder's own root, so a file at its top is in Documents.
        EXTERNAL_STORAGE -> id.substringAfter(':', "").substringBeforeLast('/', "").ifEmpty { if (id.startsWith("home:")) "Documents" else "" }
        // A raw path's parent is a folder only below a storage's top ("/storage/emulated/0", "/storage/1A2B-3C4D", "/sdcard").
        DOWNLOADS -> id.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")?.substringBeforeLast('/', "")?.takeUnless { STORAGE_TOP.matches(it) }
        else -> null
    } ?: return null
    return parent.substringAfterLast('/').ifBlank { null }
}

/** The top of a storage, as a raw Downloads path names it. */
private val STORAGE_TOP = Regex("""/storage/emulated/\d+|/storage/[^/]+|/sdcard|/mnt/[^/]+""")

/** [resultFolder] and the size its provider reports for [uri]; a size of 0 counts as unknown, as some providers report it for any file. */
@WorkerThread
internal fun resultPlace(resolver: ContentResolver, uri: Uri): ResultPlace {
    val size = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null // the provider refused or failed: the card shows the folder alone, or nothing
    }
    return ResultPlace(size?.takeIf { it > 0 }, resultFolder(uri))
}

/**
 * The line under the result's name (rule 14): "1.2 MB · Download" (the size in the locale's format), the size or the folder alone when only
 * one is known, nothing for neither. Read off the main thread, it shows once known and takes no room until then.
 */
@Composable
private fun ResultPlaceLine(uri: Uri) {
    val context = LocalContext.current
    val place by produceState<ResultPlace?>(null, uri) { value = withContext(Dispatchers.IO) { resultPlace(context.contentResolver, uri) } }
    val size = place?.size?.let { Formatter.formatShortFileSize(context, it) }
    val folder = place?.folder
    val line = if (size != null && folder != null) stringResource(R.string.tool_result_size_folder, size, folder) else size ?: folder
    if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = LocalScanPalette.current.meta)
}

/** The report rows (R8): a card headed What was removed, one row per label and count of [rows], each row read as one. */
@Composable
private fun ReportRows(rows: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    ToolCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ToolSectionLabel(stringResource(R.string.tool_report_removed), Modifier.padding(top = 8.dp, bottom = 4.dp))
            rows.forEachIndexed { index, (label, count) ->
                if (index > 0) HorizontalDivider(color = colors.outline)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                    Text("$count", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                }
            }
        }
    }
}
