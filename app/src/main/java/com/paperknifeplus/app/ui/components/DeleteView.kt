package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.TargetStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeleteView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("delete")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var decryptedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var rangeText by remember { mutableStateOf("") }
    var pagesToDeleteSet by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var progressCount by remember { mutableIntStateOf(0) }
    val check = rememberFileCheck()
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var showRangeInput by remember { mutableStateOf(false) }
    val showLoadingWarning = rememberLoadingWarning(check.running || currentState == ToolState.PROCESSING)

    fun dropDecryptedCopy() {
        deleteDecryptedCopy(context, decryptedUri)
        decryptedUri = null
    }
    DisposableEffect(Unit) { onDispose { deleteDecryptedCopy(context, decryptedUri) } }

    fun showPages(uri: Uri, count: Int) {
        selectedUri = uri
        pageCount = count
        pagesToDeleteSet = emptySet()
        rangeText = ""
        currentState = ToolState.CONFIGURING
    }

    // Cancel, Back, and a file that cannot be read close the prompt and go back to choosing a file.
    fun closePrompt() {
        fileToUnlock = null
        unlockError = false
        dropDecryptedCopy()
        selectedUri = null
        currentState = ToolState.SELECTING
    }

    // An encrypted file is decrypted to a cache copy once, so page previews can use the fast native renderer.
    suspend fun unlock(source: Uri, password: String) {
        val decrypted = decryptToCache(context, source, password)
        decrypted.resolve(context, onWrongPassword = { unlockError = true; fileToUnlock = fileName }, onUnreadable = { closePrompt() }) { copy ->
            decryptedUri = copy.uri
            unlockPassword = password
            showPages(copy.uri, copy.pageCount)
            fileToUnlock = null
        }
    }

    fun handleFileSelection(uri: Uri, password: String? = null) {
        dropDecryptedCopy()
        selectedUri = uri
        sourceUri = uri
        unlockPassword = ""
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
        check.start {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                PdfAccess.Encrypted -> if (password == null) fileToUnlock = fileName else unlock(uri, password)
                PdfAccess.Open -> showPages(uri, getPageCount(context, uri, null))
            }
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it, initialPassword) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = sourceUri
        if (uri == null || selectedUri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val toDelete = pagesToDeleteSet
        val password = unlockPassword
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { progressCount = toDelete.size }
                deletePages(context, source, uri, password, toDelete)
                val timeStr = formatElapsed(startTime)
                val finalCount = getPageCount(context, uri, password.ifEmpty { null })
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = getUriDetails(context, uri).name
                    outputs.saved(uri, fileName, toolText(context, R.string.delete_pages_removed, toDelete.size), Icons.Filled.Delete, finalCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Throwable) {
                if (!isToolFailure(e)) throw e
                Log.w("DeleteView", "Delete failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolFailureText(context, e), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_delete), stringResource(R.string.delete_remove_pages_from_pdf), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (check.running) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Filled.Delete,
                            title = stringResource(R.string.delete_delete_pages_instantly),
                            task = stringResource(R.string.tool_delete_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            action = stringResource(R.string.delete_delete_pages, pagesToDeleteSet.size),
                            onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-cleaned", "pdf")) },
                            actionEnabled = pagesToDeleteSet.isNotEmpty() && pagesToDeleteSet.size < pageCount,
                            scroll = false
                        ) {
                            // The file card and the choices scroll with the pages, so no text size squeezes the grid out (rule 10).
                            UnifiedPdfPreview(
                                uri = selectedUri!!,
                                fileName = fileName,
                                pageCount = pageCount,
                                mode = PreviewMode.GRID,
                                password = null,
                                accentColor = accentColor,
                                selectedPages = pagesToDeleteSet,
                                onToggleSelection = { index ->
                                    val newSet = if (pagesToDeleteSet.contains(index)) pagesToDeleteSet - index else pagesToDeleteSet + index
                                    pagesToDeleteSet = newSet
                                    rangeText = PageRanges.format(newSet)
                                }
                            ) {
                                ToolFileCard(
                                    uri = selectedUri!!,
                                    password = null,
                                    name = fileName,
                                    details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                    onChange = { dropDecryptedCopy(); selectedUri = null; currentState = ToolState.SELECTING },
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                    // Only a file that needs an open password is unlocked with one, and the saved copy keeps it.
                                    keepsPassword = unlockPassword.isNotEmpty()
                                )
                                Column(Modifier.padding(horizontal = 20.dp)) {
                                    ToolStatusRow(
                                        stringResource(R.string.delete_marked_for_deletion, pagesToDeleteSet.size, pageCount),
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                    ) {
                                        TextButton(onClick = { showRangeInput = !showRangeInput }) {
                                            Icon(if (showRangeInput) Icons.Filled.KeyboardArrowUp else Icons.Filled.Create, null, modifier = Modifier.size(16.dp), tint = accentColor)
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (showRangeInput) stringResource(R.string.action_hide_range) else stringResource(R.string.action_enter_range), style = MaterialTheme.typography.labelSmall, color = accentColor)
                                        }
                                    }

                                    // Hidden while the range field is open, to save space.
                                    AnimatedVisibility(visible = !showRangeInput) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).height(IntrinsicSize.Min),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    val all = (0 until pageCount).toSet()
                                                    pagesToDeleteSet = all
                                                    rangeText = PageRanges.format(all)
                                                },
                                                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = TargetStep.ACT.drawn),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text(stringResource(R.string.action_select_all), style = MaterialTheme.typography.labelSmall)
                                            }
                                            Button(
                                                onClick = {
                                                    pagesToDeleteSet = emptySet()
                                                    rangeText = ""
                                                },
                                                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = TargetStep.ACT.drawn),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text(stringResource(R.string.action_clear_all), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    if (showRangeInput) {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Column(Modifier.padding(16.dp)) {
                                                OutlinedTextField(
                                                    value = rangeText,
                                                    onValueChange = {
                                                        rangeText = it
                                                        pagesToDeleteSet = PageRanges.parse(it, pageCount)
                                                    },
                                                    label = { Text(stringResource(R.string.delete_example_2_4_6_9)) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true,
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = accentColor,
                                                        cursorColor = accentColor
                                                    )
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text(stringResource(R.string.delete_tip_pages_selected_in_grid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            text = stringResource(R.string.delete_removing_selected_pages),
                            current = progressCount,
                            total = pagesToDeleteSet.size,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.delete_delete_complete),
                            subMessage = stringResource(R.string.delete_selected_pages_were_removed),
                            result = outputUri?.let { ToolResult(it, fileName, password = unlockPassword.ifEmpty { null }) },
                            onDone = onBack,
                            onProcessMore = {
                                dropDecryptedCopy()
                                selectedUri = null
                                outputUri = null
                                unlockPassword = ""
                                currentState = ToolState.SELECTING
                            },
                            onOpen = {
                                outputUri?.let { uri ->
                                    scope.launch {
                                        val count = getPageCount(context, uri, null)
                                        onOpenPreview(uri, fileName, count)
                                    }
                                }
                            },
                            keepsPassword = unlockPassword.isNotEmpty()
                        )
                    }
                }
            }

            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { check.stop(); closePrompt() },
                    onUnlocked = { pass -> selectedUri?.let { source -> check.start { unlock(source, pass) } } },
                    isError = unlockError,
                    accentColor = accentColor,
                    isLoading = check.running
                )
            }
        }
    }
}
