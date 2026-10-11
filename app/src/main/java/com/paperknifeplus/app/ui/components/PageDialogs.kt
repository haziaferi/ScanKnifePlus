package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.ui.theme.PaperPink

/** Desaturates a page image, for previews of the Grayscale tool. */
val GrayscaleColorFilter: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** "Go to Page" dialog for a document of [pageCount] pages, starting with [initialInput]; [onJump] receives a valid 0-based page index. */
@Composable
fun JumpToPageDialog(initialInput: String, pageCount: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(initialInput) }
    val go = {
        val pageNum = input.toIntOrNull()
        if (pageNum != null && pageNum in 1..pageCount) onJump(pageNum - 1)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Page", fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.all { char -> char.isDigit() }) input = it },
                label = { Text("Page Number (1-$pageCount)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() })
            )
        },
        confirmButton = {
            TextButton(onClick = go) { Text("GO", fontWeight = FontWeight.Black, color = PaperPink) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.Gray) }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}
