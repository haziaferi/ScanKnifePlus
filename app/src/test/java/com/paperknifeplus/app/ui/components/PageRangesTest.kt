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

    @Test
    fun `the lenient parse keeps a range with equal ends apart from a single page`() {
        assertEquals(setOf(2), PageRanges.parse("5-5", 3))
        assertEquals(emptySet<Int>(), PageRanges.parse("5", 3))
    }

    @Test
    fun `a range list keeps its ranges in order and runs an open range to the last page`() {
        assertEquals(RangeList.Valid(listOf(0..2, 4..4, 7..9)), PageRanges.parseList("1-3, 5, 8-", 10))
        assertEquals(RangeList.Valid(listOf(7..9, 0..1, 0..1)), PageRanges.parseList(" 8 - 10 ,1-2,, 1 -2, ", 10))
        assertEquals(RangeList.Valid(listOf(9..9)), PageRanges.parseList("10-", 10))
    }

    @Test
    fun `a range list naming no pages is empty`() {
        assertEquals(RangeList.Empty, PageRanges.parseList("", 10))
        assertEquals(RangeList.Empty, PageRanges.parseList(" , ,", 10))
    }

    @Test
    fun `a range list refuses the first part outside the document`() {
        assertEquals(RangeList.OutOfBounds("0-2", 0, 10), PageRanges.parseList("1, 0-2, 99", 10))
        assertEquals(RangeList.OutOfBounds("9-11", 11, 10), PageRanges.parseList("9-11", 10))
        assertEquals(RangeList.OutOfBounds("11", 11, 10), PageRanges.parseList("11", 10))
        assertEquals(RangeList.OutOfBounds("1", 1, 0), PageRanges.parseList("1", 0))
        assertEquals(RangeList.OutOfBounds("99999999999", Int.MAX_VALUE, 10), PageRanges.parseList("99999999999", 10))
    }

    @Test
    fun `a range list refuses a reversed range`() {
        assertEquals(RangeList.Reversed("5-2", 5, 2), PageRanges.parseList("1, 5-2", 10))
        assertEquals(RangeList.Valid(listOf(4..4)), PageRanges.parseList("5-5", 10))
    }

    @Test
    fun `a range list refuses what is not a page or a range`() {
        for (part in listOf("a", "-4", "+3", "1-2-3", "2-b", "1 2", "3–5")) {
            assertEquals(part, RangeList.Malformed(part), PageRanges.parseList("1, $part", 10))
        }
    }
}
