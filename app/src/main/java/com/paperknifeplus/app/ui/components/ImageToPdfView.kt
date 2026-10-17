package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.paperknifeplus.app.data.image.PdfImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageToPdfView(
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("img2pdf")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.CONFIGURING) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pageSize by remember { mutableStateOf("Fit") }
    
    var progressCount by remember { mutableIntStateOf(0) }
    var processingTime by remember { mutableStateOf("") }
    val showLoadingWarning = rememberLoadingWarning(currentState == ToolState.PROCESSING)

    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var outputPages by remember { mutableIntStateOf(0) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> 
        selectedUris = selectedUris + uris 
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { saveUri ->
            currentState = ToolState.PROCESSING
            val startTime = System.currentTimeMillis()
            val images = selectedUris
            scope.launch(Dispatchers.IO) {
                try {
                    val pages = convertImagesToPdf(context, images, saveUri, pageSize) { current, _ ->
                        progressCount = current
                    }
                    val timeStr = formatElapsed(startTime)
                    val outputName = getUriDetails(context, saveUri).name
                    withContext(Dispatchers.Main) {
                        processingTime = timeStr
                        outputUri = saveUri
                        outputPages = pages
                        outputs.saved(saveUri, outputName, "$pages images", Icons.Filled.Description, pages)
                        currentState = ToolState.SUCCESS
                    }
                } catch (e: CancellationException) {
                    deleteCreatedDocument(context, saveUri)
                    throw e
                } catch (e: Exception) {
                    Log.w("ImageToPdfView", "Conversion failed", e)
                    deleteCreatedDocument(context, saveUri)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        currentState = ToolState.CONFIGURING
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = "Image to PDF",
                    subtitle = "PHOTO TO DOCUMENT CONVERTER",
                    showChange = selectedUris.isNotEmpty() && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = { pickLauncher.launch("image/*") },
                    changeLabel = "Add"
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            when (currentState) {
                ToolState.CONFIGURING -> {
                    if (selectedUris.isEmpty()) {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("image/*") },
                            icon = Icons.Outlined.PictureAsPdf,
                            title = "Tap to select images",
                            subtitle = "JPG, PNG, OR WEBP",
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        var selectedIndex by remember { mutableStateOf<Int?>(null) }
                        
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                ToolSectionLabel("REORDER ASSETS")
                                ToolStatusText("${selectedUris.size} IMAGES SELECTED")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = pageSize == "Fit",
                                    onClick = { pageSize = "Fit" },
                                    label = { Text("ORIGINAL", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentColor, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                                )
                                Spacer(Modifier.width(4.dp))
                                FilterChip(
                                    selected = pageSize == "A4",
                                    onClick = { pageSize = "A4" },
                                    label = { Text("A4 PAPER", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentColor, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                                )
                            }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            itemsIndexed(selectedUris) { index, uri ->
                                val isSelected = selectedIndex == index
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(0.707f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(BorderStroke(2.dp, if (isSelected) accentColor else Color.Transparent), RoundedCornerShape(16.dp))
                                        .toggleable(value = isSelected, role = Role.Checkbox) { selectedIndex = if (it) index else null }
                                        .semantics { contentDescription = "Image ${index + 1}"; stateDescription = if (isSelected) "Selected" else "Not selected" }
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(model = uri, imageLoader = PdfImageLoader.get(context)),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    if (isSelected) {
                                        Box(modifier = Modifier.fillMaxSize().background(accentColor.copy(alpha = 0.1f)))
                                        Surface(
                                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp),
                                            color = accentColor,
                                            shape = CircleShape
                                        ) {
                                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(4.dp))
                                        }
                                    }

                                    Surface(
                                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                                        color = pageBadge,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("${index + 1}", color = pageBadgeText, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                    }
                                    
                                    IconButton(
                                        onClick = { selectedUris = selectedUris.filterIndexed { i, _ -> i != index }; if (isSelected) selectedIndex = null },
                                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(48.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, "Remove image ${index + 1}", tint = pageBadgeText, modifier = Modifier.background(pageBadge, CircleShape).padding(6.dp).size(16.dp))
                                    }
                                }
                            }
                        }

                        if (selectedIndex != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            val current = selectedIndex!!
                                            if (current > 0) {
                                                val list = selectedUris.toMutableList()
                                                val item = list.removeAt(current)
                                                list.add(current - 1, item)
                                                selectedUris = list
                                                selectedIndex = current - 1
                                            }
                                        },
                                        enabled = selectedIndex!! > 0
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Move back", tint = if (selectedIndex!! > 0) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    Text("POSITION: ${selectedIndex!! + 1}", fontSize = 12.sp, fontWeight = FontWeight.Black)

                                    IconButton(
                                        onClick = {
                                            val current = selectedIndex!!
                                            if (current < selectedUris.size - 1) {
                                                val list = selectedUris.toMutableList()
                                                val item = list.removeAt(current)
                                                list.add(current + 1, item)
                                                selectedUris = list
                                                selectedIndex = current + 1
                                            }
                                        },
                                        enabled = selectedIndex!! < selectedUris.size - 1
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, "Move forward", tint = if (selectedIndex!! < selectedUris.size - 1) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        
                        Button(
                            onClick = { saveLauncher.launch(outputFileName("images", "pdf")) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Create PDF from ${selectedUris.size} Images", fontWeight = FontWeight.Black)
                        }
                    }
                }
                ToolState.PROCESSING -> {
                    ProcessingStateView(
                        text = "Packaging high-res document...",
                        current = progressCount,
                        total = selectedUris.size,
                        showWarning = showLoadingWarning
                    )
                }
                ToolState.SUCCESS -> {
                    SuccessView(
                        message = "PDF Created",
                        subMessage = "Images merged into document",
                        processingTime = processingTime,
                        onDone = onBack,
                        onProcessMore = { selectedUris = emptyList(); currentState = ToolState.CONFIGURING },
                        onPreview = { outputUri?.let { uri -> onOpenPreview(uri, "Created PDF", outputPages) } },
                    )
                }
                ToolState.SELECTING -> {}
            }
        }
    }
}
