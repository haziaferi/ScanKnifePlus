// Part of ScanKnife+'s port of OpenScan (Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause). See NOTICE.md.
// Dart semantics that OpenScan's code relies on, reproduced so ported arithmetic gives bit-identical results.
package com.haziaferi.scanknifeplus.scanner

/**
 * Dart's `double.round()`: nearest integer, ties away from zero ([Math.round] sends negative ties towards positive infinity), and like Dart it
 * throws on NaN or infinity instead of quietly returning 0.
 */
internal fun dartRound(x: Double): Int {
    if (x.isNaN() || x.isInfinite()) throw UnsupportedOperationException("Infinity or NaN toInt")
    return saturateToInt(if (x < 0) -Math.round(-x) else Math.round(x))
}

/** Dart's `double.floor()` to int, which also throws on NaN or infinity. */
internal fun dartFloor(x: Double): Int {
    if (x.isNaN() || x.isInfinite()) throw UnsupportedOperationException("Infinity or NaN toInt")
    return saturateToInt(kotlin.math.floor(x).toLong())
}

/** Dart's `double.toInt()` (truncation towards zero), which also throws on NaN or infinity. */
internal fun dartToInt(x: Double): Int {
    if (x.isNaN() || x.isInfinite()) throw UnsupportedOperationException("Infinity or NaN toInt")
    return saturateToInt(x.toLong())
}

/**
 * Dart ints are 64-bit and saturate at the int64 limits, as do [Math.round] and [Double.toLong]. Narrowing with [Long.toInt] would keep only the
 * low 32 bits and could flip the sign, so saturate instead: any later clamp to a range inside Int then gives the same answer as Dart's.
 */
private fun saturateToInt(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

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
