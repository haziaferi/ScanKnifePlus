package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CompressView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalIsDarkTheme.current
    val accentColor = Color(0xFFF59E0B)

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var decryptedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }

    var compressionLevel by remember { mutableStateOf("Recommended") }
    var pageCount by remember { mutableIntStateOf(0) }
    var progressPage by remember { mutableIntStateOf(0) }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)

    fun dropDecryptedCopy() {
        deleteDecryptedCopy(context, decryptedUri)
        decryptedUri = null
    }
    DisposableEffect(Unit) { onDispose { deleteDecryptedCopy(context, decryptedUri) } }

    fun handleFileSelection(uri: Uri, password: String? = null) {
        dropDecryptedCopy()
        selectedUri = uri
        unlockPassword = password.orEmpty()
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
        isFileLoading = true
        scope.launch {
            val access = inspectPdf(context, uri)
            when {
                access == PdfAccess.Unreadable -> {
                    Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                needsUnlockPrompt(access, password) -> fileToUnlock = fileName
                else -> {
                    pageCount = getPageCount(context, uri, password)
                    currentState = ToolState.CONFIGURING
                }
            }
            isFileLoading = false
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it, initialPassword) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                compressPdf(context, source, uri, unlockPassword.ifEmpty { null }, compressionLevel) { current, _ ->
                    progressPage = current
                }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                val finalCount = getPageCount(context, uri, null)
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = outputName
                    SessionManager.addEntry(fileName, "Compress", "Optimized", Icons.Filled.FlashOn, uri, finalCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("CompressView", "Compression failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = "Compress",
                    subtitle = "OPTIMIZE PDF SIZE",
                    accent = accentColor,
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = { dropDecryptedCopy(); selectedUri = null; currentState = ToolState.SELECTING }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isFileLoading) {
                LoadingStateView(accentColor, showLoadingWarning, "Analyzing file structure...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") },
                            isDark = isDark,
                            icon = Icons.Outlined.Bolt,
                            title = "Tap to enter file",
                            subtitle = "OPTIMIZE ANY PDF DOCUMENT",
                            accentColor = accentColor,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(16.dp))

                            Box(modifier = Modifier.fillMaxWidth(0.65f).aspectRatio(0.707f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.COVER,
                                    password = splitPreviewPassword(selectedUri, decryptedUri, unlockPassword),
                                    accentColor = accentColor
                                )
                            }

                            Spacer(Modifier.height(16.dp))
                            Text(fileName, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text("$fileSize • $pageCount PAGES", fontSize = 10.sp, color = Color.Gray)

                            Spacer(Modifier.height(24.dp))
                            Text("OPTIMIZATION LEVEL", fontSize = 9.sp, fontWeight = FontWeight.Black, color = accentColor, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(12.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Basic", "Recommended", "Extreme").forEach { level ->
                                    val isSelected = compressionLevel == level
                                    Surface(
                                        onClick = { compressionLevel = level },
                                        color = if (isSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, if (isSelected) accentColor else Color.Gray.copy(0.1f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = accentColor))
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text(level, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text(
                                                    text = when(level) {
                                                        "Extreme" -> "Max reduction, lower image quality"
                                                        "Recommended" -> "Great balance of size and quality"
                                                        else -> "Maintains high visual fidelity"
                                                    },
                                                    fontSize = 10.sp, color = Color.Gray
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(32.dp))
                            Button(
                                onClick = {
                                    val defaultName = pdfBaseName(fileName) + "-compressed.pdf"
                                    saveLauncher.launch(defaultName)
                                },
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                            ) {
                                Text("COMPRESS & SAVE PDF", fontWeight = FontWeight.Black, color = Color.White)
                            }
                            Spacer(Modifier.height(100.dp))
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            accentColor = accentColor,
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Optimizing document size...",
                            current = progressPage,
                            total = pageCount,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Compression Complete",
                            subMessage = "", // No saved-size line: providers often still report 0 B right after the write.
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = {
                                dropDecryptedCopy()
                                selectedUri = null
                                outputUri = null
                                unlockPassword = ""
                                currentState = ToolState.SELECTING
                            },
                            onPreview = {
                                outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) }
                            },
                            accentColor = accentColor
                        )
                    }
                }
            }

            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { fileToUnlock = null; unlockError = false; selectedUri = null; currentState = ToolState.SELECTING },
                    onUnlocked = { pass ->
                        val source = selectedUri
                        if (source != null) {
                            isFileLoading = true
                            scope.launch {
                                // Decrypted once to a cache copy, so the preview can use the fast native renderer.
                                val copy = decryptToCache(context, source, pass)
                                val count = copy?.let { getPageCount(context, it, null) } ?: 0
                                if (copy != null && count > 0) {
                                    decryptedUri = copy
                                    unlockPassword = pass
                                    selectedUri = copy
                                    pageCount = count
                                    currentState = ToolState.CONFIGURING
                                    fileToUnlock = null
                                } else {
                                    deleteDecryptedCopy(context, copy)
                                    unlockError = true
                                }
                                isFileLoading = false
                            }
                        }
                    },
                    isError = unlockError,
                    accentColor = accentColor,
                    isLoading = isFileLoading
                )
            }
        }
    }
}
