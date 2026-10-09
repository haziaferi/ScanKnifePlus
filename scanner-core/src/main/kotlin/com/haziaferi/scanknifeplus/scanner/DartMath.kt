// Part of ScanKnife+'s port of OpenScan (Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause). See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner

/**
 * Helpers that reproduce Dart semantics the original OpenScan code relies on, so ported arithmetic gives bit-identical results.
 */

/**
 * Dart's `double.round()`: nearest integer, ties away from zero ([Math.round] sends negative ties towards positive infinity), and like Dart it
 * throws on NaN or infinity instead of quietly returning 0.
 */
internal fun dartRound(x: Double): Int {
    if (x.isNaN() || x.isInfinite()) throw UnsupportedOperationException("Infinity or NaN toInt")
    return if (x < 0) -Math.round(-x).toInt() else Math.round(x).toInt()
}

/** Dart's `double.floor()` to int, which also throws on NaN or infinity. */
internal fun dartFloor(x: Double): Int {
    if (x.isNaN() || x.isInfinite()) throw UnsupportedOperationException("Infinity or NaN toInt")
    return kotlin.math.floor(x).toInt()
}

/** Dart's `double.toInt()` (truncation towards zero), which also throws on NaN or infinity. */
internal fun dartToInt(x: Double): Int {
    if (x.isNaN() || x.isInfinite()) throw UnsupportedOperationException("Infinity or NaN toInt")
    return x.toInt()
}

/** Reads a pixel byte as Dart's `Uint8List` would: 0..255, never negative. */
internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

/** Writes [v] as Dart's `Uint8List` would (keeps the low 8 bits). */
internal fun ByteArray.setU8(i: Int, v: Int) {
    this[i] = v.toByte()
}

/**
 * Dart's `double.clamp`, which compares with `compareTo`: NaN counts as the largest value (so it clamps to [upper]) and -0.0 sorts below 0.0.
 * Kotlin's [coerceIn] would pass NaN through unchanged.
 */
internal fun Double.dartClamp(lower: Double, upper: Double): Double {
    require(lower.compareTo(upper) <= 0) { "Invalid clamp range $lower..$upper" }
    if (lower.isNaN()) return lower
    if (this.compareTo(lower) < 0) return lower
    if (this.compareTo(upper) > 0) return upper
    return this
}
