package com.paperknifeplus.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomPanTest {
    private val size = Size(1000f, 2000f)

    @Test
    fun `no panning at scale 1`() {
        assertEquals(Offset.Zero, zoomPanClamp(Offset(300f, -400f), size, 1f))
    }

    @Test
    fun `pan is limited to half the extra size on each axis`() {
        assertEquals(Offset(500f, -1000f), zoomPanClamp(Offset(900f, -5000f), size, 2f))
        assertEquals(Offset(-1500f, 3000f), zoomPanClamp(Offset(-9999f, 9999f), size, 4f))
        assertEquals(Offset(100f, -200f), zoomPanClamp(Offset(100f, -200f), size, 2.5f))
    }

    @Test
    fun `a centre double tap zooms without panning`() {
        assertEquals(Offset.Zero, doubleTapZoomOffset(Offset(500f, 1000f), size, 2.5f))
    }

    @Test
    fun `a corner double tap pans the corner into view, exactly to the clamp limit`() {
        val offset = doubleTapZoomOffset(Offset(0f, 0f), size, 2.5f)
        assertEquals(Offset(750f, 1500f), offset)
        assertEquals(offset, zoomPanClamp(offset, size, 2.5f))
        assertEquals(Offset(-750f, -1500f), doubleTapZoomOffset(Offset(1000f, 2000f), size, 2.5f))
    }
}
