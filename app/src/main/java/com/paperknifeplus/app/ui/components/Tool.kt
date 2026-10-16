package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.ScanPalette
import java.util.UUID

/** The [ActivityEntry.tool] label of scans; ScanExport writes it. */
const val SCAN_ENTRY_TOOL = "Scan"

/**
 * One History entry. [id] is unique: it keys History's lazy list, so a row keeps its state when newer entries shift the list. [tool] is
 * the label the producing screen gave, [SCAN_ENTRY_TOOL] for scans.
 */
data class ActivityEntry(
    val id: String,
    val name: String,
    val tool: String,
    val size: String,
    val icon: ImageVector,
    val uri: Uri? = null,
    val pageCount: Int = 0
) {
    val isScan: Boolean get() = tool == SCAN_ENTRY_TOOL

    /** The colour role of this entry's tag. */
    val tagRole: TagRole get() = if (isScan) TagRole.SCAN else TagRole.TOOL
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

/** This session's History, newest first. Compose state: change it on the main thread. */
object SessionManager {
    val history = mutableStateListOf<ActivityEntry>()

    fun addEntry(name: String, tool: String, size: String, icon: ImageVector, uri: Uri? = null, pageCount: Int = 0) {
        history.add(0, ActivityEntry(UUID.randomUUID().toString(), name, tool, size, icon, uri, pageCount))
    }

    fun clearHistory() {
        history.clear()
    }
}
