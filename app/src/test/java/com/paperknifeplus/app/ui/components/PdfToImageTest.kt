package com.paperknifeplus.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfToImageTest {
    @Test
    fun `a document opens with every page selected`() {
        assertEquals(setOf(0, 1, 2), initialSelection(3))
        assertEquals(emptySet<Int>(), initialSelection(0))
    }

    @Test
    fun `the default format is one of the chips`() {
        assertEquals("JPG", IMAGE_FORMATS.first())
        assertTrue(IMAGE_FORMATS.containsAll(listOf("JPG", "PNG", "WebP")))
    }
}
