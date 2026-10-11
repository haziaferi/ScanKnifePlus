package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

@Composable
fun PdfToTextView(
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalIsDarkTheme.current
    val accentColor = Color(0xFF14B8A6)

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var extractedText by remember { mutableStateOf("") }
    var textChunks by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileName by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var isFileLoading by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    val showLoadingWarning = rememberLoadingWarning(currentState == ToolState.PROCESSING)

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        unlockPassword = ""
        unlockError = false
        fileName = getUriDetails(context, uri).name
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
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

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveTxtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveUri ->
            val text = extractedText
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.requireOutputStream(saveUri).use { it.write(text.toByteArray()) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Saved as TXT", Toast.LENGTH_SHORT).show() }
                } catch (e: CancellationException) {
                    deleteCreatedDocument(context, saveUri)
                    throw e
                } catch (e: Exception) {
                    Log.w("PdfToTextView", "Saving the text failed", e)
                    deleteCreatedDocument(context, saveUri)
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun startExtraction() {
        val source = selectedUri ?: return
        currentState = ToolState.PROCESSING
        scope.launch {
            processText(context, source, unlockPassword.ifEmpty { null })
                .onSuccess { (text, chunks) ->
                    extractedText = text
                    textChunks = chunks
                    SessionManager.addEntry(fileName, "To Text", "${text.length} chars", Icons.Outlined.Description)
                    currentState = ToolState.SUCCESS
                }
                .onFailure { e ->
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = "PDF to Text",
                    subtitle = "EXTRACT RAW CONTENT",
                    accent = accentColor,
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = { selectedUri = null; unlockPassword = ""; currentState = ToolState.SELECTING }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isFileLoading) {
                LoadingStateView(accentColor, showLoadingWarning, "Preparing document...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") },
                            isDark = isDark,
                            icon = Icons.Outlined.Description,
                            title = "Tap to select PDF",
                            subtitle = "EXTRACT TEXT DATA",
                            accentColor = accentColor,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(16.dp))
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(0.85f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.COVER,
                                    password = unlockPassword.ifEmpty { null },
                                    accentColor = accentColor
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(fileName, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)

                            Spacer(Modifier.height(24.dp))
                            Text("READY TO EXTRACT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = accentColor, letterSpacing = 1.5.sp)
                            Text("Scan PDF text layers and output a plain .txt file.", fontSize = 12.sp, color = Color.Gray)

                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { startExtraction() },
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("EXTRACT TEXT", fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            accentColor = accentColor,
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Reading text layers...",
                            current = 0,
                            total = pageCount,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("EXTRACTED CONTENT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = accentColor, letterSpacing = 1.5.sp)
                                    Text("${extractedText.length} CHARACTERS FOUND", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                }
                                Row {
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("PDF Text", extractedText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.background(accentColor.copy(alpha = 0.1f), CircleShape).size(36.dp)) {
                                        Icon(Icons.Filled.ContentCopy, null, tint = accentColor, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = { saveTxtLauncher.launch(pdfBaseName(fileName) + ".txt") }, modifier = Modifier.background(accentColor.copy(alpha = 0.1f), CircleShape).size(36.dp)) {
                                        Icon(Icons.Filled.Save, null, tint = accentColor, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            Surface(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                color = if (isDark) Color(0xFF09090B) else Color.White,
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, Color.Gray.copy(0.1f))
                            ) {
                                Box(Modifier.padding(20.dp)) {
                                    if (extractedText.isBlank()) {
                                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Filled.FindInPage, null, modifier = Modifier.size(48.dp).alpha(0.2f), tint = accentColor)
                                            Text("No text detected. The document might be a high-res scan.", fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                        }
                                    } else {
                                        // One item per block of lines, so a long text is laid out only where it is on screen.
                                        LazyColumn {
                                            items(textChunks) { chunk ->
                                                Text(
                                                    text = chunk.removeSuffix("\n"),
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    lineHeight = 20.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = onBack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp)
                                    .height(56.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                            ) {
                                Text("DONE", fontWeight = FontWeight.Black)
                            }
                        }
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

/** The text of every page of the PDF in [input], each under a "--- PAGE n ---" header. Throws if it cannot be read. Blocking. */
internal fun extractPdfText(input: InputStream, password: String?): String =
    (if (password != null) PDDocument.load(input, password) else PDDocument.load(input)).use { document ->
        val stripper = PDFTextStripper()
        val builder = StringBuilder()
        for (i in 1..document.numberOfPages) {
            stripper.startPage = i
            stripper.endPage = i
            builder.append("--- PAGE $i ---\n")
            builder.append(stripper.getText(document))
            builder.append("\n\n")
        }
        builder.toString()
    }

/** [text] cut after every [linesPerChunk]-th line break; each chunk keeps its own line breaks, so the chunks joined are [text] again. */
internal fun textChunks(text: String, linesPerChunk: Int = 40): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    var lines = 0
    text.forEachIndexed { i, c ->
        if (c == '\n' && ++lines == linesPerChunk) {
            chunks += text.substring(start, i + 1)
            start = i + 1
            lines = 0
        }
    }
    if (start < text.length) chunks += text.substring(start)
    return chunks
}

/** Extracts the text of [uri] off the main thread, with the chunks to show it in; a failure is returned, cancellation rethrown. */
internal suspend fun processText(context: Context, uri: Uri, password: String?): Result<Pair<String, List<String>>> =
    withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.requireInputStream(uri).use { extractPdfText(it, password) }
            Result.success(text to textChunks(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PdfToTextView", "Text extraction failed", e)
            Result.failure(e)
        }
    }
