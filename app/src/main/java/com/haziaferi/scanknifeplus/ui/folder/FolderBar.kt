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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.components.DialogButton
import com.paperknifeplus.app.ui.components.DialogButtonKind
import com.paperknifeplus.app.ui.components.DialogButtons
import com.paperknifeplus.app.ui.components.DialogTitle
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep

/**
 * Home's folder bar (2b) on the bar token, apart from the ground: where files are saved, with Change; without a folder, that files are saved
 * in the app, with Move to folder while [appFiles] are kept there and Choose folder otherwise; that the folder cannot be reached, with Choose
 * again; or the copy of the scans into a new folder, with its progress. History shows the same bar over its list while files wait to move.
 * The whole bar opens Folder setup (T2); the state is said in words, never by colour alone.
 */
@Composable
fun FolderBar(status: FolderStatus, onClick: () -> Unit, modifier: Modifier = Modifier, appFiles: Boolean = false) {
    val palette = LocalScanPalette.current
    val name = folderName(status) ?: stringResource(R.string.folder_your_folder)
    val (icon, text, action) = when (status) {
        FolderStatus.NotChosen -> Triple(
            Icons.Outlined.CreateNewFolder,
            AnnotatedString(stringResource(R.string.folder_bar_in_app)),
            if (appFiles) R.string.folder_bar_move else R.string.folder_setup_choose
        )
        is FolderStatus.Ready ->
            Triple(Icons.Outlined.FolderOpen, boldName(stringResource(R.string.folder_bar_saving, name), name), R.string.folder_bar_change)
        is FolderStatus.ChooseAgain ->
            Triple(Icons.Outlined.FolderOff, boldName(stringResource(R.string.folder_bar_unreachable, name), name), R.string.folder_bar_choose_again)
        is FolderStatus.Copying -> {
            val copying = if (status.files > 0) {
                pluralStringResource(R.plurals.folder_bar_moving, status.count + status.files, status.count + status.files, name)
            } else {
                pluralStringResource(R.plurals.folder_bar_copying, status.count, status.count, name)
            }
            Triple(Icons.Outlined.FolderOpen, boldName(copying, name), null)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.bar)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = TargetStep.ACT.drawn)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        TextThenAction(
            icon = { Icon(icon, contentDescription = null, tint = if (status is FolderStatus.Ready) palette.barOk else palette.tx, modifier = Modifier.size(20.dp)) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.tx) },
            action = action?.let { { Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = palette.barMu) } }
        )
        if (status is FolderStatus.Copying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * [icon], [text] and, at the end edge, [action]: on one line while the text fits it whole beside the action; otherwise the action goes under
 * the text, which keeps the whole width beside the icon, so it never wraps into a narrow column. Mirrors right to left.
 */
@Composable
private fun TextThenAction(icon: @Composable () -> Unit, text: @Composable () -> Unit, action: (@Composable () -> Unit)?) {
    Layout(contents = listOf(icon, text, action ?: {})) { (iconM, textM, actionM), constraints ->
        val gap = 12.dp.roundToPx()
        val total = constraints.maxWidth
        val iconP = iconM.map { it.measure(Constraints()) }
        val iconW = iconP.sumOf { it.width }
        val actionW = actionM.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val textRoom = (total - iconW - gap).coerceAtLeast(0)
        val besideRoom = (textRoom - if (actionM.isEmpty()) 0 else gap + actionW).coerceAtLeast(0)
        val beside = actionM.isEmpty() || textM.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } <= besideRoom
        val textP = textM.map { it.measure(Constraints(maxWidth = if (beside) besideRoom else textRoom)) }
        val actionP = actionM.map { it.measure(Constraints(maxWidth = textRoom)) }
        val textH = textP.maxOfOrNull { it.height } ?: 0
        val iconH = iconP.maxOfOrNull { it.height } ?: 0
        val actionH = actionP.maxOfOrNull { it.height } ?: 0
        val firstLine = maxOf(textH, iconH, if (beside) actionH else 0)
        val under = 4.dp.roundToPx()
        val height = if (beside || actionP.isEmpty()) firstLine else firstLine + under + actionH
        layout(total, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            iconP.forEach { it.placeRelative(0, (firstLine - it.height) / 2) }
            textP.forEach { it.placeRelative(iconW + gap, (firstLine - it.height) / 2) }
            actionP.forEach { it.placeRelative(total - it.width, if (beside) (firstLine - it.height) / 2 else firstLine + under) }
        }
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
        title = { DialogTitle(stringResource(R.string.scan_folder_blocked_title)) },
        text = { Text(stringResource(R.string.scan_folder_blocked_body, name ?: stringResource(R.string.folder_your_folder))) },
        confirmButton = {
            DialogButtons {
                DialogButton(stringResource(R.string.scan_folder_blocked_later), onClick = onDismiss)
                DialogButton(stringResource(R.string.scan_folder_blocked_picker), onClick = onPicker)
                DialogButton(stringResource(R.string.scan_folder_blocked_choose), kind = DialogButtonKind.COMMIT, onClick = onChooseAgain)
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
        dismissButton = { DialogButton(stringResource(R.string.scan_cancel), onClick = onDismiss) }
    )
}

/** Shows the folder's pending [AppFolder.notice] once, as a toast, then clears it; and, once, that the list of saved files was rebuilt. */
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
    val rebuilt = folder.rebuilt
    val rebuiltText = stringResource(R.string.folder_list_rebuilt)
    LaunchedEffect(rebuilt) {
        if (rebuilt) {
            Toast.makeText(context, rebuiltText, Toast.LENGTH_LONG).show()
            folder.rebuiltShown()
        }
    }
}

/** The words for [notice], or null when there is nothing to say (a copy into the folder that had no scans to copy). */
internal fun noticeText(res: Resources, notice: FolderNotice): String? = when (notice) {
    is FolderNotice.Conflicts -> res.getQuantityString(
        if (notice.keptCopies > 0) R.plurals.folder_conflicts_kept else R.plurals.folder_conflicts_merged, notice.count, notice.count
    )
    is FolderNotice.Copied -> when {
        notice.unmoved > 0 -> res.getQuantityString(R.plurals.folder_move_failed, notice.unmoved, notice.unmoved)
        notice.failed > 0 -> res.getQuantityString(R.plurals.folder_copy_failed, notice.failed, notice.failed)
        // One toast for the whole move: the scans written count among the files moved.
        notice.moved > 0 -> (notice.moved + notice.written).let { res.getQuantityString(R.plurals.folder_moved, it, it) }
        notice.written > 0 -> res.getQuantityString(R.plurals.folder_copied, notice.written, notice.written)
        else -> null
    }
    is FolderNotice.CopyStopped -> if (notice.files > 0) {
        (notice.left + notice.files).let { res.getQuantityString(R.plurals.folder_move_stopped, it, it) }
    } else {
        res.getQuantityString(R.plurals.folder_copy_stopped, notice.left, notice.left)
    }
}
