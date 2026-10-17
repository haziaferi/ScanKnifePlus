package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UnlockView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("unlock")
    val accentColor = MaterialTheme.colorScheme.primary

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var decryptedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)

    fun dropDecryptedCopy() {
        deleteDecryptedCopy(context, decryptedUri)
        decryptedUri = null
    }
    DisposableEffect(Unit) { onDispose { deleteDecryptedCopy(context, decryptedUri) } }

    fun handleFileSelection(uri: Uri) {
        dropDecryptedCopy()
        selectedUri = uri
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri, countOwnerPassword = true)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                PdfAccess.Encrypted -> fileToUnlock = fileName
                PdfAccess.Open -> Toast.makeText(context, toolText(context, R.string.unlock_file_is_not_encrypted), Toast.LENGTH_SHORT).show()
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

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.requireInputStream(source).use { input ->
                    PDDocument.load(input, password).use { document ->
                        document.isAllSecurityToBeRemoved = true
                        saveAndFlush(context, document, uri)
                    }
                }
                val timeStr = formatElapsed(startTime)
                val name = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = name
                    outputs.saved(uri, fileName, toolText(context, R.string.unlock_decrypted), Icons.Outlined.LockOpen, pageCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("UnlockView", "Unlock failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show()
                    currentState = ToolState.SELECTING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = stringResource(R.string.tool_unlock),
                    subtitle = stringResource(R.string.unlock_remove_pdf_restrictions),
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = {
                        dropDecryptedCopy()
                        selectedUri = null
                        currentState = ToolState.SELECTING
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") }, 
                            icon = Icons.Outlined.LockOpen,
                            title = stringResource(R.string.tool_pick_pdf),
                            subtitle = stringResource(R.string.unlock_remove_password_protection),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(12.dp))
                            
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(0.75f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.COVER,
                                    password = null, 
                                    accentColor = accentColor
                                )
                            }
                            
                            ToolFileInfo(fileName, stringResource(R.string.tool_file_details, fileSize, pageCount))
                            
                            Spacer(Modifier.height(20.dp))
                            ToolSectionLabel(stringResource(R.string.unlock_document_unlocked))
                            Text(stringResource(R.string.unlock_ready_to_save_without_restrictions), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            
                            Spacer(Modifier.height(32.dp))
                            ToolPrimaryButton(stringResource(R.string.action_save_pdf), onClick = { 
                                    val defaultName = outputFileName(pdfBaseName(fileName) + "-unlocked", "pdf")
                                    saveLauncher.launch(defaultName) 
                                }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = password,
                            text = stringResource(R.string.unlock_decrypting_document),
                            current = 0,
                            total = 0,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.unlock_file_unlocked),
                            subMessage = stringResource(R.string.unlock_password_protection_removed),
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = { 
                                dropDecryptedCopy()
                                selectedUri = null
                                password = ""
                                currentState = ToolState.SELECTING 
                            },
                            onPreview = { outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) } },
                        )
                    }
                }
            }
            
            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = {
                        fileToUnlock = null
                        unlockError = false
                        selectedUri = null
                        currentState = ToolState.SELECTING
                    },
                    onUnlocked = { pass ->
                        val source = selectedUri
                        if (source != null) {
                            isFileLoading = true
                            scope.launch {
                                // Decrypted once to a cache copy, so the cover preview can use the fast native renderer.
                                val copy = decryptToCache(context, source, pass)
                                if (copy != null) {
                                    decryptedUri = copy
                                    password = pass
                                    selectedUri = copy
                                    pageCount = getPageCount(context, copy, null)
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
