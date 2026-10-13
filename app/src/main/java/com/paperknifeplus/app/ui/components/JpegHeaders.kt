package com.paperknifeplus.app.ui.components

import java.io.ByteArrayOutputStream

private const val SOI = 0xD8
private const val SOS = 0xDA
private const val APP1 = 0xE1
private const val TAG_ORIENTATION = 0x0112
private const val TAG_GPS_IFD = 0x8825

/** One marker segment of a JPEG header: [start] is its 0xFF, [end] is one past its last byte. */
private class Segment(val marker: Int, val start: Int, val end: Int) {
    val payloadStart get() = start + 4
}

/** What Image to PDF needs from a JPEG's headers, read from the bytes without decoding the image. */
internal class JpegInfo(
    /** Baseline sequential (SOF0), which every PDF reader can decode. */
    val baseline: Boolean,
    val components: Int,
    /** The EXIF orientation, 1 (upright) when there is none. */
    val orientation: Int
) {
    companion object {
        /** The headers of [bytes], or null if they are not a well-formed JPEG header. */
        fun read(bytes: ByteArray): JpegInfo? {
            val segments = segments(bytes) ?: return null
            val frame = segments.firstOrNull { it.marker in 0xC0..0xCF && it.marker != 0xC4 && it.marker != 0xC8 && it.marker != 0xCC } ?: return null
            if (frame.end - frame.payloadStart < 6) return null
            val orientation = segments.firstNotNullOfOrNull { exifTags(bytes, it)?.get(TAG_ORIENTATION) }
            return JpegInfo(frame.marker == 0xC0, bytes[frame.payloadStart + 5].toInt() and 0xFF, orientation?.takeIf { it in 1..8 } ?: 1)
        }
    }
}

/**
 * [jpeg] without the header segments that carry a location: an EXIF block with a GPS directory (or one too broken to tell), and XMP or
 * any other application segment naming GPS properties. Everything else, the image data included, is copied byte for byte.
 */
internal fun withoutGpsSegments(jpeg: ByteArray): ByteArray {
    val segments = segments(jpeg) ?: return jpeg
    val kept = segments.filterNot { carriesGps(jpeg, it) }
    if (kept.size == segments.size) return jpeg
    val out = ByteArrayOutputStream(jpeg.size)
    out.write(jpeg, 0, 2)
    kept.dropLast(1).forEach { out.write(jpeg, it.start, it.end - it.start) }
    val scan = segments.last()
    out.write(jpeg, scan.start, jpeg.size - scan.start)
    return out.toByteArray()
}

private fun carriesGps(bytes: ByteArray, segment: Segment): Boolean {
    if (segment.marker !in 0xE0..0xEF) return false
    if (isExif(bytes, segment)) return exifTags(bytes, segment)?.containsKey(TAG_GPS_IFD) ?: true
    // XMP writes GPS properties as exif:GPSLatitude and the like.
    return String(bytes, segment.payloadStart, segment.end - segment.payloadStart, Charsets.ISO_8859_1).contains(":GPS")
}

/** The header segments of [bytes] up to and including the start of scan (always last), or null if it is not a well-formed JPEG header. */
private fun segments(bytes: ByteArray): List<Segment>? {
    if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != SOI) return null
    val segments = ArrayList<Segment>()
    var pos = 2
    while (pos + 4 <= bytes.size) {
        if (u8(bytes, pos) != 0xFF) return null
        while (pos + 1 < bytes.size && u8(bytes, pos + 1) == 0xFF) pos++ // fill bytes
        val marker = u8(bytes, pos + 1)
        if (marker == 0x01 || marker in 0xD0..0xD7) {
            pos += 2
            continue
        }
        if (pos + 4 > bytes.size) return null
        val length = u16(bytes, pos + 2, bigEndian = true)
        if (length < 2 || pos + 2 + length > bytes.size) return null
        segments += Segment(marker, pos, pos + 2 + length)
        if (marker == SOS) return segments
        pos += 2 + length
    }
    return null
}

private fun isExif(bytes: ByteArray, segment: Segment): Boolean {
    val p = segment.payloadStart
    return segment.marker == APP1 && segment.end - p >= 6 && String(bytes, p, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000"
}

/** The first directory (IFD0) of an EXIF segment as tag to value (the first SHORT or LONG of each entry); null if it is no readable EXIF. */
private fun exifTags(bytes: ByteArray, segment: Segment): Map<Int, Int>? {
    if (!isExif(bytes, segment)) return null
    val tiff = segment.payloadStart + 6
    val end = segment.end
    if (end - tiff < 8) return null
    val bigEndian = when (String(bytes, tiff, 2, Charsets.ISO_8859_1)) {
        "MM" -> true
        "II" -> false
        else -> return null
    }
    if (u16(bytes, tiff + 2, bigEndian) != 42) return null
    val ifd = tiff + u32(bytes, tiff + 4, bigEndian)
    if (ifd < tiff || ifd + 2 > end) return null
    val count = u16(bytes, ifd, bigEndian)
    if (ifd + 2 + count * 12 > end) return null
    val tags = HashMap<Int, Int>()
    for (i in 0 until count) {
        val entry = ifd + 2 + i * 12
        val type = u16(bytes, entry + 2, bigEndian)
        tags[u16(bytes, entry, bigEndian)] = if (type == 4) u32(bytes, entry + 8, bigEndian) else u16(bytes, entry + 8, bigEndian)
    }
    return tags
}

private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xFF

private fun u16(bytes: ByteArray, at: Int, bigEndian: Boolean) =
    if (bigEndian) (u8(bytes, at) shl 8) or u8(bytes, at + 1) else (u8(bytes, at + 1) shl 8) or u8(bytes, at)

private fun u32(bytes: ByteArray, at: Int, bigEndian: Boolean) =
    if (bigEndian) (u16(bytes, at, true) shl 16) or u16(bytes, at + 2, true) else (u16(bytes, at + 2, false) shl 16) or u16(bytes, at, false)
