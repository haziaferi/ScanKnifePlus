package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import coil.compose.rememberAsyncImagePainter
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.ui.folder.FolderHolder
import com.haziaferi.scanknifeplus.ui.folder.RecentPdfs
import com.paperknifeplus.app.MainActivity
import com.paperknifeplus.app.R
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.ScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.inlineTarget
import java.util.UUID
import kotlinx.coroutines.delay

/** The [ActivityEntry.toolId] of scans; ScanExport writes it. Tools use their [ToolCatalog] id. */
const val SCAN_ENTRY_TOOL = "scan"

/**
 * One History entry of this session. [id] is unique: it keys History's lazy list, so a row keeps its state when newer entries shift the
 * list. [toolId] is the [ToolCatalog] id of the tool that made it, [SCAN_ENTRY_TOOL] for scans; [time] orders it among the saved files.
 */
data class ActivityEntry(
    val id: String,
    val name: String,
    val toolId: String,
    val size: String,
    val icon: ImageVector,
    val uri: Uri? = null,
    val pageCount: Int = 0,
    val time: Long = 0
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

/**
 * This session's History rows that are not saved files (a text read on screen), newest first; saved files are listed by [SavedFiles] across
 * restarts. Compose state: change it on the main thread. Tools record through [ToolOutputs].
 */
object SessionManager {
    val history = mutableStateListOf<ActivityEntry>()

    fun addEntry(name: String, toolId: String, size: String, icon: ImageVector, uri: Uri? = null, pageCount: Int = 0) {
        history.add(0, ActivityEntry(UUID.randomUUID().toString(), name, toolId, size, icon, uri, pageCount, System.currentTimeMillis()))
    }

    fun clearHistory() {
        history.clear()
    }
}

/**
 * Where the tools' outputs are listed under [toolId]: a saved file in the index of saved files, which History reads across restarts, and a
 * text shown on screen in this session's list. Call on the main thread.
 */
class ToolOutputs(private val toolId: String, private val context: Context) {
    /**
     * A file the tool saved at [uri] (where its [SaveTarget] put it) as [name], with [pageCount] pages, listed (and a picker file's grant
     * taken) before this returns, so a kill or a cancel right after it never loses the row or deletes the listed file. History draws it from
     * its row (its date, size and tool), so [summary] and [icon], which describe a session row, are not kept for it. Blocks briefly.
     */
    fun saved(uri: Uri, name: String, summary: String, icon: ImageVector, pageCount: Int = 0) {
        SavedFiles.of(context).record(uri, name, toolId, pageCount)
    }

    /** An output that is not a file the user saved (text shown on screen): this session's History only. */
    fun record(name: String, summary: String, icon: ImageVector, uri: Uri? = null, pageCount: Int = 0) =
        SessionManager.addEntry(name, toolId, summary, icon, uri, pageCount)
}

/**
 * [id] formatted with [args], for text outside composition: toasts, History summaries and semantics set in callbacks. Inside composition
 * screens use stringResource, which follows configuration changes.
 */
fun toolText(context: Context, @StringRes id: Int, vararg args: Any?): String = context.getString(id, *args)

/** The [ToolOutputs] of the tool whose [ToolCatalog] id is [toolId]. */
@Composable
fun rememberToolOutputs(toolId: String): ToolOutputs {
    val context = LocalContext.current.applicationContext
    return remember(toolId, context) { ToolOutputs(toolId, context) }
}

/**
 * The top bar of the tool screens, Settings and About: a back button named [backLabel], [title] over [subtitle], a [changeLabel] button
 * (Change) when [showChange], and the (i) that brings a closed tip back ([onShowTip], R4) when there is one; the three are T2.
 */
@Composable
fun ToolTopBar(
    title: String,
    subtitle: String?,
    showChange: Boolean,
    onBack: () -> Unit,
    onChange: () -> Unit = {},
    changeLabel: String = stringResource(R.string.tool_change),
    backLabel: String = stringResource(R.string.tool_back),
    onShowTip: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), color = colors.background) {
        BarRow(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            leading = {
                RowIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, backLabel, tint = colors.onBackground)
                }
            },
            middle = {
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onBackground,
                        modifier = Modifier.semantics { heading() }
                    )
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            },
            trailing = {
                if (showChange || onShowTip != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showChange) {
                            // Weighted, so the (i) is measured first and keeps its 48 dp when the label must wrap.
                            TextButton(
                                onClick = onChange,
                                modifier = Modifier.weight(1f, fill = false).fillMaxHeight().heightIn(min = TargetStep.ACT.drawn)
                            ) {
                                Text(changeLabel, style = MaterialTheme.typography.labelLarge, color = colors.onBackground)
                            }
                        }
                        if (onShowTip != null) {
                            RowIconButton(onClick = onShowTip) {
                                Icon(Icons.Outlined.Info, stringResource(R.string.tip_show), tint = colors.onBackground, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        )
    }
}

/**
 * An icon button (T2) that grows with the peers in its row: the whole of its height takes the touch, so the row's hit areas leave no gap,
 * while the press highlight stays a round 48 dp one, T2's drawn size (Material's IconButton draws 40 dp), and the icon stays centred. Use it
 * inside a row of fixed height, as the top bar's or one with `IntrinsicSize.Min`.
 */
@Composable
internal fun RowIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: @Composable () -> Unit) {
    Box(
        modifier
            .sizeIn(minWidth = TargetStep.ACT.hit, minHeight = TargetStep.ACT.hit)
            .fillMaxHeight()
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = TargetStep.ACT.hit / 2),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(TargetStep.ACT.hit), contentAlignment = Alignment.Center) { icon() }
    }
}

/**
 * A bar: [leading], then [middle], then [trailing]. On one line when the title and the trailing group each get at least their longest word:
 * the leading and trailing controls are then peers at the height of the taller (R14 rule 1), each measured at the width it gets, the title
 * takes the width between, and each is centred in the row. Otherwise the trailing group moves under the title, at the end edge and its own
 * width, so a line breaks between words; a word breaks only when it alone is wider than its line. The leading control then keeps its own
 * step, level with the title's top, and the trailing controls share one height among themselves. Mirrors right to left.
 */
@Composable
private fun BarRow(modifier: Modifier, leading: @Composable () -> Unit, middle: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Layout(contents = listOf(leading, middle, trailing), modifier = modifier) { (lead, mid, trail), constraints ->
        val total = constraints.maxWidth
        val leadWidths = lead.map { minOf(it.maxIntrinsicWidth(Constraints.Infinity), total) }
        val leadWidth = leadWidths.sum()
        val titleWord = mid.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0
        val trailWord = trail.sumOf { it.minIntrinsicWidth(Constraints.Infinity) }
        if (trail.isEmpty() || leadWidth + titleWord + trailWord <= total) {
            val trailRoom = (total - leadWidth - titleWord).coerceAtLeast(0)
            val trailWidths = trail.map { minOf(it.maxIntrinsicWidth(Constraints.Infinity), trailRoom) }
            val controls = (lead.zip(leadWidths) + trail.zip(trailWidths)).maxOfOrNull { (it, width) -> it.minIntrinsicHeight(width) } ?: 0
            val peer = { maxWidth: Int -> Constraints(maxWidth = maxWidth.coerceAtLeast(0), minHeight = controls, maxHeight = controls) }
            val leadPlaced = lead.map { it.measure(peer(total)) }
            val trailPlaced = trail.map { it.measure(peer(trailRoom)) }
            val rest = (total - leadPlaced.sumOf { it.width } - trailPlaced.sumOf { it.width }).coerceAtLeast(0)
            val midPlaced = mid.map { it.measure(Constraints(minWidth = rest, maxWidth = rest)) }
            val height = maxOf(controls, midPlaced.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(total, height) {
                var x = 0
                for (p in leadPlaced + midPlaced + trailPlaced) {
                    p.placeRelative(x, (height - p.height) / 2)
                    x += p.width
                }
            }
        } else {
            val loose = Constraints(maxWidth = total)
            val leadPlaced = lead.map { it.measure(loose) }
            val rest = (total - leadPlaced.sumOf { it.width }).coerceAtLeast(0)
            val midPlaced = mid.map { it.measure(Constraints(minWidth = rest, maxWidth = rest)) }
            // One height for the trailing controls, so a grown label and the (i) beside it stay peers (R14 rule 1).
            val trailPlaced = trail.map {
                val shared = it.minIntrinsicHeight(minOf(it.maxIntrinsicWidth(Constraints.Infinity), total))
                it.measure(Constraints(maxWidth = total, minHeight = shared, maxHeight = shared))
            }
            val firstLine = maxOf(leadPlaced.maxOfOrNull { it.height } ?: 0, midPlaced.maxOfOrNull { it.height } ?: 0)
            val height = (firstLine + trailPlaced.sumOf { it.height }).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(total, height) {
                var x = 0
                for (p in leadPlaced + midPlaced) {
                    // A title shorter than the controls is centred on them; a taller one starts level with them.
                    p.placeRelative(x, ((leadPlaced.maxOfOrNull { it.height } ?: 0) - p.height).coerceAtLeast(0) / 2)
                    x += p.width
                }
                var y = firstLine
                for (p in trailPlaced) {
                    p.placeRelative(total - p.width, y)
                    y += p.height
                }
            }
        }
    }
}

/**
 * The top bar of a tool screen in [state]: none while the tool works; on the result, how long it took ([processingTime]) under [title] and
 * back named Back to tools; otherwise [subtitle], Change ([onChange]) for a tool whose files are a list rather than one file card, and the
 * (i) of a closed tip ([onShowTip]).
 */
@Composable
fun ToolScreenBar(
    state: ToolState,
    title: String,
    subtitle: String,
    processingTime: String,
    onBack: () -> Unit,
    onChange: (() -> Unit)? = null,
    onShowTip: (() -> Unit)? = null
) {
    when (state) {
        ToolState.PROCESSING -> Unit
        ToolState.SUCCESS -> ToolTopBar(
            title = title,
            subtitle = processingTime.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.tool_done_in, it) },
            showChange = false,
            onBack = onBack,
            backLabel = stringResource(R.string.tool_back_to_tools)
        )
        else -> ToolTopBar(
            title = title,
            subtitle = subtitle,
            showChange = onChange != null,
            onBack = onBack,
            onChange = onChange ?: {},
            onShowTip = onShowTip
        )
    }
}

/**
 * The recent PDFs for the empty tool screens, newest first: the user's folder's while it is listed, the files kept in the app while no
 * folder is listed (none chosen, or it can't be reached); null while the folder is read.
 */
val LocalFolderPdfs = staticCompositionLocalOf<@Composable () -> RecentPdfs?> { { appFolderPdfs() } }

@Composable
private fun appFolderPdfs(): RecentPdfs? {
    // Only the app's own activity holds the folder (its FolderHolder, made in onCreate); any other host, such as a test, has none.
    val activity = LocalActivity.current as? MainActivity ?: return null
    val folder = remember(activity) { ViewModelProvider(activity)[FolderHolder::class.java].folder }
    return folder.toolPdfs
}

/**
 * The empty tool screen (1b, then 1c): a card naming the tool's [title] and its three steps ([chooseStep], [task], saving), then the newest
 * PDFs of the user's folder, or those kept in the app without a listed folder ([LocalFolderPdfs]), which [onOpenRecent] opens in the tool,
 * and [chooseLabel] (T1, the picker) pinned at the bottom. With none to list, or without [onOpenRecent] (a tool that takes images), only
 * the card shows.
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
    val pdfs = if (onOpenRecent != null) LocalFolderPdfs.current() else null
    val recent = pdfs?.items
    ToolConfigLayout(action = chooseLabel, onAction = onChoose, actionIcon = Icons.Outlined.Description) {
        ToolCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(52.dp).background(colors.surfaceVariant, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(28.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )
                }
                HorizontalDivider(color = colors.outline)
                // With no folder listed, the files to start from are those kept in the app, not the folder's.
                ToolStep(1, chooseStep, stringResource(if (pdfs?.kept == true) R.string.tool_step_choose_body_kept else R.string.tool_step_choose_body))
                ToolStep(2, stringResource(R.string.tool_step_options), task)
                ToolStep(3, stringResource(R.string.tool_step_save), stringResource(R.string.tool_step_save_body))
            }
        }
        if (!recent.isNullOrEmpty() && onOpenRecent != null) {
            Text(
                stringResource(if (pdfs?.kept == true) R.string.tool_recent_kept else R.string.home_recent_folder),
                style = MaterialTheme.typography.titleMedium,
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
                style = MaterialTheme.typography.bodySmall,
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
        Box(
            Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).background(colors.surfaceVariant, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
 * to save ([secondary], T2) shows it beside the action, the two one height. A tool that saves one new PDF says so in a line over the action
 * ([savesNewPdf], R6). An action that saves through [save] gets "Save elsewhere…" (T3) under it, which runs it with the system picker. When
 * pinning that would leave the content less than half the window (large text on a short phone), or over a page grid at large text, it goes to the
 * end of the scrolling content instead, or under a page grid's file card ([LocalSaveElsewhere]), so every page stays in reach (rule 10).
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
    savesNewPdf: Boolean = false,
    save: SaveTarget? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize.height
    var total by remember { mutableIntStateOf(0) }
    var bar by remember { mutableIntStateOf(0) }
    val slot = remember { SaveElsewhereSlot() }
    // Judged with the button pinned, wherever it is now, so the choice never flips back and forth.
    val t3 = with(density) { TargetStep.ACT.hit.roundToPx() }
    val pinnedBar = if (slot.inContent) bar + t3 else bar
    // A page grid at large text needs room for a whole page, which half the window may not give (a page in one column is taller).
    val squeezed = total - pinnedBar < window / 2 || !scroll && density.fontScale >= LARGE_TEXT
    val inContent = save != null && total > 0 && (scroll || slot.claimed) && squeezed
    SideEffect { slot.inContent = inContent }
    val elsewhere: @Composable () -> Unit = {
        if (save != null) SaveElsewhereButton(onClick = { save.elsewhere(onAction) }, enabled = actionEnabled, modifier = Modifier.padding(vertical = 8.dp))
    }
    // Above the keyboard, so a text field never hides the action (the window draws edge to edge).
    Column(modifier.fillMaxSize().imePadding().onSizeChanged { total = it.height }) {
        CompositionLocalProvider(LocalSaveElsewhere provides slot.takeIf { save != null }?.also { it.content = elsewhere }) {
            Column(Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                content()
                if (inContent && scroll) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { elsewhere() }
            }
        }
        ToolActionBar(bottom = if (save != null && !inContent) 0.dp else 20.dp, modifier = Modifier.onSizeChanged { bar = it.height }) {
            if (savesNewPdf) {
                Text(
                    stringResource(R.string.tool_saves_new_pdf),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (secondary == null) {
                ToolPrimaryButton(action, onClick = onAction, modifier = Modifier.fillMaxWidth(), enabled = actionEnabled, icon = actionIcon)
            } else {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolPrimaryButton(action, onClick = onAction, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = actionEnabled, icon = actionIcon)
                    ToolSecondaryButton(
                        secondary.label,
                        onClick = secondary.onClick,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        enabled = secondary.enabled
                    )
                }
            }
            if (save != null && !inContent) {
                SaveElsewhereButton(onClick = { save.elsewhere(onAction) }, enabled = actionEnabled, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

/**
 * Where [ToolConfigLayout] keeps its "Save elsewhere…" when the bar cannot: a page grid that can show it under its header [claimed] it while
 * composing, and shows [content] while [inContent].
 */
class SaveElsewhereSlot internal constructor() {
    var claimed by mutableStateOf(false)
        internal set
    var inContent by mutableStateOf(false)
        internal set
    internal var content: @Composable () -> Unit = {}
}

/** The [SaveElsewhereSlot] of the tool screen around a lazy page grid, which shows its button under its header when it moves there. */
val LocalSaveElsewhere = staticCompositionLocalOf<SaveElsewhereSlot?> { null }

/** The text scale from which a page grid keeps its tool's Save elsewhere after its last page, so a whole page always fits in view. */
private const val LARGE_TEXT = 1.5f

/** A second action of a tool screen: [label], [onClick], and whether it is [enabled]. */
class ToolAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The strip under a tool screen that holds its pinned actions: a top rule, then [content] on the background, [bottom] over its lower edge
 * (none under a T3, whose own margin and hit area take that room, so the bar costs the screen as little as it can).
 */
@Composable
fun ToolActionBar(modifier: Modifier = Modifier, bottom: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom), content = content)
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
 * The picked file as a small card (2b): its first page (from [uri], opened with [password]), [name] over [details] (size and pages), the
 * [PasswordLine] when the tool's output [keepsPassword], and Change ([onChange], T3). The name wraps rather than clips.
 */
@Composable
fun ToolFileCard(
    uri: Uri,
    password: String?,
    name: String,
    details: String,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
    keepsPassword: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    ToolCard(modifier) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(uri, password, Modifier.size(width = 48.dp, height = 64.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(details, style = MaterialTheme.typography.bodySmall, color = LocalScanPalette.current.meta)
                if (keepsPassword) PasswordLine()
            }
            CardChange(onChange)
        }
    }
}

/** A file card's Change (T3): an outlined pill. */
@Composable
internal fun CardChange(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.inlineTarget().clip(RoundedCornerShape(20.dp)).border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.tool_change), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
    }
}

/**
 * The fact that a tool's output keeps the open password of its source (decision 20d), under the file on the file card and the result card:
 * a lock and one wording wherever it shows.
 */
@Composable
fun PasswordLine(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurface, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(stringResource(R.string.tool_keeps_password), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
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
            Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
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
            Text(text, style = MaterialTheme.typography.titleMedium, color = colors.onBackground, textAlign = TextAlign.Center)
            if (total > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tool_page_progress, current, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
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
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A heading over a group of a tool's options. */
@Composable
fun ToolSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() }
    )
}

/** A count or state under a tool's file (pages selected, locked), read out when it changes. */
@Composable
fun ToolStatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/**
 * The [status] line of a page grid with its text [action] (T3) at the end edge. Side by side while the action's label fits one line beside the
 * status's longest word, the status then wrapping between words; otherwise, measured at the current text size, the action moves under the
 * status, so neither breaks inside a word (rule 10).
 */
@Composable
fun ToolStatusRow(status: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Layout(contents = listOf({ ToolStatusText(status) }, action), modifier = modifier) { (text, act), constraints ->
        val total = constraints.maxWidth
        val actionWidth = act.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val word = text.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0
        if (word + actionWidth <= total) {
            val actionPlaced = act.map { it.measure(Constraints(maxWidth = actionWidth)) }
            val textPlaced = text.map { it.measure(Constraints(maxWidth = total - actionWidth)) }
            val height = (actionPlaced + textPlaced).maxOfOrNull { it.height } ?: 0
            layout(total, height) {
                textPlaced.forEach { it.placeRelative(0, (height - it.height) / 2) }
                actionPlaced.forEach { it.placeRelative(total - it.width, (height - it.height) / 2) }
            }
        } else {
            val textPlaced = text.map { it.measure(Constraints(maxWidth = total)) }
            val actionPlaced = act.map { it.measure(Constraints(maxWidth = total)) }
            val first = textPlaced.maxOfOrNull { it.height } ?: 0
            layout(total, first + (actionPlaced.maxOfOrNull { it.height } ?: 0)) {
                textPlaced.forEach { it.placeRelative(0, 0) }
                actionPlaced.forEach { it.placeRelative(total - it.width, first) }
            }
        }
    }
}

/** One choice of a single-choice option (T2): a card with a radio button, [title] and [description]; selected is tonal. */
@Composable
fun ToolOptionRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.primaryContainer else colors.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) colors.onPrimaryContainer else colors.outline),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TargetStep.ACT.drawn)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A tool's rank-1 action (T1): filled with the primary role, its label growing with the text size. */
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
        modifier = modifier.heightIn(min = TargetStep.COMMIT.drawn),
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
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** A tool's second action (T2): outlined. */
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
        modifier = modifier.heightIn(min = TargetStep.ACT.drawn),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.surfaceVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
}

/** A badge over a page image, such as its number: dark in Light and OLED alike, since pages are light. Its text is [pageBadgeText]. */
val pageBadge: Color @Composable get() = LocalScanPalette.current.camera.cam.copy(alpha = 0.75f)

/** The text and icons on [pageBadge]. */
val pageBadgeText: Color @Composable get() = LocalScanPalette.current.camera.onCam

/** Marks drawn over a page (the crop frame and its handles): the palette's Light accent in both themes, since pages are light (15a). */
val pageMark: Color
    @Composable get() = LocalScanPalette.current.let { tokens -> AppPalette.entries.firstOrNull { it.oled == tokens }?.light ?: tokens }.acf

/** The dimming over the part of a page a mark leaves out (outside the crop frame): the camera colour, as [pageBadge]. */
val pageDim: Color @Composable get() = LocalScanPalette.current.camera.cam.copy(alpha = 0.55f)
