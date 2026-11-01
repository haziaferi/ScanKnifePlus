package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The frame Crop starts with: the whole page, so nothing is cut until a handle moves. */
internal val FULL_FRAME = CropArea.Fractions(0f, 0f, 1f, 1f)

/** The narrowest the frame gets, as a fraction of the page's width or height. */
internal const val MIN_FRAME = 0.05f

/** A corner handle of the crop frame; [left] and [top] say which edges it moves, [label] names it. */
internal enum class FrameCorner(val left: Boolean, val top: Boolean, @StringRes val label: Int) {
    TOP_LEFT(true, true, R.string.scan_corner_top_left),
    TOP_RIGHT(false, true, R.string.scan_corner_top_right),
    BOTTOM_RIGHT(false, false, R.string.scan_corner_bottom_right),
    BOTTOM_LEFT(true, false, R.string.scan_corner_bottom_left)
}

/** An edge of the crop frame, which TalkBack moves as an adjustable value (dragging a corner is not accessible). */
internal enum class FrameEdge(@StringRes val label: Int) {
    LEFT(R.string.crop_edge_left), TOP(R.string.crop_edge_top), RIGHT(R.string.crop_edge_right), BOTTOM(R.string.crop_edge_bottom)
}

/** This frame with [corner] dragged by [dx] and [dy], fractions of the page's width and height, kept on the page and [MIN_FRAME] across. */
internal fun CropArea.Fractions.moveCorner(corner: FrameCorner, dx: Float, dy: Float): CropArea.Fractions = CropArea.Fractions(
    left = if (corner.left) (left + dx).coerceIn(0f, right - MIN_FRAME) else left,
    top = if (corner.top) (top + dy).coerceIn(0f, bottom - MIN_FRAME) else top,
    right = if (corner.left) right else (right + dx).coerceIn(left + MIN_FRAME, 1f),
    bottom = if (corner.top) bottom else (bottom + dy).coerceIn(top + MIN_FRAME, 1f)
)

/** Where [edge] of this frame is: a fraction of the page's width (left, right) or height (top, bottom). */
internal fun CropArea.Fractions.edge(edge: FrameEdge): Float = when (edge) {
    FrameEdge.LEFT -> left
    FrameEdge.TOP -> top
    FrameEdge.RIGHT -> right
    FrameEdge.BOTTOM -> bottom
}

/** This frame with [edge] at [value], kept on the page and [MIN_FRAME] from the opposite edge. */
internal fun CropArea.Fractions.withEdge(edge: FrameEdge, value: Float): CropArea.Fractions = when (edge) {
    FrameEdge.LEFT -> copy(left = value.coerceIn(0f, right - MIN_FRAME))
    FrameEdge.TOP -> copy(top = value.coerceIn(0f, bottom - MIN_FRAME))
    FrameEdge.RIGHT -> copy(right = value.coerceIn(left + MIN_FRAME, 1f))
    FrameEdge.BOTTOM -> copy(bottom = value.coerceIn(top + MIN_FRAME, 1f))
}

/**
 * Crop pages: one frame, dragged by its corners on the page in the viewer, kept as fractions of the page so every page gets it scaled
 * (14a); the grid shows it on the other pages, and Pages chooses all of them or a range. The saved copy's pages get the frame as their crop
 * box, the content stays in the file (hence the tip pointing to Redact), and it keeps the source's password.
 */
@Composable
fun CropView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("crop")
    val tip = rememberToolTip("crop")

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    // The source's open password, null when it opens without one; the file as picked is used throughout, never a decrypted copy.
    var password by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var boxes by remember { mutableStateOf<List<PageBox>>(emptyList()) }
    var frame by remember { mutableStateOf(FULL_FRAME) }
    var shownPage by remember { mutableIntStateOf(0) }
    var useRange by remember { mutableStateOf(false) }
    var rangeText by remember { mutableStateOf("") }
    var readPage by remember { mutableStateOf<Int?>(null) }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var outputName by remember { mutableStateOf("") }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)
    val pageCount = boxes.size
    val range = PageRanges.parseList(rangeText, pageCount)

    fun reset() {
        sourceUri = null
        password = null
        boxes = emptyList()
        frame = FULL_FRAME
        shownPage = 0
        useRange = false
        rangeText = ""
        outputUri = null
        currentState = ToolState.SELECTING
    }

    // Reads the pages of [source] opened with [pass]; a password that does not open it asks again.
    suspend fun open(source: Uri, pass: String?) {
        val read = readPageBoxes(context, source, pass)
        when {
            read.isNotEmpty() -> {
                boxes = read
                password = pass
                fileToUnlock = null
                currentState = ToolState.CONFIGURING
            }
            pass != null -> {
                unlockError = true
                fileToUnlock = fileName
            }
            else -> {
                Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                reset()
            }
        }
    }

    fun handleFileSelection(uri: Uri, given: String? = null) {
        reset()
        sourceUri = uri
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                    reset()
                }
                PdfAccess.Encrypted -> if (given == null) fileToUnlock = fileName else open(uri, given)
                PdfAccess.Open -> open(uri, null)
            }
            isFileLoading = false
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it, initialPassword) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
        val source = sourceUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        val pass = password
        val area = frame
        // The range was checked before the button could be pressed.
        val pages = if (useRange) (range as RangeList.Valid).ranges.flatten().toSet() else null
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val cropped = cropPdf(context, source, uri, pass, area, pages)
                val timeStr = formatElapsed(startTime)
                val name = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    outputName = name
                    val summary = resources.getQuantityString(R.plurals.crop_pages_cropped, cropped, cropped)
                    outputs.saved(uri, name, summary, Icons.Outlined.Crop, pageCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("CropView", "Cropping failed", e)
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
            ToolScreenBar(
                currentState,
                stringResource(R.string.tool_crop),
                stringResource(R.string.tool_crop_desc),
                processingTime,
                onBack,
                onShowTip = if (currentState == ToolState.CONFIGURING && !tip.shown) tip::show else null
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.Crop,
                            title = stringResource(R.string.crop_title),
                            task = stringResource(R.string.crop_task),
                            onChoose = { pickLauncher.launch(PDF_MIME) },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        val source = sourceUri
                        if (source != null && pageCount > 0) {
                            val error = if (useRange) rangeError(range) else null
                            ToolConfigLayout(
                                action = stringResource(if (error != null) R.string.range_check else R.string.crop_and_save),
                                onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-cropped", "pdf")) },
                                actionEnabled = error == null
                            ) {
                                ToolFileCard(
                                    uri = source,
                                    password = password,
                                    name = fileName,
                                    details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                    onChange = { reset() },
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                    keepsPassword = password != null
                                )
                                if (tip.shown) {
                                    ToolTipCard(
                                        stringResource(R.string.crop_tip),
                                        onClose = tip::close,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                                    )
                                }
                                val shown = shownPage.coerceIn(0, pageCount - 1)
                                PageViewer(
                                    uri = source,
                                    password = password,
                                    page = shown,
                                    pageCount = pageCount,
                                    aspect = boxes[shown].displayAspect,
                                    onPageChange = { shownPage = it },
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                    pagePadding = PaddingValues(vertical = 20.dp)
                                ) {
                                    CropFrame(frame, onChange = { frame = it })
                                }
                                ToolSectionLabel(
                                    stringResource(R.string.crop_frame_others),
                                    Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
                                )
                                PageGrid(
                                    pageCount = pageCount,
                                    isSelected = { it == shown },
                                    tapLabel = { stringResource(R.string.page_show, it + 1) },
                                    onTap = { shownPage = it },
                                    onRead = { readPage = it },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) { index ->
                                    PdfPageMiniature(source, password, index, boxes[index].displayAspect) { CropMarks(frame) }
                                }
                                Text(
                                    stringResource(R.string.crop_same_frame) + " " + stringResource(R.string.page_hold_hint),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
                                )
                                ToolSectionLabel(
                                    stringResource(R.string.pages_label),
                                    Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
                                )
                                SegmentedChoice(
                                    options = listOf(stringResource(R.string.pages_all), stringResource(R.string.pages_range)),
                                    selected = if (useRange) 1 else 0,
                                    onSelect = { useRange = it == 1 },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                if (useRange) {
                                    PageRangeField(rangeText, { rangeText = it }, error, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = sourceUri,
                            password = password,
                            text = stringResource(R.string.crop_cropping),
                            current = 0,
                            total = 0,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.crop_done),
                            subMessage = pluralStringResource(R.plurals.scan_pages, pageCount, pageCount),
                            result = outputUri?.let { ToolResult(it, outputName, password = password) },
                            onDone = onBack,
                            onProcessMore = { reset() },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, outputName, pageCount) } },
                            keepsPassword = password != null
                        )
                    }
                }
            }

            val source = sourceUri
            val reading = readPage
            if (source != null && reading != null && currentState == ToolState.CONFIGURING) {
                PageLightbox(
                    uri = source,
                    initialPage = reading,
                    totalCount = pageCount,
                    password = password,
                    onDismiss = { readPage = null },
                    fileName = fileName
                )
            }

            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { fileToUnlock = null; unlockError = false; reset() },
                    onUnlocked = { pass ->
                        val unlocking = sourceUri
                        if (unlocking != null) {
                            isFileLoading = true
                            scope.launch {
                                open(unlocking, pass)
                                isFileLoading = false
                            }
                        }
                    },
                    isError = unlockError,
                    accentColor = MaterialTheme.colorScheme.primary,
                    isLoading = isFileLoading
                )
            }
        }
    }
}

/**
 * The crop frame over the page in the viewer: the outside dimmed, the frame and an L at each corner in [pageMark], and a 48 dp handle on
 * each corner that drags it ([onChange]). For TalkBack each edge is an adjustable value in steps of 1% of the page, since dragging alone is
 * not accessible; the handles themselves are hidden from it.
 */
@Composable
private fun CropFrame(frame: CropArea.Fractions, onChange: (CropArea.Fractions) -> Unit) {
    val mark = pageMark
    val dim = pageDim
    val current by rememberUpdatedState(frame)
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val half = with(LocalDensity.current) { (TARGET / 2).roundToPx() }
        Canvas(Modifier.fillMaxSize()) {
            drawCropFrame(frame, mark, dim, 2.dp.toPx())
            FrameCorner.entries.forEach { drawCornerMark(frame, it, mark) }
        }
        FrameEdge.entries.forEach { edge ->
            val label = stringResource(edge.label)
            val (x, y) = when (edge) {
                FrameEdge.LEFT, FrameEdge.RIGHT -> frame.edge(edge) * width to (frame.top + frame.bottom) / 2 * height
                FrameEdge.TOP, FrameEdge.BOTTOM -> (frame.left + frame.right) / 2 * width to frame.edge(edge) * height
            }
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt() - half, y.roundToInt() - half) }
                    .size(TARGET)
                    .semantics {
                        contentDescription = label
                        progressBarRangeInfo = ProgressBarRangeInfo(frame.edge(edge), 0f..1f, steps = 99)
                        setProgress { value ->
                            change(current.withEdge(edge, value))
                            true
                        }
                    }
            )
        }
        FrameCorner.entries.forEach { corner ->
            val label = stringResource(corner.label)
            val x = (if (corner.left) frame.left else frame.right) * width
            val y = (if (corner.top) frame.top else frame.bottom) * height
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt() - half, y.roundToInt() - half) }
                    .size(TARGET)
                    .semantics {
                        contentDescription = label
                        hideFromAccessibility()
                    }
                    .pointerInput(corner, width, height) {
                        // The corner follows the finger from where the drag began, slop included, so it stays under the finger and a
                        // drag past the page's edge and back does not drift.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = current
                            val first = awaitTouchSlopOrCancellation(down.id) { pointer, _ -> pointer.consume() } ?: return@awaitEachGesture
                            var moved = first.position - down.position
                            change(start.moveCorner(corner, moved.x / width, moved.y / height))
                            drag(first.id) { pointer ->
                                moved += pointer.positionChange()
                                pointer.consume()
                                change(start.moveCorner(corner, moved.x / width, moved.y / height))
                            }
                        }
                    }
            )
        }
    }
}

/** The touch target of a frame handle (and of an edge for TalkBack). */
private val TARGET: Dp = 48.dp

/** The frame as a miniature in the grid shows it over its page: the outside dimmed and a thin frame, no handles. */
@Composable
private fun CropMarks(frame: CropArea.Fractions) {
    val mark = pageMark
    val dim = pageDim
    Canvas(Modifier.fillMaxSize()) { drawCropFrame(frame, mark, dim, 1.5.dp.toPx()) }
}

/** Dims the page outside [frame] with [dim] and outlines the frame, [stroke] wide and inside it, in [mark]. */
private fun DrawScope.drawCropFrame(frame: CropArea.Fractions, mark: Color, dim: Color, stroke: Float) {
    val l = frame.left * size.width
    val t = frame.top * size.height
    val r = frame.right * size.width
    val b = frame.bottom * size.height
    drawRect(dim, Offset.Zero, Size(size.width, t))
    drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
    drawRect(dim, Offset(0f, t), Size(l, b - t))
    drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
    drawRect(mark, Offset(l + stroke / 2, t + stroke / 2), Size(r - l - stroke, b - t - stroke), style = Stroke(stroke))
}

/** The L of a handle: two 20 x 4 dp bars from [corner]'s point along its two edges, inside the frame. */
private fun DrawScope.drawCornerMark(frame: CropArea.Fractions, corner: FrameCorner, mark: Color) {
    val x = (if (corner.left) frame.left else frame.right) * size.width
    val y = (if (corner.top) frame.top else frame.bottom) * size.height
    val long = 20.dp.toPx()
    val thick = 4.dp.toPx()
    val left = if (corner.left) x else x - long
    val top = if (corner.top) y else y - thick
    drawRect(mark, Offset(left, top), Size(long, thick))
    drawRect(mark, Offset(if (corner.left) x else x - thick, if (corner.top) y else y - long), Size(thick, long))
}
