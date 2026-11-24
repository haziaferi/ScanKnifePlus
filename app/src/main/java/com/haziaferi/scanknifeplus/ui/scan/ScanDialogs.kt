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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.scanner.filter.DocumentFilters
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.components.DialogButton
import com.paperknifeplus.app.ui.components.DialogButtonKind
import com.paperknifeplus.app.ui.components.DialogButtons
import com.paperknifeplus.app.ui.components.DialogTitle
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.inlineTarget
import java.util.Locale

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

@StringRes
private fun languageLabel(language: OcrLanguage): Int = when (language) {
    OcrLanguage.ENGLISH -> R.string.scan_ocr_english
    OcrLanguage.ITALIAN -> R.string.scan_ocr_italian
}

@StringRes
private fun readingLabel(reading: OcrReading): Int = when (reading) {
    OcrReading.AUTOMATIC -> R.string.scan_ocr_automatic
    OcrReading.TABLES -> R.string.scan_ocr_tables
    OcrReading.RECEIPTS -> R.string.scan_ocr_receipts
}

/** The OCR language a save starts with: Italian on a phone set to Italian, else English. Not remembered: it follows the phone each time. */
internal fun defaultOcrLanguage(locale: Locale): OcrLanguage =
    if (locale.language == Locale.ITALIAN.language) OcrLanguage.ITALIAN else OcrLanguage.ENGLISH

/** Picks one of the scanner's filters for the selected page; [current] is its applied filter (null: none). */
@Composable
internal fun FilterDialog(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val selected = current ?: DocumentFilters.default.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.scan_filter)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (filter in DocumentFilters.all) {
                    ChoiceRow(filterLabel(filter.name), filter.name == selected) { onPick(filter.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(R.string.scan_cancel, onClick = onDismiss) }
    )
}

/**
 * The export options before saving: page size (Fit included) and an optional password, where empty means none (decision 6a), then Make
 * searchable ([searchable] to start with) and, while it is on, the OCR language ([language] to start with), the Reading layout (Automatic each
 * time) and the .txt copy ([txt] to start with). The PDF is then saved into the user's [folder] when one is chosen (its name, shown here), else
 * where the system file picker says.
 */
@Composable
internal fun SaveDialog(
    onSave: (SaveOptions) -> Unit,
    onDismiss: () -> Unit,
    folder: String? = null,
    searchable: Boolean = true,
    txt: Boolean = false,
    language: OcrLanguage = OcrLanguage.ENGLISH,
) {
    var size by remember { mutableStateOf(PdfPageSize.DEFAULT) }
    var password by remember { mutableStateOf("") }
    var makeSearchable by remember { mutableStateOf(searchable) }
    var ocrLanguage by remember { mutableStateOf(language) }
    var reading by remember { mutableStateOf(OcrReading.AUTOMATIC) }
    var alsoTxt by remember { mutableStateOf(txt) }
    val usable = remember(password) { ScanExport.isUsablePassword(password) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.scan_save_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.scan_page_size), style = MaterialTheme.typography.labelLarge)
                ChipRow(PdfPageSize.entries, size, ::sizeLabel) { size = it }
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
                SwitchRow(R.string.scan_ocr_searchable, R.string.scan_ocr_searchable_hint, makeSearchable) { makeSearchable = it }
                if (makeSearchable) {
                    Text(stringResource(R.string.scan_ocr_language), style = MaterialTheme.typography.labelLarge)
                    ChipRow(OcrLanguage.entries, ocrLanguage, ::languageLabel) { ocrLanguage = it }
                    Text(stringResource(R.string.scan_ocr_reading), style = MaterialTheme.typography.labelLarge)
                    ChipRow(OcrReading.entries, reading, ::readingLabel) { reading = it }
                    SwitchRow(R.string.scan_ocr_txt, null, alsoTxt) { alsoTxt = it }
                }
                val where = if (folder == null) stringResource(R.string.scan_save_where) else stringResource(R.string.scan_save_into, folder)
                Text(where, style = MaterialTheme.typography.bodySmall, color = LocalScanPalette.current.mu)
            }
        },
        confirmButton = {
            DialogButtons {
                DialogButton(R.string.scan_cancel, onClick = onDismiss)
                val save = if (folder == null) R.string.scan_save_choose else R.string.scan_save_folder
                DialogButton(save, enabled = usable, kind = DialogButtonKind.COMMIT) {
                    onSave(SaveOptions(size, password, if (makeSearchable) TextOptions(ocrLanguage, reading, alsoTxt) else null))
                }
            }
        }
    )
}

/**
 * Leaving without Done (decision 6, words of rule 5): Save scan keeps the [count] scanned pages in the app ([onKeep]), Discard drops them,
 * Keep scanning stays ([onStay]).
 */
@Composable
internal fun LeaveDialog(count: Int, onKeep: () -> Unit, onDiscard: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { DialogTitle(stringResource(R.string.scan_leave_title)) },
        text = { Text(pluralStringResource(R.plurals.scan_leave_body, count, count)) },
        confirmButton = {
            DialogButtons {
                DialogButton(R.string.scan_leave_stay, onClick = onStay)
                DialogButton(stringResource(R.string.scan_discard), color = LocalScanPalette.current.danger, onClick = onDiscard)
                DialogButton(R.string.scan_leave_save, kind = DialogButtonKind.COMMIT, onClick = onKeep)
            }
        }
    )
}

@Composable
private fun DialogButton(@StringRes label: Int, enabled: Boolean = true, kind: DialogButtonKind = DialogButtonKind.TEXT, onClick: () -> Unit) =
    DialogButton(stringResource(label), enabled = enabled, kind = kind, onClick = onClick)

@Composable
private fun ChoiceRow(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalScanPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = TargetStep.ACT.drawn).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.acf, unselectedColor = c.bd))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = c.tx, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * One choice of [options] as a row of chips (T3), [selected] marked; drawn chips sit [TargetStep.INLINE_GAP] apart, across and down, since
 * each chip's 48 dp hit area already holds the vertical gap.
 */
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> Int, onPick: (T) -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(TargetStep.INLINE_GAP)) {
        for (option in options) OptionChip(label(option), option == selected) { onPick(option) }
    }
}

/** A switch with its [title] and, if given, a [subtitle], the whole row toggling it. */
@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes subtitle: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalScanPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = TargetStep.ACT.drawn).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = c.tx)
            subtitle?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = c.mu) }
        }
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = c.acf, uncheckedBorderColor = c.bd))
    }
}

/** A choice chip (T3). */
@Composable
private fun OptionChip(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalScanPalette.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .inlineTarget()
            .clip(shape)
            .then(if (selected) Modifier.background(c.acSoft).border(1.5.dp, c.ac, shape) else Modifier.border(1.dp, c.bd, shape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = if (selected) c.ac else c.tx)
    }
}
