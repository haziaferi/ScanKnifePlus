package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.TargetStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RearrangeView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("rearrange")
    val accentColor = MaterialTheme.colorScheme.primary
    // A move arrow that would do nothing (no page picked, or the first or last) is drawn faded, as well as inert.
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val arrowTint = { enabled: Boolean -> if (enabled) accentColor else faded }

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var decryptedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    // The source's open password, empty when it opens without one; the saved copy keeps it.
    var unlockPassword by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageOrder by remember { mutableStateOf<List<Int>>(emptyList()) }
    val check = rememberFileCheck()
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }

    var selectedPageIndex by remember { mutableStateOf<Int?>(null) }
    var showMoveToDialog by remember { mutableStateOf(false) }
    var moveToInput by remember { mutableStateOf("") }

    fun dropDecryptedCopy() {
        deleteDecryptedCopy(context, decryptedUri)
        decryptedUri = null
    }
    DisposableEffect(Unit) { onDispose { deleteDecryptedCopy(context, decryptedUri) } }

    // Cancel, Back, and a file that cannot be read close the prompt and go back to choosing a file.
    fun closePrompt() {
        fileToUnlock = null
        unlockError = false
        selectedUri = null
        currentState = ToolState.SELECTING
    }

    // A protected file is decrypted once to a cache copy, so page previews can use the fast native renderer; saving reads the original
    // with its password. A wrong password, typed or handed over by the reader, opens the prompt with its error.
    suspend fun unlock(source: Uri, password: String) {
        decryptToCache(context, source, password).resolve(
            context,
            onWrongPassword = { unlockError = true; fileToUnlock = fileName },
            onUnreadable = { closePrompt() }
        ) { copy ->
            decryptedUri = copy.uri
            unlockPassword = password
            selectedUri = copy.uri
            pageOrder = (0 until copy.pageCount).toList()
            currentState = ToolState.CONFIGURING
            fileToUnlock = null
        }
    }

    fun handleFileSelection(uri: Uri, password: String? = null) {
        dropDecryptedCopy()
        selectedUri = uri
        sourceUri = uri
        unlockPassword = ""
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
        check.start {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                    selectedUri = null
                    currentState = ToolState.SELECTING
                }
                PdfAccess.Encrypted -> if (password == null) fileToUnlock = fileName else unlock(uri, password)
                // As in Delete and Crop, a file that opens without a password keeps none, not even one the reader handed over.
                PdfAccess.Open -> {
                    pageOrder = (0 until getPageCount(context, uri, null)).toList()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it, initialPassword) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberSaveTarget("application/pdf") { uri ->
        val source = sourceUri
        if (uri == null || selectedUri == null || source == null) return@rememberSaveTarget
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val order = pageOrder
        val password = unlockPassword
        scope.launch(Dispatchers.IO) {
            try {
                rearrangePages(context, source, uri, password, order)
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = outputName
                    outputs.saved(uri, fileName, toolText(context, R.string.rearrange_reordered_pages), Icons.Filled.GridView, order.size)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Throwable) {
                if (!isToolFailure(e)) throw e
                Log.w("RearrangeView", "Rearranging failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolFailureText(context, e), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_rearrange), stringResource(R.string.rearrange_move_pages_to_new_slots), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (check.running) {
                LoadingStateView(false, stringResource(R.string.rearrange_preparing_rearrange_grid))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Filled.SwapVert,
                            title = stringResource(R.string.rearrange_reorder_pages_reliably),
                            task = stringResource(R.string.tool_rearrange_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            save = saveLauncher,
                            action = stringResource(R.string.action_save_pdf),
                            onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-reordered", "pdf")) },
                            scroll = false
                        ) {
                            // The file card scrolls with the pages, so no text size squeezes the grid or the move bar under it (rule 10).
                            Box(modifier = Modifier.weight(1f)) {
                                UnifiedPdfPreview(
                                    uri = selectedUri!!,
                                    fileName = fileName,
                                    pageCount = pageOrder.size,
                                    mode = PreviewMode.REORDER,
                                    password = null,
                                    accentColor = accentColor,
                                    pageOrder = pageOrder,
                                    selectedPages = selectedPageIndex?.let { setOf(it) } ?: emptySet(),
                                    onToggleSelection = { index ->
                                        selectedPageIndex = if (selectedPageIndex == index) null else index
                                    }
                                ) {
                                    ToolFileCard(
                                        uri = selectedUri!!,
                                        password = null,
                                        name = fileName,
                                        details = pluralStringResource(R.plurals.tool_file_details, pageOrder.size, fileSize, pageOrder.size),
                                        onChange = { dropDecryptedCopy(); selectedUri = null; currentState = ToolState.SELECTING; selectedPageIndex = null },
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                        keepsPassword = unlockPassword.isNotEmpty()
                                    )
                                    Column(Modifier.padding(horizontal = 20.dp)) {
                                        ToolStatusText(stringResource(R.string.rearrange_pages_tap_to_select, pageOrder.size))
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                            }
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    // The arrows and Move to grow together (R14 rule 1).
                                    Row(
                                        modifier = Modifier.padding(12.dp).height(IntrinsicSize.Min),
                                        horizontalArrangement = Arrangement.SpaceAround,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RowIconButton(
                                            onClick = {
                                                selectedPageIndex?.let { current ->
                                                    if (current > 0) {
                                                        val newList = pageOrder.toMutableList()
                                                        val item = newList.removeAt(current)
                                                        newList.add(current - 1, item)
                                                        pageOrder = newList
                                                        selectedPageIndex = current - 1
                                                    }
                                                }
                                            },
                                            enabled = selectedPageIndex != null && selectedPageIndex!! > 0
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, stringResource(R.string.action_move_back), tint = arrowTint(selectedPageIndex != null && selectedPageIndex!! > 0))
                                        }

                                        Button(
                                            onClick = { showMoveToDialog = true },
                                            enabled = selectedPageIndex != null,
                                            shape = RoundedCornerShape(12.dp),
                                            // Weighted, so the arrows are measured first and keep their 48 dp when the label must wrap.
                                            modifier = Modifier.weight(1f, fill = false).fillMaxHeight().heightIn(min = TargetStep.ACT.drawn)
                                        ) {
                                            Text(stringResource(R.string.rearrange_move_to), style = MaterialTheme.typography.labelSmall)
                                        }

                                        RowIconButton(
                                            onClick = {
                                                selectedPageIndex?.let { current ->
                                                    if (current < pageOrder.size - 1) {
                                                        val newList = pageOrder.toMutableList()
                                                        val item = newList.removeAt(current)
                                                        newList.add(current + 1, item)
                                                        pageOrder = newList
                                                        selectedPageIndex = current + 1
                                                    }
                                                }
                                            },
                                            enabled = selectedPageIndex != null && selectedPageIndex!! < pageOrder.size - 1
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, stringResource(R.string.action_move_forward), tint = arrowTint(selectedPageIndex != null && selectedPageIndex!! < pageOrder.size - 1))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            text = stringResource(R.string.rearrange_applying_new_order),
                            current = 0,
                            total = 0,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.rearrange_rearrange_complete),
                            subMessage = stringResource(R.string.rearrange_page_order_updated_successfully),
                            result = outputUri?.let { ToolResult(it, fileName, password = unlockPassword.ifEmpty { null }) },
                            onDone = onBack,
                            onProcessMore = {
                                dropDecryptedCopy()
                                selectedUri = null
                                selectedPageIndex = null
                                currentState = ToolState.SELECTING
                            },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, fileName, pageOrder.size) } },
                            keepsPassword = unlockPassword.isNotEmpty()
                        )
                    }
                }
            }

            if (showMoveToDialog) {
                AlertDialog(
                    onDismissRequest = { showMoveToDialog = false },
                    title = { DialogTitle(stringResource(R.string.rearrange_move_page_to_position)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.rearrange_current_position, selectedPageIndex!! + 1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = moveToInput,
                                onValueChange = { if (it.all { c -> c.isDigit() }) moveToInput = it },
                                label = { Text(stringResource(R.string.rearrange_target_page_range, pageOrder.size)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    },
                    confirmButton = {
                        DialogButtons {
                            DialogButton(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) {
                                showMoveToDialog = false
                                moveToInput = ""
                            }
                            DialogButton(stringResource(R.string.action_move), kind = DialogButtonKind.COMMIT) {
                                val target = moveToInput.toIntOrNull()?.let { it - 1 }
                                if (target != null && target in 0 until pageOrder.size && selectedPageIndex != null) {
                                    val newList = pageOrder.toMutableList()
                                    val item = newList.removeAt(selectedPageIndex!!)
                                    newList.add(target, item)
                                    pageOrder = newList
                                    selectedPageIndex = target
                                    showMoveToDialog = false
                                    moveToInput = ""
                                } else {
                                    Toast.makeText(context, toolText(context, R.string.rearrange_invalid_page_number), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            }

            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { check.stop(); closePrompt() },
                    onUnlocked = { pass -> selectedUri?.let { source -> check.start { unlock(source, pass) } } },
                    isError = unlockError,
                    accentColor = accentColor,
                    isLoading = check.running
                )
            }
        }
    }
}
