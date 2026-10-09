// Part of ScanKnife+'s port of OpenScan (Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause). See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

/** A decoded image as a flat RGBA buffer (stride 4, row-major, no padding). Decoding and encoding happen outside scanner-core. */
class RgbaImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "Invalid size ${width}x$height" }
        require(pixels.size == width * height * 4) { "Expected ${width * height * 4} bytes, got ${pixels.size}" }
    }
}
