package com.paperknifeplus.app

import android.content.ContentResolver
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import com.haziaferi.scanknifeplus.ui.folder.FolderHolder
import com.haziaferi.scanknifeplus.ui.folder.FolderNotices
import com.haziaferi.scanknifeplus.ui.folder.FolderSetup
import com.haziaferi.scanknifeplus.ui.scan.ScanFlow
import com.paperknifeplus.app.ui.components.AboutView
import com.paperknifeplus.app.ui.components.HistoryView
import com.paperknifeplus.app.ui.components.HomeView
import com.paperknifeplus.app.ui.components.LocalOutputFolder
import com.paperknifeplus.app.ui.components.OutputFolder
import com.paperknifeplus.app.ui.components.PreferencesManager
import com.paperknifeplus.app.ui.components.SettingsView
import com.paperknifeplus.app.ui.components.ToolCatalog
import com.paperknifeplus.app.ui.components.ToolLaunch
import com.paperknifeplus.app.ui.components.ToolPickerContent
import com.paperknifeplus.app.ui.components.UltraPreview
import com.paperknifeplus.app.ui.components.getUriDetails
import com.paperknifeplus.app.ui.components.sweepDecryptedCopies
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bottom bar's tabs, also the pages of the main pager. */
const val HOME_ROUTE = "home"
const val HISTORY_ROUTE = "history"

/** The scan flow's route, opened by the bottom bar's Scan button: the one full-screen route that is neither the shell's nor a [ToolCatalog] id. */
const val SCAN_ROUTE = "scan"

/** Full-screen routes of the shell itself; apart from these and [SCAN_ROUTE], every route is a [ToolCatalog] id. */
private const val SETTINGS_ROUTE = "settings"
private const val ABOUT_ROUTE = "about"
private const val PREVIEW_ROUTE = "ultra_preview"
private const val FOLDER_ROUTE = "folder"

/** The PDF an "Open with" [intent] carries: ACTION_VIEW with the PDF MIME type, or a path ending in ".pdf" when no type is given. */
fun pdfUriFrom(intent: Intent?): Uri? {
    val uri = intent?.data ?: return null
    if (intent.action != Intent.ACTION_VIEW) return null
    return uri.takeIf { intent.type == "application/pdf" || it.path.orEmpty().endsWith(".pdf", ignoreCase = true) }
}

/**
 * True for a content:// URI served by another app. The activity is exported, so a file:// URI or one of this app's own providers could
 * make it open (and offer to share) its private files.
 */
fun isExternalContentUri(uri: Uri, packageName: String): Boolean {
    val authority = uri.authority ?: return false
    return uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) &&
        authority != packageName && !authority.startsWith("$packageName.")
}

/** previewData as a Bundle-friendly list: Uri, name, page count. */
private val PreviewDataSaver = listSaver<Triple<Uri, String, Int>?, Any>(
    save = { data -> data?.let { listOf(it.first, it.second, it.third) }.orEmpty() },
    restore = { list -> if (list.size == 3) Triple(list[0] as Uri, list[1] as String, list[2] as Int) else null }
)

/** Decrypted copies older than this are left over from an earlier session and swept at start. */
private const val STALE_COPY_AGE_MS = 24L * 60 * 60 * 1000

class MainActivity : ComponentActivity() {
    /** A PDF handed over by another app, waiting to be opened in the reader. */
    private var incomingPdf by mutableStateOf<Uri?>(null)

    private fun acceptIncoming(intent: Intent?) {
        pdfUriFrom(intent)?.takeIf { isExternalContentUri(it, packageName) }?.let { incomingPdf = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncoming(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Created once per launch, not per recreation: its launch work (the sweep, then the folder's sync conflicts) runs off the main thread.
        val folder = ViewModelProvider(this)[FolderHolder::class.java].folder
        enableEdgeToEdge()
        // themes.xml can only hold the default palette; the stored one paints the first frame, so Rose and Graphite do not flash Still Water.
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val startOled = ThemeMode.fromStored(PreferencesManager.getTheme(applicationContext)).isOled(systemDark)
        window.setBackgroundDrawable(PreferencesManager.getPalette(applicationContext).tokens(startOled).bg.toArgb().toDrawable())
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        // Only on a fresh start: after recreation getIntent() still holds the last 'Open with' intent, and handling it again would reopen a
        // reader the user may have closed; an open reader comes back from saved state instead.
        if (savedInstanceState == null) {
            acceptIncoming(intent)
            // Only stale copies: another window of the app may still be working on a fresh one.
            lifecycleScope.launch(Dispatchers.IO) { sweepDecryptedCopies(cacheDir, minAgeMs = STALE_COPY_AGE_MS) }
        }
        // Folder setup opens by itself on a first launch, unless another app handed over a PDF to read.
        val startRoute = if (savedInstanceState == null && incomingPdf == null && folder.offersSetup) FOLDER_ROUTE else null

        setContent {
            var themePreference by remember { mutableIntStateOf(PreferencesManager.getTheme(applicationContext)) }
            var palette by remember { mutableStateOf(PreferencesManager.getPalette(applicationContext)) }
            val themeMode = ThemeMode.fromStored(themePreference)

            PaperKnifePlusTheme(palette = palette, themeMode = themeMode) {
                val sheetState = rememberModalBottomSheetState()
                var showToolPicker by remember { mutableStateOf(false) }
                // Whether the picked tool opens with the last-viewed file; the sheet's chip clears it for that opening only.
                var pickerKeepsFile by remember { mutableStateOf(true) }
                val scope = rememberCoroutineScope()

                var currentTool by rememberSaveable { mutableStateOf(startRoute) }
                // Where Folder setup goes back to: Settings when it was opened from there.
                var folderReturn by rememberSaveable { mutableStateOf<String?>(null) }
                // Not saveable, and not needed after a recreation: the scan flow's model, which outlives one, has the pages by then.
                var importUris by remember { mutableStateOf(emptyList<Uri>()) }
                var previewData by rememberSaveable(stateSaver = PreviewDataSaver) { mutableStateOf<Triple<Uri, String, Int>?>(null) }
                var toolInitialUri by rememberSaveable { mutableStateOf<Uri?>(null) }
                // Not saveable: saved state may be written to disk, and a password must not be.
                var toolInitialPassword by remember { mutableStateOf<String?>(null) }

                val mainScreens = listOf(HOME_ROUTE, HISTORY_ROUTE)
                val pagerState = rememberPagerState { mainScreens.size }
                val showTab: (String) -> Unit = { scope.launch { pagerState.scrollToPage(mainScreens.indexOf(it)) } }

                val openPreview: (Uri, String, Int) -> Unit = { uri, name, count ->
                    previewData = Triple(uri, name, count)
                    currentTool = PREVIEW_ROUTE
                }
                val closeTool = { currentTool = null }
                val openFolder: (String?) -> Unit = { from ->
                    folderReturn = from
                    currentTool = FOLDER_ROUTE
                }

                val outputFolder = remember(folder) { OutputFolder(folder::copyOutput) }
                FolderNotices(folder)
                // Files a sync app changed while the app was away show when it comes back.
                LifecycleResumeEffect(folder) {
                    folder.refresh()
                    onPauseOrDispose {}
                }

                // An incoming PDF replaces whatever is open; the request is consumed once the reader shows it.
                val incoming = incomingPdf
                LaunchedEffect(incoming) {
                    if (incoming != null) {
                        val name = withContext(Dispatchers.IO) { getUriDetails(applicationContext, incoming).name }
                        toolInitialPassword = null
                        openPreview(incoming, name, 0)
                        incomingPdf = null
                    }
                }

                BackHandler(enabled = currentTool != null || pagerState.currentPage != 0 || showToolPicker) {
                    when {
                        showToolPicker -> scope.launch { sheetState.hide() }.invokeOnCompletion { showToolPicker = false }
                        currentTool == ABOUT_ROUTE -> currentTool = SETTINGS_ROUTE
                        currentTool == FOLDER_ROUTE -> currentTool = folderReturn
                        currentTool != null -> currentTool = null
                        else -> showTab(HOME_ROUTE)
                    }
                }

                // Edge to edge: the system navigation bar draws over a strip of the bottom bar's surface colour, below the content.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.weight(1f),
                                userScrollEnabled = currentTool == null,
                                beyondViewportPageCount = 0
                            ) { page ->
                                when (mainScreens[page]) {
                                    HOME_ROUTE -> HomeView(
                                        onAllTools = {
                                            pickerKeepsFile = true
                                            showToolPicker = true
                                        },
                                        onSettings = { currentTool = SETTINGS_ROUTE },
                                        onImportPhotos = {
                                            importUris = it
                                            currentTool = SCAN_ROUTE
                                        },
                                        onSeeAll = { showTab(HISTORY_ROUTE) },
                                        onOpenPreview = openPreview,
                                        folder = folder.status,
                                        onFolder = { openFolder(null) },
                                        recentFolder = folder.history?.items
                                    )
                                    HISTORY_ROUTE -> HistoryView(
                                        onItemClick = { uri, name, count ->
                                            toolInitialPassword = null
                                            openPreview(uri, name, count)
                                        },
                                        folder = folder.history,
                                        onChooseFolder = { openFolder(null) },
                                        onRefresh = { folder.refresh() }
                                    )
                                }
                            }
                            if (currentTool == null) {
                                MainBottomBar(
                                    currentScreen = mainScreens[pagerState.currentPage],
                                    onNavigate = showTab,
                                    onScan = { currentTool = SCAN_ROUTE }
                                )
                            }
                        }

                        if (showToolPicker) {
                            val preloaded = previewData?.takeIf { pickerKeepsFile }
                            ModalBottomSheet(
                                onDismissRequest = { showToolPicker = false },
                                sheetState = sheetState,
                                containerColor = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                            ) {
                                ToolPickerContent(
                                    fileName = preloaded?.second,
                                    onRemoveFile = { pickerKeepsFile = false },
                                    onToolClick = { tool ->
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showToolPicker = false
                                            toolInitialUri = preloaded?.first
                                            toolInitialPassword = null
                                            currentTool = tool
                                        }
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(visible = currentTool != null, enter = fadeIn(), exit = fadeOut()) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                when (val route = currentTool) {
                                    SETTINGS_ROUTE -> SettingsView(
                                        onThemeChange = { theme ->
                                            themePreference = theme
                                            PreferencesManager.setTheme(applicationContext, theme)
                                        },
                                        palette = palette,
                                        onPaletteChange = {
                                            palette = it
                                            PreferencesManager.setPalette(applicationContext, it)
                                        },
                                        onOpenAbout = { currentTool = ABOUT_ROUTE },
                                        onBack = closeTool,
                                        folder = folder.status,
                                        onFolder = { openFolder(SETTINGS_ROUTE) }
                                    )
                                    FOLDER_ROUTE -> FolderSetup(folder, onDone = { currentTool = folderReturn })
                                    ABOUT_ROUTE -> AboutView(onBack = { currentTool = SETTINGS_ROUTE })
                                    PREVIEW_ROUTE -> previewData?.let { (uri, name, count) ->
                                        // Keyed by file, so a new 'Open with' file starts with a fresh reader (no old prompt, scroll or zoom).
                                        key(uri) {
                                            UltraPreview(
                                                uri = uri, fileName = name, pageCount = count,
                                                onDismiss = closeTool,
                                                onOpenInTool = { tool, targetUri, targetPass ->
                                                    toolInitialUri = targetUri
                                                    toolInitialPassword = targetPass
                                                    currentTool = tool
                                                }
                                            )
                                        }
                                    }
                                    SCAN_ROUTE -> {
                                        val leave = {
                                            importUris = emptyList()
                                            closeTool()
                                        }
                                        ScanFlow(onClose = leave, onSaved = leave, importUris = importUris, folder = folder)
                                    }
                                    // Any other route is a tool id; its saved outputs are copied into the folder too (answer 5).
                                    else -> ToolCatalog.byId(route)?.let { tool ->
                                        CompositionLocalProvider(LocalOutputFolder provides outputFolder) {
                                            key(tool.id) { tool.screen(ToolLaunch(toolInitialUri, toolInitialPassword, closeTool, openPreview)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars)
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }
            }
        }
    }
}

/** Home | Scan | History (3c): Scan is the bar's filled rank-1 action; the active tab is the only other accent (3d). */
@Composable
internal fun MainBottomBar(currentScreen: String, onNavigate: (String) -> Unit, onScan: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavTab(Icons.Outlined.Home, stringResource(R.string.nav_home), currentScreen == HOME_ROUTE) { onNavigate(HOME_ROUTE) }
                ScanButton(onScan)
                NavTab(Icons.Outlined.History, stringResource(R.string.nav_history), currentScreen == HISTORY_ROUTE) { onNavigate(HISTORY_ROUTE) }
            }
        }
    }
}

/** A tab of at least 64 x 48 dp; selection shows as accent colour, a bolder label and the tab's selected state. */
@Composable
private fun NavTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalScanPalette.current
    val color = if (selected) palette.ac else palette.nav
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, color = color)
    }
}

/** The Scan pill: at least 116 x 56 dp (T1), filled with the primary role. */
@Composable
private fun ScanButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.widthIn(min = 116.dp).heightIn(min = 56.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.nav_scan), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
