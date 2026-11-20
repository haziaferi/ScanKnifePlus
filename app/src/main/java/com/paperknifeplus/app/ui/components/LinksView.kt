package com.paperknifeplus.app.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
 * What keeps Remove links from saving, as its disabled button names it; null when it can save. A range error the field shows comes first,
 * then a range not typed yet (until [rangeEdited] its field shows the helper, not an error), then choices that would [remove] nothing.
 */
@StringRes
internal fun linksBlocker(useRange: Boolean, range: RangeList, rangeEdited: Boolean, remove: LinkRemoval): Int? = when {
    useRange && rangeEdited && range !is RangeList.Valid -> R.string.range_check
    useRange && range !is RangeList.Valid -> R.string.links_type_pages
    remove.isEmpty -> R.string.links_nothing_to_remove
    else -> null
}

/**
 * Links, Remove: the links on every page or on a range go, all of them or only those that leave the file, and every script and action that
 * runs on its own goes from the whole file ([removeLinks]). The counts come first: once a file is chosen (and unlocked), [findLinks] reads it
 * once, and each choice sums what it found per page and kind, so the card says what saving will take out, or that there is nothing to
 * remove and so nothing to save. The saved copy keeps the source's password.
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
    var found by remember { mutableStateOf<FoundLinks?>(null) }
    var useRange by remember { mutableStateOf(false) }
    var rangeText by remember { mutableStateOf("") }
    // "No pages given." waits for the first edit, so choosing Page range shows the helper rather than an error.
    var rangeEdited by remember { mutableStateOf(false) }
    var keepInside by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf<LinkRemoval?>(null) }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var outputName by remember { mutableStateOf("") }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)
    val pageCount = found?.pageCount ?: 0
    val range = PageRanges.parseList(rangeText, pageCount)
    // A range not typed or with an error takes no page's links yet.
    val pages = if (!useRange) null else (range as? RangeList.Valid)?.ranges?.flatten()?.toSet() ?: emptySet()
    val options = LinkOptions(pages, keepInside)

    fun reset() {
        sourceUri = null
        password = null
        found = null
        useRange = false
        rangeText = ""
        rangeEdited = false
        keepInside = false
        removed = null
        outputUri = null
        currentState = ToolState.SELECTING
    }

    // A file that is damaged, or too large to parse, says it cannot be read.
    fun unreadable(source: Uri, e: Throwable) {
        Log.w("LinksView", "Cannot count the links of $source", e)
        Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
        fileToUnlock = null
        reset()
    }

    // Finds what saving can remove from [source] opened with [pass], parsing it once: a file that needs a password asks for it (again after
    // a wrong one), while one with only an owner password opens without it.
    suspend fun count(source: Uri, pass: String?) {
        try {
            val counts = findLinks(context, source, pass)
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
            unreadable(source, e)
        } catch (e: OutOfMemoryError) {
            unreadable(source, e)
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
            count(uri, given)
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
        val chosen = options
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val removal = removeLinksPdf(context, source, uri, pass, chosen)
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
                            val error = if (useRange && rangeEdited) rangeError(range) else null
                            val blocker = linksBlocker(useRange, range, rangeEdited, counts.removal(options))
                            ToolConfigLayout(
                                action = stringResource(blocker ?: R.string.links_remove_and_save),
                                onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-no-links", "pdf")) },
                                actionEnabled = blocker == null
                            ) {
                                ToolFileCard(
                                    uri = source,
                                    password = password,
                                    name = fileName,
                                    details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                    onChange = { reset() },
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                    // With nothing in the file to remove nothing is saved, so no copy keeps the password.
                                    keepsPassword = password != null && !counts.isEmpty
                                )
                                LinkCountsCard(counts, options, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                                // A file with nothing to remove has nothing to choose either.
                                if (!counts.isEmpty) {
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
                                        PageRangeField(
                                            value = rangeText,
                                            onValueChange = {
                                                rangeText = it
                                                rangeEdited = true
                                            },
                                            error = error,
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                                        )
                                    }
                                    ToolSectionLabel(
                                        stringResource(R.string.links_which),
                                        Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
                                    )
                                    SegmentedChoice(
                                        options = listOf(stringResource(R.string.links_all), stringResource(R.string.links_external)),
                                        selected = if (keepInside) 1 else 0,
                                        onSelect = { keepInside = it == 1 },
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    if (keepInside) {
                                        Text(
                                            stringResource(R.string.links_external_note),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
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
 * What saving will remove with [options], summed from what the file holds ([found]) before saving: the links (only those that leave the
 * file with [LinkOptions.keepInside]) and the scripts that run on their own, each a number over its name. With a range the heading says
 * "these pages", and a line says that what runs on its own goes from the whole file. With nothing in the file, that there is nothing to
 * remove, so nothing is saved; with choices that remove nothing, the counts and that line.
 */
@Composable
internal fun LinkCountsCard(found: FoundLinks, options: LinkOptions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val removal = found.removal(options)
    ToolCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolSectionLabel(stringResource(if (options.pages == null) R.string.links_found else R.string.links_found_on_pages))
            if (found.isEmpty) {
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
                val links = if (options.keepInside) R.plurals.links_count_external else R.plurals.links_count_links
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountTile(removal.links, pluralStringResource(links, removal.links), Modifier.weight(1f))
                    CountTile(removal.scripts, pluralStringResource(R.plurals.links_count_scripts, removal.scripts), Modifier.weight(1f))
                }
                if (options.pages != null) {
                    Text(stringResource(R.string.links_whole_file), fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
                Text(
                    if (removal.isEmpty) {
                        stringResource(R.string.links_none_body)
                    } else {
                        stringResource(R.string.links_nothing_changes) + " " + stringResource(R.string.links_what_stays)
                    },
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One count of [LinkCountsCard]: [count] large over its [label], read as one, and read out when a choice changes it (the tile is the node
 * whose text changes, so it is the live region); both tiles of a row take the taller one's height.
 */
@Composable
private fun CountTile(count: Int, label: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxHeight()
            .heightIn(min = 88.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$count", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp, color = colors.onSurface)
        Text(label, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}
