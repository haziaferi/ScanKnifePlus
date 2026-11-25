package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.TargetStep

/**
 * The tool sheet, both the All tools sheet and the reader's: every [ToolCatalog] tool (decision 38) under the title and the search field,
 * which stay in view while the list scrolls (rule 15). [fileName] is the file the chosen tool opens with, shown as a chip (35) at the top of
 * the list; removing it calls [onRemoveFile], after which the tool opens empty. No chip without [onRemoveFile].
 */
@Composable
fun ToolPickerContent(onToolClick: (String) -> Unit, fileName: String? = null, onRemoveFile: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    // As tall as the sheet allows: a sheet is as tall as its content, so a list that narrowed as the user typed would pull the sheet, and
    // the field being typed in, down the screen.
    Column(Modifier.fillMaxHeight()) {
        Text(
            stringResource(R.string.tools_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() }
        )
        ToolSearchField(query, onChange = { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
        ToolsView(
            onToolClick = onToolClick,
            modifier = Modifier.weight(1f).navigationBarsPadding(),
            query = query,
            contentPadding = PaddingValues(bottom = 24.dp),
            header = {
                if (fileName != null && onRemoveFile != null) {
                    item(key = "file") { FileChip(fileName, onRemoveFile, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) }
                }
            }
        )
    }
}

/**
 * The state of a sheet that holds [ToolPickerContent]: it opens at full height, not half, so the pinned title and search field sit where
 * the list starts and the first tools show under them; half open at twice the text size on a 640 dp phone, they left no room for one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberToolSheetState(): SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

/**
 * The search over the tools' names and descriptions (decision 3): at least T2 high, with a clear button (T3) while it holds text. It takes
 * focus, and so the keyboard, only when tapped; the keyboard's Search key puts the keyboard away.
 */
@Composable
private fun ToolSearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().heightIn(min = TargetStep.ACT.drawn),
        placeholder = { Text(stringResource(R.string.tools_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.history_search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            focusManager.clearFocus()
        }),
        shape = RoundedCornerShape(14.dp)
    )
}

/** The pre-loaded file as a chip (T3 drawn): label, name and a remove button (T3). */
@Composable
private fun FileChip(fileName: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.tools_file_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.heightIn(min = TargetStep.INLINE.drawn)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    fileName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.tools_remove_file, fileName),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
