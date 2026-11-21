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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
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
    val check = rememberFileCheck()
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    val showLoadingWarning = rememberLoadingWarning(check.running || currentState == ToolState.PROCESSING)

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
        check.start {
            when (inspectPdf(context, uri, countOwnerPassword = true)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                PdfAccess.Encrypted -> fileToUnlock = fileName
                PdfAccess.Open -> Toast.makeText(context, toolText(context, R.string.unlock_file_is_not_encrypted), Toast.LENGTH_SHORT).show()
            }
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
                rewritePdf(context, source, password, uri) {}
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
            } catch (e: Throwable) {
                if (!isToolFailure(e)) throw e
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
            ToolScreenBar(currentState, stringResource(R.string.tool_unlock), stringResource(R.string.unlock_remove_pdf_restrictions), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (check.running) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.LockOpen,
                            title = stringResource(R.string.unlock_remove_password_protection),
                            task = stringResource(R.string.tool_unlock_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            action = stringResource(R.string.action_save_pdf),
                            onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-unlocked", "pdf")) }
                        ) {
                            ToolFileCard(
                                uri = selectedUri!!,
                                password = null,
                                name = fileName,
                                details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                onChange = { dropDecryptedCopy(); selectedUri = null; currentState = ToolState.SELECTING },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            )
                            ToolSectionLabel(stringResource(R.string.unlock_document_unlocked), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
                            Text(
                                stringResource(R.string.unlock_ready_to_save_without_restrictions),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)
                            )
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
                            result = outputUri?.let { ToolResult(it, fileName) },
                            onDone = onBack,
                            onProcessMore = {
                                dropDecryptedCopy()
                                selectedUri = null
                                password = ""
                                currentState = ToolState.SELECTING
                            },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) } }
                        )
                    }
                }
            }
            
            if (fileToUnlock != null) {
                // Cancel, Back, and a file that cannot be read close the prompt and go back to choosing a file.
                fun closePrompt() {
                    fileToUnlock = null
                    unlockError = false
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { check.stop(); closePrompt() },
                    onUnlocked = { pass ->
                        val source = selectedUri
                        if (source != null) {
                            check.start {
                                // Decrypted once to a cache copy, so the cover preview can use the fast native renderer.
                                val decrypted = decryptToCache(context, source, pass)
                                decrypted.resolve(context, onWrongPassword = { unlockError = true }, onUnreadable = { closePrompt() }) { copy ->
                                    decryptedUri = copy.uri
                                    password = pass
                                    selectedUri = copy.uri
                                    pageCount = copy.pageCount
                                    currentState = ToolState.CONFIGURING
                                    fileToUnlock = null
                                }
                            }
                        }
                    },
                    isError = unlockError,
                    accentColor = accentColor,
                    isLoading = check.running
                )
            }
        }
    }
}
