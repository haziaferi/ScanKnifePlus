package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import kotlin.math.max
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accentColor = MaterialTheme.colorScheme.primary

    var fileA by remember { mutableStateOf<Uri?>(null) }
    var fileB by remember { mutableStateOf<Uri?>(null) }
    var nameA by remember { mutableStateOf("") }
    var nameB by remember { mutableStateOf("") }
    var passA by remember { mutableStateOf("") }
    var passB by remember { mutableStateOf("") }

    var isComparing by remember { mutableStateOf(false) }
    var fileToUnlock by remember { mutableStateOf<Pair<Uri, String>?>(null) } // Uri, target ("A" or "B")
    var unlockError by remember { mutableStateOf(false) }
    var isFileLoading by remember { mutableStateOf(false) }

    fun setFile(target: String, uri: Uri, password: String) {
        val name = getUriDetails(context, uri).name
        if (target == "A") {
            fileA = uri
            nameA = name
            passA = password
        } else {
            fileB = uri
            nameB = name
            passB = password
        }
    }

    fun handleFileSelection(uri: Uri, target: String) {
        isFileLoading = true
        unlockError = false
        scope.launch {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                PdfAccess.Encrypted -> fileToUnlock = uri to target
                PdfAccess.Open -> setFile(target, uri, "")
            }
            isFileLoading = false
        }
    }

    val pickALauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it, "A") }
    }

    val pickBLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it, "B") }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it, "A") }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_compare),
                subtitle = if (isComparing) stringResource(R.string.compare_synchronized_view) else stringResource(R.string.compare_visual_difference_tool),
                showChange = isComparing || fileA != null || fileB != null,
                onBack = { if (isComparing) isComparing = false else onBack() },
                onChange = {
                    if (isComparing) {
                        isComparing = false
                    } else {
                        fileA = null; fileB = null; nameA = ""; nameB = ""; passA = ""; passB = ""
                    }
                },
                changeLabel = if (isComparing) stringResource(R.string.tool_change) else stringResource(R.string.action_reset)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(false, stringResource(R.string.compare_analyzing_document))
            } else if (!isComparing) {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(24.dp))
                    ToolSectionLabel(stringResource(R.string.compare_select_documents))
                    Spacer(Modifier.height(16.dp))
                    
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CompareFileCard(stringResource(R.string.compare_original_a), nameA, fileA != null, Modifier.weight(1f).fillMaxHeight()) { pickALauncher.launch("application/pdf") }
                        CompareFileCard(stringResource(R.string.compare_revised_b), nameB, fileB != null, Modifier.weight(1f).fillMaxHeight()) { pickBLauncher.launch("application/pdf") }
                    }
                    
                    Spacer(Modifier.height(32.dp))
                    
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Filled.Compare, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.compare_pick_two_files_to_begin), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        }
                    }
                    
                    ToolPrimaryButton(
                        stringResource(R.string.compare_start),
                        onClick = { isComparing = true },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        enabled = fileA != null && fileB != null
                    )
                }
            } else {
                ComparisonViewer(fileA!!, fileB!!, nameA, nameB, passA, passB)
            }

            if (fileToUnlock != null) {
                val (uri, target) = fileToUnlock!!
                LockedFilePrompt(
                    fileName = getUriDetails(context, uri).name,
                    onDismiss = { fileToUnlock = null; unlockError = false },
                    onUnlocked = { pass ->
                        isFileLoading = true
                        scope.launch {
                            if (getPageCount(context, uri, pass) > 0) {
                                setFile(target, uri, pass)
                                fileToUnlock = null
                            } else {
                                unlockError = true
                            }
                            isFileLoading = false
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

@Composable
fun ComparisonViewer(uriA: Uri, uriB: Uri, nameA: String, nameB: String, passA: String, passB: String) {
    val context = LocalContext.current
    var pageCountA by remember { mutableIntStateOf(0) }
    var pageCountB by remember { mutableIntStateOf(0) }
    val imageLoader = PdfImageLoader.get(context)

    val listState = rememberLazyListState()
    var lightboxData by remember { mutableStateOf<Triple<Uri, Int, Int>?>(null) }
    var lightboxPass by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uriA, uriB) {
        pageCountA = getPageCount(context, uriA, passA.ifEmpty { null })
        pageCountB = getPageCount(context, uriB, passB.ifEmpty { null })
    }

    val maxPages = remember(pageCountA, pageCountB) { max(pageCountA, pageCountB) }

    @Composable
    fun RowScope.Cell(uri: Uri, password: String, index: Int, count: Int, endLabel: String) {
        Box(Modifier.weight(1f).padding(8.dp)) {
            if (index < count) {
                ComparePage(uri, index, password.ifEmpty { null }, imageLoader) {
                    lightboxData = Triple(uri, index, count)
                    lightboxPass = password.ifEmpty { null }
                }
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(endLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            items(maxPages) { index ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Cell(uriA, passA, index, pageCountA, stringResource(R.string.compare_end_of_file_a))
                    Cell(uriB, passB, index, pageCountB, stringResource(R.string.compare_end_of_file_b))
                }
                if (index < maxPages - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    lightboxData?.let { (uri, page, total) ->
        PageLightbox(
            uri = uri,
            initialPage = page,
            totalCount = total,
            password = lightboxPass,
            onDismiss = { lightboxData = null; lightboxPass = null }
        )
    }
}

/**
 * One page thumbnail, drawn as [PdfPageItem] draws it but rendered at most 1.5x as wide as its cell (and at most at scale 2), since a
 * half-width cell would otherwise get a full-width bitmap.
 */
@Composable
private fun ComparePage(uri: Uri, index: Int, password: String?, imageLoader: ImageLoader, onClick: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.aspectRatio(0.707f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val maxWidthPx = (constraints.maxWidth * 1.5f).toInt()
        val request = remember(uri, index, password, maxWidthPx) { PdfPageRequest(uri, index, password, scale = 2.0f, maxWidthPx = maxWidthPx) }
        val painter = rememberAsyncImagePainter(model = request, imageLoader = imageLoader)
        Image(painter = painter, contentDescription = stringResource(R.string.tool_page_n, index + 1), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (painter.state is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (painter.state is AsyncImagePainter.State.Error && password != null) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            color = pageBadge, shape = RoundedCornerShape(8.dp)
        ) {
            Text("${index + 1}", color = pageBadgeText, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

/** One of the two files to compare: a square card that opens the picker; a picked file shows tonal, with a check mark and its name. */
@Composable
fun CompareFileCard(label: String, name: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = if (isSelected) colors.onPrimaryContainer else colors.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 148.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (isSelected) colors.primaryContainer else colors.surface,
        border = BorderStroke(1.dp, if (isSelected) colors.onPrimaryContainer else colors.outline)
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.AddCircle, null, tint = content, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = content, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(if (isSelected) name else stringResource(R.string.compare_select_file), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = content, textAlign = TextAlign.Center)
        }
    }
}
