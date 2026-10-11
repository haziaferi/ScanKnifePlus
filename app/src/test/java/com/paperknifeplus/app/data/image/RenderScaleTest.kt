package com.paperknifeplus.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

class RenderScaleTest {
    private fun bytes(w: Float, h: Float, scale: Float) = floor(w * scale).toLong() * floor(h * scale).toLong() * 4

    @Test
    fun `an A4 page at 12x is scaled down to the budget`() {
        val scale = clampRenderScale(595f, 842f, 12f)
        assertTrue(scale < 12f)
        assertTrue(bytes(595f, 842f, scale) <= MAX_RENDER_BYTES)
        // As large as the budget allows: one pixel more per axis would not fit.
        assertTrue(bytes(595f, 842f, scale * 1.01f) > MAX_RENDER_BYTES)
    }

    @Test
    fun `an A4 page at 1_8x is unchanged`() {
        assertEquals(1.8f, clampRenderScale(595f, 842f, 1.8f))
    }

    @Test
    fun `an A0 page at 1_8x is clamped`() {
        assertTrue(bytes(2384f, 3370f, 1.8f) > MAX_RENDER_BYTES)
        val scale = clampRenderScale(2384f, 3370f, 1.8f)
        assertTrue(scale < 1.8f)
        assertTrue(bytes(2384f, 3370f, scale) <= MAX_RENDER_BYTES)
    }

    @Test
    fun `clamping keeps the aspect ratio`() {
        for ((w, h) in listOf(595f to 842f, 2384f to 3370f, 3370f to 2384f, 100f to 5000f)) {
            val scale = clampRenderScale(w, h, 20f)
            val ratio = floor(w * scale) / floor(h * scale)
            assertEquals(w / h, ratio, 0.01f * (w / h))
        }
    }

    @Test
    fun `a width cap lowers the scale for small cells only`() {
        assertEquals(540f / 595f, effectiveRenderScale(595f, 842f, 2f, maxWidthPx = 540), 1e-6f)
        assertEquals(2f, effectiveRenderScale(595f, 842f, 2f, maxWidthPx = 2000))
        assertEquals(2f, effectiveRenderScale(595f, 842f, 2f, maxWidthPx = null))
        // The cap and the budget combine: a huge page is held to the budget even when the cap allows more.
        val capped = effectiveRenderScale(14400f, 14400f, 2f, maxWidthPx = 30000)
        assertTrue(bytes(14400f, 14400f, capped) <= MAX_RENDER_BYTES)
        assertTrue(abs(capped - clampRenderScale(14400f, 14400f, 2f)) < 1e-6f)
    }
}
