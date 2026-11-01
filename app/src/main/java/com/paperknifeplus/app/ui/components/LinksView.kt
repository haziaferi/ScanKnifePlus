package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Links, Remove: every link and every script that runs when the file opens go. The counts come first: once a file is chosen (and
 * unlocked), [countLinkRemoval] runs on it in memory, so the card right above the button says what saving will take out, or that there is
 * nothing to remove and so nothing to save. The saved copy keeps the source's password.
 */
@Composable
fun LinksView(
    initialUri: Uri? = null,
    initialPassword: String? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("links")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    // The source's open password, null when it opens without one; the file as picked is used throughout, never a decrypted copy.
    var password by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf<LinkRemoval?>(null) }
    var removed by remember { mutableStateOf<LinkRemoval?>(null) }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var outputName by remember { mutableStateOf("") }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)

    fun reset() {
        sourceUri = null
        password = null
        found = null
        removed = null
        outputUri = null
        currentState = ToolState.SELECTING
    }

    // Counts what saving will remove from [source] opened with [pass]; a wrong password asks for it again.
    suspend fun count(source: Uri, pass: String?) {
        try {
            val counts = countLinkRemoval(context, source, pass)
            pageCount = getPageCount(context, source, pass)
            password = pass
            found = counts
            fileToUnlock = null
            currentState = ToolState.CONFIGURING
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidPasswordException) {
            unlockError = pass != null
            fileToUnlock = fileName
        } catch (e: Exception) {
            Log.w("LinksView", "Cannot count the links of $source", e)
            Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
            fileToUnlock = null
            reset()
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
                PdfAccess.Encrypted -> if (given == null) fileToUnlock = fileName else count(uri, given)
                PdfAccess.Open -> count(uri, null)
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
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val removal = removeLinksPdf(context, source, uri, pass)
                val timeStr = formatElapsed(startTime)
                val name = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    outputName = name
                    removed = removal
                    outputs.saved(uri, name, toolText(context, R.string.links_removed), Icons.Outlined.LinkOff, pageCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("LinksView", "Removing links failed", e)
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
            ToolScreenBar(currentState, stringResource(R.string.tool_links), stringResource(R.string.tool_links_desc), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.tool_preparing))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.LinkOff,
                            title = stringResource(R.string.links_remove_title),
                            task = stringResource(R.string.links_remove_task),
                            onChoose = { pickLauncher.launch(PDF_MIME) },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        val source = sourceUri
                        val counts = found
                        if (source != null && counts != null) {
                            val none = counts.links == 0 && counts.scripts == 0
                            ToolConfigLayout(
                                action = stringResource(if (none) R.string.links_nothing_to_remove else R.string.links_remove_and_save),
                                onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-no-links", "pdf")) },
                                actionEnabled = !none
                            ) {
                                ToolFileCard(
                                    uri = source,
                                    password = password,
                                    name = fileName,
                                    details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                    onChange = { reset() },
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                    // With nothing to remove nothing is saved, so no copy keeps the password.
                                    keepsPassword = password != null && !none
                                )
                                LinkCountsCard(counts, Modifier.padding(16.dp))
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = sourceUri,
                            password = password,
                            text = stringResource(R.string.links_removing),
                            current = 0,
                            total = 0,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        val done = removed
                        SuccessView(
                            message = stringResource(R.string.links_done),
                            subMessage = pluralStringResource(R.plurals.scan_pages, pageCount, pageCount),
                            result = outputUri?.let { ToolResult(it, outputName, password = password) },
                            onDone = onBack,
                            onProcessMore = { reset() },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, outputName, pageCount) } },
                            keepsPassword = password != null,
                            report = if (done == null) emptyList() else listOf(
                                stringResource(R.string.links_report_links) to done.links,
                                stringResource(R.string.links_report_scripts) to done.scripts
                            )
                        )
                    }
                }
            }

            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { fileToUnlock = null; unlockError = false; reset() },
                    onUnlocked = { pass ->
                        val source = sourceUri
                        if (source != null) {
                            isFileLoading = true
                            scope.launch {
                                count(source, pass)
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
}

/**
 * What saving will remove, counted before it and shown right above the button: the links and the scripts that run when the file opens,
 * each a number over its name; with neither, that there is nothing to remove, so nothing is saved.
 */
@Composable
internal fun LinkCountsCard(found: LinkRemoval, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    ToolCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolSectionLabel(stringResource(R.string.links_found))
            if (found.links == 0 && found.scripts == 0) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.links_none), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                }
                Text(stringResource(R.string.links_none_body), fontSize = 14.sp, color = colors.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountTile(found.links, pluralStringResource(R.plurals.links_count_links, found.links), Modifier.weight(1f))
                    CountTile(found.scripts, pluralStringResource(R.plurals.links_count_scripts, found.scripts), Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.links_nothing_changes) + " " + stringResource(R.string.links_what_stays),
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/** One count of [LinkCountsCard]: [count] large over its [label], read as one; both tiles of a row take the taller one's height. */
@Composable
private fun CountTile(count: Int, label: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxHeight()
            .heightIn(min = 88.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$count", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp, color = colors.onSurface)
        Text(label, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}
