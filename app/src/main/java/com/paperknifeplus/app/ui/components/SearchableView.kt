package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import coil.compose.rememberAsyncImagePainter
import com.haziaferi.scanknifeplus.ocr.OcrEngine
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.TesseractOcrEngine
import com.haziaferi.scanknifeplus.ui.scan.defaultOcrLanguage
import com.haziaferi.scanknifeplus.ui.scan.handwrittenNotice
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.ui.theme.TargetStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Make PDF searchable: one PDF, or one or more photos made into a new PDF, gets the recognised text of its pages as an invisible layer. The
 * language follows the phone to start with, as in the scanner's Save. Reading pauses after the current page while the app is in the
 * background; Cancel, or leaving the screen, saves nothing and removes the file the picker created, as the other tools' back path does. [openEngine] and [renders] are the
 * engine and page renderer, replaced in tests.
 */
@Composable
internal fun SearchableView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit,
    openEngine: (Context) -> OcrEngine = TesseractOcrEngine::open,
    renders: ((Context, Uri, String?) -> PageRenders)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("searchable")
    val accentColor = MaterialTheme.colorScheme.primary
    val locale = LocalLocale.current.platformLocale

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    val check = rememberFileCheck()

    var language by remember { mutableStateOf(defaultOcrLanguage(locale)) }
    var reading by remember { mutableStateOf(OcrReading.AUTOMATIC) }
    var redo by remember { mutableStateOf(false) }

    var progressPage by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var processingTime by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var outputName by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchableResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val showLoadingWarning = rememberLoadingWarning(check.running || currentState == ToolState.PROCESSING)

    // Reading finishes the page it is on and waits while the app is in the background (decision of 2026-10-20, item 6).
    val running = remember { MutableStateFlow(true) }
    LifecycleStartEffect(running) {
        running.value = true
        onStopOrDispose { running.value = false }
    }

    fun reset() {
        selectedUri = null
        photos = emptyList()
        unlockPassword = ""
        currentState = ToolState.SELECTING
    }

    fun handleFileSelection(uri: Uri) {
        photos = emptyList()
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
                    reset()
                }
                PdfAccess.Encrypted -> fileToUnlock = fileName
                PdfAccess.Open -> {
                    pageCount = getPageCount(context, uri, null)
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    fun handlePhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        selectedUri = null
        unlockPassword = ""
        photos = uris
        currentState = ToolState.CONFIGURING
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { handleFileSelection(it) } }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { handlePhotos(it) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { saveUri ->
        if (saveUri == null) return@rememberLauncherForActivityResult
        val source = selectedUri
        val images = photos
        if (source == null && images.isEmpty()) return@rememberLauncherForActivityResult
        val options = SearchableOptions(language, reading, redo)
        val password = unlockPassword
        val engine = { openEngine(context) }
        val pageRenders: (Uri, String?) -> PageRenders = { uri, pass -> renders?.invoke(context, uri, pass) ?: NativePageRenders(context, uri, pass) }
        val onPage = { page: Int, total: Int ->
            progressPage = page
            progressTotal = total
        }
        progressPage = 0
        progressTotal = 0
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        job = scope.launch(Dispatchers.IO) {
            try {
                clearSearchableWork(context)
                val done = if (source != null) {
                    makeSearchable(context, source, password, saveUri, options, engine, running, renders = pageRenders, onPage = onPage)
                } else {
                    makePhotosSearchable(context, images, saveUri, options, engine, running, pageRenders, onPage)
                }
                val timeStr = formatElapsed(startTime)
                val name = getUriDetails(context, saveUri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = saveUri
                    outputName = name
                    result = done
                    outputs.saved(saveUri, name, readSummary(context, done), Icons.Outlined.FindInPage, done.pages)
                    handwrittenNotice(context.resources, done.handwritten)?.let {
                        Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show()
                    }
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, saveUri)
                throw e
            } catch (e: Throwable) {
                if (!isToolFailure(e)) throw e
                Log.w("SearchableView", "Making the PDF searchable failed", e)
                deleteCreatedDocument(context, saveUri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolFailureText(context, e), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        currentState = ToolState.CONFIGURING
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    Scaffold(
        topBar = {
            val title = stringResource(R.string.tool_searchable)
            val subtitle = stringResource(R.string.scan_ocr_searchable_hint)
            if (currentState == ToolState.SELECTING && !check.running) {
                // The empty screen's own button picks a PDF; photos come from this one.
                ToolTopBar(
                    title = title,
                    subtitle = subtitle,
                    showChange = true,
                    onBack = onBack,
                    onChange = { pickPhotos.launch("image/*") },
                    changeLabel = stringResource(R.string.tool_pick_images)
                )
            } else {
                ToolScreenBar(currentState, title, subtitle, processingTime, onBack)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (check.running) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.FindInPage,
                            title = stringResource(R.string.searchable_title),
                            task = stringResource(R.string.scan_ocr_searchable_hint),
                            onChoose = { pickPdf.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            action = stringResource(R.string.scan_ocr_searchable),
                            onAction = {
                                val stem = if (photos.isNotEmpty()) "images" else pdfBaseName(fileName)
                                saveLauncher.launch(outputFileName("$stem-searchable", "pdf"))
                            }
                        ) {
                            val source = selectedUri
                            if (source != null) {
                                ToolFileCard(
                                    uri = source,
                                    password = unlockPassword.ifEmpty { null },
                                    name = fileName,
                                    details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                    onChange = { reset() },
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                                )
                            } else {
                                PhotosCard(photos, onChange = { reset() }, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                            }
                            // Each choice under its label, as Crop's Pages: the label 20 dp from the screen's edge, the choice 16 dp, in line
                            // with the file card; the gap between the groups is wider than label to control.
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                ToolSectionLabel(stringResource(R.string.scan_ocr_language), Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp))
                                SegmentedChoice(
                                    OcrLanguage.entries.map { stringResource(languageLabel(it)) },
                                    OcrLanguage.entries.indexOf(language),
                                    onSelect = { language = OcrLanguage.entries[it] }
                                )
                                ToolSectionLabel(stringResource(R.string.scan_ocr_reading), Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp))
                                SegmentedChoice(
                                    OcrReading.entries.map { stringResource(readingLabel(it)) },
                                    OcrReading.entries.indexOf(reading),
                                    onSelect = { reading = OcrReading.entries[it] }
                                )
                                if (source != null) {
                                    Row(
                                        Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp).fillMaxWidth().heightIn(min = TargetStep.ACT.drawn)
                                            .toggleable(value = redo, role = Role.Switch) { redo = it },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            stringResource(R.string.searchable_redo),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                                        )
                                        Switch(checked = redo, onCheckedChange = null)
                                    }
                                }
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                ProcessingStateView(
                                    uri = selectedUri,
                                    password = unlockPassword.ifEmpty { null },
                                    text = if (progressPage > 0) {
                                        stringResource(R.string.scan_ocr_progress, progressPage, progressTotal)
                                    } else {
                                        stringResource(R.string.tool_preparing)
                                    },
                                    current = 0,
                                    total = 0,
                                    showWarning = showLoadingWarning
                                )
                            }
                            ToolActionBar {
                                ToolSecondaryButton(stringResource(R.string.action_cancel), onClick = { cancel() }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    ToolState.SUCCESS -> {
                        val done = result
                        SuccessView(
                            message = stringResource(R.string.searchable_done),
                            subMessage = if (done != null) resultSummary(context, done) else "",
                            result = outputUri?.let { ToolResult(it, outputName) },
                            onDone = onBack,
                            onProcessMore = { reset() },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, outputName, done?.pages ?: 0) } }
                        )
                    }
                }
            }

            if (fileToUnlock != null) {
                // Cancel, Back, and a file that cannot be read close the prompt and go back to choosing a file.
                fun closePrompt() {
                    fileToUnlock = null
                    unlockError = false
                    reset()
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
                                    pageCount = count
                                    currentState = ToolState.CONFIGURING
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
}

/** The picked photos as a card: the first one small, how many there are, and Change ([onChange]). */
@Composable
private fun PhotosCard(photos: List<Uri>, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    ToolCard(modifier) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceVariant)) {
                photos.firstOrNull()?.let { first ->
                    Image(
                        painter = rememberAsyncImagePainter(model = first, imageLoader = PdfImageLoader.get(LocalContext.current)),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Text(
                stringResource(R.string.img2pdf_images_selected, photos.size),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            CardChange(onChange)
        }
    }
}

@StringRes
private fun languageLabel(language: OcrLanguage): Int = when (language) {
    OcrLanguage.ENGLISH -> R.string.scan_ocr_english
    OcrLanguage.ITALIAN -> R.string.scan_ocr_italian
}

@StringRes
private fun readingLabel(reading: OcrReading): Int = when (reading) {
    OcrReading.AUTOMATIC -> R.string.scan_ocr_automatic
    OcrReading.TABLES -> R.string.scan_ocr_tables
    OcrReading.RECEIPTS -> R.string.scan_ocr_receipts
}

/** How many pages a run read, for History. */
internal fun readSummary(context: Context, result: SearchableResult): String =
    context.resources.getQuantityString(R.plurals.searchable_read, result.read, result.read)

/** The result line: the pages read and, when some were skipped, how many already had text. */
internal fun resultSummary(context: Context, result: SearchableResult): String {
    val read = readSummary(context, result)
    if (result.kept == 0) return read
    return read + " " + context.resources.getQuantityString(R.plurals.searchable_kept, result.kept, result.kept)
}
