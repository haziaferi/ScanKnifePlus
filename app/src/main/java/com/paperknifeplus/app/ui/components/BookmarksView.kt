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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.data.image.PdDocumentPool
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
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
    val isDark = LocalIsDarkTheme.current
    val accentColor = Color(0xFFF43F5E)

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

    /** Reads the outline with [pass]; false if the document does not open with it. */
    suspend fun extractBookmarks(uri: Uri, pass: String?): Boolean {
        val outline = PdDocumentPool.withDocument(context, uri, pass) { document -> readOutline(document) to document.numberOfPages }
            ?: return false
        bookmarks = outline.first
        pageCount = outline.second
        unlockPassword = pass.orEmpty()
        currentState = ToolState.CONFIGURING
        return true
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
                    SessionManager.addEntry(outputName, "Bookmarks", summary(finalCount), Icons.Filled.BookmarkBorder, saveUri, finalCount)
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

    fun reset() {
        selectedUri = null
        unlockPassword = ""
        bookmarks = emptyList()
        currentState = ToolState.SELECTING
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = "Bookmarks",
                    subtitle = "EDIT DOCUMENT NAVIGATION",
                    accent = accentColor,
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = ::reset
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isFileLoading) {
                LoadingStateView(accentColor, false, "Reading outlines...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") }, 
                            isDark = isDark,
                            icon = Icons.Filled.BookmarkBorder,
                            title = "Tap to enter file",
                            subtitle = "MANAGE PDF BOOKMARKS",
                            accentColor = accentColor,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${bookmarks.size} BOOKMARKS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accentColor)
                                Button(onClick = { showAddDialog = true }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)) {
                                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("ADD", fontSize = 11.sp, fontWeight = FontWeight.Black)
                                }
                            }

                            if (bookmarks.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No bookmarks found", color = Color.Gray, fontSize = 14.sp)
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemsIndexed(bookmarks) { index, bm ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        ) {
                                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Bookmark, null, tint = accentColor, modifier = Modifier.size(20.dp))
                                                Spacer(Modifier.width(16.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(bm.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    Text("Page ${bm.pageIndex?.let { it + 1 } ?: "-"}", fontSize = 10.sp, color = Color.Gray)
                                                }
                                                IconButton(onClick = { bookmarks = bookmarks.withoutBranch(index) }) {
                                                    Icon(Icons.Filled.Delete, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { saveLauncher.launch(pdfBaseName(fileName) + "-nav.pdf") },
                                    modifier = Modifier.weight(1f).height(60.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                                ) {
                                    Text("SAVE PDF", fontWeight = FontWeight.Black)
                                }
                                OutlinedButton(
                                    onClick = { extractLauncher.launch(pdfBaseName(fileName) + "-extract.pdf") },
                                    modifier = Modifier.weight(1f).height(60.dp),
                                    enabled = bookmarks.isNotEmpty(),
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
                                ) {
                                    Text("EXTRACT PAGES", fontWeight = FontWeight.Black, color = accentColor)
                                }
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            accentColor = accentColor,
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
                            accentColor = accentColor
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
                }, colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)) { Text("ADD") }
            }
        )
    }
}
