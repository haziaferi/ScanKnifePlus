package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun DeleteView(
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
    var pagesToDeleteSet by remember { mutableStateOf<Set<Int>>(emptySet()) }

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

    fun showPages(uri: Uri, count: Int) {
        selectedUri = uri
        pageCount = count
        pagesToDeleteSet = emptySet()
        rangeText = ""
        currentState = ToolState.CONFIGURING
    }

    // An encrypted file is decrypted to a cache copy once, so page previews can use the fast native renderer.
    fun unlock(source: Uri, password: String) {
        isFileLoading = true
        scope.launch {
            val copy = decryptToCache(context, source, password)
            if (copy != null) {
                decryptedUri = copy
                unlockPassword = password
                showPages(copy, getPageCount(context, copy, null))
                fileToUnlock = null
            } else {
                unlockError = true
                fileToUnlock = fileName
            }
            isFileLoading = false
        }
    }

    fun handleFileSelection(uri: Uri, password: String? = null) {
        dropDecryptedCopy()
        selectedUri = uri
        fileName = getUriDetails(context, uri).name
        unlockError = false
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                    isFileLoading = false
                }
                PdfAccess.Encrypted -> if (password == null) {
                    fileToUnlock = fileName
                    isFileLoading = false
                } else {
                    unlock(uri, password)
                }
                PdfAccess.Open -> {
                    showPages(uri, getPageCount(context, uri, null))
                    isFileLoading = false
                }
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
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val toDelete = pagesToDeleteSet
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { progressCount = 0 }
                context.contentResolver.requireInputStream(source).use { input ->
                    PDDocument.load(input, unlockPassword).use { document ->
                        PDDocument().use { target ->
                            val kept = mutableListOf<Int>()
                            for (i in 0 until document.numberOfPages) {
                                if (i in toDelete) withContext(Dispatchers.Main) { progressCount++ } else kept += i
                            }
                            target.appendPagesFrom(kept.map { document.getPage(it) })
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
                    SessionManager.addEntry(fileName, "Delete", "${toDelete.size} pages removed", Icons.Filled.Delete, uri, finalCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("DeleteView", "Delete failed", e)
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
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Delete", fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                        Text("REMOVE PAGES FROM PDF", fontSize = 8.sp, fontWeight = FontWeight.Black, color = accentColor, letterSpacing = 1.sp)
                    }
                }
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
                            icon = Icons.Filled.Delete,
                            title = "Tap to enter file",
                            subtitle = "DELETE PAGES INSTANTLY",
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
                                    Text("${pagesToDeleteSet.size} / $pageCount MARKED FOR DELETION", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accentColor)
                                }
                                
                                TextButton(onClick = { showRangeInput = !showRangeInput }) {
                                    Icon(if (showRangeInput) Icons.Filled.KeyboardArrowUp else Icons.Filled.Create, null, modifier = Modifier.size(16.dp), tint = accentColor)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (showRangeInput) "HIDE RANGE" else "ENTER RANGE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = accentColor)
                                }
                            }

                            // Hidden while the range field is open, to save space.
                            AnimatedVisibility(visible = !showRangeInput) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { 
                                            val all = (0 until pageCount).toSet()
                                            pagesToDeleteSet = all
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
                                            pagesToDeleteSet = emptySet()
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
                                                pagesToDeleteSet = PageRanges.parse(it, pageCount)
                                            },
                                            label = { Text("Example: 2, 4-6, 9") },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = accentColor,
                                                cursorColor = accentColor
                                            )
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text("Tip: Pages selected in grid will be removed.", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
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
                                )
                            }
                            
                            Button(
                                onClick = { 
                                    val defaultName = pdfBaseName(fileName) + "-cleaned.pdf"
                                    saveLauncher.launch(defaultName) 
                                }, 
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).height(60.dp), 
                                enabled = pagesToDeleteSet.isNotEmpty() && pagesToDeleteSet.size < pageCount,
                                shape = RoundedCornerShape(20.dp), 
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                            ) {
                                Text("DELETE ${pagesToDeleteSet.size} PAGES", fontWeight = FontWeight.Black, color = Color.White)
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            accentColor = accentColor,
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Removing selected pages...",
                            current = progressCount,
                            total = pagesToDeleteSet.size,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Delete Complete",
                            subMessage = "Selected pages were removed",
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
                        dropDecryptedCopy()
                        selectedUri = null
                        currentState = ToolState.SELECTING
                    },
                    onUnlocked = { pass -> selectedUri?.let { unlock(it, pass) } },
                    isError = unlockError,
                    accentColor = accentColor,
                    isLoading = isFileLoading
                )
            }
        }
    }
}
