package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageNumbersView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("page-numbers")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf(initialUri) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }

    var position by remember { mutableStateOf("Bottom Right") }
    var fontSize by remember { mutableFloatStateOf(14f) }
    var format by remember { mutableStateOf("Page {n}") }
    var numberColor by remember { mutableStateOf(DocumentInk.Black) }
    var showPreviewOverlay by remember { mutableStateOf(true) }
    val previewDate = remember { pageDate() }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showPositionSheet by remember { mutableStateOf(false) }
    var showColorSheet by remember { mutableStateOf(false) }

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        fileName = getUriDetails(context, uri).name
        unlockError = false
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, UNREADABLE_PDF_MESSAGE, Toast.LENGTH_LONG).show()
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

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val numbering = PageNumbering(format, position, fontSize, numberColor)
        scope.launch(Dispatchers.IO) {
            try {
                rewritePdf(context, source, unlockPassword, uri) { applyPageNumbers(it, numbering) }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = outputName
                    outputs.saved(uri, fileName, "Numbering applied", Icons.Filled.FormatListNumbered, pageCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("PageNumbersView", "Numbering failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (currentState != ToolState.SUCCESS && currentState != ToolState.PROCESSING) {
                ToolTopBar(
                    title = "Page Numbers",
                    subtitle = "CUSTOM PAGINATION",
                    showChange = selectedUri != null && currentState == ToolState.CONFIGURING,
                    onBack = onBack,
                    onChange = { selectedUri = null; currentState = ToolState.SELECTING }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(false, "Preparing document...")
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        SelectionGrid(
                            onSelect = { pickLauncher.launch("application/pdf") },
                            icon = Icons.Filled.FormatListNumbered,
                            title = "Tap to enter file",
                            subtitle = "ADD PAGE NUMBERS",
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
                                val numberOverlay: @Composable (BoxScope.(Int) -> Unit) = { index ->
                                    if (showPreviewOverlay) {
                                        val text = expandPageTokens(format, index + 1, pageCount, previewDate)
                                        Box(Modifier.fillMaxSize(), contentAlignment = when {
                                            position.contains("Bottom") && position.contains("Left") -> Alignment.BottomStart
                                            position.contains("Bottom") && position.contains("Center") -> Alignment.BottomCenter
                                            position.contains("Bottom") && position.contains("Right") -> Alignment.BottomEnd
                                            position.contains("Top") && position.contains("Left") -> Alignment.TopStart
                                            position.contains("Top") && position.contains("Center") -> Alignment.TopCenter
                                            position.contains("Top") && position.contains("Right") -> Alignment.TopEnd
                                            else -> Alignment.BottomEnd
                                        }) {
                                            Text(
                                                text = text,
                                                color = numberColor,
                                                fontSize = (fontSize / 2).sp,
                                                fontWeight = FontWeight.Black,
                                                modifier = Modifier.padding(6.dp)
                                            )
                                        }
                                    }
                                }

                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    pageCount = pageCount,
                                    mode = PreviewMode.GRID,
                                    password = unlockPassword.ifEmpty { null },
                                    accentColor = accentColor,
                                    showIndexNumbers = false,
                                    disableLightbox = true, // By product request: tapping a page here does not open the lightbox.
                                    itemOverlay = numberOverlay
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Surface(
                                onClick = { showSettingsSheet = true },
                                color = accentColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.width(80.dp).heightIn(min = 48.dp)
                            ) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.KeyboardArrowUp, "Numbering options", tint = accentColor, modifier = Modifier.size(24.dp))
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            ToolPrimaryButton("SAVE DOCUMENT", onClick = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-numbered", "pdf")) }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = "Applying numbering...",
                            current = 0,
                            total = 0,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = "Numbering Complete",
                            subMessage = "Page numbers added successfully",
                            processingTime = processingTime,
                            onDone = onBack,
                            onProcessMore = {
                                selectedUri = null
                                currentState = ToolState.SELECTING
                            },
                            onPreview = {
                                outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) }
                            },
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

            if (showSettingsSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showSettingsSheet = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 48.dp)
                            .navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ToolSectionLabel("PAGE NUMBER SETTINGS")
                        Spacer(Modifier.height(24.dp))

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE PREVIEW", fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                            Switch(checked = showPreviewOverlay, onCheckedChange = { showPreviewOverlay = it }, colors = SwitchDefaults.colors(checkedTrackColor = accentColor))
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = format,
                                onValueChange = { format = it },
                                label = { Text("Format ({n})", fontSize = 12.sp) },
                                modifier = Modifier.weight(1.5f),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentColor, focusedLabelColor = accentColor)
                            )

                            Surface(
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
                                onClick = { showColorSheet = true }
                            ) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(numberColor).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text("COLOR", fontSize = 12.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("FONT SIZE: ${fontSize.toInt()} PT", fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                            Slider(
                                value = fontSize,
                                onValueChange = { fontSize = it },
                                valueRange = 8f..36f,
                                modifier = Modifier.weight(2f),
                                colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
                            onClick = { showPositionSheet = true }
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Place, null, tint = accentColor, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    ToolSectionLabel("POSITION")
                                    Text(position.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (showPositionSheet) {
                ModalBottomSheet(onDismissRequest = { showPositionSheet = false }) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp).navigationBarsPadding()) {
                        Text("SELECT POSITION", modifier = Modifier.padding(16.dp), fontSize = 12.sp, fontWeight = FontWeight.Black, color = accentColor)
                        listOf("Top Left", "Top Center", "Top Right", "Bottom Left", "Bottom Center", "Bottom Right").forEach { pos ->
                            ListItem(
                                headlineContent = { Text(pos, fontWeight = if (position == pos) FontWeight.Bold else FontWeight.Normal) },
                                leadingContent = { RadioButton(selected = position == pos, onClick = null) },
                                modifier = Modifier.clickable { position = pos; showPositionSheet = false }
                            )
                        }
                    }
                }
            }

            if (showColorSheet) {
                ModalBottomSheet(onDismissRequest = { showColorSheet = false }) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp).navigationBarsPadding()) {
                        Text("SELECT COLOR", modifier = Modifier.padding(16.dp), fontSize = 12.sp, fontWeight = FontWeight.Black, color = accentColor)
                        DocumentInk.pageNumbers.forEach { ink ->
                            val col = ink.color
                            ListItem(
                                headlineContent = { Text(ink.name, fontWeight = if (numberColor == col) FontWeight.Bold else FontWeight.Normal) },
                                leadingContent = { Box(Modifier.size(24.dp).clip(CircleShape).background(col).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) },
                                trailingContent = { RadioButton(selected = numberColor == col, onClick = null) },
                                modifier = Modifier.clickable { numberColor = col; showColorSheet = false }
                            )
                        }
                    }
                }
            }
        }
    }
}
