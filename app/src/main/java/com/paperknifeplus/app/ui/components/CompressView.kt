package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
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
    val outputs = rememberToolOutputs("compress")
    val accentColor = MaterialTheme.colorScheme.primary

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
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
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
                    outputs.saved(uri, fileName, toolText(context, R.string.compress_optimized), Icons.Filled.FlashOn, finalCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("CompressView", "Compression failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_compress), stringResource(R.string.compress_optimize_pdf_size), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.compress_analyzing_file_structure))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.Bolt,
                            title = stringResource(R.string.compress_optimize_any_pdf_document),
                            task = stringResource(R.string.tool_compress_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            action = stringResource(R.string.compress_compress_and_save_pdf),
                            onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-compressed", "pdf")) }
                        ) {
                            ToolFileCard(
                                uri = selectedUri!!,
                                password = splitPreviewPassword(selectedUri, decryptedUri, unlockPassword),
                                name = fileName,
                                details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                onChange = { dropDecryptedCopy(); selectedUri = null; currentState = ToolState.SELECTING },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            )
                            ToolSectionLabel(
                                stringResource(R.string.compress_optimization_level),
                                Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
                            )
                            Column(
                                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("Basic", "Recommended", "Extreme").forEach { level ->
                                    ToolOptionRow(
                                        selected = compressionLevel == level,
                                        title = stringResource(
                                            when (level) {
                                                "Extreme" -> R.string.compress_level_extreme
                                                "Recommended" -> R.string.compress_level_recommended
                                                else -> R.string.compress_level_basic
                                            }
                                        ),
                                        description = when (level) {
                                            "Extreme" -> stringResource(R.string.compress_max_reduction)
                                            "Recommended" -> stringResource(R.string.compress_great_balance)
                                            else -> stringResource(R.string.compress_maintains_high_visual_fidelity)
                                        },
                                        onClick = { compressionLevel = level }
                                    )
                                }
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = stringResource(R.string.compress_optimizing_document_size),
                            current = progressPage,
                            total = pageCount,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.compress_compression_complete),
                            subMessage = "", // No saved-size line: providers often still report 0 B right after the write.
                            result = outputUri?.let { ToolResult(it, fileName) },
                            onDone = onBack,
                            onProcessMore = {
                                dropDecryptedCopy()
                                selectedUri = null
                                outputUri = null
                                unlockPassword = ""
                                currentState = ToolState.SELECTING
                            },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) } }
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
