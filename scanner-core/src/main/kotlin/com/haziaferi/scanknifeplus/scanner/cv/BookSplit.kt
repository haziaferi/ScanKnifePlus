// Part of ScanKnife+'s port of OpenScan (Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause). See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

/** One of the two facing pages of an open book, as the reader sees them. */
enum class BookPage { LEFT, RIGHT }

/**
 * Book mode, which OpenScan does not have: an open book photographed as one spread is cut into its two facing pages along the line joining
 * the points a given fraction of the way along the spread's top and bottom edges.
 */
object BookSplit {
    /** Where the split line starts out: the middle of the spread. */
    const val DEFAULT_SPLIT = 0.5

    /**
     * The quad of [page] in [spread], split [split] of the way (0 to 1, from the book's left) along its top and bottom edges. The book reads
     * upright once its image is turned clockwise by [quarterTurns]. The result is in [spread]'s coordinates and in the image's own corner order,
     * so warping it and turning the warp by [quarterTurns] gives the upright page. Throws IllegalArgumentException for a [split] outside 0 to 1.
     */
    fun page(spread: Quad, page: BookPage, split: Double = DEFAULT_SPLIT, quarterTurns: Int = 0): Quad {
        require(split in 0.0..1.0) { "Split outside the spread: $split" }
        val turns = quarterTurns.mod(4)
        val corners = spread.points
        // The corners as the reader sees them: turning the image clockwise once brings its bottom-left corner to the top-left.
        val book = List(4) { corners[(it - turns).mod(4)] }
        val top = lerp(book[0], book[1], split)
        val bottom = lerp(book[3], book[2], split)
        val half = when (page) {
            BookPage.LEFT -> listOf(book[0], top, bottom, book[3])
            BookPage.RIGHT -> listOf(top, book[1], book[2], bottom)
        }
        val image = List(4) { half[(it + turns).mod(4)] }
        return Quad(image[0], image[1], image[2], image[3])
    }

    /**
     * [page] of [spread] (in [decoded]'s pixel coordinates) warped as [PerspectiveCrop.warpToPage] warps a page, capped at [maxEdge], and turned
     * upright by [quarterTurns]; see [page]. Returns null if the warp fails or [split] is outside 0 to 1. Blocking.
     */
    fun cropPage(decoded: RgbaImage, spread: Quad, page: BookPage, split: Double, quarterTurns: Int, maxEdge: Int?): RgbaImage? {
        if (split !in 0.0..1.0) return null
        val warped = PerspectiveCrop.warpToPage(decoded, page(spread, page, split, quarterTurns), maxEdge) ?: return null
        return if (quarterTurns.mod(4) != 0) PerspectiveCrop.rotateQuarterTurns(warped, quarterTurns) else warped
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
}
