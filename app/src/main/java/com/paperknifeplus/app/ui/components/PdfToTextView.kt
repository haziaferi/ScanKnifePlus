package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PdfToTextView(
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("pdf2text")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var extractedText by remember { mutableStateOf("") }
    var textChunks by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var isFileLoading by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    val showLoadingWarning = rememberLoadingWarning(currentState == ToolState.PROCESSING)

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        unlockPassword = ""
        unlockError = false
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri)) {
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

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveTxtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveUri ->
            val text = extractedText
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.requireOutputStream(saveUri).use { it.write(text.toByteArray()) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, toolText(context, R.string.pdf2text_saved_as_txt), Toast.LENGTH_SHORT).show()
                        outputs.copy(saveUri)
                    }
                } catch (e: CancellationException) {
                    deleteCreatedDocument(context, saveUri)
                    throw e
                } catch (e: Exception) {
                    Log.w("PdfToTextView", "Saving the text failed", e)
                    deleteCreatedDocument(context, saveUri)
                    withContext(Dispatchers.Main) { Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show() }
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
                    outputs.record(fileName, toolText(context, R.string.pdf2text_chars, text.length), Icons.Outlined.Description)
                    currentState = ToolState.SUCCESS
                }
                .onFailure { e ->
                    Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    Scaffold(
        topBar = {
            // The extracted text has its own header, with copy and save.
            if (currentState != ToolState.SUCCESS) {
                ToolScreenBar(currentState, stringResource(R.string.tool_pdf2text), stringResource(R.string.pdf2text_extract_raw_content), "", onBack)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.Description,
                            title = stringResource(R.string.pdf2text_extract_text_data),
                            task = stringResource(R.string.tool_pdf2text_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(action = stringResource(R.string.pdf2text_extract_text), onAction = { startExtraction() }) {
                            ToolFileCard(
                                uri = selectedUri!!,
                                password = unlockPassword.ifEmpty { null },
                                name = fileName,
                                details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                onChange = { selectedUri = null; unlockPassword = ""; currentState = ToolState.SELECTING },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            )
                            Text(
                                stringResource(R.string.pdf2text_scan_pdf_text_layers_and),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                            )
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = stringResource(R.string.pdf2text_reading_text_layers),
                            current = 0,
                            total = pageCount,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    ToolSectionLabel(stringResource(R.string.pdf2text_extracted_content))
                                    Text(stringResource(R.string.pdf2text_characters_found, extractedText.length), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row {
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("PDF Text", extractedText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, toolText(context, R.string.pdf2text_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Filled.ContentCopy, stringResource(R.string.pdf2text_copy_text), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = { saveTxtLauncher.launch(outputFileName(pdfBaseName(fileName), "txt")) }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Filled.Save, stringResource(R.string.pdf2text_save_as_txt), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }

                            Surface(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Box(Modifier.padding(20.dp)) {
                                    if (extractedText.isBlank()) {
                                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Filled.FindInPage, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(stringResource(R.string.pdf2text_no_text_detected_the_document), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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

                            ToolPrimaryButton(stringResource(R.string.action_done), onClick = onBack, modifier = Modifier.fillMaxWidth().padding(24.dp))
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
