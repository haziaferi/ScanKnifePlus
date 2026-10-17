package com.haziaferi.scanknifeplus.ui.scan

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.scanner.filter.DocumentFilters
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette

/** The label of each of the scanner's filters, by their stable names (DocumentFilters.all, in picker order). */
@StringRes
internal fun filterLabel(name: String): Int = when (name) {
    DocumentFilters.Auto.name -> R.string.scan_filter_auto
    DocumentFilters.Lighten.name -> R.string.scan_filter_lighten
    DocumentFilters.Grayscale.name -> R.string.scan_filter_grayscale
    DocumentFilters.BlackAndWhite.name -> R.string.scan_filter_bw
    DocumentFilters.Whiteboard.name -> R.string.scan_filter_whiteboard
    else -> R.string.scan_filter_original
}

@StringRes
private fun sizeLabel(size: PdfPageSize): Int = when (size) {
    PdfPageSize.A4 -> R.string.scan_size_a4
    PdfPageSize.LETTER -> R.string.scan_size_letter
    PdfPageSize.LEGAL -> R.string.scan_size_legal
    PdfPageSize.FIT -> R.string.scan_size_fit
}

/** Picks one of the scanner's filters for the selected page; [current] is its applied filter (null: none). */
@Composable
internal fun FilterDialog(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val selected = current ?: DocumentFilters.default.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_filter)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (filter in DocumentFilters.all) {
                    ChoiceRow(filterLabel(filter.name), filter.name == selected) { onPick(filter.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_cancel)) } }
    )
}

/**
 * The export options before saving: page size (Fit included) and an optional password, where empty means none (decision 6a). The PDF is
 * then saved into the user's [folder] when one is chosen (its name, shown here), else where the system file picker says.
 */
@Composable
internal fun SaveDialog(onSave: (PdfPageSize, String) -> Unit, onDismiss: () -> Unit, folder: String? = null) {
    var size by remember { mutableStateOf(PdfPageSize.DEFAULT) }
    var password by remember { mutableStateOf("") }
    val usable = remember(password) { ScanExport.isUsablePassword(password) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_save_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scan_page_size), style = scanText(14, FontWeight.Bold))
                FlowRow(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (option in PdfPageSize.entries) SizeChip(sizeLabel(option), option == size) { size = option }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.scan_password)) },
                    supportingText = { Text(stringResource(if (usable) R.string.scan_password_hint else R.string.scan_password_invalid)) },
                    isError = !usable,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                val where = if (folder == null) stringResource(R.string.scan_save_where) else stringResource(R.string.scan_save_into, folder)
                Text(where, style = scanText(13, FontWeight.Medium), color = LocalScanPalette.current.mu)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(size, password) }, enabled = usable) {
                Text(stringResource(if (folder == null) R.string.scan_save_choose else R.string.scan_save_folder))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_cancel)) } }
    )
}

/** Leaving without Done (decision 6): keep the [count] scanned pages in the app, discard them, or stay. */
@Composable
internal fun LeaveDialog(count: Int, onKeep: () -> Unit, onDiscard: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.scan_leave_title)) },
        text = { Text(pluralStringResource(R.plurals.scan_leave_body, count, count)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.scan_discard), color = LocalScanPalette.current.danger) }
                TextButton(onClick = onKeep) { Text(stringResource(R.string.scan_keep)) }
            }
        },
        dismissButton = { TextButton(onClick = onStay) { Text(stringResource(R.string.scan_cancel)) } }
    )
}

@Composable
private fun ChoiceRow(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalScanPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.acf, unselectedColor = c.bd))
        Text(stringResource(label), style = scanText(15, FontWeight.Medium), color = c.tx, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SizeChip(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalScanPalette.current
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(c.acSoft).border(1.5.dp, c.ac, shape) else Modifier.border(1.dp, c.bd, shape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(label), style = scanText(14, FontWeight.Bold), color = if (selected) c.ac else c.tx)
    }
}
