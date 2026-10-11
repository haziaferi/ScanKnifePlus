package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import coil.compose.rememberAsyncImagePainter
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.paperknifeplus.app.ui.theme.PaperPink
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

/** The aspect ratio Sign and Watermark assume for a page whose box could not be read: A4 portrait. */
internal const val FALLBACK_PAGE_ASPECT = 0.707f

data class PlacedSignature(
    val id: String = java.util.UUID.randomUUID().toString(),
    val pageIndex: Int,
    val bitmap: Bitmap,
    val normalizedX: Float, // Centre, as fractions of the displayed page
    val normalizedY: Float,
    val normalizedWidth: Float, // Width as a fraction of the displayed page width
    val rotation: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalIsDarkTheme.current
    val accentColor = PaperPink

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf(initialUri) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var pageBoxes by remember { mutableStateOf<List<PageBox>>(emptyList()) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    val pageCount = pageBoxes.size

    var placedSignatures by remember { mutableStateOf<List<PlacedSignature>>(emptyList()) }
    var activeSignatureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeOffset by remember { mutableStateOf(Offset.Zero) }
    var activeScale by remember { mutableFloatStateOf(1f) }
    var activeRotation by remember { mutableFloatStateOf(0f) }
    var isFocusMode by remember { mutableStateOf(false) }
    var lightboxPage by remember { mutableStateOf<Int?>(null) }
    // The lightbox page area as its overlay measured it, which APPLY normalises against.
    var lightboxSize by remember { mutableStateOf(Size.Zero) }

    var showSignOptions by remember { mutableStateOf(false) }
    var showSignaturePad by remember { mutableStateOf(false) }
    var savedSignatures by remember { mutableStateOf<List<File>>(emptyList()) }

    val imageLoader = PdfImageLoader.get(context)

    suspend fun loadSavedSignatures() {
        savedSignatures = withContext(Dispatchers.IO) {
            signatureDir(context).listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }.orEmpty()
        }
    }

    fun saveSignature(bitmap: Bitmap) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    File(signatureDir(context), "sig_${System.currentTimeMillis()}.png").outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    Log.w("SignView", "Could not save the signature", e)
                }
            }
            loadSavedSignatures()
        }
    }

    fun startPlacing(bitmap: Bitmap?) {
        activeSignatureBitmap = bitmap
        activeOffset = Offset.Zero
        activeScale = 1f
        activeRotation = 0f
        isFocusMode = true
    }

    fun fitRectOf(pageIndex: Int, containerWidth: Float, containerHeight: Float) =
        LayoutMath.getFitRect(containerWidth, containerHeight, pageBoxes.getOrNull(pageIndex)?.displayAspect ?: FALLBACK_PAGE_ASPECT)

    LaunchedEffect(Unit) { loadSavedSignatures() }

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        fileName = getUriDetails(context, uri).name
        unlockError = false
        isFileLoading = true
        scope.launch {
            val access = inspectPdf(context, uri)
            when {
                access == PdfAccess.Unreadable -> {
                    Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                needsUnlockPrompt(access, password = null) -> fileToUnlock = fileName
                else -> {
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

    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                readPickedImage(context, it).onSuccess { bitmap ->
                    startPlacing(bitmap)
                    showSignOptions = false
                }
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val signatures = placedSignatures
        scope.launch(Dispatchers.IO) {
            try {
                rewritePdf(context, source, unlockPassword, uri) { applySignatures(it, signatures) }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    SessionManager.addEntry(outputName, "Sign", "Document signed locally", Icons.Filled.Draw, uri, pageCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("SignView", "Signing failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    var selectedSignatureFile by remember { mutableStateOf<File?>(null) }

    val exportSignatureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val fileToExport = selectedSignatureFile
        if (uri == null || fileToExport == null) return@rememberLauncherForActivityResult
        scope.launch { Toast.makeText(context, exportSignature(context, fileToExport, uri), Toast.LENGTH_SHORT).show() }
    }

    // Placed signatures and the one being placed, positioned over the page as the page image is fitted into this overlay's box.
    @Composable
    fun SignatureOverlay(pageIndex: Int, onMeasured: ((Size) -> Unit)?) {
        val measured = if (onMeasured == null) Modifier else Modifier.onSizeChanged { onMeasured(it.toSize()) }
        BoxWithConstraints(Modifier.fillMaxSize().then(measured)) {
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()
            val fitRect = fitRectOf(pageIndex, containerWidth, containerHeight)
            val pageWidth = fitRect.width()
            val pageHeight = fitRect.height()

            placedSignatures.filter { it.pageIndex == pageIndex }.forEach { sig ->
                ComposeImage(
                    bitmap = sig.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .requiredSize(
                            width = (pageWidth * sig.normalizedWidth).dp / LocalDensity.current.density,
                            height = (pageWidth * sig.normalizedWidth * (sig.bitmap.height.toFloat() / sig.bitmap.width.toFloat())).dp / LocalDensity.current.density
                        )
                        .graphicsLayer {
                            translationX = fitRect.left + (sig.normalizedX * pageWidth) - (containerWidth / 2)
                            translationY = fitRect.top + (sig.normalizedY * pageHeight) - (containerHeight / 2)
                            rotationZ = sig.rotation
                        }
                        .align(Alignment.Center)
                )
            }

            if (isFocusMode && activeSignatureBitmap != null && (lightboxPage == pageIndex || lightboxPage == null)) {
                val bitmap = activeSignatureBitmap!!
                val baseWidth = pageWidth * 0.4f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, rot ->
                                activeOffset += pan
                                activeScale = (activeScale * zoom).coerceIn(0.2f, 5f)
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
                            }
                            .align(Alignment.Center)
                            .border(1.dp, accentColor, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = "Sign PDF",
                    subtitle = "SECURE SIGNATURE",
                    accent = accentColor,
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = { selectedUri = null; currentState = ToolState.SELECTING; placedSignatures = emptyList() }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(accentColor, false, "Preparing document...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") },
                            isDark = isDark,
                            icon = Icons.Filled.Draw,
                            title = "Tap to enter file",
                            subtitle = "SIGN ANY PDF DOCUMENT",
                            accentColor = accentColor,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
                        )
                    }
                    ToolState.CONFIGURING -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(12.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.GRID,
                                    password = unlockPassword.ifEmpty { null },
                                    accentColor = accentColor,
                                    showSelectionIcon = false,
                                    showZoomIcon = false,
                                    itemOverlay = { SignatureOverlay(it, onMeasured = null) },
                                    onToggleSelection = { index -> lightboxPage = index }
                                )
                            }

                            val hasSigned = placedSignatures.isNotEmpty()
                            Button(
                                onClick = { if (hasSigned) saveLauncher.launch(pdfBaseName(fileName) + "-signed.pdf") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).height(60.dp),
                                enabled = hasSigned,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hasSigned) accentColor else Color.Gray.copy(0.3f),
                                    contentColor = if (hasSigned) Color.White else Color.Gray
                                )
                            ) {
                                Text(if (hasSigned) "SAVE SIGNED PDF" else "SELECT A PAGE TO SIGN", fontWeight = FontWeight.Black)
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            accentColor = accentColor,
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Synthesizing PDF...",
                            current = 0,
                            total = 0,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Sign Complete",
                            subMessage = "Signature placed successfully",
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = {
                                selectedUri = null
                                placedSignatures = emptyList()
                                currentState = ToolState.SELECTING
                            },
                            onPreview = { outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) } },
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
            onDismiss = { lightboxPage = null; isFocusMode = false; activeSignatureBitmap = null },
            itemOverlay = { SignatureOverlay(it) { size -> lightboxSize = size } },
            bottomBar = { currentPage ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(0.85f),
                    border = BorderStroke(1.dp, Color.White.copy(0.1f))
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isFocusMode) {
                            TextButton(onClick = { isFocusMode = false; activeSignatureBitmap = null }, modifier = Modifier.weight(1f)) {
                                Text("CANCEL", color = Color.LightGray, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    activeSignatureBitmap?.let {
                                        val fitRect = fitRectOf(currentPage, lightboxSize.width, lightboxSize.height)
                                        placedSignatures = placedSignatures + PlacedSignature(
                                            pageIndex = currentPage,
                                            bitmap = it,
                                            normalizedX = (activeOffset.x + (lightboxSize.width / 2) - fitRect.left) / fitRect.width(),
                                            normalizedY = (activeOffset.y + (lightboxSize.height / 2) - fitRect.top) / fitRect.height(),
                                            normalizedWidth = 0.4f * activeScale,
                                            rotation = activeRotation
                                        )
                                    }
                                    isFocusMode = false
                                    activeSignatureBitmap = null
                                },
                                modifier = Modifier.weight(1.5f).height(50.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                            ) {
                                Text("APPLY", fontWeight = FontWeight.Black)
                            }
                        } else {
                            val hasAppliedOnThisPage = placedSignatures.any { it.pageIndex == currentPage }

                            if (!hasAppliedOnThisPage) {
                                Button(
                                    onClick = { showSignOptions = true },
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                                ) {
                                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("ADD SIGN", fontWeight = FontWeight.Black)
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = { lightboxPage = null },
                                        modifier = Modifier.weight(1f).height(50.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(0.3f))
                                    ) {
                                        Text("SIGN MORE", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Black)
                                    }
                                    Button(
                                        onClick = {
                                            lightboxPage = null
                                            saveLauncher.launch(pdfBaseName(fileName) + "-signed.pdf")
                                        },
                                        modifier = Modifier.weight(1f).height(50.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                                    ) {
                                        Text("SAVE", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (showSignOptions) {
        AlertDialog(
            onDismissRequest = { showSignOptions = false },
            title = { Text("Add Signature", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SignOptionCard("Draw", Icons.Filled.Gesture, accentColor, Modifier.weight(1f)) { showSignaturePad = true; showSignOptions = false }
                        SignOptionCard("Upload", Icons.Filled.CloudUpload, Color.Gray, Modifier.weight(1f)) { pngLauncher.launch("image/png"); showSignOptions = false }
                    }

                    if (savedSignatures.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        Text("SAVED SIGNATURES", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 1.sp)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(savedSignatures) { file ->
                                Box {
                                    Surface(
                                        modifier = Modifier.size(100.dp, 60.dp).clickable {
                                            showSignOptions = false
                                            scope.launch { startPlacing(withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }) }
                                        },
                                        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color.Gray.copy(0.2f))
                                    ) { ComposeImage(painter = rememberAsyncImagePainter(file, imageLoader), contentDescription = null, modifier = Modifier.padding(8.dp)) }

                                    IconButton(
                                        onClick = {
                                            selectedSignatureFile = file
                                            exportSignatureLauncher.launch("signature_${System.currentTimeMillis()}.png")
                                        },
                                        modifier = Modifier.align(Alignment.BottomEnd).size(22.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.FileDownload,
                                            contentDescription = "Export signature",
                                            tint = accentColor.copy(alpha = 0.7f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showSignaturePad) {
        SignaturePadDialog(
            onDismiss = { showSignaturePad = false },
            onSave = { bitmap, shouldSave ->
                if (shouldSave) saveSignature(bitmap)
                startPlacing(bitmap)
                showSignaturePad = false
                showSignOptions = false
            },
            accentColor = accentColor,
            initialColor = Color.Black
        )
    }
}

/**
 * Decodes the image the user picked: success with the bitmap (null if the file is no image, as BitmapFactory says), or failure, already
 * logged, when it cannot be read at all, e.g. a provider's SecurityException or running out of memory.
 */
internal suspend fun readPickedImage(context: Context, uri: Uri): Result<Bitmap?> = withContext(Dispatchers.IO) {
    try {
        Result.success(context.contentResolver.requireInputStream(uri).use { BitmapFactory.decodeStream(it) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("SignView", "Cannot read the picked image $uri", e)
        Result.failure(e)
    } catch (e: OutOfMemoryError) {
        Log.w("SignView", "Out of memory decoding the picked image $uri", e)
        Result.failure(e)
    }
}

/** Copies a saved signature [file] to [uri], removing [uri] on failure; returns the message to show. */
internal suspend fun exportSignature(context: Context, file: File, uri: Uri): String = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.requireOutputStream(uri).use { it.write(file.readBytes()) }
        "Signature exported"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("SignView", "Signature export failed", e)
        deleteCreatedDocument(context, uri)
        "Export failed: ${e.message}"
    }
}

private fun signatureDir(context: Context): File = File(context.filesDir, "signatures").apply { mkdirs() }

/** Stamps [signatures] onto their pages of [document]; each keeps its own image, as each has its own bitmap. */
private fun applySignatures(document: PDDocument, signatures: List<PlacedSignature>) {
    signatures.groupBy { it.pageIndex }.forEach { (pageIdx, sigs) ->
        if (pageIdx < document.numberOfPages) {
            val page = document.getPage(pageIdx)
            val box = PageBox.of(page)
            document.appendTo(page).use { cs ->
                sigs.forEach { sig ->
                    val image = LosslessFactory.createFromImage(document, sig.bitmap)
                    cs.drawStamp(image, box, sig.normalizedX, sig.normalizedY, sig.normalizedWidth, sig.rotation)
                }
            }
        }
    }
}

/** Size of a signature bitmap and the translation that moves the strokes into it. */
internal data class SignatureFrame(val width: Int, val height: Int, val dx: Float, val dy: Float)

/**
 * The frame for strokes whose paths span [left]..[right] x [top]..[bottom] in pad pixels, drawn [strokePx] wide: half a stroke plus
 * [padding] around them, at least 1x1, so no part of the signature is cut off wherever on the pad it was drawn.
 */
internal fun signatureFrame(left: Float, top: Float, right: Float, bottom: Float, strokePx: Float, padding: Float = 16f): SignatureFrame {
    val inset = strokePx / 2 + padding
    val frameLeft = left - inset
    val frameTop = top - inset
    val width = ceil(right + inset - frameLeft).toInt().coerceAtLeast(1)
    val height = ceil(bottom + inset - frameTop).toInt().coerceAtLeast(1)
    return SignatureFrame(width, height, -frameLeft, -frameTop)
}

/** Renders the pad's strokes, in their colours and [strokePx] wide, into a transparent bitmap cropped to them by [signatureFrame]. */
internal fun renderSignatureBitmap(paths: List<Pair<Path, Color>>, strokePx: Float = 16f): Bitmap {
    val bounds = RectF()
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    paths.forEach { (path, _) ->
        path.computeBounds(bounds, true)
        left = minOf(left, bounds.left)
        top = minOf(top, bounds.top)
        right = maxOf(right, bounds.right)
        bottom = maxOf(bottom, bounds.bottom)
    }
    val frame = if (paths.isEmpty()) signatureFrame(0f, 0f, 0f, 0f, strokePx) else signatureFrame(left, top, right, bottom, strokePx)
    val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.translate(frame.dx, frame.dy)
    paths.forEach { (path, color) ->
        paint.color = color.toArgb()
        canvas.drawPath(path, paint)
    }
    return bitmap
}

/** One pen stroke on the signature pad. Its points are snapshot state, so the pad redraws as a drag adds them. */
private class PadStroke(val color: Color) {
    val points = mutableStateListOf<Offset>()

    fun toPath(): Path = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    }
}

@Composable
fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onSave: (Bitmap, Boolean) -> Unit,
    accentColor: Color,
    initialColor: Color
) {
    val density = LocalDensity.current
    val strokes = remember { mutableStateListOf<PadStroke>() }
    var shouldSaveSignature by remember { mutableStateOf(true) }
    var strokeColor by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw Signature", fontWeight = FontWeight.Black) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Color.Black, Color.Blue, Color(0xFFC00000), Color(0xFF0070C0), PaperPink).forEach { color ->
                        Surface(
                            onClick = { strokeColor = color },
                            modifier = Modifier.size(32.dp).border(2.dp, if (strokeColor == color) accentColor else Color.Transparent, CircleShape),
                            shape = CircleShape,
                            color = color
                        ) {}
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color.Gray.copy(0.2f), RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    strokes.add(PadStroke(strokeColor).apply { points.add(offset) })
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    strokes.lastOrNull()?.points?.add(change.position)
                                }
                            )
                        }
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        strokes.forEach { stroke ->
                            drawPath(
                                path = stroke.toPath().asComposePath(),
                                color = stroke.color,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = with(density) { 4.dp.toPx() },
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = shouldSaveSignature, onCheckedChange = { shouldSaveSignature = it }, colors = CheckboxDefaults.colors(checkedColor = accentColor))
                    Text("Save signature", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear() }) { Text("CLEAR", color = Color.Gray, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (strokes.isNotEmpty()) onSave(renderSignatureBitmap(strokes.map { it.toPath() to it.color }), shouldSaveSignature) },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(12.dp),
                enabled = strokes.isNotEmpty()
            ) { Text("ADOPT", fontWeight = FontWeight.Black) }
        }
    )
}

@Composable
fun SignOptionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.1f))
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color)
        }
    }
}
