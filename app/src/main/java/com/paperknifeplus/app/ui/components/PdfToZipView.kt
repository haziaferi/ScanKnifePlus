package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val outputs = rememberToolOutputs("pdf2zip")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
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
                // The source is read into memory once, as before, so no input stream stays open across the export.
                val bytes = context.contentResolver.requireInputStream(source).use { it.readBytes() }
                context.contentResolver.requireOutputStream(uri).use { os ->
                    ZipOutputStream(os).use { zipOut ->
                        PDDocument.load(bytes, unlockPassword).use { sourceDoc ->
                            for (i in 0 until sourceDoc.numberOfPages) {
                                withContext(Dispatchers.Main) { progressCount = i + 1 }
                                // Saved to a buffer: PDDocument.save closes the stream it writes to, which would close the ZIP.
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
                    outputUri = uri
                    outputs.saved(uri, fileName, toolText(context, R.string.pdf2zip_pages_archived, pageCount), Icons.Outlined.FolderZip)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("PdfToZipView", "Archiving failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_pdf2zip), stringResource(R.string.pdf2zip_pages_to_archive), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(false, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.FolderZip,
                            title = stringResource(R.string.pdf2zip_create_pages_archive),
                            task = stringResource(R.string.tool_pdf2zip_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            action = stringResource(R.string.pdf2zip_convert_and_save_zip),
                            onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-pages", "zip")) }
                        ) {
                            ToolFileCard(
                                uri = selectedUri!!,
                                password = unlockPassword.ifEmpty { null },
                                name = fileName,
                                details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                onChange = { selectedUri = null; unlockPassword = ""; currentState = ToolState.SELECTING },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            )
                            ToolSectionLabel(stringResource(R.string.pdf2zip_archive_info), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
                            Text(
                                stringResource(R.string.pdf2zip_bundle_note),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)
                            )
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = stringResource(R.string.pdf2zip_archiving_page_of, progressCount, pageCount),
                            current = progressCount,
                            total = pageCount,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.pdf2zip_archive_created),
                            subMessage = stringResource(R.string.pdf2zip_individual_pages_saved_to_zip, pageCount),
                            result = outputUri?.let { ToolResult(it, fileName, ZIP_MIME) },
                            onDone = onBack,
                            onProcessMore = { selectedUri = null; outputUri = null; unlockPassword = ""; currentState = ToolState.SELECTING }
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
