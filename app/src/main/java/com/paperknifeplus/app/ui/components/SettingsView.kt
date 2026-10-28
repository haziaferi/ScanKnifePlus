package com.paperknifeplus.app.ui.components

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
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

/** The bottom sheets Settings rows open. */
private enum class SettingsSheet { THEME, PALETTE, FOLDER, AUTHOR, CLEAR_HISTORY }

/**
 * Settings (4c): groups under headings (Appearance, Files, Privacy and storage, About), each a card of one-line rows of at least 60 dp that
 * show the current value and open a bottom sheet with the choices; Clear cache acts at once. Then the version.
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
    val colors = MaterialTheme.colorScheme
    val appName = stringResource(R.string.app_name)
    val cacheCleared = stringResource(R.string.settings_cache_cleared)
    val cachePartly = stringResource(R.string.settings_cache_partly)
    val historyCleared = stringResource(R.string.settings_history_cleared)

    var author by remember { mutableStateOf(PreferencesManager.getDefaultAuthor(context)) }
    var currentTheme by remember { mutableIntStateOf(PreferencesManager.getTheme(context)) }
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }

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
                    val theme = THEMES.firstOrNull { it.first.stored == currentTheme } ?: THEMES.first()
                    SettingsRow(Icons.Outlined.DarkMode, stringResource(R.string.settings_theme), stringResource(theme.second)) { sheet = SettingsSheet.THEME }
                    HorizontalDivider(color = colors.outline)
                    SettingsRow(Icons.Outlined.Palette, stringResource(R.string.settings_palette), stringResource(palette.label)) { sheet = SettingsSheet.PALETTE }
                }
            }

            item(key = "files") {
                SettingsGroup(stringResource(R.string.settings_files)) {
                    SettingsRow(Icons.Outlined.Folder, stringResource(R.string.settings_folder), folderValue(folder)) { sheet = SettingsSheet.FOLDER }
                    HorizontalDivider(color = colors.outline)
                    SettingsRow(
                        Icons.Outlined.Person,
                        stringResource(R.string.settings_author),
                        author.ifBlank { stringResource(R.string.settings_value_not_set) }
                    ) { sheet = SettingsSheet.AUTHOR }
                }
            }

            item(key = "privacy") {
                SettingsGroup(stringResource(R.string.settings_privacy)) {
                    SettingsRow(Icons.Outlined.DeleteForever, stringResource(R.string.settings_clear_cache), value = null, chevron = false) {
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) { clearAppCache(context) }
                            Toast.makeText(context, if (cleared) cacheCleared else cachePartly, Toast.LENGTH_SHORT).show()
                        }
                    }
                    HorizontalDivider(color = colors.outline)
                    SettingsRow(
                        Icons.Outlined.DeleteSweep,
                        stringResource(R.string.settings_clear_history),
                        value = null,
                        chevron = false,
                        tint = colors.error
                    ) { sheet = SettingsSheet.CLEAR_HISTORY }
                }
            }

            item(key = "about") {
                SettingsGroup(stringResource(R.string.settings_about)) {
                    SettingsRow(Icons.Outlined.Info, stringResource(R.string.settings_about_app, appName), value = null, onClick = onOpenAbout)
                }
            }

            item(key = "version") {
                Text(
                    stringResource(R.string.settings_version, appName, appVersionName(context)),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }
        }
    }

    val close = { sheet = null }
    when (sheet) {
        SettingsSheet.THEME -> SettingsSheetFrame(stringResource(R.string.settings_theme), close) {
            ChoiceList(THEMES, selected = { it.first.stored == currentTheme }, label = { stringResource(it.second) }) {
                currentTheme = it.first.stored
                onThemeChange(it.first.stored)
                close()
            }
        }
        SettingsSheet.PALETTE -> SettingsSheetFrame(stringResource(R.string.settings_palette), close) {
            ChoiceList(AppPalette.entries, selected = { it == palette }, label = { stringResource(it.label) }) {
                onPaletteChange(it)
                close()
            }
        }
        SettingsSheet.FOLDER -> SettingsSheetFrame(stringResource(R.string.settings_folder), close) {
            SheetBody(folderSubtitle(folder))
            ToolPrimaryButton(stringResource(folderAction(folder)), onClick = { close(); onFolder() }, modifier = Modifier.fillMaxWidth())
        }
        SettingsSheet.AUTHOR -> SettingsSheetFrame(stringResource(R.string.settings_author), close) {
            SheetBody(stringResource(R.string.settings_author_body))
            OutlinedTextField(
                value = author,
                onValueChange = {
                    author = it
                    PreferencesManager.setDefaultAuthor(context, it)
                },
                placeholder = { Text(stringResource(R.string.settings_author_hint), fontSize = 15.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            ToolPrimaryButton(stringResource(R.string.action_done), onClick = close, modifier = Modifier.fillMaxWidth())
        }
        SettingsSheet.CLEAR_HISTORY -> SettingsSheetFrame(stringResource(R.string.settings_clear_history_title), close) {
            SheetBody(stringResource(R.string.settings_clear_history_confirm_body))
            Button(
                onClick = {
                    SessionManager.clearHistory()
                    close()
                    Toast.makeText(context, historyCleared, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)
            ) {
                Text(stringResource(R.string.settings_clear_history_confirm), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel), fontSize = 15.sp, color = colors.onSurface)
            }
        }
        null -> Unit
    }
}

/** The Folder row's value: the folder's name, that it must be chosen again, or that none is chosen. */
@Composable
private fun folderValue(folder: FolderStatus): String = when (folder) {
    FolderStatus.NotChosen -> stringResource(R.string.settings_value_not_chosen)
    is FolderStatus.ChooseAgain -> stringResource(R.string.folder_bar_choose_again)
    is FolderStatus.Ready, is FolderStatus.Copying -> folderName(folder) ?: stringResource(R.string.folder_your_folder)
}

/** The Folder sheet's text: where files are saved, that the folder must be chosen again, or that each file is saved with the picker. */
@Composable
private fun folderSubtitle(folder: FolderStatus): String {
    val name = folderName(folder) ?: stringResource(R.string.folder_your_folder)
    return when (folder) {
        FolderStatus.NotChosen -> stringResource(R.string.settings_folder_none)
        is FolderStatus.ChooseAgain -> stringResource(R.string.settings_folder_unreachable, name)
        is FolderStatus.Ready, is FolderStatus.Copying -> stringResource(R.string.folder_bar_saving, name)
    }
}

/** The Folder sheet's action, which opens Folder setup: the folder bar's words for the same state. */
private fun folderAction(folder: FolderStatus): Int = when (folder) {
    FolderStatus.NotChosen -> R.string.folder_setup_choose
    is FolderStatus.ChooseAgain -> R.string.folder_bar_choose_again
    is FolderStatus.Ready, is FolderStatus.Copying -> R.string.folder_bar_change
}

/** A heading (16 sp, as Home's "Recent in your folder") over a card of rows. */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() }
        )
        ToolCard { Column { content() } }
    }
}

/**
 * A one-line row of at least 60 dp: icon tile, [title], its current [value] and, with [chevron], an arrow; the whole row is one button.
 * [tint] marks a destructive row (in words too: its title says what it does).
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String?,
    chevron: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
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
        Box(Modifier.size(36.dp).background(colors.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = tint, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 14.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** A Settings bottom sheet: [title] as its heading over [content], scrolling when the text is large. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheetFrame(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            content()
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun SheetBody(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One choice of several as rows of at least 56 dp with a radio button; the chosen one is tonal and marked selected, not by colour alone. */
@Composable
private fun <T> ChoiceList(options: List<T>, selected: (T) -> Boolean, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val on = selected(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .border(1.dp, if (on) colors.onPrimaryContainer else colors.outline, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) colors.primaryContainer else colors.surface)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioButton(selected = on, onClick = null)
                Text(label(option), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (on) colors.onPrimaryContainer else colors.onSurface)
            }
        }
    }
}
