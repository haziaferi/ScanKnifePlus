package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.ui.folder.FolderBar
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.inlineTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many outputs Home's Recent list shows. */
private const val RECENT_COUNT = 3

/** What Import photos lets the user pick. */
private const val IMAGE_TYPES = "image/*"

/**
 * Home: header (wordmark, All tools, Settings), the folder bar (2b, [folder], opening Folder setup with [onFolder]; Move to folder while
 * [appFiles] wait in the app), the scan card and the Recent list (1a, 3c, 3d), which follows History: the newest files of the folder
 * ([recentFolder]) when one is listed, else this session's outputs, with the files [saved] in the app or elsewhere. The card's upper part
 * starts a scan ([onScan], as the bottom bar's Scan does; rule 3), and below it Import photos (the system picker, then the scan flow with the
 * picked images) and Open PDF (the system picker, then the reader); the accent stays on Scan, the active tab and the Scan tag.
 */
@Composable
fun HomeView(
    onAllTools: () -> Unit,
    onSettings: () -> Unit,
    onScan: () -> Unit,
    onImportPhotos: (List<Uri>) -> Unit,
    onSeeAll: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit,
    folder: FolderStatus = FolderStatus.NotChosen,
    onFolder: () -> Unit = {},
    recentFolder: List<FolderItem>? = null,
    saved: List<SavedEntry> = emptyList(),
    appFiles: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) onImportPhotos(uris)
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                val name = withContext(Dispatchers.IO) { getUriDetails(context, it).name }
                onOpenPreview(it, name, getPageCount(context, it, null))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        HomeHeader(onAllTools = onAllTools, onSettings = onSettings)
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            FolderBar(folder, onClick = onFolder, appFiles = appFiles)
            ScanCard(onScan = onScan, onImportPhotos = { pickPhotos.launch(IMAGE_TYPES) }, onOpenPdf = { pickPdf.launch("application/pdf") })
            if (recentFolder != null) {
                FolderRecentList(recentFolder, onSeeAll = onSeeAll, onOpenPreview = onOpenPreview, saved = saved)
            } else {
                RecentList(SessionManager.history, onSeeAll = onSeeAll, onOpenPreview = onOpenPreview, saved = saved)
            }
        }
    }
}

/** The wordmark with the All tools and Settings buttons (3c). */
@Composable
private fun HomeHeader(onAllTools: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WordmarkRow(fontSize = 22.sp, markHeight = 36.dp, modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() })
        HeaderButton(Icons.Outlined.GridView, stringResource(R.string.home_all_tools), onAllTools)
        HeaderButton(Icons.Outlined.Settings, stringResource(R.string.home_settings), onSettings)
    }
}

/** An outlined round icon button (T2). */
@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(TargetStep.ACT.hit).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * The scan card: its upper part (the scan tile, "Scan a document" and its subtitle) is one T1 target that starts a scan ([onScan]), read
 * as one button; below it, outside that target, the Import photos and Open PDF buttons (T2).
 */
@Composable
private fun ScanCard(onScan: () -> Unit, onImportPhotos: () -> Unit, onOpenPdf: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalScanPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Column {
            // Edge to edge in the card, which clips the press to its corners; clickable merges the texts into one node for TalkBack.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onScan)
                    .heightIn(min = TargetStep.COMMIT.drawn)
                    .padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier.size(52.dp).background(palette.scanBg, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = palette.scanFg, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.home_scan_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onSurface,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(stringResource(R.string.home_scan_subtitle), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Row(
                Modifier.height(IntrinsicSize.Min).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CardButton(stringResource(R.string.home_import_photos), onImportPhotos, Modifier.weight(1f).fillMaxHeight())
                CardButton(stringResource(R.string.home_open_pdf), onOpenPdf, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** An outlined card button (T2); its label wraps at large text sizes instead of clipping. */
@Composable
private fun CardButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = TargetStep.ACT.drawn),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

/** The newest of this session's outputs ([recent]) and the [saved] files, as History lists them without a folder. */
@Composable
internal fun RecentList(
    recent: List<ActivityEntry>,
    onSeeAll: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit,
    saved: List<SavedEntry> = emptyList()
) {
    RecentHeader(stringResource(R.string.home_recent), onSeeAll)
    RecentRows(historyRows(null, withFolder = false, saved, recent), onSeeAll, onOpenPreview)
}

/** The folder's newest files ("Recent in your folder") and the [saved] files apart from it, as History lists them with the folder. */
@Composable
internal fun FolderRecentList(
    recent: List<FolderItem>,
    onSeeAll: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit,
    saved: List<SavedEntry> = emptyList()
) {
    RecentHeader(stringResource(R.string.home_recent_folder), onSeeAll)
    RecentRows(historyRows(recent, withFolder = true, saved, emptyList()), onSeeAll, onOpenPreview)
}

/** The first [RECENT_COUNT] of [rows]; a PDF opens in the reader, any other file opens History, where it can be shared. */
@Composable
private fun RecentRows(rows: List<HistoryRow>, onSeeAll: () -> Unit, onOpenPreview: (Uri, String, Int) -> Unit) {
    val context = LocalContext.current
    if (rows.isEmpty()) {
        RecentEmpty()
        return
    }
    EntryList(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        rows.take(RECENT_COUNT).forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            when (row) {
                is HistoryRow.Session -> EntryRow(
                    entry = row.entry,
                    onClick = row.entry.uri?.let { uri ->
                        {
                            if (canOpenInReader(historyEntryMime(context.contentResolver, uri))) onOpenPreview(uri, row.entry.name, row.entry.pageCount)
                            else onSeeAll()
                        }
                    }
                )
                is HistoryRow.Folder -> {
                    val uri = row.item.uri
                    FolderRow(row.item, onClick = if (uri != null && canOpenInReader(row.item.mime)) ({ onOpenPreview(uri, row.item.name, 0) }) else onSeeAll)
                }
                is HistoryRow.Saved -> {
                    val uri = remember(row.entry) { SavedFiles.of(context).uriOf(row.entry) }
                    val pdf = canOpenInReader(savedMime(row.entry))
                    SavedRow(row.entry, onClick = if (uri != null && pdf) ({ onOpenPreview(uri, row.entry.name, row.entry.pages) }) else onSeeAll)
                }
            }
        }
    }
}

/** The Recent heading with See all (T3), which opens History. */
@Composable
private fun RecentHeader(title: String, onSeeAll: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        Box(
            modifier = Modifier
                .inlineTarget()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onSeeAll)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.home_see_all),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onBackground,
                textDecoration = TextDecoration.Underline
            )
        }
    }
}

@Composable
private fun RecentEmpty() {
    Text(
        stringResource(R.string.home_recent_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/** The surface card that holds the Recent rows. */
@Composable
private fun EntryList(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column { content() }
    }
}
