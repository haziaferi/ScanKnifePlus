package com.paperknifeplus.app.ui.components

/** The page-range text the Split and Delete screens accept, e.g. "1-3, 5, 9-7": 1-based pages in, 0-based page indices out. */
object PageRanges {
    /**
     * Parses [input] for a document of [max] pages. Each comma-separated part is read on its own, so a malformed part is skipped without
     * losing the others; ranges are clamped to 1..[max] and may run backwards, single pages outside it are ignored.
     */
    fun parse(input: String, max: Int): Set<Int> {
        if (max < 1) return emptySet()
        val pages = mutableSetOf<Int>()
        for (part in input.split(',')) {
            val bounds = part.split('-', limit = 2)
            if (bounds.size == 2) {
                val start = bounds[0].trim().toIntOrNull()?.coerceIn(1, max) ?: continue
                val end = bounds[1].trim().toIntOrNull()?.coerceIn(1, max) ?: continue
                for (page in minOf(start, end)..maxOf(start, end)) pages.add(page - 1)
            } else {
                val page = part.trim().toIntOrNull() ?: continue
                if (page in 1..max) pages.add(page - 1)
            }
        }
        return pages
    }

    /** Writes 0-based [pages] back as the shortest range text, e.g. {0, 1, 2, 4} as "1-3, 5". */
    fun format(pages: Set<Int>): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.sorted()
        val parts = mutableListOf<String>()
        fun addRun(start: Int, end: Int) {
            parts += if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"
        }
        var start = sorted[0]
        var prev = start
        for (page in sorted.drop(1)) {
            if (page != prev + 1) {
                addRun(start, prev)
                start = page
            }
            prev = page
        }
        addRun(start, prev)
        return parts.joinToString(", ")
    }
}
