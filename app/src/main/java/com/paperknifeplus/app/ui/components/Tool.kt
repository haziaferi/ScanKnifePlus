package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import coil.compose.rememberAsyncImagePainter
import com.haziaferi.scanknifeplus.ui.folder.OutputCopy
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
    OutputCopy.CHOOSE_AGAIN -> R.string.tool_copy_unreachable
    OutputCopy.FAILED -> R.string.tool_copy_failed
}

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

/** The empty tool screen: one card, the whole of it a button that opens the file picker. */
@Composable
fun SelectionGrid(onSelect: () -> Unit, icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Box(
                modifier = Modifier.size(64.dp).background(colors.surfaceVariant, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(32.dp))
            }
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = colors.onSurface, textAlign = TextAlign.Center)
            Text(subtitle, fontSize = 14.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
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

/** The picked file under its preview: [name], wrapping rather than clipping, over its [details] line (size and pages). */
@Composable
fun ToolFileInfo(name: String, details: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Text(details, fontSize = 13.sp, color = LocalScanPalette.current.meta, textAlign = TextAlign.Center)
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
