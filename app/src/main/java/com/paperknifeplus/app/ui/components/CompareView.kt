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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accentColor = Color(0xFFF59E0B)

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
                PdfAccess.Unreadable -> Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (isComparing) isComparing = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Compare", fontSize = 16.sp, fontWeight = FontWeight.Black)
                        Text(if (isComparing) "SYNCHRONIZED VIEW" else "VISUAL DIFFERENCE TOOL", fontSize = 8.sp, fontWeight = FontWeight.Black, color = accentColor, letterSpacing = 1.sp)
                    }
                    if (!isComparing && (fileA != null || fileB != null)) {
                        TextButton(onClick = { fileA = null; fileB = null; nameA = ""; nameB = ""; passA = ""; passB = "" }) {
                            Text("RESET", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.Gray)
                        }
                    } else if (isComparing) {
                        TextButton(onClick = { isComparing = false }) {
                            Text("CHANGE", fontSize = 11.sp, fontWeight = FontWeight.Black, color = accentColor)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(accentColor, false, "Analyzing document...")
            } else if (!isComparing) {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Text("SELECT DOCUMENTS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(16.dp))
                    
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CompareFileCard("ORIGINAL (A)", nameA, fileA != null, accentColor, Modifier.weight(1f)) { pickALauncher.launch("application/pdf") }
                        CompareFileCard("REVISED (B)", nameB, fileB != null, accentColor, Modifier.weight(1f)) { pickBLauncher.launch("application/pdf") }
                    }
                    
                    Spacer(Modifier.height(32.dp))
                    
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Filled.Compare, null, modifier = Modifier.size(48.dp).alpha(0.1f))
                            Spacer(Modifier.height(16.dp))
                            Text("Pick two files to begin comparison.", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                        }
                    }
                    
                    Button(
                        onClick = { isComparing = true },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).height(60.dp),
                        enabled = fileA != null && fileB != null,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White)
                    ) {
                        Text("START COMPARISON", fontWeight = FontWeight.Black)
                    }
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
                Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.Gray.copy(0.05f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(endLabel, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Gray.copy(0.4f))
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
                    Cell(uriA, passA, index, pageCountA, "END OF FILE A")
                    Cell(uriB, passB, index, pageCountB, "END OF FILE B")
                }
                if (index < maxPages - 1) {
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
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
            .background(if (LocalIsDarkTheme.current) Color(0xFF18181B) else Color(0xFFF4F4F5))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val maxWidthPx = (constraints.maxWidth * 1.5f).toInt()
        val request = remember(uri, index, password, maxWidthPx) { PdfPageRequest(uri, index, password, scale = 2.0f, maxWidthPx = maxWidthPx) }
        val painter = rememberAsyncImagePainter(model = request, imageLoader = imageLoader)
        Image(painter = painter, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (painter.state is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (painter.state is AsyncImagePainter.State.Error && password != null) {
            Icon(Icons.Filled.Lock, null, tint = Color.Gray.copy(0.3f), modifier = Modifier.size(32.dp))
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            color = Color.Black.copy(0.6f), shape = RoundedCornerShape(8.dp)
        ) {
            Text("${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

@Composable
fun CompareFileCard(label: String, name: String, isSelected: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(24.dp),
        color = if (isSelected) color.copy(0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
        border = BorderStroke(1.dp, if (isSelected) color.copy(0.3f) else Color.Transparent)
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.AddCircle, 
                null, 
                tint = if (isSelected) color else Color.Gray.copy(0.5f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(label, fontSize = 8.sp, fontWeight = FontWeight.Black, color = if (isSelected) color else Color.Gray, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (isSelected) name else "Select File", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
