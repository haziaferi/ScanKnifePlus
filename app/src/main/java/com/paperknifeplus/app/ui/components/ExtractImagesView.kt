package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipOutputStream

@Composable
fun ExtractImagesView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("extract-images")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf(initialUri) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var extractedCount by remember { mutableIntStateOf(0) }
    var resultSummary by remember { mutableStateOf("") }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                PdfAccess.Encrypted -> fileToUnlock = fileName
                PdfAccess.Open -> {
                    pageCount = getPageCount(context, uri, null)
                    currentState = ToolState.CONFIGURING
                }
            }
            isFileLoading = false
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val counts = context.contentResolver.requireOutputStream(uri).use { output ->
                    ZipOutputStream(output).use { zip ->
                        context.contentResolver.requireInputStream(source).use { input ->
                            PDDocument.load(input, unlockPassword).use { document ->
                                writeImages(document, zip) { extractedCount = it }
                            }
                        }
                    }
                }
                counts.emptyReason?.let { throw IOException(it) }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    fileName = outputName
                    resultSummary = counts.summary
                    outputs.saved(uri, fileName, "$extractedCount assets", Icons.Filled.Collections)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("ExtractImagesView", "Extraction failed", e)
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
                    title = "Extract Image",
                    subtitle = "EXTRACT ASSETS FROM PDFS",
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = { selectedUri = null; currentState = ToolState.SELECTING }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, "Preparing document...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") },
                            icon = Icons.Outlined.PhotoLibrary,
                            title = "Tap to enter file",
                            subtitle = "EXTRACT ASSETS FROM PDF",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(16.dp))

                            Box(modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(0.707f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.COVER,
                                    password = unlockPassword.ifEmpty { null },
                                    accentColor = accentColor
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            ToolFileInfo(fileName, "$fileSize • $pageCount PAGES")

                            Spacer(Modifier.height(24.dp))
                            ToolSectionLabel("READY TO STRIP ASSETS")
                            Text("Strip and save all raw image files from the document.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Spacer(Modifier.height(32.dp))

                            ToolPrimaryButton("EXTRACT IMAGES (ZIP)", onClick = {
                                    val safeName = outputFileName(pdfBaseName(fileName) + "-assets", "zip")
                                    saveLauncher.launch(safeName)
                                }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(100.dp))
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Stripping image assets...",
                            current = extractedCount,
                            total = 0,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Extraction Complete",
                            subMessage = resultSummary,
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = {
                                selectedUri = null
                                unlockPassword = ""
                                extractedCount = 0
                                currentState = ToolState.SELECTING
                            },
                            showPreviewButton = false,
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
                                val count = getPageCount(context, source, pass)
                                if (count > 0) {
                                    unlockPassword = pass
                                    pageCount = count
                                    currentState = ToolState.CONFIGURING
                                    fileToUnlock = null
                                } else {
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
