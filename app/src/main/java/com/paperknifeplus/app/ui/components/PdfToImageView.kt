package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The image formats offered, as the segments show them; the first is the default. */
internal val IMAGE_FORMATS = listOf("JPG", "PNG", "WebP")

/** The qualities offered, each the value the export takes and the segment's label; the first is the default. */
private val QUALITIES = listOf(
    "Standard" to R.string.pdf2img_quality_standard,
    "HD" to R.string.pdf2img_quality_hd,
    "Ultra HD" to R.string.pdf2img_quality_ultra
)

/** Pages selected when a document is opened: all of them. */
internal fun initialSelection(pageCount: Int): Set<Int> = (0 until pageCount).toSet()

@Composable
fun PdfToImageView(
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("pdf2img")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }

    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var format by remember { mutableStateOf(IMAGE_FORMATS.first()) }
    var quality by remember { mutableStateOf(QUALITIES.first().first) }

    var progressCount by remember { mutableIntStateOf(0) }
    var processingTime by remember { mutableStateOf("") }
    var lightboxPage by remember { mutableStateOf<Int?>(null) }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    val check = rememberFileCheck()
    val showLoadingWarning = rememberLoadingWarning(currentState == ToolState.PROCESSING)

    val imageLoader = PdfImageLoader.get(context)

    fun showPages(count: Int) {
        pageCount = count
        selectedPages = initialSelection(count)
        currentState = ToolState.CONFIGURING
    }

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        unlockPassword = ""
        unlockError = false
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        check.start {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                PdfAccess.Encrypted -> fileToUnlock = fileName
                PdfAccess.Open -> showPages(getPageCount(context, uri, null))
            }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                convertPdfToImages(context, source, uri, unlockPassword.ifEmpty { null }, selectedPages.toList().sorted(), format, quality) { current, _ ->
                    progressCount = current
                }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    fileName = outputName
                    outputUri = uri
                    outputs.saved(uri, fileName, toolText(context, R.string.pdf2img_images, selectedPages.size), Icons.Outlined.BurstMode)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Throwable) {
                // Each page is drawn as a bitmap of up to 3 times its size, which can run out of memory.
                if (!isToolFailure(e)) throw e
                Log.w("PdfToImageView", "Export failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolFailureText(context, e), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_pdf2img), stringResource(R.string.pdf2img_native_export_engine), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (currentState) {
                ToolState.SELECTING -> {
                    ToolEmptyState(
                        icon = Icons.Outlined.BurstMode,
                        title = stringResource(R.string.pdf2img_convert_document_to_images),
                        task = stringResource(R.string.tool_pdf2img_desc),
                        onChoose = { pickLauncher.launch("application/pdf") },
                        onOpenRecent = { handleFileSelection(it) }
                    )
                }
                ToolState.CONFIGURING -> {
                    ToolConfigLayout(
                        action = stringResource(R.string.pdf2img_convert_pages_to, selectedPages.size, format),
                        onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName), "zip")) },
                        actionEnabled = selectedPages.isNotEmpty(),
                        scroll = false
                    ) {
                        val qualityLabels = QUALITIES.map { stringResource(it.second) }
                        // The file card and the choices scroll with the pages, so at large text they never squeeze the grid out (rule 10).
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)
                        ) {
                            item(key = "options", span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    ToolFileCard(
                                        uri = selectedUri!!,
                                        password = unlockPassword.ifEmpty { null },
                                        name = fileName,
                                        details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                        onChange = { selectedUri = null; unlockPassword = ""; currentState = ToolState.SELECTING },
                                        modifier = Modifier.padding(top = 12.dp)
                                    )
                                    // Each choice under its label, as Crop's Pages, 20 dp from the screen's edges: the gap between the groups is
                                    // wider than label to control.
                                    Column(Modifier.padding(horizontal = 4.dp)) {
                                        ToolSectionLabel(stringResource(R.string.pdf2img_format), Modifier.padding(top = 20.dp, bottom = 8.dp))
                                        SegmentedChoice(IMAGE_FORMATS, IMAGE_FORMATS.indexOf(format), onSelect = { format = IMAGE_FORMATS[it] })
                                        ToolSectionLabel(stringResource(R.string.pdf2img_quality), Modifier.padding(top = 20.dp, bottom = 8.dp))
                                        SegmentedChoice(
                                            qualityLabels,
                                            QUALITIES.indexOfFirst { it.first == quality },
                                            onSelect = { quality = QUALITIES[it].first }
                                        )
                                        ToolSectionLabel(
                                            stringResource(R.string.pdf2img_select_pages_to_export),
                                            Modifier.padding(start = 4.dp, top = 20.dp)
                                        )
                                    }
                                }
                            }
                            items(pageCount, key = { it }) { index ->
                                val isSelected = selectedPages.contains(index)
                                PdfPageItem(
                                    uri = selectedUri!!,
                                    index = index,
                                    password = unlockPassword.ifEmpty { null },
                                    imageLoader = imageLoader,
                                    accentColor = accentColor,
                                    onClick = { lightboxPage = index },
                                    modifier = Modifier.border(
                                        BorderStroke(2.dp, if (isSelected) accentColor else Color.Transparent),
                                        RoundedCornerShape(12.dp)
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(if (isSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent)
                                            .toggleable(value = isSelected, role = Role.Checkbox) { selectedPages = if (it) selectedPages + index else selectedPages - index }
                                            .semantics { contentDescription = toolText(context, R.string.tool_page_n, index + 1); stateDescription = if (isSelected) toolText(context, R.string.state_selected) else toolText(context, R.string.state_not_selected) }
                                    )

                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                            .size(24.dp),
                                        color = if (isSelected) accentColor else pageBadge,
                                        shape = CircleShape
                                    ) {
                                        // The tick shows only when selected, so the state is in the shape and not only in the colour.
                                        if (isSelected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                ToolState.PROCESSING -> {
                    ProcessingStateView(
                        uri = selectedUri,
                        password = unlockPassword.ifEmpty { null },
                        text = stringResource(R.string.pdf2img_rendering_page_of, progressCount, selectedPages.size),
                        current = progressCount,
                        total = selectedPages.size,
                        showWarning = showLoadingWarning
                    )
                }
                ToolState.SUCCESS -> {
                    SuccessView(
                        message = stringResource(R.string.pdf2img_images_exported),
                        subMessage = stringResource(R.string.pdf2img_pages_saved_to_zip, selectedPages.size),
                        result = outputUri?.let { ToolResult(it, fileName, ZIP_MIME) },
                        onDone = onBack,
                        onProcessMore = { selectedUri = null; outputUri = null; unlockPassword = ""; currentState = ToolState.SELECTING }
                    )
                }
            }

            if (fileToUnlock != null) {
                // Cancel, Back, and a file that cannot be read close the prompt and go back to choosing a file.
                fun closePrompt() {
                    fileToUnlock = null
                    unlockError = false
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { check.stop(); closePrompt() },
                    onUnlocked = { pass ->
                        val source = selectedUri
                        if (source != null) {
                            check.start {
                                val counted = checkPageCount(context, source, pass)
                                counted.resolve(context, onWrongPassword = { unlockError = true }, onUnreadable = { closePrompt() }) { count ->
                                    unlockPassword = pass
                                    showPages(count)
                                    fileToUnlock = null
                                }
                            }
                        }
                    },
                    isError = unlockError,
                    accentColor = accentColor,
                    isLoading = check.running
                )
            }
        }
    }

    if (lightboxPage != null && selectedUri != null) {
        PageLightbox(
            uri = selectedUri!!,
            initialPage = lightboxPage!!,
            totalCount = pageCount,
            password = unlockPassword.ifEmpty { null },
            onDismiss = { lightboxPage = null },
            selectedPages = selectedPages,
            onToggleSelection = { index ->
                selectedPages = if (selectedPages.contains(index)) selectedPages - index else selectedPages + index
            }
        )
    }
}
