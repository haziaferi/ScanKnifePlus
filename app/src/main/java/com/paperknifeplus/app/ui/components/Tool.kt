package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import coil.compose.rememberAsyncImagePainter
import com.haziaferi.scanknifeplus.ui.folder.FolderHolder
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.OutputCopy
import com.haziaferi.scanknifeplus.ui.folder.folderPdfs
import com.paperknifeplus.app.MainActivity
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.ScanPalette
import java.util.UUID
import kotlinx.coroutines.delay

/** The [ActivityEntry.toolId] of scans; ScanExport writes it. Tools use their [ToolCatalog] id. */
const val SCAN_ENTRY_TOOL = "scan"

/**
 * One History entry. [id] is unique: it keys History's lazy list, so a row keeps its state when newer entries shift the list. [toolId] is
 * the [ToolCatalog] id of the tool that made it, [SCAN_ENTRY_TOOL] for scans.
 */
data class ActivityEntry(
    val id: String,
    val name: String,
    val toolId: String,
    val size: String,
    val icon: ImageVector,
    val uri: Uri? = null,
    val pageCount: Int = 0
) {
    val isScan: Boolean get() = toolId == SCAN_ENTRY_TOOL

    /** The colour role of this entry's tag. */
    val tagRole: TagRole get() = if (isScan) TagRole.SCAN else TagRole.TOOL

    /** The tag's words: "Scan", the catalog name of the tool, or "Tool output" for an id the catalog does not have. */
    @get:StringRes
    val tag: Int get() = if (isScan) R.string.history_tag_scan else ToolCatalog.byId(toolId)?.name ?: R.string.history_tag_tool
}

/** The groups of [ToolCatalog], in the order the tool list shows them. */
enum class ToolCategory(@StringRes val label: Int) {
    EDIT(R.string.tool_category_edit),
    OPTIMIZE(R.string.tool_category_optimize),
    SECURE(R.string.tool_category_secure),
    CONVERT(R.string.tool_category_convert)
}

/** Which palette pair draws a tag. The accepted design (3d) keeps the accent to scans, so every tool shares the [TOOL] pair. */
enum class TagRole {
    SCAN, TOOL;

    /** The tag's fill and text colours in [palette]. */
    fun colors(palette: ScanPalette): Pair<Color, Color> = when (this) {
        SCAN -> palette.scanBg to palette.scanFg
        TOOL -> palette.toolBg to palette.toolFg
    }
}

/** What a tool screen opens with: a pre-loaded file (and its password, never saved to state) and the shell's callbacks. */
class ToolLaunch(
    val initialUri: Uri?,
    val initialPassword: String?,
    val onBack: () -> Unit,
    val onOpenPreview: (Uri, String, Int) -> Unit
)

/** One entry of [ToolCatalog]: [id] is the stable route name; [screen] draws the tool for a [ToolLaunch]. */
class Tool(
    val id: String,
    @StringRes val name: Int,
    @StringRes val description: Int,
    val icon: ImageVector,
    val category: ToolCategory,
    val screen: @Composable (ToolLaunch) -> Unit
)

/** This session's History, newest first. Compose state: change it on the main thread. Tools record through [ToolOutputs]. */
object SessionManager {
    val history = mutableStateListOf<ActivityEntry>()

    fun addEntry(name: String, toolId: String, size: String, icon: ImageVector, uri: Uri? = null, pageCount: Int = 0) {
        history.add(0, ActivityEntry(UUID.randomUUID().toString(), name, toolId, size, icon, uri, pageCount))
    }

    fun clearHistory() {
        history.clear()
    }
}

/** The user's folder as the tools see it: where an output saved with the system picker is copied too (user answer 5). */
fun interface OutputFolder {
    /** Copies the file at [uri] into the folder, off the main thread; [onResult] runs on the main thread. */
    fun copy(uri: Uri, onResult: (OutputCopy) -> Unit)
}

/** The folder tool outputs are copied into; the shell provides the app's folder, and without it nothing is copied. */
val LocalOutputFolder = staticCompositionLocalOf { OutputFolder { _, onResult -> onResult(OutputCopy.NO_FOLDER) } }

/**
 * The one save path of the tools' outputs: each is recorded in History under [toolId], and each file the user saved with the system picker
 * is then copied into [folder]. The copy never blocks or undoes the picker save: a folder that cannot be reached, or a copy that fails, only
 * shows one short message. Call on the main thread.
 */
class ToolOutputs(private val toolId: String, private val folder: OutputFolder, private val context: Context) {
    /** An output the user saved with the picker at [uri]: History, then the copy into the folder. */
    fun saved(uri: Uri, name: String, summary: String, icon: ImageVector, pageCount: Int = 0) {
        record(name, summary, icon, uri, pageCount)
        copy(uri)
    }

    /** An output that is not a file the user saved (text shown on screen): History only. */
    fun record(name: String, summary: String, icon: ImageVector, uri: Uri? = null, pageCount: Int = 0) =
        SessionManager.addEntry(name, toolId, summary, icon, uri, pageCount)

    /** A further file the user saved from a result with the picker, already in History (the extracted text as .txt): the folder only. */
    fun copy(uri: Uri) = folder.copy(uri) { result ->
        copyMessage(result)?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_LONG).show() }
    }
}

/** What the user is told after a copy into the folder: nothing when it worked or no folder is chosen. */
@StringRes
internal fun copyMessage(result: OutputCopy): Int? = when (result) {
    OutputCopy.COPIED, OutputCopy.NO_FOLDER -> null
    OutputCopy.CHOOSE_AGAIN, OutputCopy.FAILED -> R.string.tool_copy_failed
}

/**
 * [id] formatted with [args], for text outside composition: toasts, History summaries and semantics set in callbacks. Inside composition
 * screens use stringResource, which follows configuration changes.
 */
fun toolText(context: Context, @StringRes id: Int, vararg args: Any?): String = context.getString(id, *args)

/** The [ToolOutputs] of the tool whose [ToolCatalog] id is [toolId], copying into [LocalOutputFolder]. */
@Composable
fun rememberToolOutputs(toolId: String): ToolOutputs {
    val context = LocalContext.current.applicationContext
    val folder = LocalOutputFolder.current
    return remember(toolId, folder, context) { ToolOutputs(toolId, folder, context) }
}

/**
 * The top bar of the tool screens, Settings and About: a 48 dp back button named [backLabel], [title] over [subtitle], and a [changeLabel]
 * button (Change) when [showChange].
 */
@Composable
fun ToolTopBar(
    title: String,
    subtitle: String?,
    showChange: Boolean,
    onBack: () -> Unit,
    onChange: () -> Unit = {},
    changeLabel: String = stringResource(R.string.tool_change),
    backLabel: String = stringResource(R.string.tool_back)
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), color = colors.background) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, backLabel, tint = colors.onBackground)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.onBackground,
                    modifier = Modifier.semantics { heading() }
                )
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
            if (showChange) {
                TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(changeLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                }
            }
        }
    }
}

/**
 * The top bar of a tool screen in [state]: none while the tool works; on the result, how long it took ([processingTime]) under [title] and
 * back named Back to tools; otherwise [subtitle], and Change ([onChange]) for a tool whose files are a list rather than one file card.
 */
@Composable
fun ToolScreenBar(state: ToolState, title: String, subtitle: String, processingTime: String, onBack: () -> Unit, onChange: (() -> Unit)? = null) {
    when (state) {
        ToolState.PROCESSING -> Unit
        ToolState.SUCCESS -> ToolTopBar(
            title = title,
            subtitle = processingTime.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.tool_done_in, it) },
            showChange = false,
            onBack = onBack,
            backLabel = stringResource(R.string.tool_back_to_tools)
        )
        else -> ToolTopBar(title = title, subtitle = subtitle, showChange = onChange != null, onBack = onBack, onChange = onChange ?: {})
    }
}

/** The PDFs of the user's folder for the empty tool screens, newest first; null while no folder is listed (none chosen, unreadable, reading). */
val LocalFolderPdfs = staticCompositionLocalOf<@Composable () -> List<FolderItem>?> { { appFolderPdfs() } }

@Composable
private fun appFolderPdfs(): List<FolderItem>? {
    // Only the app's own activity holds the folder (its FolderHolder, made in onCreate); any other host, such as a test, has none.
    val activity = LocalActivity.current as? MainActivity ?: return null
    val folder = remember(activity) { ViewModelProvider(activity)[FolderHolder::class.java].folder }
    return folder.history?.items?.let { folderPdfs(it) }
}

/**
 * The empty tool screen (1b, then 1c): a card naming the tool's [title] and its three steps ([chooseStep], [task], saving), then the newest
 * PDFs of the user's folder ([LocalFolderPdfs]), which [onOpenRecent] opens in the tool, and [chooseLabel] (T1, the picker) pinned at the
 * bottom. Without a listed folder, or without [onOpenRecent] (a tool that takes images), only the card shows.
 */
@Composable
fun ToolEmptyState(
    icon: ImageVector,
    title: String,
    task: String,
    onChoose: () -> Unit,
    onOpenRecent: ((Uri) -> Unit)?,
    chooseLabel: String = stringResource(R.string.tool_pick_pdf),
    chooseStep: String = stringResource(R.string.tool_step_choose_pdf)
) {
    val colors = MaterialTheme.colorScheme
    val recent = if (onOpenRecent != null) LocalFolderPdfs.current() else null
    ToolConfigLayout(action = chooseLabel, onAction = onChoose, actionIcon = Icons.Outlined.Description) {
        ToolCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(52.dp).background(colors.surfaceVariant, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(28.dp))
                    }
                    Text(
                        title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 26.sp,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )
                }
                HorizontalDivider(color = colors.outline)
                ToolStep(1, chooseStep, stringResource(R.string.tool_step_choose_body))
                ToolStep(2, stringResource(R.string.tool_step_options), task)
                ToolStep(3, stringResource(R.string.tool_step_save), stringResource(R.string.tool_step_save_body))
            }
        }
        if (!recent.isNullOrEmpty() && onOpenRecent != null) {
            Text(
                stringResource(R.string.home_recent_folder),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp).semantics { heading() }
            )
            ToolCard(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    recent.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = colors.outline)
                        FolderRow(item, onClick = item.uri?.let { uri -> { onOpenRecent(uri) } })
                    }
                }
            }
            Text(
                stringResource(R.string.tool_recent_note),
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)
            )
        }
    }
}

/** One numbered step of the empty tool screen: the number in a recessed circle, [title] over [body]. */
@Composable
private fun ToolStep(number: Int, title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = colors.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
            Text(body, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
    }
}

/** A card on the background, as the prototype's: surface, outline, 18 dp corners. */
@Composable
fun ToolCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
        content = content
    )
}

/**
 * A tool screen with its rank-1 [action] (T1, [actionEnabled]) pinned full width at the bottom (2b), so no text size scrolls it away; the
 * [content] above it scrolls, or with [scroll] false fills the space and scrolls itself (a lazy grid of pages). A tool with a second way
 * to save ([secondary], T2) shows it beside the action.
 */
@Composable
fun ToolConfigLayout(
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    actionIcon: ImageVector? = null,
    scroll: Boolean = true,
    secondary: ToolAction? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier), content = content)
        ToolActionBar {
            if (secondary == null) {
                ToolPrimaryButton(action, onClick = onAction, modifier = Modifier.fillMaxWidth(), enabled = actionEnabled, icon = actionIcon)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolPrimaryButton(action, onClick = onAction, modifier = Modifier.weight(1f), enabled = actionEnabled, icon = actionIcon)
                    ToolSecondaryButton(
                        secondary.label,
                        onClick = secondary.onClick,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        enabled = secondary.enabled
                    )
                }
            }
        }
    }
}

/** A second action of a tool screen: [label], [onClick], and whether it is [enabled]. */
class ToolAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** The strip under a tool screen that holds its pinned actions: a top rule, then [content] on the background. */
@Composable
fun ToolActionBar(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp), content = content)
    }
}

/** The first page of [uri] (opened with [password]) small, as a file card's thumbnail. */
@Composable
fun PdfThumbnail(uri: Uri, password: String?, modifier: Modifier = Modifier) {
    val request = remember(uri, password) { PdfPageRequest(uri, 0, password, THUMBNAIL_SCALE) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))) {
        Image(
            painter = rememberAsyncImagePainter(request, PdfImageLoader.get(LocalContext.current)),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

/** The render scale of [PdfThumbnail]: enough for 64 dp on a dense screen. */
internal const val THUMBNAIL_SCALE = 0.4f

/**
 * The picked file as a small card (2b): its first page (from [uri], opened with [password]), [name] over [details] (size and pages), and
 * Change ([onChange], T3: 40 dp visible in a 48 dp target). The name wraps rather than clips.
 */
@Composable
fun ToolFileCard(uri: Uri, password: String?, name: String, details: String, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    ToolCard(modifier) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(uri, password, Modifier.size(width = 48.dp, height = 64.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colors.onSurface)
                Text(details, fontSize = 13.sp, color = LocalScanPalette.current.meta)
            }
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onChange),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.heightIn(min = 40.dp).border(1.dp, colors.outline, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.tool_change), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                }
            }
        }
    }
}

/** True once [active] (loading or processing) has lasted five seconds, so the screen can say the work may take a while. */
@Composable
fun rememberLoadingWarning(active: Boolean): Boolean {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(5000)
            show = true
        } else {
            show = false
        }
    }
    return show
}

/** A file is being opened: a spinner, [text], and after a while (with [showWarning]) that it may take a moment. */
@Composable
fun LoadingStateView(showWarning: Boolean, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            SlowNote(showWarning, stringResource(R.string.tool_loading_slow))
        }
    }
}

/** A tool is working: the file's cover (from [uri] or [preview]), a spinner, [text], the page reached and, after a while, a note. */
@Composable
fun ProcessingStateView(
    preview: Bitmap? = null,
    uri: Uri? = null,
    password: String? = null,
    text: String,
    current: Int,
    total: Int,
    showWarning: Boolean
) {
    val imageLoader = PdfImageLoader.get(LocalContext.current)
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp).fillMaxHeight()
        ) {
            Surface(
                modifier = Modifier.size(160.dp, 226.dp), // A4 aspect ratio
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceVariant,
                border = BorderStroke(1.dp, colors.outline)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (uri != null) {
                        val request = remember(uri, password) { PdfPageRequest(uri, 0, password, 0.8f) }
                        Image(
                            painter = rememberAsyncImagePainter(request, imageLoader),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else if (preview != null) {
                        Image(bitmap = preview.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(color = colors.primary, modifier = Modifier.size(32.dp))
                    }
                }
            }

            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(color = colors.primary, modifier = Modifier.size(40.dp), strokeWidth = 4.dp)
            Spacer(Modifier.height(24.dp))
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colors.onBackground, textAlign = TextAlign.Center)
            if (total > 0) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tool_page_progress, current, total), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
            SlowNote(showWarning, stringResource(R.string.tool_processing_slow))
        }
    }
}

/** The note that long work is still running, on the recessed surface, with an icon and words. */
@Composable
private fun SlowNote(visible: Boolean, text: String) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(top = 24.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A heading over a group of a tool's options. */
@Composable
fun ToolSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() }
    )
}

/** A count or state under a tool's file (pages selected, locked), read out when it changes. */
@Composable
fun ToolStatusText(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** One choice of a single-choice option: a card of at least 56 dp with a radio button, [title] and [description]; selected is tonal. */
@Composable
fun ToolOptionRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.primaryContainer else colors.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) colors.onPrimaryContainer else colors.outline),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colors.onSurface)
                Text(description, fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A tool's rank-1 action (T1): filled with the primary role, at least 56 dp high, its label growing with the text size. */
@Composable
fun ToolPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** A tool's second action (T2): outlined, at least 48 dp high. */
@Composable
fun ToolSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.surfaceVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** A badge over a page image, such as its number: dark in Light and OLED alike, since pages are light. Its text is [pageBadgeText]. */
val pageBadge: Color @Composable get() = LocalScanPalette.current.camera.cam.copy(alpha = 0.75f)

/** The text and icons on [pageBadge]. */
val pageBadgeText: Color @Composable get() = LocalScanPalette.current.camera.onCam
