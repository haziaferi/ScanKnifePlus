package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One entry of the merge list. [id] tells apart entries for the same file, which may be added more than once. */
data class MergeFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: String,
    val isLocked: Boolean,
    val password: String? = null,
    val isUnlocked: Boolean = false,
    val decryptedUri: Uri? = null,
    val pageCount: Int = 0
)

/** [files] with the entry at [index] swapped with its neighbour [delta] (-1 up, +1 down) away; unchanged when that is out of range. */
internal fun moveMergeFile(files: List<MergeFile>, index: Int, delta: Int): List<MergeFile> {
    val target = index + delta
    if (index !in files.indices || target !in files.indices) return files
    return files.toMutableList().apply {
        this[index] = files[target]
        this[target] = files[index]
    }
}

@Composable
fun MergeView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("merge")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedFiles by remember { mutableStateOf<List<MergeFile>>(emptyList()) }
    var nextFileId by remember { mutableLongStateOf(0L) }
    var processingTime by remember { mutableStateOf("") }
    var progressCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var mergedUri by remember { mutableStateOf<Uri?>(null) }
    var mergedName by remember { mutableStateOf("") }

    var fileToUnlock by remember { mutableStateOf<MergeFile?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var lightboxFile by remember { mutableStateOf<MergeFile?>(null) }

    val imageLoader = PdfImageLoader.get(context)

    /** Replaces the list, deleting the decrypted copies no remaining entry uses (entries for the same file share one). */
    fun updateFiles(files: List<MergeFile>) {
        val kept = files.mapNotNullTo(HashSet()) { it.decryptedUri }
        selectedFiles.mapNotNull { it.decryptedUri }.filter { it !in kept }.forEach { deleteDecryptedCopy(context, it) }
        selectedFiles = files
    }
    DisposableEffect(Unit) { onDispose { selectedFiles.forEach { deleteDecryptedCopy(context, it.decryptedUri) } } }

    fun handleUris(uris: List<Uri>, password: String? = null) {
        if (uris.isNotEmpty()) {
            isFileLoading = true
            scope.launch {
                var unreadable = 0
                val newFiles = uris.mapNotNull { uri ->
                    val details = getUriDetails(context, uri)
                    when (inspectPdf(context, uri)) {
                        PdfAccess.Unreadable -> {
                            unreadable++
                            null
                        }
                        PdfAccess.Encrypted -> {
                            // A password handed over by the preview unlocks the file right away, as the unlock prompt would.
                            val copy = password?.let { decryptToCache(context, uri, it) }
                            if (copy == null) {
                                MergeFile(nextFileId++, uri, details.name, details.size, isLocked = true)
                            } else {
                                val count = getPageCount(context, copy, null)
                                MergeFile(
                                    nextFileId++, uri, details.name, details.size,
                                    isLocked = true, password = password, isUnlocked = true, decryptedUri = copy, pageCount = count
                                )
                            }
                        }
                        PdfAccess.Open -> MergeFile(nextFileId++, uri, details.name, details.size, isLocked = false, pageCount = getPageCount(context, uri, null))
                    }
                }
                if (unreadable > 0) Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                if (newFiles.isNotEmpty()) {
                    selectedFiles = selectedFiles + newFiles
                    currentState = ToolState.CONFIGURING
                }
                isFileLoading = false
            }
        }
    }

    LaunchedEffect(initialUri, initialPassword) {
        if (initialUri != null) {
            handleUris(listOf(initialUri), initialPassword)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        handleUris(uris)
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val files = selectedFiles
        scope.launch(Dispatchers.IO) {
            try {
                val finalCount = mergeFiles(context, files, uri) { current, _ -> scope.launch(Dispatchers.Main) { progressCount = current } }
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    mergedUri = uri
                    mergedName = outputName
                    outputs.saved(uri, outputName, toolText(context, R.string.merge_files_joined, files.size), Icons.Filled.Layers, finalCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("MergeView", "Merge failed", e)
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
                stringResource(R.string.tool_merge),
                stringResource(R.string.merge_join_multiple_documents),
                processingTime,
                onBack,
                onChange = if (selectedFiles.isNotEmpty()) ({ updateFiles(emptyList()); currentState = ToolState.SELECTING }) else null
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(false, stringResource(R.string.merge_reading_files))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Filled.Layers,
                            title = stringResource(R.string.merge_select_multiple_pdfs),
                            task = stringResource(R.string.tool_merge_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleUris(listOf(it)) },
                            chooseLabel = stringResource(R.string.tool_pick_pdfs),
                            chooseStep = stringResource(R.string.tool_step_choose_pdfs)
                        )
                    }

                    ToolState.CONFIGURING -> {
                        val listState = rememberLazyListState()
                        val enoughFiles = selectedFiles.size > 1
                        val allUnlocked = selectedFiles.all { !it.isLocked || it.isUnlocked }
                        ToolConfigLayout(
                            // The disabled action says what is missing: a second file (one recent file opens Merge with one), or a password.
                            action = when {
                                !enoughFiles -> stringResource(R.string.merge_add_another_file)
                                !allUnlocked -> stringResource(R.string.merge_unlock_all_files_first)
                                else -> stringResource(R.string.merge_merge_files, selectedFiles.size)
                            },
                            onAction = { saveLauncher.launch(outputFileName("merged_${System.currentTimeMillis() / 1000}", "pdf")) },
                            actionEnabled = enoughFiles && allUnlocked,
                            scroll = false
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
                            ) {
                                itemsIndexed(
                                    items = selectedFiles,
                                    key = { _, file -> file.id }
                                ) { index, file ->
                                    Box(
                                        modifier = Modifier
                                            .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    ) {
                                        MergeFileListItem(
                                            file = file,
                                            index = index,
                                            accentColor = accentColor,
                                            imageLoader = imageLoader,
                                            onDelete = { updateFiles(selectedFiles.filterIndexed { i, _ -> i != index }) },
                                            onUnlock = { fileToUnlock = file },
                                            onClick = { lightboxFile = file },
                                            onMoveUp = { selectedFiles = moveMergeFile(selectedFiles, index, -1) },
                                            onMoveDown = { selectedFiles = moveMergeFile(selectedFiles, index, +1) },
                                            isFirst = index == 0,
                                            isLast = index == selectedFiles.lastIndex
                                        )
                                    }
                                }

                                item {
                                    Surface(
                                        onClick = { pickLauncher.launch("application/pdf") },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = accentColor.copy(0.05f),
                                        border = BorderStroke(1.dp, accentColor.copy(0.2f))
                                    ) {
                                        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Add, null, tint = accentColor)
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.action_add_more), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accentColor)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedFiles.firstOrNull()?.decryptedUri ?: selectedFiles.firstOrNull()?.uri,
                            password = if (selectedFiles.firstOrNull()?.decryptedUri != null) null else selectedFiles.firstOrNull()?.password,
                            text = stringResource(R.string.merge_merging_documents),
                            current = progressCount,
                            total = selectedFiles.size,
                            showWarning = false
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.merge_merge_complete),
                            subMessage = stringResource(R.string.merge_successfully_joined_documents, selectedFiles.size),
                            result = mergedUri?.let { ToolResult(it, mergedName) },
                            onDone = onBack,
                            onProcessMore = { updateFiles(emptyList()); mergedUri = null; currentState = ToolState.SELECTING },
                            onOpen = { mergedUri?.let { uri -> scope.launch { onOpenPreview(uri, toolText(context, R.string.merge_merged_pdf), getPageCount(context, uri, null)) } } }
                        )
                    }
                }
            }

            if (fileToUnlock != null) {
                val currentFileToUnlock = fileToUnlock!!
                LockedFilePrompt(
                    fileName = currentFileToUnlock.name,
                    onDismiss = { fileToUnlock = null; unlockError = false },
                    onUnlocked = { password ->
                        isFileLoading = true
                        scope.launch {
                            val decryptedUri = decryptToCache(context, currentFileToUnlock.uri, password)
                            if (decryptedUri != null) {
                                val count = getPageCount(context, decryptedUri, null)
                                updateFiles(selectedFiles.map { if (it.uri == currentFileToUnlock.uri) it.copy(isUnlocked = true, password = password, decryptedUri = decryptedUri, pageCount = count) else it })
                                fileToUnlock = null
                                unlockError = false
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

    if (lightboxFile != null) {
        val currentLightboxFile = lightboxFile!!
        PageLightbox(
            uri = currentLightboxFile.decryptedUri ?: currentLightboxFile.uri,
            initialPage = 0,
            totalCount = currentLightboxFile.pageCount,
            password = if (currentLightboxFile.decryptedUri != null) null else currentLightboxFile.password,
            onDismiss = { lightboxFile = null }
        )
    }
}

@Composable
fun MergeFileListItem(
    file: MergeFile,
    index: Int,
    accentColor: Color,
    imageLoader: coil.ImageLoader,
    onDelete: () -> Unit,
    onUnlock: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(80.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (file.isLocked && !file.isUnlocked) {
                    Icon(Icons.Outlined.Lock, null, tint = accentColor.copy(0.3f), modifier = Modifier.align(Alignment.Center).size(24.dp))
                } else {
                    val request = remember(file.uri, file.decryptedUri, file.password) {
                        PdfPageRequest(file.decryptedUri ?: file.uri, 0, if (file.decryptedUri != null) null else file.password, 0.4f)
                    }
                    Image(
                        painter = rememberAsyncImagePainter(request, imageLoader),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clickable { onClick() },
                        contentScale = ContentScale.Crop
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    color = pageBadge,
                    shape = CircleShape
                ) {
                    Text("${index + 1}", color = pageBadgeText, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }

            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(file.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(file.size, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (file.isLocked && !file.isUnlocked) {
                    ToolStatusText(stringResource(R.string.merge_locked))
                }
            }

            if (file.isLocked && !file.isUnlocked) {
                IconButton(onClick = onUnlock) {
                    Icon(Icons.Filled.LockOpen, stringResource(R.string.action_unlock_file, file.name), tint = accentColor)
                }
            }

            Column {
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp, stringResource(R.string.merge_move_up, file.name),
                        tint = if (isFirst) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown, stringResource(R.string.merge_move_down, file.name),
                        tint = if (isLast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Filled.DeleteOutline, stringResource(R.string.action_remove, file.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
