package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette

/**
 * The tools' shared result screen: a check mark, [message] and [subMessage], how long the work took, then Process more files (T1), Open
 * preview (shown with [showPreviewButton], enabled with [onPreview]) and Back to tools. It scrolls, so nothing clips at large text sizes.
 */
@Composable
fun SuccessView(
    message: String = "Task Complete",
    subMessage: String = "Saved successfully",
    processingTime: String = "",
    onDone: () -> Unit,
    onProcessMore: () -> Unit,
    onPreview: (() -> Unit)? = null,
    showPreviewButton: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalScanPalette.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(modifier = Modifier.size(96.dp), shape = CircleShape, color = palette.toolBg) {
            Icon(Icons.Filled.Check, null, tint = palette.toolFg, modifier = Modifier.padding(24.dp).size(48.dp))
        }

        Spacer(Modifier.height(32.dp))
        Text(
            message,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 26.sp,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
            if (subMessage.isNotBlank()) {
                Text(subMessage, fontSize = 15.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (processingTime.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(color = palette.toolBg, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        stringResource(R.string.tool_done_in, processingTime),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.toolFg,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(48.dp))

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ToolPrimaryButton(stringResource(R.string.tool_process_more), onClick = onProcessMore, modifier = Modifier.fillMaxWidth())

            if (showPreviewButton) {
                OutlinedButton(
                    onClick = { onPreview?.invoke() },
                    enabled = onPreview != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    val tint = if (onPreview != null) colors.onSurface else colors.onSurfaceVariant
                    Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(20.dp), tint = tint)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(if (onPreview != null) R.string.tool_open_preview else R.string.tool_preview_unavailable),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tint
                    )
                }
            }

            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tool_back_to_tools), fontWeight = FontWeight.SemiBold, color = colors.onBackground, fontSize = 15.sp)
            }
        }
    }
}
