package com.paperknifeplus.app.ui.components

import java.io.BufferedOutputStream
import java.io.OutputStream
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A baseline JPEG encoder for one-channel images, which the platform cannot write: Bitmap.compress always stores three YCbCr channels. It uses
 * the IJG luminance quantisation table scaled by quality and the standard luminance Huffman tables (JPEG spec, annex K). Rows are pulled eight
 * at a time, so memory stays at a few rows whatever the image size.
 */
internal object GrayJpegEncoder {
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    )
    private val LUMINANCE_QUANT = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99
    )
    private val DC_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_VALUES = IntArray(12) { it }
    private val AC_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
    private val AC_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
        0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
        0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
        0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa
    )

    /** COS[u * 8 + x] = C(u) / 2 * cos((2x + 1) u pi / 16), the 1-D DCT basis. */
    private val COS = FloatArray(64) { i ->
        val u = i / 8
        val x = i % 8
        ((if (u == 0) 1 / sqrt(2.0) else 1.0) / 2 * cos((2 * x + 1) * u * Math.PI / 16)).toFloat()
    }

    /**
     * Writes a [width] x [height] gray JPEG at [quality] (1-100) to [out], which stays open. [readRow] fills a row of 8-bit samples for row
     * y, top to bottom. Blocking.
     */
    fun encode(width: Int, height: Int, quality: Int, out: OutputStream, readRow: (y: Int, row: ByteArray) -> Unit) {
        require(width in 1..65535 && height in 1..65535) { "Unsupported JPEG size $width x $height" }
        val scale = quality.coerceIn(1, 100).let { if (it < 50) 5000 / it else 200 - 2 * it }
        val quant = IntArray(64) { ((LUMINANCE_QUANT[it] * scale + 50) / 100).coerceIn(1, 255) }
        val dc = HuffmanTable(DC_BITS, DC_VALUES)
        val ac = HuffmanTable(AC_BITS, AC_VALUES)

        val buffered = BufferedOutputStream(out, 64 * 1024)
        writeHeaders(buffered, width, height, quant)
        val bits = BitWriter(buffered)
        val strip = Array(8) { ByteArray(width) }
        val block = FloatArray(64)
        val rowPass = FloatArray(64)
        var previousDc = 0
        for (top in 0 until height step 8) {
            // Rows past the bottom repeat the last row, and columns past the right edge the last column, as libjpeg pads.
            for (r in 0 until 8) {
                if (top + r < height) readRow(top + r, strip[r]) else strip[r] = strip[r - 1].copyOf()
            }
            for (left in 0 until width step 8) {
                for (y in 0 until 8) for (x in 0 until 8) {
                    block[y * 8 + x] = (strip[y][minOf(left + x, width - 1)].toInt() and 0xFF) - 128f
                }
                forwardDct(block, rowPass)
                val coefficients = IntArray(64) { (block[ZIGZAG[it]] / quant[ZIGZAG[it]]).roundToInt() }
                previousDc = encodeBlock(bits, coefficients, previousDc, dc, ac)
            }
        }
        bits.flush()
        buffered.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        buffered.flush()
    }

    private fun writeHeaders(out: OutputStream, width: Int, height: Int, quant: IntArray) {
        fun marker(code: Int, payload: ByteArray) {
            out.write(0xFF)
            out.write(code)
            out.write((payload.size + 2) shr 8)
            out.write((payload.size + 2) and 0xFF)
            out.write(payload)
        }
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        fun huffman(tableClass: Int, bitsCounts: IntArray, values: IntArray) =
            bytes(tableClass shl 4, *bitsCounts) + bytes(*values)

        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        marker(0xDB, bytes(0) + ByteArray(64) { quant[ZIGZAG[it]].toByte() })
        marker(0xC0, bytes(8, height shr 8, height and 0xFF, width shr 8, width and 0xFF, 1, 1, 0x11, 0))
        marker(0xC4, huffman(0, DC_BITS, DC_VALUES))
        marker(0xC4, huffman(1, AC_BITS, AC_VALUES))
        marker(0xDA, bytes(1, 1, 0x00, 0, 63, 0))
    }

    /** In-place 2-D DCT of [block] (row-major, level-shifted samples) into coefficients in natural order; [tmp] is scratch space. */
    private fun forwardDct(block: FloatArray, tmp: FloatArray) {
        for (y in 0 until 8) for (u in 0 until 8) {
            var sum = 0f
            for (x in 0 until 8) sum += COS[u * 8 + x] * block[y * 8 + x]
            tmp[y * 8 + u] = sum
        }
        for (v in 0 until 8) for (u in 0 until 8) {
            var sum = 0f
            for (y in 0 until 8) sum += COS[v * 8 + y] * tmp[y * 8 + u]
            block[v * 8 + u] = sum
        }
    }

    /** Huffman-codes one block of zigzag-ordered [coefficients]; returns its DC value, which the next block is coded against. */
    private fun encodeBlock(bits: BitWriter, coefficients: IntArray, previousDc: Int, dc: HuffmanTable, ac: HuffmanTable): Int {
        val diff = coefficients[0] - previousDc
        val dcSize = magnitudeBits(diff)
        bits.write(dc.codes[dcSize], dc.sizes[dcSize])
        if (dcSize > 0) bits.write(amplitude(diff, dcSize), dcSize)

        var run = 0
        for (k in 1 until 64) {
            val value = coefficients[k]
            if (value == 0) {
                run++
                continue
            }
            while (run > 15) {
                bits.write(ac.codes[0xF0], ac.sizes[0xF0])
                run -= 16
            }
            val size = magnitudeBits(value)
            val symbol = (run shl 4) or size
            bits.write(ac.codes[symbol], ac.sizes[symbol])
            bits.write(amplitude(value, size), size)
            run = 0
        }
        if (run > 0) bits.write(ac.codes[0x00], ac.sizes[0x00])
        return coefficients[0]
    }

    private fun magnitudeBits(value: Int): Int = 32 - Integer.numberOfLeadingZeros(if (value < 0) -value else value)

    // Negative values are sent as their ones' complement in [size] bits (JPEG spec F.1.2.1).
    private fun amplitude(value: Int, size: Int): Int = if (value < 0) value + (1 shl size) - 1 else value

    private class HuffmanTable(bitsCounts: IntArray, values: IntArray) {
        val codes = IntArray(256)
        val sizes = IntArray(256)

        init {
            var code = 0
            var k = 0
            for (length in 1..16) {
                repeat(bitsCounts[length - 1]) {
                    codes[values[k]] = code
                    sizes[values[k]] = length
                    code++
                    k++
                }
                code = code shl 1
            }
        }
    }

    /** Packs bits most-significant first, stuffing a zero byte after every 0xFF as entropy-coded data requires. */
    private class BitWriter(private val out: OutputStream) {
        private var buffer = 0
        private var count = 0

        fun write(value: Int, size: Int) {
            for (i in size - 1 downTo 0) {
                buffer = (buffer shl 1) or ((value shr i) and 1)
                if (++count == 8) emit()
            }
        }

        /** Pads the last byte with one bits. */
        fun flush() {
            while (count != 0) write(1, 1)
        }

        private fun emit() {
            out.write(buffer)
            if (buffer == 0xFF) out.write(0)
            buffer = 0
            count = 0
        }
    }
}
