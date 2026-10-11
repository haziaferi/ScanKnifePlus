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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SplitView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalIsDarkTheme.current
    val accentColor = Color(0xFFF43F5E)

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var decryptedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var rangeText by remember { mutableStateOf("") }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var fileName by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var progressCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var showRangeInput by remember { mutableStateOf(false) }
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
                    pageCount = getPageCount(context, uri, password)
                    selectedPages = emptySet()
                    rangeText = ""
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
        val pages = selectedPages
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { progressCount = 0 }
                context.contentResolver.requireInputStream(source).use { input ->
                    PDDocument.load(input, unlockPassword).use { document ->
                        PDDocument().use { target ->
                            pages.sorted().forEach { index ->
                                if (index < document.numberOfPages) {
                                    target.appendPageFrom(document.getPage(index))
                                    withContext(Dispatchers.Main) { progressCount++ }
                                }
                            }
                            saveAndFlush(context, target, uri)
                        }
                    }
                }
                val timeStr = formatElapsed(startTime)
                val finalCount = getPageCount(context, uri, null)
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = getUriDetails(context, uri).name
                    SessionManager.addEntry(fileName, "Split", "${pages.size} pages extracted", Icons.Filled.ContentCut, uri, finalCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("SplitView", "Split failed", e)
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
                    title = "Split",
                    subtitle = "EXTRACT PAGES FROM PDF",
                    accent = accentColor,
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = {
                        dropDecryptedCopy()
                        unlockPassword = ""
                        selectedUri = null
                        currentState = ToolState.SELECTING
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(accentColor, showLoadingWarning, "Preparing document...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") }, 
                            isDark = isDark,
                            icon = Icons.Filled.ContentCut,
                            title = "Tap to enter file",
                            subtitle = "SPLIT PDF INTO PARTS",
                            accentColor = accentColor,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(fileName, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
                                    Text("${selectedPages.size} / $pageCount PAGES SELECTED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accentColor)
                                }
                                
                                TextButton(onClick = { showRangeInput = !showRangeInput }) {
                                    Icon(if (showRangeInput) Icons.Filled.KeyboardArrowUp else Icons.Filled.Create, null, modifier = Modifier.size(16.dp), tint = accentColor)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (showRangeInput) "HIDE RANGE" else "ENTER RANGE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = accentColor)
                                }
                            }

                            AnimatedVisibility(visible = !showRangeInput) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { 
                                            val all = (0 until pageCount).toSet()
                                            selectedPages = all
                                            rangeText = PageRanges.format(all)
                                        },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = accentColor.copy(alpha = 0.1f), contentColor = accentColor),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("SELECT ALL", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    }
                                    Button(
                                        onClick = { 
                                            selectedPages = emptySet()
                                            rangeText = ""
                                        },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.1f), contentColor = Color.Gray),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("CLEAR ALL", fontSize = 10.sp, fontWeight = FontWeight.Black)
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
                                                selectedPages = PageRanges.parse(it, pageCount)
                                            },
                                            label = { Text("Example: 1-5, 8, 11-13") },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = accentColor,
                                                cursorColor = accentColor
                                            )
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text("Tip: Use commas for lists and dashes for spans.", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.GRID,
                                    password = splitPreviewPassword(selectedUri, decryptedUri, unlockPassword),
                                    accentColor = accentColor,
                                    selectedPages = selectedPages,
                                    onToggleSelection = { index ->
                                        val newSet = if (selectedPages.contains(index)) selectedPages - index else selectedPages + index
                                        selectedPages = newSet
                                        rangeText = PageRanges.format(newSet)
                                    }
                                )
                            }
                            
                            Button(
                                onClick = { saveLauncher.launch(pdfBaseName(fileName) + "-split.pdf") }, 
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).height(60.dp), 
                                enabled = selectedPages.isNotEmpty(),
                                shape = RoundedCornerShape(20.dp), 
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("EXTRACT ${selectedPages.size} PAGES", fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            accentColor = accentColor,
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Extracting specified pages...",
                            current = progressCount,
                            total = selectedPages.size,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Split Complete",
                            subMessage = "Selected pages saved successfully",
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
                                outputUri?.let { uri ->
                                    scope.launch {
                                        val count = getPageCount(context, uri, null)
                                        onOpenPreview(uri, fileName, count)
                                    }
                                }
                            },
                            accentColor = accentColor
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
                                // Decrypted once to a cache copy, so page previews can use the fast native renderer.
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

/**
 * The password page previews need: none for the decrypted cache copy (so the fast native renderer is used), and the unlock password
 * when Split works on the original encrypted file, as it does when the preview hands over a password.
 */
internal fun splitPreviewPassword(selectedUri: Uri?, decryptedUri: Uri?, unlockPassword: String): String? =
    if (selectedUri != null && selectedUri == decryptedUri) null else unlockPassword.ifEmpty { null }
