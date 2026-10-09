// Part of ScanKnife+'s port of OpenScan (Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause). See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner

/**
 * Helpers that reproduce Dart semantics the original OpenScan code relies on, so ported arithmetic gives bit-identical results.
 */

/** Dart's `double.round()`: nearest integer, ties away from zero. ([Math.round] sends negative ties towards positive infinity.) */
internal fun dartRound(x: Double): Int = if (x < 0) -Math.round(-x).toInt() else Math.round(x).toInt()

/** Reads a pixel byte as Dart's `Uint8List` would: 0..255, never negative. */
internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

/** Writes [v] as Dart's `Uint8List` would (keeps the low 8 bits). */
internal fun ByteArray.setU8(i: Int, v: Int) {
    this[i] = v.toByte()
}
