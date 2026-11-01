package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R

/** What is wrong with a page range the tools read with [PageRanges.parseList], in words; null for a valid one. */
@Composable
fun rangeError(result: RangeList): String? = when (result) {
    is RangeList.Valid -> null
    RangeList.Empty -> stringResource(R.string.range_empty)
    is RangeList.Malformed -> stringResource(R.string.range_malformed, result.part)
    is RangeList.OutOfBounds -> pluralStringResource(R.plurals.range_out_of_bounds, result.pageCount, result.part, result.page, result.pageCount)
    is RangeList.Reversed -> stringResource(R.string.range_reversed, result.part, result.end, result.start)
}

/**
 * The shared page-range field: [value] in a single-line box labelled "Pages" for TalkBack (the screen's section label shows it), then the
 * [error] with a warning icon, read out when it changes, or else the helper "For example 1-3, 5, 8-".
 */
@Composable
fun PageRangeField(value: String, onValueChange: (String) -> Unit, error: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.pages_label)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            isError = error != null,
            textStyle = TextStyle(fontSize = 16.sp),
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                errorContainerColor = colors.surface,
                unfocusedBorderColor = colors.outline,
                focusedBorderColor = colors.primary,
                cursorColor = colors.primary
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                contentDescription = label
                if (error != null) this.error(error)
            }
        )
        if (error != null) {
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.error, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Text(error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
            }
        } else {
            Text(stringResource(R.string.range_example), fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
    }
}
