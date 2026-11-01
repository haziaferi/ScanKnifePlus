package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R

/**
 * Whether a tool's tip shows (R4, decision 6): it does until the user closes it; closed, it stays closed for that tool, kept in the app's
 * preferences, until [show] (the top bar's (i)) brings it back. Read and change it on the main thread.
 */
class ToolTipState(private val context: Context, private val toolId: String) {
    var shown by mutableStateOf(!PreferencesManager.isTipClosed(context, toolId))
        private set

    fun close() {
        shown = false
        PreferencesManager.setTipClosed(context, toolId, true)
    }

    fun show() {
        shown = true
        PreferencesManager.setTipClosed(context, toolId, false)
    }
}

/** The [ToolTipState] of the tool whose catalog id is [toolId]. */
@Composable
fun rememberToolTip(toolId: String): ToolTipState {
    val context = LocalContext.current.applicationContext
    return remember(toolId, context) { ToolTipState(context, toolId) }
}

/**
 * A tip that points elsewhere (R4): a card in the screen, under the file card, so it scrolls with the body and covers nothing. An (i), [text]
 * and the [link] when there is one, and a 48 dp close button ([onClose]).
 */
@Composable
fun ToolTipCard(text: String, onClose: () -> Unit, modifier: Modifier = Modifier, link: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, colors.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.onSurface, modifier = Modifier.padding(top = 14.dp).size(20.dp))
            Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurface)
                link?.invoke()
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.tip_close), tint = colors.onSurface, modifier = Modifier.size(22.dp))
            }
        }
    }
}
