package com.paperknifeplus.app.ui.components

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.haziaferi.scanknifeplus.ui.folder.folderName
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.LocalScanPalette
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

@Composable
fun SettingsView(
    isDarkMode: Boolean,
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
    val tokens = LocalScanPalette.current
    val appName = stringResource(R.string.app_name)

    var autoAuthor by remember { mutableStateOf(PreferencesManager.getDefaultAuthor(context)) }
    var currentTheme by remember { mutableIntStateOf(PreferencesManager.getTheme(context)) }

    var showClearConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    modifier = Modifier.weight(1f),
                    text = buildAnnotatedString {
                        append("Settings")
                        withStyle(SpanStyle(color = tokens.ac)) { append(".") }
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = (-1).sp
                )
                Logo(modifier = Modifier.size(24.dp), partColor = tokens.tx)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                SettingsGroup("APPEARANCE") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeOption("System", Icons.Outlined.SettingsSuggest, currentTheme == 0, Modifier.weight(1f)) {
                            currentTheme = 0
                            onThemeChange(0)
                        }
                        ThemeOption("Light", Icons.Outlined.LightMode, currentTheme == 1, Modifier.weight(1f)) {
                            currentTheme = 1
                            onThemeChange(1)
                        }
                        ThemeOption("OLED", Icons.Outlined.DarkMode, currentTheme == 2, Modifier.weight(1f)) {
                            currentTheme = 2
                            onThemeChange(2)
                        }
                    }
                    Text(
                        "COLOUR PALETTE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = LocalScanPalette.current.ac, letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppPalette.entries.forEach { option ->
                            ThemeOption(option.label, paletteIcon(option), option == palette, Modifier.weight(1f)) { onPaletteChange(option) }
                        }
                    }
                }
            }

            item {
                SettingsGroup("USER PROFILE") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                        Text("DEFAULT AUTHOR", fontSize = 9.sp, fontWeight = FontWeight.Black, color = tokens.ac, letterSpacing = 1.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("This name is automatically added to the 'Author' field in the metadata of every PDF you save.", fontSize = 10.sp, color = tokens.mu)
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = autoAuthor,
                            onValueChange = {
                                autoAuthor = it
                                PreferencesManager.setDefaultAuthor(context, it)
                            },
                            placeholder = { Text("e.g. John Doe", fontSize = 14.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = tokens.ac,
                                cursorColor = tokens.ac
                            ),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            item {
                SettingsGroup("PRIVACY & STORAGE") {
                    SettingsItem(
                        Icons.Outlined.Folder, stringResource(R.string.settings_folder), folderSubtitle(folder), subtitleSize = 12.sp, onClick = onFolder
                    )
                    SettingsItem(Icons.Outlined.DeleteSweep, "Clear History", "Wipe all session history now") {
                        showClearConfirm = true
                    }
                    SettingsItem(Icons.Outlined.DeleteForever, "Clear Cache", "Purge all temporary documents and previews") {
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) { clearAppCache(context) }
                            Toast.makeText(context, if (cleared) "Cache cleared successfully" else "Partial cache clear", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            item {
                SettingsGroup("ABOUT") {
                    SettingsItem(Icons.Outlined.Info, "About $appName", "How it works, credits and licenses", onClick = onOpenAbout)
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$appName V${appVersionName(context)}", fontSize = 8.sp, color = tokens.mu, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear History?", fontWeight = FontWeight.Black) },
            text = { Text("This will permanently remove all entries from your history tab. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        SessionManager.clearHistory()
                        showClearConfirm = false
                        Toast.makeText(context, "History wiped", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LocalScanPalette.current.sf2, contentColor = MaterialTheme.colorScheme.onSurface)
                ) { Text("WIPE EVERYTHING", fontWeight = FontWeight.Black) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("CANCEL", color = LocalScanPalette.current.ac) }
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

@Composable
fun ThemeOption(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalScanPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) palette.acSoft else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) palette.ac else palette.bd)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = if (selected) palette.ac else palette.mu)
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black, color = if (selected) palette.ac else palette.mu, textAlign = TextAlign.Center)
        }
    }
}

private fun paletteIcon(palette: AppPalette): ImageVector = when (palette) {
    AppPalette.STILL_WATER -> Icons.Outlined.WaterDrop
    AppPalette.ROSE -> Icons.Outlined.LocalFlorist
    AppPalette.GRAPHITE_SCHOLASTIC -> Icons.Outlined.School
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            color = LocalScanPalette.current.mu,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            color = LocalScanPalette.current.sf,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, LocalScanPalette.current.bd)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    accentColor: Color? = null,
    subtitleSize: TextUnit = 11.sp,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accentColor?.copy(alpha = 0.1f) ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accentColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = subtitleSize, color = LocalScanPalette.current.mu)
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = LocalScanPalette.current.mu,
            modifier = Modifier.size(18.dp)
        )
    }
}
