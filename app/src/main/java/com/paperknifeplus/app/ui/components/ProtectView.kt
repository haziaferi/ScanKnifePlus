package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProtectView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("protect")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var protectPassword by remember { mutableStateOf("") }
    // The password the saved output was encrypted with, so its result card can draw the first page; kept in memory only.
    var savedPassword by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)

    fun handleFileSelection(uri: Uri) {
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

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch {
            try {
                protectPdf(context, source, uri, unlockPassword.ifEmpty { null }, protectPassword)
                processingTime = formatElapsed(startTime)
                outputUri = uri
                savedPassword = protectPassword
                fileName = withContext(Dispatchers.IO) { getUriDetails(context, uri).name }
                outputs.saved(uri, fileName, toolText(context, R.string.protect_encrypted), Icons.Outlined.Lock, pageCount)
                currentState = ToolState.SUCCESS
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                // Includes IllegalArgumentException from SASLprep for passwords with prohibited characters.
                Log.w("ProtectView", "Protect failed", e)
                deleteCreatedDocument(context, uri)
                Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show()
                currentState = ToolState.CONFIGURING
            }
        }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_protect), stringResource(R.string.protect_encrypt_your_document), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.Security,
                            title = stringResource(R.string.protect_protect_any_pdf_document),
                            task = stringResource(R.string.tool_protect_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            action = stringResource(R.string.protect_save),
                            onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-protected", "pdf")) },
                            actionEnabled = protectPassword.isNotBlank()
                        ) {
                            ToolFileCard(
                                uri = selectedUri!!,
                                password = unlockPassword.ifEmpty { null },
                                name = fileName,
                                details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                onChange = { selectedUri = null; currentState = ToolState.SELECTING },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            )
                            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
                                ToolSectionLabel(stringResource(R.string.protect_set_protection))
                                Spacer(Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = protectPassword,
                                    onValueChange = { protectPassword = it },
                                    label = { Text(stringResource(R.string.protect_new_password), fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = TextFieldDefaults.colors(
                                        focusedIndicatorColor = accentColor,
                                        cursorColor = accentColor,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent
                                    )
                                )

                                Spacer(Modifier.height(12.dp))
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            stringResource(R.string.protect_no_recovery, stringResource(R.string.app_name)),
                                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = stringResource(R.string.protect_applying_encryption_policy),
                            current = 0,
                            total = 0,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.protect_protect_complete),
                            subMessage = stringResource(R.string.protect_document_encrypted_with_password),
                            result = outputUri?.let { ToolResult(it, fileName, password = savedPassword) },
                            onDone = onBack,
                            onProcessMore = {
                                selectedUri = null
                                unlockPassword = ""
                                protectPassword = ""
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
