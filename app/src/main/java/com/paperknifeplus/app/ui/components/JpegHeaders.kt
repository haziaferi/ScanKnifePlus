package com.paperknifeplus.app.ui.components

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val SOI = 0xD8
private const val SOS = 0xDA
private const val APP1 = 0xE1
private const val TAG_ORIENTATION = 0x0112
private const val TAG_GPS_IFD = 0x8825

// Each header segment is at most 64 KB; a header larger than this is not a photo worth passing through.
private const val MAX_HEADER_BYTES = 4 * 1024 * 1024

/** One marker segment of a JPEG header: [start] is its 0xFF, [end] is one past its last byte. */
private class Segment(val marker: Int, val start: Int, val end: Int) {
    val payloadStart get() = start + 4
}

/**
 * A JPEG's header, read from a stream up to the start of the compressed data and no further, so the image itself is never held in memory.
 * Image to PDF uses it to decide whether a file can be embedded as it is, and to copy it without location data.
 */
internal class JpegHeader private constructor(private val bytes: ByteArray, private val segments: List<Segment>) {
    private val frame = segments.firstOrNull { it.marker in 0xC0..0xCF && it.marker != 0xC4 && it.marker != 0xC8 && it.marker != 0xCC }
        ?.takeIf { it.end - it.payloadStart >= 6 }

    /** Baseline sequential (SOF0), which every PDF reader can decode. */
    val baseline: Boolean get() = frame?.marker == 0xC0
    val components: Int get() = frame?.let { u8(bytes, it.payloadStart + 5) } ?: 0
    val width: Int get() = frame?.let { u16(bytes, it.payloadStart + 3, bigEndian = true) } ?: 0
    val height: Int get() = frame?.let { u16(bytes, it.payloadStart + 1, bigEndian = true) } ?: 0

    /** The EXIF orientation, 1 (upright) when there is none. */
    val orientation: Int = segments.firstNotNullOfOrNull { exifTags(bytes, it)?.get(TAG_ORIENTATION) }?.takeIf { it in 1..8 } ?: 1

    /**
     * Writes the JPEG to [out]: this header without the segments that carry a location (an EXIF block with a GPS directory or too broken to
     * tell, and XMP or any other application segment naming GPS properties), then [rest], the stream it was read from, byte for byte.
     */
    fun copyWithoutGps(rest: InputStream, out: OutputStream) {
        out.write(bytes, 0, 2)
        for (segment in segments) if (!carriesGps(bytes, segment)) out.write(bytes, segment.start, segment.end - segment.start)
        rest.copyTo(out)
    }

    companion object {
        /** Reads the header from [input], leaving it at the compressed data; null if it is not a well-formed JPEG header. Blocking. */
        fun read(input: InputStream): JpegHeader? = try {
            readSegments(DataInputStream(input))
        } catch (e: IOException) {
            null
        }

        private fun readSegments(data: DataInputStream): JpegHeader {
            if (data.readUnsignedByte() != 0xFF || data.readUnsignedByte() != SOI) throw IOException("No start of image")
            val bytes = ByteArrayOutputStream().apply { write(0xFF); write(SOI) }
            val segments = ArrayList<Segment>()
            do {
                if (data.readUnsignedByte() != 0xFF) throw IOException("No marker")
                var marker = data.readUnsignedByte()
                while (marker == 0xFF) marker = data.readUnsignedByte() // fill bytes
                if (marker == 0x01 || marker in 0xD0..0xD7) continue
                val length = data.readUnsignedShort()
                if (length < 2 || bytes.size() + length > MAX_HEADER_BYTES) throw IOException("Bad segment length")
                val start = bytes.size()
                bytes.write(byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()))
                bytes.write(ByteArray(length - 2).also { data.readFully(it) })
                segments += Segment(marker, start, start + 2 + length)
            } while (marker != SOS)
            return JpegHeader(bytes.toByteArray(), segments)
        }
    }
}

private fun carriesGps(bytes: ByteArray, segment: Segment): Boolean {
    if (segment.marker !in 0xE0..0xEF) return false
    if (isExif(bytes, segment)) return exifTags(bytes, segment)?.containsKey(TAG_GPS_IFD) ?: true
    // XMP writes GPS properties as exif:GPSLatitude and the like.
    return String(bytes, segment.payloadStart, segment.end - segment.payloadStart, Charsets.ISO_8859_1).contains(":GPS")
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
