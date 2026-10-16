package com.paperknifeplus.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether an entry whose output has type [mime] opens in the PDF reader (ZIP exports and file-less entries do not). */
internal fun canOpenInReader(mime: String?): Boolean = mime == "application/pdf"

/** The share intent for [entry]'s output of type [mime]; null when the entry has no file (e.g. PDF to Text). */
internal fun historyShareIntent(entry: ActivityEntry, mime: String): Intent? = entry.uri?.let { ScanExport.shareIntent(it, entry.name, mime) }

/** History's filter chips (2a), each with the message shown when it matches nothing. */
enum class HistoryFilter(@StringRes val label: Int, @StringRes val empty: Int) {
    ALL(R.string.history_filter_all, R.string.history_empty_all),
    SCANS(R.string.history_filter_scans, R.string.history_empty_scans),
    TOOLS(R.string.history_filter_tools, R.string.history_empty_tools);

    fun matches(entry: ActivityEntry): Boolean = when (this) {
        ALL -> true
        SCANS -> entry.isScan
        TOOLS -> !entry.isScan
    }
}

/** This session's outputs, newest first, filtered by the chips; the list scrolls inside the screen. [onBack] returns to Home. */
@Composable
fun HistoryView(onItemClick: (Uri, String, Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val items = SessionManager.history.filter(filter::matches)

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.history_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.history_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() }
                )
                Text(stringResource(R.string.history_subtitle), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        FilterChips(selected = filter, onSelect = { filter = it })

        if (items.isEmpty()) {
            Text(
                stringResource(filter.empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    // Keyed by id, so each row resolves its type once, off the main thread, and keeps it when newer entries shift the list.
                    var mime by remember(item.uri) { mutableStateOf<String?>(null) }
                    LaunchedEffect(item.uri) {
                        val uri = item.uri
                        if (uri != null) mime = withContext(Dispatchers.IO) { historyEntryMime(context.contentResolver, uri) }
                    }
                    val chooserTitle = stringResource(R.string.history_share_chooser)
                    val failed = stringResource(R.string.history_share_failed)
                    CardSlice(first = index == 0, last = index == items.lastIndex) {
                        EntryRow(
                            entry = item,
                            onClick = if (canOpenInReader(mime)) {
                                { item.uri?.let { uri -> onItemClick(uri, item.name, item.pageCount) } }
                            } else null,
                            // Sharing doubles as "Save to Files", the most reliable way to export on Android.
                            onShare = if (item.uri == null) null else {
                                {
                                    val intent = historyShareIntent(item, mime ?: historyEntryMime(context.contentResolver, item.uri))
                                    try {
                                        intent?.let { context.startActivity(Intent.createChooser(it, chooserTitle)) }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
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
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.outline, shape(18))
            .padding(start = 1.dp, end = 1.dp, top = if (first) 1.dp else 0.dp, bottom = 1.dp)
            .clip(shape(17))
            .background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

/** One horizontally scrolling row of filter chips; the selected chip is tonal (3e) and also marked by semantics, not colour alone. */
@Composable
private fun FilterChips(selected: HistoryFilter, onSelect: (HistoryFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HistoryFilter.entries.forEach { option ->
            val on = option == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .then(if (on) Modifier.background(colors.primaryContainer, RoundedCornerShape(24.dp)) else Modifier)
                    .border(1.dp, if (on) colors.onPrimaryContainer else colors.outline, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(option.label),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) colors.onPrimaryContainer else colors.onBackground,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * One output: icon tile, name, meta line and the type tag ("Scan" or the tool's label). The row reacts only with an [onClick]; the share
 * button shows only with an [onShare]. Names wrap rather than clip, so the row grows with the text size.
 */
@Composable
internal fun EntryRow(entry: ActivityEntry, onClick: (() -> Unit)?, onShare: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val openLabel = stringResource(R.string.history_open)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = if (onShare != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(width = 36.dp, height = 44.dp).background(colors.surfaceVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(entry.icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            if (entry.size.isNotBlank()) Text(entry.size, fontSize = 12.sp, color = LocalScanPalette.current.meta)
        }
        EntryTag(entry)
        if (onShare != null) {
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.history_share, entry.name), tint = colors.onSurface)
            }
        }
    }
}

/** The entry's type as text on its [TagRole] colours, so the type never rests on colour alone. */
@Composable
private fun EntryTag(entry: ActivityEntry) {
    val (bg, fg) = entry.tagRole.colors(LocalScanPalette.current)
    Text(
        entry.tool,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = fg,
        maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
