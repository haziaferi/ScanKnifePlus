package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RearrangeView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("rearrange")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var decryptedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var pageOrder by remember { mutableStateOf<List<Int>>(emptyList()) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }

    var selectedPageIndex by remember { mutableStateOf<Int?>(null) }
    var showMoveToDialog by remember { mutableStateOf(false) }
    var moveToInput by remember { mutableStateOf("") }

    fun dropDecryptedCopy() {
        deleteDecryptedCopy(context, decryptedUri)
        decryptedUri = null
    }
    DisposableEffect(Unit) { onDispose { deleteDecryptedCopy(context, decryptedUri) } }

    fun handleFileSelection(uri: Uri, password: String? = null) {
        dropDecryptedCopy()
        selectedUri = uri
        sourceUri = uri
        unlockPassword = password.orEmpty()
        fileName = getUriDetails(context, uri).name
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
                    pageOrder = (0 until getPageCount(context, uri, password)).toList()
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
        val source = sourceUri
        if (uri == null || selectedUri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val order = pageOrder
        val password = unlockPassword
        scope.launch(Dispatchers.IO) {
            try {
                rearrangePages(context, source, uri, password, order)
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = outputName
                    outputs.saved(uri, fileName, "Reordered pages", Icons.Filled.GridView, order.size)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("RearrangeView", "Rearranging failed", e)
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
                    title = "Rearrange",
                    subtitle = "MOVE PAGES TO NEW SLOTS",
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = { dropDecryptedCopy(); selectedUri = null; currentState = ToolState.SELECTING }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isFileLoading) {
                LoadingStateView(false, "Preparing Rearrange Grid...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") },
                            icon = Icons.Filled.SwapVert,
                            title = "Tap to enter file",
                            subtitle = "REORDER PAGES RELIABLY",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(fileName, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    ToolStatusText("${pageOrder.size} PAGES • TAP TO SELECT")
                                }
                                IconButton(onClick = { dropDecryptedCopy(); selectedUri = null; currentState = ToolState.SELECTING; selectedPageIndex = null }) {
                                    Icon(Icons.Filled.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageOrder.size,
                                    mode = PreviewMode.REORDER,
                                    password = splitPreviewPassword(selectedUri, decryptedUri, unlockPassword),
                                    accentColor = accentColor,
                                    pageOrder = pageOrder,
                                    selectedPages = selectedPageIndex?.let { setOf(it) } ?: emptySet(),
                                    onToggleSelection = { index ->
                                        selectedPageIndex = if (selectedPageIndex == index) null else index
                                    }
                                )
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            selectedPageIndex?.let { current ->
                                                if (current > 0) {
                                                    val newList = pageOrder.toMutableList()
                                                    val item = newList.removeAt(current)
                                                    newList.add(current - 1, item)
                                                    pageOrder = newList
                                                    selectedPageIndex = current - 1
                                                }
                                            }
                                        },
                                        enabled = selectedPageIndex != null && selectedPageIndex!! > 0
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Move Back", tint = if (selectedPageIndex != null && selectedPageIndex!! > 0) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    Button(
                                        onClick = { showMoveToDialog = true },
                                        enabled = selectedPageIndex != null,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("MOVE TO...", fontSize = 12.sp, fontWeight = FontWeight.Black)
                                    }

                                    IconButton(
                                        onClick = {
                                            selectedPageIndex?.let { current ->
                                                if (current < pageOrder.size - 1) {
                                                    val newList = pageOrder.toMutableList()
                                                    val item = newList.removeAt(current)
                                                    newList.add(current + 1, item)
                                                    pageOrder = newList
                                                    selectedPageIndex = current + 1
                                                }
                                            }
                                        },
                                        enabled = selectedPageIndex != null && selectedPageIndex!! < pageOrder.size - 1
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, "Move Forward", tint = if (selectedPageIndex != null && selectedPageIndex!! < pageOrder.size - 1) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            Button(
                                onClick = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-reordered", "pdf")) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).heightIn(min = 56.dp),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(text = "SAVE NEW ORDER", fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = splitPreviewPassword(selectedUri, decryptedUri, unlockPassword),
                            text = "Applying new order...",
                            current = 0,
                            total = 0,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Rearrange Complete",
                            subMessage = "Page order updated successfully",
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = {
                                dropDecryptedCopy()
                                selectedUri = null
                                selectedPageIndex = null
                                currentState = ToolState.SELECTING
                            },
                            onPreview = {
                                outputUri?.let { uri -> onOpenPreview(uri, fileName, pageOrder.size) }
                            },
                        )
                    }
                }
            }

            if (showMoveToDialog) {
                AlertDialog(
                    onDismissRequest = { showMoveToDialog = false },
                    title = { Text("Move Page to Position", fontWeight = FontWeight.Black) },
                    text = {
                        Column {
                            Text("Current position: ${selectedPageIndex!! + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = moveToInput,
                                onValueChange = { if (it.all { c -> c.isDigit() }) moveToInput = it },
                                label = { Text("Target Page (1-${pageOrder.size})") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val target = moveToInput.toIntOrNull()?.let { it - 1 }
                            if (target != null && target in 0 until pageOrder.size && selectedPageIndex != null) {
                                val newList = pageOrder.toMutableList()
                                val item = newList.removeAt(selectedPageIndex!!)
                                newList.add(target, item)
                                pageOrder = newList
                                selectedPageIndex = target
                                showMoveToDialog = false
                                moveToInput = ""
                            } else {
                                Toast.makeText(context, "Invalid Page Number", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("MOVE", color = accentColor, fontWeight = FontWeight.Black) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showMoveToDialog = false; moveToInput = "" }) { Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                )
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
                                // Decrypted once to a cache copy, so page previews can use the fast native renderer.
                                val copy = decryptToCache(context, source, pass)
                                if (copy != null) {
                                    val count = getPageCount(context, copy, null)
                                    decryptedUri = copy
                                    unlockPassword = pass
                                    selectedUri = copy
                                    pageOrder = (0 until count).toList()
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
