package com.paperknifeplus.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "UltraPreview"

/** Scale of the page renders shown first; the sharper layer replaces them once it is ready. */
private const val LOW_RES_SCALE = 1.8f

/** Pixel budget of one sharp page layer, well under the 100 MiB a canvas may draw. */
private const val HIGH_RES_BUDGET_BYTES = 48f * 1024 * 1024

/**
 * Render scale of the sharp page layer for a page of [widthPt] x [heightPt] points: as sharp as [HIGH_RES_BUDGET_BYTES] of ARGB allows, at
 * most 12x and never below the first render's scale.
 */
fun highResScale(widthPt: Float, heightPt: Float): Float = max(LOW_RES_SCALE, min(12f, sqrt(HIGH_RES_BUDGET_BYTES / (4f * widthPt * heightPt))))

/** Where a link sits on its displayed page, as fractions of the page's width and height from the top-left corner. */
data class LinkArea(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Maps the link rectangle [llx], [lly], [urx], [ury] (user space) onto the displayed [page]: the cropBox origin is subtracted and the
 * page rotated as pdfium shows it, so links line up on rotated, offset and cropped pages.
 */
fun mapLinkRect(llx: Float, lly: Float, urx: Float, ury: Float, page: PageBox): LinkArea {
    val a = page.pdfToDisplay(llx, lly)
    val b = page.pdfToDisplay(urx, ury)
    return LinkArea(
        left = min(a.x, b.x) / page.displayWidth,
        top = min(a.y, b.y) / page.displayHeight,
        width = abs(a.x - b.x) / page.displayWidth,
        height = abs(a.y - b.y) / page.displayHeight
    )
}

private val SAFE_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")
private val URI_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/**
 * [url] with its scheme in lower case if it is a web, mail or phone link, else null: a PDF is untrusted, and its links must not open files,
 * content providers, intents or other apps' deep links.
 */
fun safeLinkUri(url: String): String? {
    val trimmed = url.trim()
    val scheme = URI_SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase() ?: return null
    return if (scheme in SAFE_LINK_SCHEMES) scheme + trimmed.substring(scheme.length) else null
}

/**
 * The file the reader was opened with ([uri]) and the copy it shows ([displayUri], a decrypted cache copy once unlocked). Tools are handed
 * the original with the password the user unlocked it with, never the cache copy.
 */
internal data class ReaderSource(val uri: Uri, val displayUri: Uri = uri, val unlockedPassword: String? = null) {
    fun unlocked(copy: Uri, password: String) = copy(displayUri = copy, unlockedPassword = password)

    /** The uri and password [UltraPreview]'s onOpenInTool passes on. */
    fun toolHandOff(): Pair<Uri, String?> = uri to unlockedPassword
}

/** What the reader needs of a document: each page's displayed box and its web links. */
internal data class ReaderDocument(val pages: List<PageBox>, val links: List<PdfLink>)

/** Reads [input]'s page boxes and URI links. Parsing spills to temp files, so a large PDF does not fill the heap. Blocking; throws on failure. */
internal fun readReaderDocument(input: InputStream): ReaderDocument =
    PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
        val pages = document.pages.map(PageBox::of)
        val links = document.pages.flatMapIndexed { index, page ->
            page.annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
                val url = (link.action as? PDActionURI)?.uri
                val rect = link.rectangle
                if (url != null && rect != null) PdfLink(index, rect, url) else null
            }
        }
        ReaderDocument(pages, links)
    }

/** [uri]'s [ReaderDocument], or null when it cannot be read, also for lack of memory. */
internal suspend fun loadReaderDocument(context: Context, uri: Uri): ReaderDocument? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.requireInputStream(uri).use(::readReaderDocument)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot load $uri", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory loading $uri", e)
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UltraPreview(
    uri: Uri,
    fileName: String,
    pageCount: Int,
    onDismiss: () -> Unit,
    onOpenInTool: (String, Uri, String?) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpPageInput by remember { mutableStateOf("") }
    var showToolPicker by remember { mutableStateOf(false) }
    // A link tapped in the PDF waits here until the user confirms it (decision 27d), as every other web link in the app does.
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    var targetScale by remember { mutableFloatStateOf(1f) }
    var targetOffset by remember { mutableStateOf(Offset.Zero) }

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "scale"
    )
    val offset by animateOffsetAsState(
        targetValue = targetOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "offset"
    )

    var links by remember { mutableStateOf<List<PdfLink>>(emptyList()) }
    val linksByPage = remember(links) { links.groupBy { it.pageIndex } }
    var pageBoxes by remember { mutableStateOf<List<PageBox>>(emptyList()) }
    var isInitializing by remember { mutableStateOf(true) }
    var source by remember(uri) { mutableStateOf(ReaderSource(uri)) }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var isDecrypting by remember { mutableStateOf(false) }
    var activePageCount by remember { mutableIntStateOf(pageCount) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    // The shared loader's fetcher and keyer with a larger cache of its own, for the sharp pages shown here.
    val imageLoader = remember {
        PdfImageLoader.get(context).newBuilder()
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.45).build() }
            .build()
    }

    DisposableEffect(uri) {
        onDispose { deleteDecryptedCopy(context, source.displayUri) }
    }

    // Opens the file, or with [password] its decrypted copy; run again after unlocking, cancelling the previous load.
    fun initializeReader(password: String?) {
        loadJob?.cancel()
        loadJob = scope.launch {
            isInitializing = true
            if (password == null) {
                when (inspectPdf(context, uri)) {
                    PdfAccess.Unreadable -> {
                        Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                        onDismiss()
                        return@launch
                    }
                    PdfAccess.Encrypted -> {
                        fileToUnlock = fileName
                        isInitializing = false
                        return@launch
                    }
                    PdfAccess.Open -> {}
                }
            } else {
                isDecrypting = true
                val copy = decryptToCache(context, uri, password)
                isDecrypting = false
                if (copy == null) {
                    unlockError = true
                    fileToUnlock = fileName
                    isInitializing = false
                    return@launch
                }
                deleteDecryptedCopy(context, source.displayUri)
                source = source.unlocked(copy, password)
            }

            val document = loadReaderDocument(context, source.displayUri)
            if (document == null) {
                Toast.makeText(context, toolText(context, R.string.reader_failed_to_load_document), Toast.LENGTH_SHORT).show()
            } else {
                activePageCount = document.pages.size
                pageBoxes = document.pages
                links = document.links
            }
            isInitializing = false
        }
    }

    LaunchedEffect(uri) {
        initializeReader(null)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isInitializing) {
                LoadingStateView(showWarning = false, text = stringResource(R.string.reader_loading_document_view))
            } else if (fileToUnlock == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { tap ->
                                    if (targetScale > 1.01f) {
                                        targetScale = 1f
                                        targetOffset = Offset.Zero
                                    } else {
                                        targetScale = 3f
                                        targetOffset = doubleTapZoomOffset(tap, size.toSize(), 3f)
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (targetScale * zoom).coerceIn(1f, 10f)

                                if (newScale > 1f) {
                                    targetScale = newScale
                                    targetOffset = zoomPanClamp(targetOffset + pan, size.toSize(), newScale)
                                } else {
                                    targetScale = 1f
                                    targetOffset = Offset.Zero
                                }
                            }
                        }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        contentPadding = PaddingValues(top = 80.dp, bottom = 100.dp), // Top padding keeps the first page clear of the header.
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        userScrollEnabled = scale == 1f
                    ) {
                        items(activePageCount) { index ->
                            PdfPageReaderItem(
                                uri = source.displayUri,
                                index = index,
                                imageLoader = imageLoader,
                                pageBox = pageBoxes.getOrNull(index),
                                links = linksByPage[index].orEmpty(),
                                onLinkClick = { url ->
                                    val safe = safeLinkUri(url)
                                    if (safe != null) {
                                        pendingLink = safe
                                    } else {
                                        Toast.makeText(context, toolText(context, R.string.reader_cannot_open_link), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            pendingLink?.let { WebLinkPrompt(it, onDismiss = { pendingLink = null }) }

            if (!isInitializing && fileToUnlock == null) {
                var isDragging by remember { mutableStateOf(false) }
                var dragProgress by remember { mutableFloatStateOf(0f) }
                var trackHeight by remember { mutableFloatStateOf(0f) }

                val scrollPercentage by remember {
                    derivedStateOf {
                        if (isDragging) dragProgress
                        else if (activePageCount <= 1) 0f
                        else {
                            val layoutInfo = listState.layoutInfo
                            val visibleItems = layoutInfo.visibleItemsInfo
                            if (visibleItems.isEmpty()) 0f
                            else {
                                val firstItem = visibleItems.first()
                                val index = firstItem.index
                                val offset = -firstItem.offset.toFloat()
                                val size = firstItem.size.toFloat()
                                val progress = (index + (offset / size)) / (activePageCount - 1).coerceAtLeast(1)
                                progress.coerceIn(0f, 1f)
                            }
                        }
                    }
                }

                val currentPage by remember {
                    derivedStateOf {
                        if (!listState.canScrollForward && activePageCount > 0) activePageCount
                        else (listState.firstVisibleItemIndex + 1).coerceIn(1, activePageCount)
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .fillMaxHeight(0.75f)
                        .width(48.dp)
                        .onGloballyPositioned { trackHeight = it.size.height.toFloat() }
                        .pointerInput(activePageCount, trackHeight) {
                            detectTapGestures { offset ->
                                if (trackHeight > 0) {
                                    val newPercent = (offset.y / trackHeight).coerceIn(0f, 1f)
                                    val targetPage = (newPercent * (activePageCount - 1)).roundToInt()
                                    scope.launch { listState.scrollToItem(targetPage.coerceIn(0, activePageCount - 1)) }
                                }
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier.fillMaxHeight().width(6.dp).align(Alignment.Center).background(MaterialTheme.colorScheme.outline, CircleShape)
                    )

                    if (trackHeight > 0 && activePageCount > 1) {
                        val minThumbHeight = 48.dp
                        val thumbHeightFactor = 1f / activePageCount.coerceAtLeast(1)
                        val thumbHeightPx = with(density) {
                            (trackHeight * thumbHeightFactor.coerceIn(0.08f, 0.25f)).coerceAtLeast(minThumbHeight.toPx())
                        }

                        Box(
                            modifier = Modifier
                                .height(with(density) { thumbHeightPx.toDp() })
                                .width(12.dp)
                                .graphicsLayer {
                                    translationY = scrollPercentage * (trackHeight - thumbHeightPx)
                                }
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .align(Alignment.TopCenter)
                                .draggable(
                                    orientation = Orientation.Vertical,
                                    state = rememberDraggableState { delta ->
                                        if (trackHeight > thumbHeightPx && activePageCount > 1) {
                                            isDragging = true
                                            val newPercent = (scrollPercentage + delta / (trackHeight - thumbHeightPx)).coerceIn(0f, 1f)
                                            dragProgress = newPercent
                                            val targetPage = (newPercent * (activePageCount - 1)).roundToInt()
                                            scope.launch { listState.scrollToItem(targetPage.coerceIn(0, activePageCount - 1)) }
                                        }
                                    },
                                    onDragStopped = { isDragging = false }
                                )
                        )
                    }
                }

                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
                ) {
                    Surface(
                        onClick = {
                            jumpPageInput = currentPage.toString()
                            showJumpDialog = true
                        },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(24.dp),
                        tonalElevation = 12.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$currentPage", fontWeight = FontWeight.Black, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.reader_of_pages, activePageCount), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                            Spacer(Modifier.width(16.dp))
                            Box(Modifier.height(16.dp).width(1.dp).background(MaterialTheme.colorScheme.outline))
                            Spacer(Modifier.width(16.dp))
                            Icon(Icons.Filled.UnfoldMore, null, modifier = Modifier.size(16.dp).alpha(0.5f), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tool_back), modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }

                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(fileName, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.reader_pdf_viewer), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    IconButton(onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, toolText(context, R.string.action_share)))
                        } catch (e: Exception) {
                            Toast.makeText(context, toolText(context, R.string.tool_share_failed), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.action_share), tint = MaterialTheme.colorScheme.onSurface)
                    }

                    IconButton(onClick = { showToolPicker = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.reader_tools), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }

        if (showToolPicker) {
            ModalBottomSheet(
                onDismissRequest = { showToolPicker = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
                ToolPickerContent(
                    onToolClick = { tool ->
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showToolPicker = false
                            val (toolUri, toolPassword) = source.toolHandOff()
                            onOpenInTool(tool, toolUri, toolPassword)
                        }
                    }
                )
            }
        }

        if (fileToUnlock != null) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 100.dp)) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = onDismiss,
                    onUnlocked = { pass ->
                        fileToUnlock = null
                        initializeReader(pass)
                    },
                    accentColor = MaterialTheme.colorScheme.primary,
                    isLoading = isDecrypting,
                    isError = unlockError
                )
            }
        }

        if (showJumpDialog) {
            JumpToPageDialog(
                initialInput = jumpPageInput,
                pageCount = activePageCount,
                onJump = { index ->
                    scope.launch { listState.scrollToItem(index) }
                    showJumpDialog = false
                },
                onDismiss = { showJumpDialog = false }
            )
        }
    }
}

@Composable
fun PdfPageReaderItem(
    uri: Uri,
    index: Int,
    imageLoader: ImageLoader,
    pageBox: PageBox?,
    links: List<PdfLink>,
    onLinkClick: (String) -> Unit
) {
    val context = LocalContext.current
    val lowResRequest = remember(uri, index) { PdfPageRequest(uri, index, null, LOW_RES_SCALE, priority = 1) }
    // An explicit size: Coil would otherwise wait to measure the image, which stays hidden until this request succeeds.
    val highResRequest = remember(uri, index, pageBox) {
        val scale = pageBox?.let { highResScale(it.displayWidth, it.displayHeight) } ?: LOW_RES_SCALE
        ImageRequest.Builder(context).data(PdfPageRequest(uri, index, null, scale, priority = 0)).size(coil.size.Size.ORIGINAL).build()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(pageBox?.displayAspect ?: FALLBACK_PAGE_ASPECT)
            .padding(vertical = 4.dp)
            .shadow(4.dp)
            .background(DocumentInk.Paper)
    ) {
        val lowResPainter = rememberAsyncImagePainter(lowResRequest, imageLoader)
        val highResPainter = rememberAsyncImagePainter(highResRequest, imageLoader)

        Image(
            painter = lowResPainter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        AnimatedVisibility(
            visible = highResPainter.state is AsyncImagePainter.State.Success,
            enter = fadeIn(tween(400)),
            exit = fadeOut()
        ) {
            Image(
                painter = highResPainter,
                contentDescription = stringResource(R.string.tool_page_n, index + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        if (lowResPainter.state is AsyncImagePainter.State.Loading && highResPainter.state is AsyncImagePainter.State.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
        }

        if (pageBox != null) {
            links.forEach { link ->
                val area = mapLinkRect(link.rect.lowerLeftX, link.rect.lowerLeftY, link.rect.upperRightX, link.rect.upperRightY, pageBox)
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * area.left, y = maxHeight * area.top)
                        .size(width = maxWidth * area.width, height = maxHeight * area.height)
                        .clickable(onClickLabel = link.url, role = Role.Button) { onLinkClick(link.url) }
                        .semantics { contentDescription = link.url }
                )
            }
        }
    }
}
