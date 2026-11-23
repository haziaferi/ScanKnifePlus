package com.paperknifeplus.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extra_bold, FontWeight.ExtraBold),
    Font(R.font.plus_jakarta_sans_extra_bold, FontWeight.Black) // Fallback to ExtraBold for Black
)

/** A style of the scale: Plus Jakarta Sans at [size] sp on lines [lineHeight] sp apart. */
private fun jakarta(weight: FontWeight, size: Int, lineHeight: Int, letterSpacing: Double = 0.5) = TextStyle(
    fontFamily = PlusJakartaSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp
)

/**
 * The app's type scale (rule 1 of 2026-11-21): every Material slot in Plus Jakarta Sans, so no Material component falls back to the system
 * font, and nothing under 12 sp. The sizes are those the screens drew, by role; the display slots are unused and keep Material's sizes.
 */
val Typography = Typography(
    displayLarge = jakarta(FontWeight.ExtraBold, 57, 64, -0.3),
    displayMedium = jakarta(FontWeight.ExtraBold, 45, 52, -0.3),
    displaySmall = jakarta(FontWeight.ExtraBold, 36, 44, -0.3),
    // A large figure: Links' counts.
    headlineLarge = jakarta(FontWeight.ExtraBold, 28, 36, -0.3),
    // The folder setup's welcome title.
    headlineMedium = jakarta(FontWeight.ExtraBold, 26, 34, -0.3),
    // Home's Scan a document, and every dialog's title (Material's default for it).
    headlineSmall = jakarta(FontWeight.ExtraBold, 24, 32, -0.3),
    // A screen's title in its top bar, and the heading of a screen's main card (the empty tool, Done, a locked file).
    titleLarge = jakarta(FontWeight.ExtraBold, 21, 28),
    // A section heading over a card, a card's heading at its size, and the label of a screen's one primary action (T1).
    titleMedium = jakarta(FontWeight.Bold, 17, 24),
    // The title of a row, an option or a card, and the label of a T2 button (a tool's second action, Import photos).
    titleSmall = jakarta(FontWeight.Bold, 15, 22),
    // Text with no style of its own (Material's default), and what is typed into a field.
    bodyLarge = jakarta(FontWeight.Medium, 16, 24),
    // Body text: notes, tips, empty states.
    bodyMedium = jakarta(FontWeight.Medium, 14, 20),
    // A row's second line, meta (size, date) and hints under a field.
    bodySmall = jakarta(FontWeight.Medium, 13, 18),
    // A T3 button's or text link's label, a counter, and Material's default for every button (a dialog's among them).
    labelLarge = jakarta(FontWeight.Bold, 14, 20),
    // A small label: a group's heading inside a card, a status line, a chip, a field's error.
    labelMedium = jakarta(FontWeight.Bold, 13, 18),
    // A caption or badge: a page's number, a tag, a small control's label.
    labelSmall = jakarta(FontWeight.ExtraBold, 12, 16)
)

/** Hints and banners over the live camera: body text a step heavier, so it holds up over the picture. */
val CameraText = jakarta(FontWeight.Bold, 14, 20)

/** Marks text that shows a document's own content, such as its extracted text or a page number as it will print: the scale does not hold it. */
val DocumentContent = SemanticsPropertyKey<Unit>("DocumentContent")

/** Marks this text as [DocumentContent]. */
fun Modifier.documentContent(): Modifier = semantics { this[DocumentContent] = Unit }

/** The text taken out of a PDF, shown as it is ([DocumentContent]), so in monospace. */
val DocumentText = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.5.sp
)

/**
 * The wordmark's letters ([com.paperknifeplus.app.ui.components.Wordmark]), which set their own weights. Its size is the logo's geometry,
 * given beside the mark's height; the line height is the one the logo was drawn with.
 */
val WordmarkText = TextStyle(fontFamily = PlusJakartaSans, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = (-0.015).em)
