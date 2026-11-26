package com.paperknifeplus.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.haziaferi.scanknifeplus.ui.folder.FolderBar
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.FolderKind
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.haziaferi.scanknifeplus.ui.folder.HistoryFolder
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.ScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.inlineTarget
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether an entry whose output has type [mime] opens in the PDF reader (ZIP exports and file-less entries do not). */
internal fun canOpenInReader(mime: String?): Boolean = mime == "application/pdf"

/** The share intent for [entry]'s output of type [mime]; null when the entry has no file (e.g. PDF to Text). */
internal fun historyShareIntent(entry: ActivityEntry, mime: String): Intent? = entry.uri?.let { ScanExport.shareIntent(it, entry.name, mime) }

/** History's filter chips (2a), each with the message shown when it matches nothing; [CONFLICTS] shows only for the folder. */
enum class HistoryFilter(@StringRes val label: Int, @StringRes val empty: Int) {
    ALL(R.string.history_filter_all, R.string.history_empty_all),
    SCANS(R.string.history_filter_scans, R.string.history_empty_scans),
    TOOLS(R.string.history_filter_tools, R.string.history_empty_tools),
    CONFLICTS(R.string.history_filter_conflicts, R.string.history_empty_conflicts);

    fun matches(entry: ActivityEntry): Boolean = when (this) {
        ALL -> true
        SCANS -> entry.isScan
        TOOLS -> !entry.isScan
        CONFLICTS -> false
    }

    fun matches(item: FolderItem): Boolean = when (this) {
        ALL -> true
        SCANS -> item.kind == FolderKind.SCAN
        TOOLS -> item.kind == FolderKind.TOOL
        CONFLICTS -> item.kind == FolderKind.CONFLICT
    }

    fun matches(entry: SavedEntry): Boolean = when (this) {
        ALL -> true
        SCANS -> entry.tool == SCAN_ENTRY_TOOL
        TOOLS -> entry.tool != SCAN_ENTRY_TOOL
        CONFLICTS -> false
    }

    fun matches(row: HistoryRow): Boolean = when (row) {
        is HistoryRow.Folder -> matches(row.item)
        is HistoryRow.Saved -> matches(row.entry)
        is HistoryRow.Session -> matches(row.entry)
    }
}

/** True if [name] holds the search [query]; a blank query matches everything. */
internal fun matchesQuery(name: String, query: String): Boolean = query.isBlank() || name.contains(query.trim(), ignoreCase = true)

/** One row of History or Home's Recent: a file of the folder's listing, a file the app saved, or a row of this session (a text read). */
sealed interface HistoryRow {
    val key: String
    val name: String
    val time: Long

    data class Folder(val item: FolderItem) : HistoryRow {
        override val key get() = "folder:${item.key}"
        override val name get() = item.name
        override val time get() = item.modified
    }

    data class Saved(val entry: SavedEntry) : HistoryRow {
        override val key get() = "saved:${entry.id}"
        override val name get() = entry.name
        override val time get() = entry.time
    }

    data class Session(val entry: ActivityEntry) : HistoryRow {
        override val key get() = "session:${entry.id}"
        override val name get() = entry.name
        override val time get() = entry.time
    }
}

/**
 * The rows History shows, newest first: with a folder, its listing ([folderItems]) and the [saved] files kept in the app or saved elsewhere
 * (those in the folder are its listing's); without one, those saved files and this session's rows ([session]).
 */
internal fun historyRows(folderItems: List<FolderItem>?, withFolder: Boolean, saved: List<SavedEntry>, session: List<ActivityEntry>): List<HistoryRow> {
    val files = saved.filter { it.place != SavedPlace.FOLDER }.map { HistoryRow.Saved(it) }
    val rest = if (withFolder) folderItems.orEmpty().map { HistoryRow.Folder(it) } else session.map { HistoryRow.Session(it) }
    return (files + rest).sortedByDescending { it.time }
}

/** The URI that opens and shares a saved file: its file in the app's own folder, else where it was saved. */
internal fun SavedFiles.uriOf(entry: SavedEntry): Uri? = fileOf(entry)?.let(::uriOf) ?: entry.uri

/**
 * History: the user's [folder] when one is chosen, and the files [saved] in the app or elsewhere (else this session's rows with them); newest
 * first, narrowed by the search over file names and the filter chips, in a list that scrolls inside the screen. A folder that cannot be read
 * shows a warning with [onChooseFolder] over the files saved apart from it, never an empty list. [onRefresh] runs each time History shows,
 * so the list follows changes a sync made. [onMoveToFolder], while files wait in the app for a folder, shows the folder bar's line over the
 * list; a file kept in the app has Delete, which asks first, then hands it to [onDelete].
 */
@Composable
fun HistoryView(
    onItemClick: (Uri, String, Int) -> Unit,
    folder: HistoryFolder? = null,
    onChooseFolder: () -> Unit = {},
    onRefresh: () -> Unit = {},
    saved: List<SavedEntry> = emptyList(),
    onMoveToFolder: (() -> Unit)? = null,
    onDelete: (SavedEntry) -> Unit = {}
) {
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(Unit) { refresh() }
    var chosen by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SavedEntry?>(null) }
    val filters = if (folder != null) HistoryFilter.entries else HistoryFilter.entries - HistoryFilter.CONFLICTS
    val filter = chosen.takeIf { it in filters } ?: HistoryFilter.ALL
    val yourFolder = stringResource(R.string.folder_your_folder)
    val rows = historyRows(folder?.items, folder != null, saved, SessionManager.history)

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // History is a tab of the bottom bar, so it has no back arrow of its own.
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() }
            )
            val subtitle = if (folder != null) {
                stringResource(R.string.history_folder_subtitle, folder.name ?: yourFolder)
            } else {
                stringResource(R.string.history_subtitle)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val unreadable = folder?.unreadable == true
        val folderName = folder?.name ?: yourFolder
        if (unreadable && rows.isEmpty()) {
            UnreadableCard(folderName, onChooseFolder, Modifier.verticalScroll(rememberScrollState()))
            return@Column
        }
        // One list, so the search and the chips scroll away with the rows and leave them room at large text sizes.
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (onMoveToFolder != null) {
                item(key = "move") { FolderBar(FolderStatus.NotChosen, onClick = onMoveToFolder, modifier = Modifier.padding(top = 12.dp), appFiles = true) }
            }
            if (unreadable) item(key = "unreadable") { UnreadableCard(folderName, onChooseFolder) }
            item(key = "search") { SearchField(query, onChange = { query = it }) }
            item(key = "filters") { FilterChips(options = filters, selected = filter, onSelect = { chosen = it }) }
            // A folder still being read says so, never an empty list; the files saved apart from it show meanwhile.
            if (folder != null && !unreadable && folder.items == null) item(key = "reading") { EmptyMessage(stringResource(R.string.history_reading)) }
            historyItems(rows, filter, query, onItemClick, emptyMessage = !(folder != null && folder.items == null && !unreadable)) { deleting = it }
        }
    }
    deleting?.let { entry ->
        DeleteSavedDialog(entry.name, onDelete = {
            deleting = null
            onDelete(entry)
        }, onDismiss = { deleting = null })
    }
}

/** "Delete this file?" for a file kept only in the app, named [name]: Cancel, or Delete, filled in the danger colour (R5). */
@Composable
internal fun DeleteSavedDialog(name: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.history_delete_title)) },
        text = { Text(stringResource(R.string.history_delete_body, name)) },
        confirmButton = {
            DialogButtons {
                DialogButton(stringResource(R.string.action_cancel), onClick = onDismiss)
                DialogButton(stringResource(R.string.history_delete), kind = DialogButtonKind.DANGER_COMMIT, onClick = onDelete)
            }
        }
    )
}

/** The search over file names (user answer 7); it narrows whichever list shows. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        placeholder = { Text(stringResource(R.string.history_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.history_search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(14.dp)
    )
}

/** What an empty list says: the filter's own message, or that the search matched nothing. */
private fun LazyListScope.emptyRow(filter: HistoryFilter, query: String) = item(key = "empty") {
    EmptyMessage(if (query.isBlank()) stringResource(filter.empty) else stringResource(R.string.history_search_empty, query.trim()))
}

/**
 * The [rows] that the [filter] and the search [query] keep, as slices of one card; when none is kept, the filter's or the search's message,
 * unless [emptyMessage] is false (the folder is still being read). A file kept in the app has Delete, which [onDelete] asks about.
 */
private fun LazyListScope.historyItems(
    rows: List<HistoryRow>,
    filter: HistoryFilter,
    query: String,
    onItemClick: (Uri, String, Int) -> Unit,
    emptyMessage: Boolean,
    onDelete: (SavedEntry) -> Unit
) {
    val shown = rows.filter { filter.matches(it) && matchesQuery(it.name, query) }
    if (shown.isEmpty()) {
        if (emptyMessage) emptyRow(filter, query)
        return
    }
    itemsIndexed(shown, key = { _, row -> row.key }) { index, row ->
        CardSlice(first = index == 0, last = index == shown.lastIndex) {
            when (row) {
                is HistoryRow.Folder -> FolderHistoryRow(row.item, onItemClick)
                is HistoryRow.Saved -> SavedHistoryRow(row.entry, onItemClick, onDelete)
                is HistoryRow.Session -> SessionHistoryRow(row.entry, onItemClick)
            }
        }
    }
}

/** A row of this session, opened or shared once its type is known. */
@Composable
private fun SessionHistoryRow(item: ActivityEntry, onItemClick: (Uri, String, Int) -> Unit) {
    val context = LocalContext.current
    // Keyed by id, so each row resolves its type once, off the main thread, and keeps it when newer entries shift the list.
    var mime by remember(item.uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.uri) {
        val uri = item.uri
        if (uri != null) mime = withContext(Dispatchers.IO) { historyEntryMime(context.contentResolver, uri) }
    }
    EntryRow(
        entry = item,
        onClick = if (canOpenInReader(mime)) {
            { item.uri?.let { uri -> onItemClick(uri, item.name, item.pageCount) } }
        } else null,
        onShare = item.uri?.let { uri -> { shareFile(context, uri, item.name, mime ?: historyEntryMime(context.contentResolver, uri)) } }
    )
}

/** A file of the folder, with Open and Open original under a conflict copy. */
@Composable
private fun FolderHistoryRow(item: FolderItem, onItemClick: (Uri, String, Int) -> Unit) {
    val context = LocalContext.current
    val open: (FolderItem) -> (() -> Unit)? = { file ->
        file.uri?.takeIf { canOpenInReader(file.mime) }?.let { uri -> { onItemClick(uri, file.name, 0) } }
    }
    Column {
        FolderRow(item, onClick = open(item), onShare = item.uri?.let { uri -> { shareFile(context, uri, item.name, item.mime) } })
        if (item.kind == FolderKind.CONFLICT) ConflictActions(item, onOpen = open(item), onOpenOriginal = item.original?.let(open))
    }
}

/** A file the app saved: a PDF opens in the reader, every file shares, and one kept in the app has Delete (T3) under it. */
@Composable
private fun SavedHistoryRow(entry: SavedEntry, onItemClick: (Uri, String, Int) -> Unit, onDelete: (SavedEntry) -> Unit) {
    val context = LocalContext.current
    val uri = remember(entry) { SavedFiles.of(context).uriOf(entry) }
    val mime = savedMime(entry)
    Column {
        SavedRow(
            entry,
            onClick = uri?.takeIf { canOpenInReader(mime) }?.let { { onItemClick(it, entry.name, entry.pages) } },
            onShare = uri?.let { { shareFile(context, it, entry.name, mime) } }
        )
        if (entry.place == SavedPlace.APP) DeleteSavedButton(entry.name) { onDelete(entry) }
    }
}

/** A saved file's type, from its name's extension, as the reader and the share sheet need it. */
internal fun savedMime(entry: SavedEntry): String = URLConnection.guessContentTypeFromName(entry.name) ?: "application/octet-stream"

/** Delete (T3) under a file kept in the app, read as "Delete [name]"; level with the row's name. */
@Composable
private fun DeleteSavedButton(name: String, onClick: () -> Unit) {
    val label = stringResource(R.string.action_delete_named, name)
    Box(
        modifier = Modifier
            .padding(start = 52.dp, bottom = 4.dp)
            .inlineTarget()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.history_delete), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** What a conflict copy is and what to do with it, with Open and Open original (prototype History). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictActions(item: FolderItem, onOpen: (() -> Unit)?, onOpenOriginal: (() -> Unit)?) {
    val original = item.originalName ?: return
    Column(
        modifier = Modifier.padding(start = 64.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.history_conflict_note, original),
            style = MaterialTheme.typography.bodySmall,
            color = LocalScanPalette.current.mu
        )
        if (onOpen != null || onOpenOriginal != null) {
            // Buttons on one line grow to one height (R14 rule 1).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onOpen?.let { RowButton(stringResource(R.string.history_open), it, Modifier.fillMaxRowHeight()) }
                onOpenOriginal?.let { RowButton(stringResource(R.string.history_conflict_open_original, original), it, Modifier.fillMaxRowHeight()) }
            }
        }
    }
}

@Composable
private fun RowButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = TargetStep.ACT.drawn),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The prototype's warning for a folder History cannot list, in place of its listing, with "Choose the folder again" (T1); [modifier] makes
 * it scroll when it stands alone.
 */
@Composable
private fun UnreadableCard(name: String, onChooseFolder: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalScanPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(palette.warnBg, RoundedCornerShape(18.dp))
            .padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = palette.warnFg, modifier = Modifier.size(22.dp))
            Text(
                stringResource(R.string.history_unreadable_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.warnFg,
                modifier = Modifier.semantics { heading() }
            )
        }
        Text(stringResource(R.string.history_unreadable_body, name), style = MaterialTheme.typography.bodyMedium, color = palette.warnFg)
        Button(
            onClick = onChooseFolder,
            modifier = Modifier.heightIn(min = TargetStep.COMMIT.drawn),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.warnFg, contentColor = palette.warnBg),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(stringResource(R.string.history_choose_again), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
}

/** Opens the share sheet for [uri]; sharing doubles as "Save to Files", the most reliable way to export on Android. */
internal fun shareFile(context: Context, uri: Uri, name: String, mime: String) {
    try {
        context.startActivity(Intent.createChooser(ScanExport.shareIntent(uri, name, mime), context.getString(R.string.history_share_chooser)))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.history_share_failed), Toast.LENGTH_SHORT).show()
    }
}

/**
 * One lazy-list row drawn as a slice of a card like Home's Recent list: an outline frame around a surface fill, rounded at the card's outer
 * corners; only the [first] row has a top edge, so each bottom edge doubles as the divider.
 */
@Composable
private fun CardSlice(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    fun shape(r: Int) = RoundedCornerShape(
        topStart = if (first) r.dp else 0.dp, topEnd = if (first) r.dp else 0.dp,
        bottomStart = if (last) r.dp else 0.dp, bottomEnd = if (last) r.dp else 0.dp
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.outline, shape(18))
            .padding(start = 1.dp, end = 1.dp, top = if (first) 1.dp else 0.dp, bottom = 1.dp)
            .clip(shape(17))
            .background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

/** One horizontally scrolling row of filter chips, tabs (T2); the selected chip is tonal (3e) and also marked by semantics, not colour alone. */
@Composable
private fun FilterChips(options: List<HistoryFilter>, selected: HistoryFilter, onSelect: (HistoryFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                modifier = Modifier
                    .heightIn(min = TargetStep.ACT.drawn)
                    .widthIn(min = TargetStep.ACT.hit)
                    .then(if (on) Modifier.background(colors.primaryContainer, RoundedCornerShape(24.dp)) else Modifier)
                    .border(1.dp, if (on) colors.onPrimaryContainer else colors.outline, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(option.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.onPrimaryContainer else colors.onBackground,
                    maxLines = 1
                )
            }
        }
    }
}

/** A session entry as an [OutputRow]: its icon, name, size line and its tool's catalog name as the tag. */
@Composable
internal fun EntryRow(entry: ActivityEntry, onClick: (() -> Unit)?, onShare: (() -> Unit)? = null) {
    OutputRow(entry.icon, entry.name, entry.size, stringResource(entry.tag), entry.tagRole.colors(LocalScanPalette.current), onClick, onShare)
}

/** A file of the folder as an [OutputRow]: its kind's icon and tag, and its date and size (or that a scan has no PDF yet). */
@Composable
internal fun FolderRow(item: FolderItem, onClick: (() -> Unit)?, onShare: (() -> Unit)? = null) {
    val context = LocalContext.current
    val palette = LocalScanPalette.current
    val (icon, tag) = when (item.kind) {
        FolderKind.SCAN -> Icons.Filled.DocumentScanner to R.string.history_tag_scan
        FolderKind.TOOL -> Icons.Filled.Description to R.string.history_tag_tool
        FolderKind.CONFLICT -> Icons.Outlined.ContentCopy to R.string.history_tag_conflict
    }
    val meta = remember(item) { folderMeta(context, item) }
    OutputRow(icon, item.name, meta, stringResource(tag), kindColors(item.kind, palette), onClick, onShare)
}

/** A file the app saved as an [OutputRow]: a scan's or its tool's icon and tag, and that it is in the app, its date and its size. */
@Composable
internal fun SavedRow(entry: SavedEntry, onClick: (() -> Unit)?, onShare: (() -> Unit)? = null) {
    val context = LocalContext.current
    val palette = LocalScanPalette.current
    val scan = entry.tool == SCAN_ENTRY_TOOL
    val tool = ToolCatalog.byId(entry.tool)
    val icon = if (scan) Icons.Filled.DocumentScanner else tool?.icon ?: Icons.Filled.Description
    val tag = if (scan) R.string.history_tag_scan else tool?.name ?: R.string.history_tag_tool
    val meta = remember(entry) { savedMeta(context, entry) }
    OutputRow(icon, entry.name, meta, stringResource(tag), (if (scan) TagRole.SCAN else TagRole.TOOL).colors(palette), onClick, onShare)
}

/** "In the app · 12 Oct 2026, 14:32 · 1.2 MB": where a saved file is when it is kept in the app, then its date and size when known. */
internal fun savedMeta(context: Context, entry: SavedEntry): String {
    val date = entry.time.takeIf { it > 0 }?.let {
        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    }
    val place = context.getString(R.string.tool_result_in_app).takeIf { entry.place == SavedPlace.APP }
    return listOfNotNull(place, date, entry.size?.let { Formatter.formatShortFileSize(context, it) }).joinToString(" · ")
}

private fun kindColors(kind: FolderKind, palette: ScanPalette): Pair<Color, Color> = when (kind) {
    FolderKind.SCAN -> palette.scanBg to palette.scanFg
    FolderKind.TOOL -> palette.toolBg to palette.toolFg
    FolderKind.CONFLICT -> palette.confBg to palette.confFg
}

/** "12 Oct 2026, 14:32 · 1.2 MB"; a scan without its PDF says so instead of a size. */
internal fun folderMeta(context: Context, item: FolderItem): String {
    val date = item.modified.takeIf { it > 0 }?.let {
        DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    }
    val rest = if (item.uri == null) context.getString(R.string.history_no_pdf) else item.size?.let { Formatter.formatShortFileSize(context, it) }
    return listOfNotNull(date, rest).joinToString(" · ")
}

/**
 * One output: icon tile, name, meta line and the type [tag] on its colours, so the type never rests on colour alone. The row (T2) reacts only
 * with an [onClick]; the share button (T3) shows only with an [onShare]. Names wrap rather than clip, so the row grows with the text size.
 */
@Composable
private fun OutputRow(
    icon: ImageVector,
    name: String,
    meta: String,
    tag: String,
    tagColors: Pair<Color, Color>,
    onClick: (() -> Unit)?,
    onShare: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    val openLabel = stringResource(R.string.history_open)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TargetStep.ACT.drawn)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = if (onShare != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(width = 36.dp, height = 44.dp).background(colors.surfaceVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = LocalScanPalette.current.meta)
        }
        Text(
            tag,
            style = MaterialTheme.typography.labelSmall,
            color = tagColors.second,
            maxLines = 1,
            modifier = Modifier.background(tagColors.first, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
        )
        if (onShare != null) {
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.history_share, name), tint = colors.onSurface)
            }
        }
    }
}
