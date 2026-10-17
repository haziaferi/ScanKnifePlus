package com.haziaferi.scanknifeplus.ui.folder

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette

/**
 * Home's folder bar (2b) on the bar token, apart from the ground: where files are saved, with Change; "Choose a folder" without one; that
 * the folder cannot be reached, with Choose again; or the copy of the scans into a new folder, with its progress. The whole bar opens Folder
 * setup (T2, at least 48 dp); the state is said in words, never by colour alone.
 */
@Composable
fun FolderBar(status: FolderStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalScanPalette.current
    val name = folderName(status) ?: stringResource(R.string.folder_your_folder)
    val (icon, text, action) = when (status) {
        FolderStatus.NotChosen -> Triple(Icons.Outlined.CreateNewFolder, AnnotatedString(stringResource(R.string.folder_bar_choose)), null)
        is FolderStatus.Ready ->
            Triple(Icons.Outlined.FolderOpen, boldName(stringResource(R.string.folder_bar_saving, name), name), R.string.folder_bar_change)
        is FolderStatus.ChooseAgain ->
            Triple(Icons.Outlined.FolderOff, boldName(stringResource(R.string.folder_bar_unreachable, name), name), R.string.folder_bar_choose_again)
        is FolderStatus.Copying ->
            Triple(Icons.Outlined.FolderOpen, boldName(pluralStringResource(R.plurals.folder_bar_copying, status.count, status.count, name), name), null)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.bar)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = if (status is FolderStatus.Ready) palette.barOk else palette.tx, modifier = Modifier.size(20.dp))
            Text(text, fontSize = 14.sp, color = palette.tx, modifier = Modifier.weight(1f))
            if (action != null) Text(stringResource(action), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.barMu)
        }
        if (status is FolderStatus.Copying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** [text] with the folder [name] in it set bold, as the prototype's bar shows it. */
private fun boldName(text: String, name: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val at = text.indexOf(name)
    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + name.length)
}

/**
 * Saving a scan while the folder cannot be reached: the pages stay, and the PDF is saved once the folder is chosen again ([onChooseAgain]),
 * or elsewhere with the system picker ([onPicker]); the scan flow stays open behind it.
 */
@Composable
fun FolderBlockedDialog(name: String?, onChooseAgain: () -> Unit, onPicker: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_folder_blocked_title)) },
        text = { Text(stringResource(R.string.scan_folder_blocked_body, name ?: stringResource(R.string.folder_your_folder))) },
        confirmButton = { TextButton(onClick = onChooseAgain) { Text(stringResource(R.string.scan_folder_blocked_choose)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_folder_blocked_later)) }
                TextButton(onClick = onPicker) { Text(stringResource(R.string.scan_folder_blocked_picker)) }
            }
        }
    )
}

/** While the folder has not been checked yet: a progress line and Cancel; [onCheck] runs once, as it shows, to start a check. */
@Composable
fun CheckingFolderDialog(onCheck: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onCheck() }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.folder_checking))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_cancel)) } }
    )
}

/** Shows the folder's pending [AppFolder.notice] once, as a toast, then clears it. */
@Composable
fun FolderNotices(folder: AppFolder) {
    val context = LocalContext.current
    val notice = folder.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            noticeText(context.resources, notice)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            folder.noticeShown()
        }
    }
}

/** The words for [notice], or null when there is nothing to say (a copy into the folder that had no scans to copy). */
internal fun noticeText(res: Resources, notice: FolderNotice): String? = when (notice) {
    is FolderNotice.Conflicts -> res.getQuantityString(
        if (notice.keptCopies > 0) R.plurals.folder_conflicts_kept else R.plurals.folder_conflicts_merged, notice.count, notice.count
    )
    is FolderNotice.Copied -> when {
        notice.failed > 0 -> res.getQuantityString(R.plurals.folder_copy_failed, notice.failed, notice.failed)
        notice.written > 0 -> res.getQuantityString(R.plurals.folder_copied, notice.written, notice.written)
        else -> null
    }
    is FolderNotice.CopyStopped -> res.getQuantityString(R.plurals.folder_copy_stopped, notice.left, notice.left)
}
