package com.paperknifeplus.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.paperknifeplus.app.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlacedWatermark(
    val id: String = java.util.UUID.randomUUID().toString(),
    val pageIndex: Int, // -1 means all pages
    val bitmap: Bitmap,
    val normalizedX: Float, // Centre, as fractions of the displayed page
    val normalizedY: Float,
    val normalizedWidth: Float, // Width as a fraction of the displayed page width
    val rotation: Float,
    val opacity: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("watermark")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf(initialUri) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageBoxes by remember { mutableStateOf<List<PageBox>>(emptyList()) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    val pageCount = pageBoxes.size

    var placedWatermarks by remember { mutableStateOf<List<PlacedWatermark>>(emptyList()) }
    var activeWatermarkBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeOffset by remember { mutableStateOf(Offset.Zero) }
    var activeScale by remember { mutableFloatStateOf(1f) }
    var activeRotation by remember { mutableFloatStateOf(0f) }
    var activeOpacity by remember { mutableFloatStateOf(1f) }
    var isFocusMode by remember { mutableStateOf(false) }
    var lightboxPage by remember { mutableStateOf<Int?>(null) }
    // The lightbox page area as its overlay measured it, which APPLY normalises against.
    var lightboxSize by remember { mutableStateOf(Size.Zero) }

    var showWatermarkOptions by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }

    fun startPlacing(bitmap: Bitmap?, opacity: Float) {
        activeWatermarkBitmap = bitmap
        activeOffset = Offset.Zero
        activeScale = 1f
        activeRotation = 0f
        activeOpacity = opacity
        isFocusMode = true
    }

    fun fitRectOf(pageIndex: Int, containerWidth: Float, containerHeight: Float) =
        LayoutMath.getFitRect(containerWidth, containerHeight, pageBoxes.getOrNull(pageIndex)?.displayAspect ?: FALLBACK_PAGE_ASPECT)

    /** The watermark being placed on lightbox page [shownOn], for [pageIndex] (-1 for every page). */
    fun placeActive(bitmap: Bitmap, pageIndex: Int, shownOn: Int): PlacedWatermark {
        val fitRect = fitRectOf(shownOn, lightboxSize.width, lightboxSize.height)
        return PlacedWatermark(
            pageIndex = pageIndex,
            bitmap = bitmap,
            normalizedX = (activeOffset.x + (lightboxSize.width / 2) - fitRect.left) / fitRect.width(),
            normalizedY = (activeOffset.y + (lightboxSize.height / 2) - fitRect.top) / fitRect.height(),
            normalizedWidth = 0.5f * activeScale,
            rotation = activeRotation,
            opacity = activeOpacity
        )
    }

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
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
                    pageBoxes = readPageBoxes(context, uri, null)
                    currentState = ToolState.CONFIGURING
                }
            }
            isFileLoading = false
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val imgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                readPickedImage(context, it).onSuccess { bitmap ->
                    startPlacing(bitmap, opacity = 1f)
                    showWatermarkOptions = false
                }
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val watermarks = placedWatermarks
        scope.launch(Dispatchers.IO) {
            try {
                rewritePdf(context, source, unlockPassword, uri) { applyWatermarks(it, watermarks) }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = outputName
                    outputs.saved(uri, outputName, toolText(context, R.string.watermark_branding_applied_locally), Icons.Filled.TextFields, pageCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("WatermarkView", "Watermarking failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    // Placed watermarks and the one being placed, positioned over the page as the page image is fitted into this overlay's box.
    @Composable
    fun WatermarkOverlay(pageIndex: Int, onMeasured: ((Size) -> Unit)?) {
        val measured = if (onMeasured == null) Modifier else Modifier.onSizeChanged { onMeasured(it.toSize()) }
        BoxWithConstraints(Modifier.fillMaxSize().then(measured)) {
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()
            val fitRect = fitRectOf(pageIndex, containerWidth, containerHeight)
            val pageWidth = fitRect.width()
            val pageHeight = fitRect.height()

            placedWatermarks.filter { it.pageIndex == pageIndex || it.pageIndex == -1 }.forEach { wm ->
                ComposeImage(
                    bitmap = wm.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .requiredSize(
                            width = (pageWidth * wm.normalizedWidth).dp / LocalDensity.current.density,
                            height = (pageWidth * wm.normalizedWidth * (wm.bitmap.height.toFloat() / wm.bitmap.width.toFloat())).dp / LocalDensity.current.density
                        )
                        .graphicsLayer {
                            translationX = fitRect.left + (wm.normalizedX * pageWidth) - (containerWidth / 2)
                            translationY = fitRect.top + (wm.normalizedY * pageHeight) - (containerHeight / 2)
                            rotationZ = wm.rotation
                            alpha = wm.opacity
                        }
                        .align(Alignment.Center)
                )
            }

            if (isFocusMode && activeWatermarkBitmap != null && (lightboxPage == pageIndex || lightboxPage == null)) {
                val bitmap = activeWatermarkBitmap!!
                val baseWidth = pageWidth * 0.5f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, rot ->
                                activeOffset += pan
                                activeScale = (activeScale * zoom).coerceIn(0.1f, 15f)
                                activeRotation += rot
                            }
                        }
                ) {
                    ComposeImage(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .requiredSize(
                                width = (baseWidth * activeScale).dp / LocalDensity.current.density,
                                height = (baseWidth * activeScale * (bitmap.height.toFloat() / bitmap.width.toFloat())).dp / LocalDensity.current.density
                            )
                            .graphicsLayer {
                                translationX = activeOffset.x
                                translationY = activeOffset.y
                                rotationZ = activeRotation
                                alpha = activeOpacity
                            }
                            .align(Alignment.Center)
                            .border(1.dp, accentColor.copy(0.3f), RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_watermark), stringResource(R.string.watermark_custom_overlay), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(false, stringResource(R.string.watermark_reading_document))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Filled.TextFields,
                            title = stringResource(R.string.watermark_watermark_pages),
                            task = stringResource(R.string.tool_watermark_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        val hasWatermark = placedWatermarks.isNotEmpty()
                        ToolConfigLayout(
                            action = if (hasWatermark) stringResource(R.string.action_save_pdf) else stringResource(R.string.watermark_select_a_page_to_watermark),
                            onAction = { if (hasWatermark) saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-watermarked", "pdf")) },
                            actionEnabled = hasWatermark,
                            scroll = false
                        ) {
                            ToolFileCard(
                                uri = selectedUri!!,
                                password = unlockPassword.ifEmpty { null },
                                name = fileName,
                                details = stringResource(R.string.tool_file_details, fileSize, pageCount),
                                onChange = { selectedUri = null; currentState = ToolState.SELECTING; placedWatermarks = emptyList() },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                            )
                            Box(modifier = Modifier.weight(1f).padding(horizontal = 20.dp)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.GRID,
                                    password = unlockPassword.ifEmpty { null },
                                    accentColor = accentColor,
                                    showSelectionIcon = false,
                                    showZoomIcon = false,
                                    itemOverlay = { WatermarkOverlay(it, onMeasured = null) },
                                    onToggleSelection = { index -> lightboxPage = index }
                                )
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = stringResource(R.string.watermark_applying_watermarks),
                            current = 0,
                            total = 0,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.watermark_watermark_complete),
                            subMessage = stringResource(R.string.watermark_watermarks_applied_successfully),
                            result = outputUri?.let { ToolResult(it, fileName) },
                            onDone = onBack,
                            onProcessMore = {
                                selectedUri = null
                                placedWatermarks = emptyList()
                                currentState = ToolState.SELECTING
                            },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) } }
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
                                val boxes = readPageBoxes(context, source, pass)
                                if (boxes.isNotEmpty()) {
                                    unlockPassword = pass
                                    pageBoxes = boxes
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

    if (lightboxPage != null) {
        PageLightbox(
            uri = selectedUri!!,
            initialPage = lightboxPage!!,
            totalCount = pageCount,
            password = unlockPassword.ifEmpty { null },
            onDismiss = { lightboxPage = null; isFocusMode = false; activeWatermarkBitmap = null },
            itemOverlay = { WatermarkOverlay(it) { size -> lightboxSize = size } },
            bottomBar = { currentPage ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        if (isFocusMode) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.watermark_opacity), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 8.dp))
                                Slider(value = activeOpacity, onValueChange = { activeOpacity = it }, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { isFocusMode = false; activeWatermarkBitmap = null }, modifier = Modifier.weight(0.8f)) {
                                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                }
                                Row(Modifier.weight(2.5f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            activeWatermarkBitmap?.let { placedWatermarks = placedWatermarks + placeActive(it, pageIndex = -1, shownOn = currentPage) }
                                            isFocusMode = false
                                            activeWatermarkBitmap = null
                                        },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                    ) { Text(stringResource(R.string.action_apply_to_all), fontSize = 14.sp, fontWeight = FontWeight.Black) }
                                    Button(
                                        onClick = {
                                            activeWatermarkBitmap?.let { placedWatermarks = placedWatermarks + placeActive(it, pageIndex = currentPage, shownOn = currentPage) }
                                            isFocusMode = false
                                            activeWatermarkBitmap = null
                                        },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) { Text(stringResource(R.string.action_apply), fontSize = 14.sp, fontWeight = FontWeight.Black) }
                                }
                            }
                        } else {
                            val hasAppliedOnThisPage = placedWatermarks.any { it.pageIndex == currentPage || it.pageIndex == -1 }

                            if (!hasAppliedOnThisPage) {
                                Button(
                                    onClick = { showWatermarkOptions = true },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.watermark_add_watermark), fontWeight = FontWeight.Black)
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = { lightboxPage = null },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    ) { Text(stringResource(R.string.action_add_more), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black) }
                                    Button(
                                        onClick = {
                                            lightboxPage = null
                                            saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-watermarked", "pdf"))
                                        },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) { Text(stringResource(R.string.action_save), fontSize = 14.sp, fontWeight = FontWeight.Black) }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (showWatermarkOptions) {
        AlertDialog(
            onDismissRequest = { showWatermarkOptions = false },
            title = { Text(stringResource(R.string.watermark_select_watermark), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolSectionLabel(stringResource(R.string.watermark_templates))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.watermark_draft, R.string.watermark_confidential, R.string.watermark_approved).map { stringResource(it) }.forEach { template ->
                            Surface(
                                onClick = {
                                    startPlacing(createTextBitmap(template, DocumentInk.TemplateGray), opacity = 0.5f)
                                    showWatermarkOptions = false
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = accentColor.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.1f))
                            ) {
                                Text(template, fontSize = 12.sp, fontWeight = FontWeight.Black, color = accentColor, modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SignOptionCard(stringResource(R.string.watermark_text_watermark), Icons.Filled.Title, accentColor, Modifier.fillMaxWidth()) {
                        showTextInput = true; showWatermarkOptions = false
                    }
                    SignOptionCard(stringResource(R.string.watermark_upload_image), Icons.Filled.CloudUpload, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth()) {
                        imgLauncher.launch("image/*"); showWatermarkOptions = false
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showTextInput) {
        var text by remember { mutableStateOf("") }
        var selectedColor by remember { mutableStateOf(DocumentInk.Black) }
        AlertDialog(
            onDismissRequest = { showTextInput = false },
            title = { Text(stringResource(R.string.watermark_text_watermark), fontWeight = FontWeight.Black) },
            text = {
                Column {
                    LazyRow(Modifier.fillMaxWidth().padding(bottom = 12.dp).selectableGroup()) {
                        items(DocumentInk.watermark) { ink -> InkSwatch(ink, selected = selectedColor == ink.color) { selectedColor = ink.color } }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.watermark_enter_watermark_text)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (text.isNotBlank()) {
                        startPlacing(createTextBitmap(text, selectedColor), opacity = 1f)
                        showTextInput = false
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = accentColor)) { Text(stringResource(R.string.action_continue)) }
            }
        )
    }
}

/**
 * Stamps every watermark onto its page, or onto every page for pageIndex -1. Each watermark's image is embedded once and shared by all the
 * pages it goes on, so "apply to all" does not multiply the file size.
 */
internal fun applyWatermarks(document: PDDocument, watermarks: List<PlacedWatermark>) {
    watermarks.forEach { wm ->
        val image = LosslessFactory.createFromImage(document, wm.bitmap)
        val targetIndices = if (wm.pageIndex == -1) (0 until document.numberOfPages) else listOf(wm.pageIndex)
        targetIndices.forEach { pageIdx ->
            if (pageIdx < document.numberOfPages) {
                val page = document.getPage(pageIdx)
                document.appendTo(page).use { cs ->
                    cs.drawStamp(image, PageBox.of(page), wm.normalizedX, wm.normalizedY, wm.normalizedWidth, wm.rotation, wm.opacity)
                }
            }
        }
    }
}

private const val TEXT_BITMAP_MIN_WIDTH = 1000
private const val TEXT_BITMAP_PADDING = 40

/** Width of a text watermark bitmap for text [measured] pixels wide: at least 1000, and wide enough that long text is not cut off. */
internal fun textBitmapWidth(measured: Float): Int = maxOf(TEXT_BITMAP_MIN_WIDTH, ceil(measured).toInt() + 2 * TEXT_BITMAP_PADDING)

fun createTextBitmap(text: String, color: Color): Bitmap {
    val paint = Paint().apply {
        this.color = color.toArgb()
        textSize = 120f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // A soft grey halo keeps white text visible on white pages.
        if (color == DocumentInk.White) {
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.GRAY)
        }
    }
    val width = textBitmapWidth(paint.measureText(text))
    val bitmap = Bitmap.createBitmap(width, 400, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawText(text, width / 2f, 220f, paint)
    return bitmap
}
