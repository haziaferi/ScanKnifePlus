package com.haziaferi.scanknifeplus.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dart's List.sort is unstable; with ties, [dartSort] must put elements in exactly the order Dart does (fixture: parity/contours.tsv). */
class DartSortParityTest {
    @Test
    fun `tie order matches Dart`() {
        val cases = ParityFixture.load("contours.tsv").filter { it.name.startsWith("sort_") }
        assertEquals(6, cases.size)
        for (c in cases) {
            val keys = c.raw("keys").split(',').map { it.toInt() }
            val ids = MutableList(keys.size) { it }
            ids.dartSort { a, b -> keys[a].compareTo(keys[b]) }
            assertEquals("$c", c.raw("order"), ids.joinToString(","))
        }
    }
}
