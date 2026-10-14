package com.paperknifeplus.app.ui.components

private const val MAX_NAME_BYTES = 255
private const val FALLBACK_STEM = "document"

// Characters Windows forbids in names; FAT32 and exFAT forbid the same set, so a name passing here survives SD cards and sync peers too.
private val FORBIDDEN = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

/**
 * The file name a tool suggests for its output: [stem] made safe on every filesystem the file may reach, plus [extension] (without the dot).
 * Letters and digits of any script stay; Windows-forbidden and control characters become `_`; trailing dots and spaces go; a reserved
 * device name (CON, NUL, COM1, ... also before a dot, as in `con.backup`) gets a `_`; and the whole name is cut to 255 UTF-8 bytes without
 * splitting a character, shortening the stem and keeping the extension.
 */
fun outputFileName(stem: String, extension: String): String {
    val suffix = ".$extension"
    var cleaned = buildString {
        stem.trim().forEach { append(if (it in FORBIDDEN || it.isISOControl()) '_' else it) }
    }.trimEnd('.', ' ').ifEmpty { FALLBACK_STEM }
    val head = cleaned.substringBefore('.')
    if (head.trimEnd(' ').uppercase() in RESERVED) cleaned = head + "_" + cleaned.substring(head.length)
    val budget = MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size
    return truncateUtf8(cleaned, budget).trimEnd('.', ' ').ifEmpty { FALLBACK_STEM } + suffix
}

/** The longest prefix of [text] that fits in [maxBytes] UTF-8 bytes, cut between code points so no character is split. */
private fun truncateUtf8(text: String, maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return text.substring(0, end)
}
