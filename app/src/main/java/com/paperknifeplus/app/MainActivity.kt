package com.paperknifeplus.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.paperknifeplus.app.ui.components.*
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.PaperPink
import com.paperknifeplus.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        enableEdgeToEdge()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        // Only on a fresh start: after recreation getIntent() still holds the last 'Open with' intent, and handling it again would reopen a
        // reader the user may have closed; an open reader comes back from saved state instead.
        if (savedInstanceState == null) {
            acceptIncoming(intent)
            // Only stale copies: another window of the app may still be working on a fresh one.
            lifecycleScope.launch(Dispatchers.IO) { sweepDecryptedCopies(cacheDir, minAgeMs = STALE_COPY_AGE_MS) }
        }

        setContent {
            var themePreference by remember { mutableIntStateOf(PreferencesManager.getTheme(applicationContext)) }
            var palette by remember { mutableStateOf(PreferencesManager.getPalette(applicationContext)) }
            val themeMode = ThemeMode.fromStored(themePreference)
            val isDarkMode = themeMode.isOled(isSystemInDarkTheme())

            PaperKnifePlusTheme(palette = palette, themeMode = themeMode) {
                val sheetState = rememberModalBottomSheetState()
                var showToolPicker by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                Box(Modifier.fillMaxSize()) {
                    var currentTool by rememberSaveable { mutableStateOf<String?>(null) }
                    var previewData by rememberSaveable(stateSaver = PreviewDataSaver) { mutableStateOf<Triple<Uri, String, Int>?>(null) }
                    var toolInitialUri by rememberSaveable { mutableStateOf<Uri?>(null) }
                    // Not saveable: saved state may be written to disk, and a password must not be.
                    var toolInitialPassword by remember { mutableStateOf<String?>(null) }

                    val mainScreens = listOf("home", "tools", "history", "settings")
                    val pagerState = androidx.compose.foundation.pager.rememberPagerState { mainScreens.size }

                    val openPreview: (Uri, String, Int) -> Unit = { uri, name, count ->
                        previewData = Triple(uri, name, count)
                        currentTool = "ultra_preview"
                    }
                    val closeTool = { currentTool = null }

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
                        if (showToolPicker) {
                            scope.launch { sheetState.hide() }.invokeOnCompletion { showToolPicker = false }
                        } else if (currentTool != null) {
                            currentTool = null
                        } else if (pagerState.currentPage != 0) {
                            scope.launch { pagerState.animateScrollToPage(0) }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .navigationBarsPadding()
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(bottom = 84.dp)) {
                            androidx.compose.foundation.pager.HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.weight(1f),
                                userScrollEnabled = currentTool == null,
                                beyondViewportPageCount = 0
                            ) { page ->
                                when (mainScreens[page]) {
                                    "home" -> HomeView(
                                        isDarkMode = isDarkMode,
                                        onThemeToggle = {
                                            themePreference = if (isDarkMode) ThemeMode.LIGHT.stored else ThemeMode.OLED.stored
                                            PreferencesManager.setTheme(applicationContext, themePreference)
                                        },
                                        onToolClick = {
                                            toolInitialUri = null
                                            if (it == "tools") {
                                                scope.launch { pagerState.animateScrollToPage(1) }
                                            } else {
                                                currentTool = it
                                            }
                                        },
                                        onOpenPreview = openPreview
                                    )
                                    "tools" -> ToolsView(onToolClick = {
                                        toolInitialUri = null
                                        toolInitialPassword = null
                                        currentTool = it
                                    })
                                    "history" -> HistoryView(onItemClick = { uri, name, count ->
                                        toolInitialPassword = null
                                        openPreview(uri, name, count)
                                    })
                                    "settings" -> SettingsView(
                                        isDarkMode = isDarkMode,
                                        onThemeChange = { theme ->
                                            themePreference = theme
                                            PreferencesManager.setTheme(applicationContext, theme)
                                        },
                                        palette = palette,
                                        onPaletteChange = {
                                            palette = it
                                            PreferencesManager.setPalette(applicationContext, it)
                                        },
                                        onOpenAbout = { currentTool = "about" }
                                    )
                                }
                            }
                        }

                        if (currentTool == null) {
                            FixedTitanBottomBar(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter),
                                currentScreen = mainScreens[pagerState.currentPage],
                                onNavigate = { screen ->
                                    val index = mainScreens.indexOf(screen)
                                    if (index != -1) {
                                        scope.launch { pagerState.scrollToPage(index) }
                                    }
                                },
                                onPlusClick = { showToolPicker = true }
                            )
                        }

                        if (showToolPicker) {
                            ModalBottomSheet(
                                onDismissRequest = { showToolPicker = false },
                                sheetState = sheetState,
                                containerColor = MaterialTheme.colorScheme.surface,
                                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray.copy(0.2f)) },
                                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                            ) {
                                ToolPickerContent(
                                    onToolClick = { tool ->
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showToolPicker = false
                                            toolInitialUri = previewData?.first
                                            toolInitialPassword = null
                                            currentTool = tool
                                        }
                                    }
                                )
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = currentTool != null,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut()
                        ) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                when (currentTool) {
                                    "about" -> AboutView(onBack = closeTool)
                                    "merge" -> MergeView(initialUri = toolInitialUri, initialPassword = toolInitialPassword, onBack = closeTool, onOpenPreview = openPreview)
                                    "split" -> SplitView(initialUri = toolInitialUri, initialPassword = toolInitialPassword, onBack = closeTool, onOpenPreview = openPreview)
                                    "delete" -> DeleteView(initialUri = toolInitialUri, initialPassword = toolInitialPassword, onBack = closeTool, onOpenPreview = openPreview)
                                    "compress" -> CompressView(initialUri = toolInitialUri, initialPassword = toolInitialPassword, onBack = closeTool, onOpenPreview = openPreview)
                                    "repair" -> RepairView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "rotate" -> RotateView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "rearrange" -> RearrangeView(initialUri = toolInitialUri, initialPassword = toolInitialPassword, onBack = closeTool, onOpenPreview = openPreview)
                                    "protect" -> ProtectView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "unlock" -> UnlockView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "grayscale" -> GrayscaleView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "metadata" -> MetadataView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "img2pdf" -> ImageToPdfView(onBack = closeTool, onOpenPreview = openPreview)
                                    "pdf2img" -> PdfToImageView(initialUri = toolInitialUri, onBack = closeTool)
                                    "pdf2text" -> PdfToTextView(initialUri = toolInitialUri, onBack = closeTool)
                                    "pdf2zip" -> PdfToZipView(initialUri = toolInitialUri, onBack = closeTool)
                                    "sign" -> SignView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "watermark" -> WatermarkView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "page-numbers" -> PageNumbersView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "bookmarks" -> BookmarksView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "extract-images" -> ExtractImagesView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "compare" -> CompareView(initialUri = toolInitialUri, onBack = closeTool, onOpenPreview = openPreview)
                                    "ultra_preview" -> {
                                        previewData?.let { (uri, name, count) ->
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
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FixedTitanBottomBar(
    modifier: Modifier = Modifier,
    currentScreen: String,
    onNavigate: (String) -> Unit,
    onPlusClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
            .height(84.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .offset(y = (-32).dp)
                .size(40.dp)
                .background(Brush.radialGradient(listOf(PaperPink.copy(alpha = 0.4f), Color.Transparent)))
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 8.dp
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxSize().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NavItem(Icons.Outlined.Home, "Home", currentScreen == "home") { onNavigate("home") }
                        NavItem(Icons.Outlined.GridView, "Tools", currentScreen == "tools") { onNavigate("tools") }
                    }

                    Spacer(modifier = Modifier.width(72.dp))

                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NavItem(Icons.Outlined.History, "History", currentScreen == "history") { onNavigate("history") }
                        NavItem(Icons.Outlined.Settings, "Settings", currentScreen == "settings" || currentScreen == "about") { onNavigate("settings") }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .offset(y = (-34).dp)
                .size(58.dp)
                .border(2.5.dp, if (LocalIsDarkTheme.current) Color.Black else Color.White, RoundedCornerShape(20.dp))
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(20.dp), spotColor = PaperPink)
                .clickable { onPlusClick() },
            shape = RoundedCornerShape(20.dp),
            color = PaperPink
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) PaperPink else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
            color = if (selected) PaperPink else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            letterSpacing = 0.2.sp
        )
    }
}
