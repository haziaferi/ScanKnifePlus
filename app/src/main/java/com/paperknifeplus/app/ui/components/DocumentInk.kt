package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One ink the user can put into a document, with the [name] a screen reader says for its swatch. */
class Ink(val name: String, val color: Color)

/**
 * The inks of the stamping tools (page numbers, signatures, watermarks). They are document content, not UI, so they stay the same in
 * every palette and theme; the screens' own colours come from the theme.
 */
object DocumentInk {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    /** The neutral grey of the watermark templates. */
    val TemplateGray = Color(0xFF888888)

    /** What a signature is drawn on: white, like the paper it goes onto. */
    val Paper = White

    val pageNumbers = listOf(
        Ink("Black", Black), Ink("White", White), Ink("Red", Color(0xFFFF0000)), Ink("Blue", Color(0xFF0000FF)),
        Ink("Indigo", Color(0xFF8B5CF6)), Ink("Emerald", Color(0xFF10B981))
    )

    val signature = listOf(
        Ink("Black", Black), Ink("Blue", Color(0xFF0000FF)), Ink("Dark red", Color(0xFFC00000)), Ink("Mid blue", Color(0xFF0070C0)),
        Ink("Rose", Color(0xFFF43F5E))
    )

    val watermark = listOf(
        Ink("Black", Black), Ink("Dark grey", Color(0xFF444444)), Ink("Grey", TemplateGray), Ink("White", White), Ink("Red", Color(0xFFFF0000)),
        Ink("Blue", Color(0xFF0000FF)), Ink("Green", Color(0xFF10B981)), Ink("Violet", Color(0xFF8B5CF6)), Ink("Amber", Color(0xFFF59E0B)),
        Ink("Pink", Color(0xFFEC4899))
    )
}

/**
 * One ink as a 40 dp swatch in a 48 dp touch area, so a row of them keeps 8 dp between swatches. The ring marks the chosen ink, and the
 * swatch is a radio button named after its ink, so the choice never rests on colour alone.
 */
@Composable
fun InkSwatch(ink: Ink, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = ink.name },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(if (selected) 3.dp else 1.dp, if (selected) colors.onSurface else colors.outline, CircleShape)
                .padding(5.dp)
                .background(ink.color, CircleShape)
        )
    }
}
