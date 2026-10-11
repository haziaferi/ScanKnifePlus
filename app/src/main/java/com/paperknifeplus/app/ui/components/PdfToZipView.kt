package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The ZIP entry for [page] of a document named [displayName], e.g. "Report_p1.pdf". The name comes from the provider, so only its last path
 * segment is used, with ':', separators and control characters replaced, and "Document" for an empty, "." or ".." name.
 */
internal fun zipPageEntryName(displayName: String, page: Int): String {
    val base = pdfBaseName(displayName.substringAfterLast('/').substringAfterLast('\\'))
        .map { if (it == ':' || it == '/' || it == '\\' || it.isISOControl()) '_' else it }
        .joinToString("")
    val safe = if (base.isBlank() || base == "." || base == "..") "Document" else base
    return "${safe}_p$page.pdf"
}

@Composable
fun PdfToZipView(
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
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var processingTime by remember { mutableStateOf("") }
    var progressCount by remember { mutableIntStateOf(0) }

    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var isFileLoading by remember { mutableStateOf(false) }

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

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val sourceName = fileName
        scope.launch(Dispatchers.IO) {
            try {
                // Read fully first: PDFBox may close the stream it loads from, which must not close the ZIP output.
                val bytes = context.contentResolver.requireInputStream(source).use { it.readBytes() }
                context.contentResolver.requireOutputStream(uri).use { os ->
                    ZipOutputStream(os).use { zipOut ->
                        PDDocument.load(bytes, unlockPassword).use { sourceDoc ->
                            for (i in 0 until sourceDoc.numberOfPages) {
                                withContext(Dispatchers.Main) { progressCount = i + 1 }
                                val pageBytes = PDDocument().use { single ->
                                    single.appendPageFrom(sourceDoc.getPage(i))
                                    ByteArrayOutputStream().also { single.save(it) }.toByteArray()
                                }
                                zipOut.putNextEntry(ZipEntry(zipPageEntryName(sourceName, i + 1)))
                                zipOut.write(pageBytes)
                                zipOut.closeEntry()
                            }
                        }
                        zipOut.finish()
                    }
                }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    fileName = outputName
                    SessionManager.addEntry(fileName, "PDF to Pages ZIP", "$pageCount pages archived", Icons.Outlined.FolderZip, uri)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("PdfToZipView", "Archiving failed", e)
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
                    title = "PDF to ZIP",
                    subtitle = "PAGES TO ARCHIVE",
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
                LoadingStateView(accentColor, false, "Preparing document...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") },
                            isDark = isDark,
                            icon = Icons.Outlined.FolderZip,
                            title = "Tap to enter file",
                            subtitle = "CREATE PAGES ARCHIVE",
                            accentColor = accentColor,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(12.dp))

                            Box(modifier = Modifier.weight(1f).fillMaxWidth(0.75f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.COVER,
                                    password = unlockPassword.ifEmpty { null },
                                    accentColor = accentColor
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            Text(fileName, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                            Text("$fileSize • $pageCount PAGES", fontSize = 10.sp, color = Color.Gray)

                            Spacer(Modifier.height(20.dp))
                            Text("ARCHIVE INFO", fontSize = 9.sp, fontWeight = FontWeight.Black, color = accentColor, letterSpacing = 1.5.sp)
                            Text(
                                "Bundle all document pages as individual PDFs into a single ZIP archive.",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp),
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(24.dp))

                            Button(
                                onClick = { saveLauncher.launch(pdfBaseName(fileName) + "-pages.zip") },
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                            ) {
                                Text("CONVERT & SAVE ZIP", fontWeight = FontWeight.Black, color = Color.White)
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            accentColor = accentColor,
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Archiving page $progressCount of $pageCount...",
                            current = progressCount,
                            total = pageCount,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Archive Created",
                            subMessage = "$pageCount individual pages saved to ZIP",
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = { selectedUri = null; unlockPassword = ""; currentState = ToolState.SELECTING },
                            showPreviewButton = false,
                            accentColor = accentColor
                        )
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
