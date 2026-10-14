package com.paperknifeplus.app.ui.components

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** Crop sets the crop box where the user sees the area, whatever the page's rotation and existing crop box. */
@RunWith(RobolectricTestRunner::class)
class PageCropTest {
    private fun PDRectangle.edges() = listOf(lowerLeftX, lowerLeftY, upperRightX, upperRightY)

    private fun assertBox(expected: List<Float>, actual: PDRectangle?) = assertEquals(expected, actual?.edges())

    // A 600 x 800 crop box offset to (100, 50) on a larger media box, so an offset that is lost shows.
    private fun box(rotation: Int) = PageBox(100f, 50f, 600f, 800f, rotation)

    @Test
    fun `an unrotated page is cut from its displayed top-left, offset by the crop box`() {
        // Left 10% and top 25% off: x from 100 + 60, y from the top edge 850 down by 200.
        assertBox(listOf(160f, 50f, 700f, 650f), cropRectangle(box(0), CropArea.Fractions(0.1f, 0.25f, 1f, 1f)))
    }

    @Test
    fun `a rotated page is cut where it is displayed`() {
        val area = CropArea.Fractions(0.1f, 0.25f, 1f, 1f)
        // Rotated 90: displayed 800 wide and 600 high; the displayed left is the user-space bottom, the displayed top the user-space left.
        assertBox(listOf(250f, 130f, 700f, 850f), cropRectangle(box(90), area))
        // Rotated 180: the displayed left is the user-space right, the displayed top the user-space bottom.
        assertBox(listOf(100f, 250f, 640f, 850f), cropRectangle(box(180), area))
        // Rotated 270: the displayed left is the user-space top, the displayed top the user-space right.
        assertBox(listOf(100f, 50f, 550f, 770f), cropRectangle(box(270), area))
    }

    @Test
    fun `a points area is cut to the page and a missed page is left alone`() {
        assertBox(listOf(100f, 50f, 700f, 830f), cropRectangle(box(0), CropArea.Points(-5f, 20f, 9000f, 9000f)))
        assertNull(cropRectangle(box(0), CropArea.Points(700f, 0f, 800f, 100f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty fractions area is refused`() {
        CropArea.Fractions(0.5f, 0f, 0.5f, 1f)
    }

    @Test
    fun `cropPages sets the crop box of the chosen pages only and keeps the media box`() {
        val bytes = PDDocument().use { doc ->
            repeat(3) { i -> doc.addPage(PDPage(PDRectangle(0f, 0f, 600f, 800f)).apply { rotation = if (i == 1) 90 else 0 }) }
            assertEquals(2, cropPages(doc, CropArea.Fractions(0f, 0f, 0.5f, 0.5f), listOf(1, 2, 7, -1)))
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }
        PDDocument.load(bytes).use { doc ->
            assertBox(listOf(0f, 0f, 600f, 800f), doc.getPage(0).cropBox)
            // Rotated 90, displayed 800 x 600: its displayed top-left quarter is user-space x 0..300, y 0..400.
            assertBox(listOf(0f, 0f, 300f, 400f), doc.getPage(1).cropBox)
            assertBox(listOf(0f, 400f, 300f, 800f), doc.getPage(2).cropBox)
            for (i in 0 until 3) assertBox(listOf(0f, 0f, 600f, 800f), doc.getPage(i).mediaBox)
        }
    }

    @Test
    fun `cropping twice cuts inside the first crop`() {
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(0f, 0f, 600f, 800f)))
            cropPages(doc, CropArea.Fractions(0.5f, 0.5f, 1f, 1f))
            cropPages(doc, CropArea.Fractions(0.5f, 0.5f, 1f, 1f))
            assertBox(listOf(450f, 0f, 600f, 200f), doc.getPage(0).cropBox)
        }
    }
}
