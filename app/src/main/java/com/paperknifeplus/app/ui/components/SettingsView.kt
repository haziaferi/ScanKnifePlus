package com.paperknifeplus.app.ui.components

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.haziaferi.scanknifeplus.ui.folder.folderName
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deletes the app's cached files (PDF previews, decrypted copies, anything else in the cache folders) and the thumbnail memory cache; blocking.
 * The scanner's folders are skipped: it cleans them itself, and deleting them mid-session or mid-export breaks it. False if something failed.
 */
fun clearAppCache(context: Context): Boolean = try {
    PdfImageLoader.get(context).memoryCache?.clear()
    context.cacheDir.listFiles()?.forEach { file ->
        if (file.name !in ScanFiles.CACHE_FOLDERS) {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }
    context.externalCacheDir?.let { if (it.exists()) it.deleteRecursively() }
    true
} catch (e: Exception) {
    Log.w("SettingsView", "Clearing the cache failed", e)
    false
}

/** The theme choices in the order Settings offers them, with their labels. */
private val THEMES = listOf(
    ThemeMode.SYSTEM to R.string.settings_theme_system,
    ThemeMode.LIGHT to R.string.settings_theme_light,
    ThemeMode.OLED to R.string.settings_theme_oled
)

/**
 * Settings in the prototype's style: groups under headings, each a card of rows of at least 60 dp (Appearance, Files, Privacy and storage,
 * About), then the version. The theme and the palette are chips that wrap at large text sizes.
 */
@Composable
fun SettingsView(
    onThemeChange: (Int) -> Unit,
    palette: AppPalette,
    onPaletteChange: (AppPalette) -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    folder: FolderStatus = FolderStatus.NotChosen,
    onFolder: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appName = stringResource(R.string.app_name)
    val cacheCleared = stringResource(R.string.settings_cache_cleared)
    val cachePartly = stringResource(R.string.settings_cache_partly)
    val historyCleared = stringResource(R.string.settings_history_cleared)

    var autoAuthor by remember { mutableStateOf(PreferencesManager.getDefaultAuthor(context)) }
    var currentTheme by remember { mutableIntStateOf(PreferencesManager.getTheme(context)) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ToolTopBar(
            title = stringResource(R.string.home_settings),
            subtitle = null,
            showChange = false,
            onBack = onBack,
            backLabel = stringResource(R.string.settings_back)
        )

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "appearance") {
                SettingsGroup(stringResource(R.string.settings_appearance)) {
                    ChoiceRow(stringResource(R.string.settings_theme), THEMES, selected = { it.first.stored == currentTheme }, label = { stringResource(it.second) }) {
                        currentTheme = it.first.stored
                        onThemeChange(it.first.stored)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    ChoiceRow(stringResource(R.string.settings_palette), AppPalette.entries, selected = { it == palette }, label = { it.label }) {
                        onPaletteChange(it)
                    }
                }
            }

            item(key = "files") {
                SettingsGroup(stringResource(R.string.settings_files)) {
                    SettingsItem(Icons.Outlined.Folder, stringResource(R.string.settings_folder), folderSubtitle(folder), onClick = onFolder)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_author), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.settings_author_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = autoAuthor,
                            onValueChange = {
                                autoAuthor = it
                                PreferencesManager.setDefaultAuthor(context, it)
                            },
                            placeholder = { Text(stringResource(R.string.settings_author_hint), fontSize = 15.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }

            item(key = "privacy") {
                SettingsGroup(stringResource(R.string.settings_privacy)) {
                    SettingsItem(Icons.Outlined.DeleteSweep, stringResource(R.string.settings_clear_history), stringResource(R.string.settings_clear_history_body)) {
                        showClearConfirm = true
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingsItem(Icons.Outlined.DeleteForever, stringResource(R.string.settings_clear_cache), stringResource(R.string.settings_clear_cache_body)) {
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) { clearAppCache(context) }
                            Toast.makeText(context, if (cleared) cacheCleared else cachePartly, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            item(key = "about") {
                SettingsGroup(stringResource(R.string.settings_about)) {
                    SettingsItem(
                        Icons.Outlined.Info,
                        stringResource(R.string.settings_about_app, appName),
                        stringResource(R.string.settings_about_body),
                        onClick = onOpenAbout
                    )
                }
            }

            item(key = "version") {
                Text(
                    stringResource(R.string.settings_version, appName, appVersionName(context)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_history_title), fontWeight = FontWeight.ExtraBold) },
            text = { Text(stringResource(R.string.settings_clear_history_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SessionManager.clearHistory()
                        showClearConfirm = false
                        Toast.makeText(context, historyCleared, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_clear_history_confirm), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }
}

/** The Folder row's second line: where files are saved, that the folder must be chosen again, or that each file is saved with the picker. */
@Composable
private fun folderSubtitle(folder: FolderStatus): String {
    val name = folderName(folder) ?: stringResource(R.string.folder_your_folder)
    return when (folder) {
        FolderStatus.NotChosen -> stringResource(R.string.settings_folder_none)
        is FolderStatus.ChooseAgain -> stringResource(R.string.settings_folder_unreachable, name)
        is FolderStatus.Ready, is FolderStatus.Copying -> stringResource(R.string.folder_bar_saving, name)
    }
}

/** A heading (16 sp, as Home's "Recent in your folder") over a card of rows. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

/** One choice of several as chips (T3: a 48 dp target, 8 dp apart), the chosen one tonal and marked selected, not by colour alone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: (T) -> Boolean, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val on = selected(option)
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .then(if (on) Modifier.background(colors.primaryContainer, RoundedCornerShape(24.dp)) else Modifier)
                        .border(1.dp, if (on) colors.onPrimaryContainer else colors.outline, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label(option), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) colors.onPrimaryContainer else colors.onSurface)
                }
            }
        }
    }
}

/** A row of at least 60 dp: icon tile, [title] over [subtitle], and an arrow; the whole row is one button. */
@Composable
private fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(colors.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.onSurface)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.onSurface)
            Text(subtitle, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
