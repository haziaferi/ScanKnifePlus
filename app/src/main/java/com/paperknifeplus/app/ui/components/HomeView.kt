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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many outputs Home's Recent list shows. */
private const val RECENT_COUNT = 3

/**
 * Home: header (wordmark, All tools, Settings), the scan card and the Recent list (1a, 3c, 3d). Scanning itself starts from the bottom bar's
 * Scan button, so the card carries Import photos and Open PDF (the system picker, then the reader); the accent stays on Scan, the active
 * tab and the Scan tag.
 */
@Composable
fun HomeView(
    onAllTools: () -> Unit,
    onSettings: () -> Unit,
    onImportPhotos: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            ScanCard(onImportPhotos = onImportPhotos, onOpenPdf = { pickPdf.launch("application/pdf") })
            RecentList(SessionManager.history.take(RECENT_COUNT), onSeeAll = onSeeAll, onOpenPreview = onOpenPreview)
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
        Text(
            stringResource(R.string.app_name),
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        HeaderButton(Icons.Outlined.GridView, stringResource(R.string.home_all_tools), onAllTools)
        HeaderButton(Icons.Outlined.Settings, stringResource(R.string.home_settings), onSettings)
    }
}

/** A 48 dp outlined round icon button (T2). */
@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onBackground)
    }
}

/** "Scan a document" with its subtitle and the Import photos and Open PDF buttons (T2); the Scan action itself is in the bottom bar (3c). */
@Composable
private fun ScanCard(onImportPhotos: () -> Unit, onOpenPdf: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier.size(52.dp).background(colors.surfaceVariant, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(28.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.home_scan_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                        color = colors.onSurface,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(stringResource(R.string.home_scan_subtitle), fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(stringResource(R.string.home_import_photos), onImportPhotos, Modifier.weight(1f))
                CardButton(stringResource(R.string.home_open_pdf), onOpenPdf, Modifier.weight(1f))
            }
        }
    }
}

/** An outlined card button of at least 48 dp (T2); its label wraps at large text sizes instead of clipping. */
@Composable
private fun CardButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

/** The newest outputs; a PDF opens in the reader, any other file opens History, where it can be shared. */
@Composable
internal fun RecentList(recent: List<ActivityEntry>, onSeeAll: () -> Unit, onOpenPreview: (Uri, String, Int) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.home_recent),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onSeeAll)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.home_see_all),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                textDecoration = TextDecoration.Underline
            )
        }
    }
    if (recent.isEmpty()) {
        Text(
            stringResource(R.string.home_recent_empty),
            fontSize = 14.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
    } else {
        EntryList(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            recent.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = colors.outline)
                EntryRow(
                    entry = entry,
                    onClick = entry.uri?.let { uri ->
                        {
                            if (canOpenInReader(historyEntryMime(context.contentResolver, uri))) onOpenPreview(uri, entry.name, entry.pageCount)
                            else onSeeAll()
                        }
                    }
                )
            }
        }
    }
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
