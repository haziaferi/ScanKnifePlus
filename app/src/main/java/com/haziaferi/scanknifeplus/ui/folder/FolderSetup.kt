package com.haziaferi.scanknifeplus.ui.folder

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette

/** The system folder picker, asking for a lasting read and write grant, which [AppFolder.choose] then persists. */
private class OpenFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent = super.createIntent(context, input).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    )
}

/**
 * Opens the system folder picker and adopts the picked folder through [folder] ([AppFolder.choose], with [copyScans]): [onPicked] runs as
 * the adoption starts and [onResult] says whether the folder could be used. A cancelled picker changes nothing and calls nothing; a phone
 * without a folder picker counts as a folder that cannot be used.
 */
@Composable
fun rememberFolderPicker(folder: AppFolder, copyScans: Boolean = true, onPicked: () -> Unit = {}, onResult: (Boolean) -> Unit): () -> Unit {
    val picked by rememberUpdatedState(onPicked)
    val result by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(OpenFolder()) { uri ->
        if (uri != null) {
            picked()
            folder.choose(uri, copyScans) { result(it) }
        }
    }
    return remember(launcher) {
        {
            try {
                launcher.launch(null)
            } catch (e: ActivityNotFoundException) {
                result(false)
            }
        }
    }
}

/** Folder setup over the app's [folder]: [onDone] runs once a folder is adopted, or after "Not now" (which no longer offers setup at launch). */
@Composable
fun FolderSetup(folder: AppFolder, onDone: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val pick = rememberFolderPicker(folder, onPicked = { choosing = true }) { adopted ->
        choosing = false
        failed = !adopted
        if (adopted) onDone()
    }
    FolderSetupScreen(
        status = folder.status,
        scansOnPhone = folder.scansOnPhone,
        choosing = choosing,
        failed = failed,
        onChoose = {
            failed = false
            pick()
        },
        onNotNow = {
            if (folder.status == FolderStatus.NotChosen) folder.dismissSetup()
            onDone()
        }
    )
}

/**
 * Folder setup (prototype "Folder"): what the folder is for, what it will hold, the copy notice when [scansOnPhone] scans will be copied
 * in, "Choose folder" (T1) and the way out, which names what happens without a folder. [failed] shows that the last pick cannot be used.
 */
@Composable
fun FolderSetupScreen(
    status: FolderStatus,
    scansOnPhone: Int,
    choosing: Boolean,
    failed: Boolean,
    onChoose: () -> Unit,
    onNotNow: () -> Unit
) {
    val palette = LocalScanPalette.current
    val title = stringResource(R.string.folder_setup_title)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier.padding(top = 24.dp).size(56.dp).background(palette.acSoft, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = palette.ac, modifier = Modifier.size(30.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                    color = palette.tx,
                    modifier = Modifier.semantics { heading() }
                )
                Text(stringResource(R.string.folder_setup_body), fontSize = 15.sp, lineHeight = 22.sp, color = palette.mu)
            }
            LayoutPreview(folderName(status))
            if (scansOnPhone > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(palette.sf2, RoundedCornerShape(14.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = palette.tx, modifier = Modifier.size(20.dp))
                    Text(
                        pluralStringResource(R.plurals.folder_setup_copy, scansOnPhone, scansOnPhone),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = palette.tx
                    )
                }
            }
            if (failed) {
                Text(
                    stringResource(R.string.folder_setup_failed),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.warnFg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.warnBg, RoundedCornerShape(14.dp))
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        Button(
            onClick = onChoose,
            enabled = !choosing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.acf, contentColor = palette.onAcf)
        ) {
            Text(stringResource(R.string.folder_setup_choose), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
            Text(
                stringResource(
                    when (status) {
                        FolderStatus.NotChosen -> R.string.folder_setup_not_now
                        is FolderStatus.ChooseAgain -> R.string.folder_setup_later
                        else -> R.string.folder_setup_keep
                    }
                ),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.mu,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** The folder as it will look: PDFs at the top level and the scans' editable pages in `Scan sources/`, each row named in text. */
@Composable
private fun LayoutPreview(name: String?) {
    val palette = LocalScanPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.sf, RoundedCornerShape(18.dp))
            .border(1.dp, palette.bd, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(name ?: stringResource(R.string.folder_setup_preview_folder), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = palette.tx)
        PreviewRow(stringResource(R.string.folder_setup_preview_scan), palette.ac, palette.tx)
        PreviewRow(stringResource(R.string.folder_setup_preview_tool), palette.ok, palette.tx)
        PreviewRow(stringResource(R.string.folder_setup_preview_sources), palette.mu, palette.mu)
    }
}

@Composable
private fun PreviewRow(text: String, dot: Color, color: Color) {
    Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, fontSize = 14.sp, color = color)
    }
}

/** The name [status] carries, if any. */
internal fun folderName(status: FolderStatus): String? = when (status) {
    FolderStatus.NotChosen -> null
    is FolderStatus.Ready -> status.name
    is FolderStatus.ChooseAgain -> status.name
    is FolderStatus.Copying -> status.name
}
