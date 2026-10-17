package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

@Composable
fun BookmarksView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("bookmarks")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var outputPageCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var unlockPassword by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }

    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun reset() {
        selectedUri = null
        unlockPassword = ""
        bookmarks = emptyList()
        currentState = ToolState.SELECTING
    }

    /** Reads the outline with [pass]; false if the document does not open with it. An unreadable outline is reported and resets. */
    suspend fun extractBookmarks(uri: Uri, pass: String?): Boolean = when (val result = loadOutline(context, uri, pass)) {
        is OutlineLoad.Loaded -> {
            bookmarks = result.bookmarks
            pageCount = result.pageCount
            unlockPassword = pass.orEmpty()
            currentState = ToolState.CONFIGURING
            true
        }
        OutlineLoad.WrongPassword -> false
        OutlineLoad.Unreadable -> {
            Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
            fileToUnlock = null
            reset()
            true
        }
    }

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        fileName = getUriDetails(context, uri).name
        unlockPassword = ""
        unlockError = false
        isFileLoading = true
        scope.launch {
            val access = inspectPdf(context, uri)
            // Files that open without a password (owner-password-only ones too) are read directly; the others ask for one.
            if (access == PdfAccess.Unreadable) {
                Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
                selectedUri = null
                currentState = ToolState.SELECTING
            } else if (!extractBookmarks(uri, null)) {
                fileToUnlock = fileName
            }
            isFileLoading = false
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    /** Runs [write] on the freshly loaded source into [saveUri], then records the result as [summary]. */
    fun save(saveUri: Uri, failurePrefix: String, summary: (Int) -> String, write: (source: PDDocument, saveUri: Uri) -> Unit) {
        val source = selectedUri ?: return
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.requireInputStream(source).use { input ->
                    PDDocument.load(input, unlockPassword).use { document ->
                        if (document.isEncrypted) document.isAllSecurityToBeRemoved = true
                        write(document, saveUri)
                    }
                }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, saveUri).name
                val finalCount = getPageCount(context, saveUri, null)
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = saveUri
                    fileName = outputName
                    outputPageCount = finalCount
                    outputs.saved(saveUri, outputName, summary(finalCount), Icons.Filled.BookmarkBorder, finalCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, saveUri)
                throw e
            } catch (e: Exception) {
                Log.w("BookmarksView", "Saving failed", e)
                deleteCreatedDocument(context, saveUri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "$failurePrefix${e.message}", Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    val extractLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val pages = bookmarks.mapNotNull { it.pageIndex }.distinct().sorted()
        if (uri != null) save(uri, "Extraction failed: ", { "$it pages extracted" }) { document, saveUri ->
            PDDocument().use { target ->
                target.appendPagesFrom(pages.filter { it < document.numberOfPages }.map { document.getPage(it) })
                saveAndFlush(context, target, saveUri)
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val edited = bookmarks
        if (uri != null) save(uri, "Error: ", { "${edited.size} bookmarks" }) { document, saveUri ->
            applyOutline(document, edited)
            saveAndFlush(context, document, saveUri)
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = "Bookmarks",
                    subtitle = "EDIT DOCUMENT NAVIGATION",
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = ::reset
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isFileLoading) {
                LoadingStateView(false, "Reading outlines...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") }, 
                            icon = Icons.Filled.BookmarkBorder,
                            title = "Tap to enter file",
                            subtitle = "MANAGE PDF BOOKMARKS",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                ToolStatusText("${bookmarks.size} BOOKMARKS")
                                Button(onClick = { showAddDialog = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("ADD", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (bookmarks.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No bookmarks found", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemsIndexed(bookmarks) { index, bm ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                                                Spacer(Modifier.width(16.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(bm.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    Text("Page ${bm.pageIndex?.let { it + 1 } ?: "-"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = { bookmarks = bookmarks.withoutBranch(index) }) {
                                                    Icon(Icons.Filled.Delete, "Delete ${bm.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ToolPrimaryButton("SAVE PDF", onClick = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-nav", "pdf")) }, modifier = Modifier.weight(1f))
                                ToolSecondaryButton(
                                    "EXTRACT PAGES",
                                    onClick = { extractLauncher.launch(outputFileName(pdfBaseName(fileName) + "-extract", "pdf")) },
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    enabled = bookmarks.isNotEmpty()
                                )
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Updating document...",
                            current = 0,
                            total = 0,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Task Complete",
                            subMessage = "Document updated successfully",
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = ::reset,
                            onPreview = { outputUri?.let { uri -> onOpenPreview(uri, fileName, outputPageCount) } },
                        )
                    }
                }
            }

            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { fileToUnlock = null; unlockError = false; reset() },
                    onUnlocked = { pass ->
                        val source = selectedUri
                        if (source != null) {
                            isFileLoading = true
                            scope.launch {
                                if (extractBookmarks(source, pass)) {
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

    if (showAddDialog) {
        var title by remember { mutableStateOf("") }
        var pageNum by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Bookmark", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pageNum, onValueChange = { if (it.all { c -> c.isDigit() }) pageNum = it }, label = { Text("Page (1-$pageCount)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = pageNum.toIntOrNull()?.let { it - 1 }
                    if (title.isNotBlank() && p != null && p in 0 until pageCount) {
                        bookmarks = bookmarks + Bookmark(title, p)
                        showAddDialog = false
                    }
                }) { Text("ADD") }
            }
        )
    }
}
