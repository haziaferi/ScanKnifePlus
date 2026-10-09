// Part of ScanKnife+'s port of OpenScan (Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause). See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.live

/** Monotonic time in microseconds. Injected so the time-based live-scan logic can be driven by a fake clock in tests. */
fun interface MicrosClock {
    fun nowMicros(): Long

    companion object {
        val SYSTEM = MicrosClock { System.nanoTime() / 1_000 }
    }
}
