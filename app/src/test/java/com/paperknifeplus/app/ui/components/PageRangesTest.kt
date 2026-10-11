package com.paperknifeplus.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PageRangesTest {
    @Test
    fun `single pages and ranges become 0-based indices`() {
        assertEquals(setOf(1), PageRanges.parse("2", 10))
        assertEquals(setOf(1, 3, 4, 5, 8), PageRanges.parse("2, 4-6, 9", 10))
    }

    @Test
    fun `a reversed range selects the same pages as the forward one`() {
        assertEquals(setOf(1, 2, 3, 4), PageRanges.parse("5-2", 10))
    }

    @Test
    fun `ranges are clamped and out-of-bounds single pages ignored`() {
        assertEquals(setOf(7, 8, 9), PageRanges.parse("8-20", 10))
        assertEquals(setOf(0, 1), PageRanges.parse("0-2", 10))
        assertEquals(emptySet<Int>(), PageRanges.parse("0, 11", 10))
    }

    @Test
    fun `an empty document selects nothing`() {
        assertEquals(emptySet<Int>(), PageRanges.parse("1-3", 0))
    }

    @Test
    fun `a malformed part is skipped without losing the parts after it`() {
        assertEquals(setOf(0, 1, 2, 8), PageRanges.parse("1-3, 7-, 9", 10))
        assertEquals(setOf(0, 5), PageRanges.parse("a-3, 1, x, -4, 6, 2-b, ,", 10))
    }

    @Test
    fun `format writes the shortest range text and round-trips`() {
        assertEquals("", PageRanges.format(emptySet()))
        assertEquals("1", PageRanges.format(setOf(0)))
        assertEquals("1-3, 5, 7-8", PageRanges.format(setOf(6, 0, 1, 2, 4, 7)))
        for (pages in listOf(setOf(0), setOf(0, 1, 2, 4), setOf(9), (0 until 10).toSet(), setOf(1, 3, 5, 6))) {
            assertEquals(pages, PageRanges.parse(PageRanges.format(pages), 10))
        }
    }
}
