package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfPlacementTest {
    private val a4 = PageBox(0f, 0f, 595f, 842f, 0)
    private val letter = PageBox(0f, 0f, 612f, 792f, 0)

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: PdfPoint, delta: Float = 1e-3f) {
        assertEquals("x", expectedX, actual.x, delta)
        assertEquals("y", expectedY, actual.y, delta)
    }

    @Test
    fun `an unrotated page at the origin maps exactly as before`() {
        for (box in listOf(a4, letter)) {
            for ((nx, ny) in listOf(0f to 0f, 0.25f to 0.1f, 0.5f to 0.5f, 0.93f to 0.97f, 1f to 1f)) {
                val point = box.toPdfPoint(nx, ny)
                assertEquals(nx * box.width, point.x, 0f)
                assertEquals(box.height - (ny * box.height), point.y, 0f)
            }
        }
    }

    @Test
    fun `a landscape page is displayed wider than tall`() {
        val landscape = PageBox(0f, 0f, 842f, 595f, 0)
        assertEquals(842f / 595f, landscape.displayAspect, 1e-6f)
        assertPoint(842f, 595f, landscape.toPdfPoint(1f, 0f))
    }

    @Test
    fun `quarter-turned pages swap their displayed size`() {
        for (rotation in listOf(90, 270)) {
            val box = a4.copy(rotation = rotation)
            assertEquals(842f, box.displayWidth)
            assertEquals(595f, box.displayHeight)
        }
        assertEquals(595f, a4.copy(rotation = 180).displayWidth)
    }

    @Test
    fun `displayed corners land on the user-space corners each rotation turns there`() {
        // Displayed top-left, top-right, bottom-right, bottom-left, as user-space corners of an A4 page turned clockwise by /Rotate.
        val expected = mapOf(
            0 to listOf(0f to 842f, 595f to 842f, 595f to 0f, 0f to 0f),
            90 to listOf(0f to 0f, 0f to 842f, 595f to 842f, 595f to 0f),
            180 to listOf(595f to 0f, 0f to 0f, 0f to 842f, 595f to 842f),
            270 to listOf(595f to 842f, 595f to 0f, 0f to 0f, 0f to 842f)
        )
        val displayed = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        for ((rotation, corners) in expected) {
            val box = a4.copy(rotation = rotation)
            displayed.zip(corners).forEach { (n, corner) ->
                assertPoint(corner.first, corner.second, box.toPdfPoint(n.first, n.second))
            }
        }
    }

    @Test
    fun `a cropBox away from the origin is offset by its lower-left corner`() {
        val cropped = PageBox(100f, 50f, 400f, 600f, 0)
        assertPoint(100f, 650f, cropped.toPdfPoint(0f, 0f))
        assertPoint(500f, 50f, cropped.toPdfPoint(1f, 1f))
        assertPoint(300f, 350f, cropped.toPdfPoint(0.5f, 0.5f))
        val turned = cropped.copy(rotation = 90)
        assertPoint(100f, 50f, turned.toPdfPoint(0f, 0f))
        assertPoint(500f, 650f, turned.toPdfPoint(1f, 1f))
    }

    @Test
    fun `pdfToDisplay undoes displayToPdf for every rotation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val box = PageBox(30f, 40f, 500f, 700f, rotation)
            for ((px, py) in listOf(0f to 0f, 12f to 34f, box.displayWidth to box.displayHeight, 100f to 5f)) {
                val pdf = box.displayToPdf(px, py)
                val back = box.pdfToDisplay(pdf.x, pdf.y)
                assertPoint(px, box.displayHeight - py, back)
            }
        }
    }

    @Test
    fun `rotations are normalised to quarter turns`() {
        assertEquals(listOf(0, 90, 180, 270, 0, 90, 270, 0), listOf(0, 90, 180, 270, 360, 450, -90, 45).map(::normalizeRotation))
    }

    @Test
    fun `PageBox reads the cropBox, clipped to the mediaBox, and the rotation`() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER).apply {
                cropBox = PDRectangle(50f, 60f, 300f, 400f)
                rotation = 270
            }
            doc.addPage(page)
            assertEquals(PageBox(50f, 60f, 300f, 400f, 270), PageBox.of(page))

            val overhanging = PDPage(PDRectangle(0f, 0f, 200f, 200f)).apply { cropBox = PDRectangle(100f, 100f, 300f, 300f) }
            assertEquals(PageBox(100f, 100f, 100f, 100f, 0), PageBox.of(overhanging))
            assertEquals(PageBox(0f, 0f, 612f, 792f, 0), PageBox.of(PDPage(PDRectangle.LETTER)))
        }
    }

    @Test
    fun `page numbers on an unrotated page start where they did`() {
        val placement = pageNumberPlacement("Bottom Right", textWidth = 40f, fontSize = 14f, box = letter)
        assertEquals(TextPlacement(612f - 40f - 30f, 30f, 0), placement)
        assertEquals(TextPlacement(30f, 792f - 30f - 14f, 0), pageNumberPlacement("Top Left", 40f, 14f, letter))
        assertEquals(TextPlacement((612f - 40f) / 2, 30f, 0), pageNumberPlacement("Bottom Center", 40f, 14f, letter))
    }

    @Test
    fun `page numbers go to the displayed corner of rotated and cropped pages`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val box = PageBox(20f, 30f, 500f, 700f, rotation)
            for (position in listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right")) {
                val placement = pageNumberPlacement(position, textWidth = 50f, fontSize = 10f, box = box)
                val shown = box.pdfToDisplay(placement.x, placement.y)
                val expectedX = if (position.endsWith("Left")) 30f else box.displayWidth - 50f - 30f
                val expectedTop = if (position.startsWith("Top")) 30f + 10f else box.displayHeight - 30f
                assertPoint(expectedX, expectedTop, shown)
                assertEquals(rotation, placement.rotation)
            }
        }
    }

    @Test
    fun `the text matrix turns text counter-clockwise by the page rotation`() {
        fun values(rotation: Int) = TextPlacement(1f, 2f, rotation).matrix().let { listOf(it.scaleX, it.shearY, it.shearX, it.scaleY, it.translateX, it.translateY) }
        assertEquals(listOf(1f, 0f, 0f, 1f, 1f, 2f), values(0))
        assertEquals(listOf(0f, 1f, -1f, 0f, 1f, 2f), values(90))
        assertEquals(listOf(-1f, 0f, 0f, -1f, 1f, 2f), values(180))
        assertEquals(listOf(0f, -1f, 1f, 0f, 1f, 2f), values(270))
    }
}

/** What drawStamp writes: the image's unit square is placed by the two cm operators it emits, rotation first. */
@RunWith(RobolectricTestRunner::class)
class DrawStampTest {
    @Before
    fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    /** The image's centre, its drawn width and height, and the angle (degrees counter-clockwise) of its x axis in user space. */
    private data class Drawn(val centreX: Float, val centreY: Float, val width: Float, val height: Float, val angle: Double)

    private fun stamp(page: PDPage, nx: Float, ny: Float, nw: Float, angle: Float): Drawn = PDDocument().use { doc ->
        doc.addPage(page)
        val image = LosslessFactory.createFromImage(doc, Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888))
        doc.appendTo(page).use { it.drawStamp(image, PageBox.of(page), nx, ny, nw, angle) }
        val tokens = PDFStreamParser(page).apply { parse() }.tokens
        val matrices = tokens.indices.filter { (tokens[it] as? Operator)?.name == "cm" }.map { i ->
            val v = (i - 6 until i).map { (tokens[it] as COSNumber).floatValue() }
            Matrix(v[0], v[1], v[2], v[3], v[4], v[5])
        }
        assertEquals("rotation, then image placement", 2, matrices.size)
        val m = matrices[1].multiply(matrices[0])
        fun apply(x: Float, y: Float) = (m.scaleX * x + m.shearX * y + m.translateX) to (m.shearY * x + m.scaleY * y + m.translateY)
        val centre = apply(0.5f, 0.5f)
        val origin = apply(0f, 0f)
        val xEnd = apply(1f, 0f)
        val yEnd = apply(0f, 1f)
        fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = Math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()).toFloat()
        Drawn(
            centre.first, centre.second, dist(origin, xEnd), dist(origin, yEnd),
            Math.toDegrees(Math.atan2((xEnd.second - origin.second).toDouble(), (xEnd.first - origin.first).toDouble()))
        )
    }

    @Test
    fun `on an unrotated page the stamp is centred where placed, sized by page width and turned clockwise`() {
        val drawn = stamp(PDPage(PDRectangle.LETTER), nx = 0.25f, ny = 0.3f, nw = 0.4f, angle = 15f)
        assertEquals(0.25f * 612f, drawn.centreX, 0.01f)
        assertEquals(792f - 0.3f * 792f, drawn.centreY, 0.01f)
        assertEquals(0.4f * 612f, drawn.width, 0.01f)
        assertEquals(0.4f * 612f / 2, drawn.height, 0.01f)
        assertEquals(-15.0, drawn.angle, 0.01)
    }

    @Test
    fun `on a rotated, cropped page the stamp uses the displayed width and turns with the page`() {
        val page = PDPage(PDRectangle.A4).apply {
            cropBox = PDRectangle(20f, 30f, 500f, 700f)
            rotation = 90
        }
        val drawn = stamp(page, nx = 0.25f, ny = 0.3f, nw = 0.4f, angle = 15f)
        val centre = PageBox.of(page).toPdfPoint(0.25f, 0.3f)
        assertEquals(centre.x, drawn.centreX, 0.01f)
        assertEquals(centre.y, drawn.centreY, 0.01f)
        assertEquals("displayed width is the cropBox height", 0.4f * 700f, drawn.width, 0.01f)
        assertEquals(0.4f * 700f / 2, drawn.height, 0.01f)
        assertEquals(90.0 - 15.0, drawn.angle, 0.01)
    }
}

/** Page Numbers end to end: the number reads upright in the picked corner of a rotated page, and the output goes into History. */
@RunWith(RobolectricTestRunner::class)
class PageNumbersViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** Answers every launch with [result], like a save dialog the user picked [result] in. */
    private fun pickerReturning(result: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, result)
            }
        }
    }

    @Test
    fun `numbers a rotated page in its displayed bottom-right corner and records the output`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.inheritedAttributes(2), "rotated.pdf")
        val output = File(context.cacheDir, "numbered.pdf").apply { delete() }
        SessionManager.clearHistory()
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(Uri.fromFile(output))) {
                PageNumbersView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("SAVE DOCUMENT")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("SAVE DOCUMENT").performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Numbering Complete")).fetchSemanticsNodes().isNotEmpty() }

        assertEquals("page-numbers", SessionManager.history.first().toolId)
        PDDocument.load(output).use { doc ->
            // The page is A4 turned 90 degrees clockwise: its displayed bottom-right is the user-space top-right, and upright text runs up.
            val first = textPositions(doc).first()
            assertTrue("starts near the right edge: ${first.textMatrix}", first.textMatrix.translateX > 595f - 30f - 20f)
            assertTrue("starts near the top: ${first.textMatrix}", first.textMatrix.translateY > 842f - 200f)
            assertEquals("turned a quarter", 0f, first.textMatrix.scaleX, 1e-3f)
            assertTrue("reads upward: ${first.textMatrix}", first.textMatrix.shearY > 0f)
        }
    }

    private fun textPositions(doc: PDDocument): List<TextPosition> {
        val positions = mutableListOf<TextPosition>()
        object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                // The stamped number is in Helvetica-Bold; the page's own text is plain Helvetica.
                if (text.unicode == "P" && text.font.name.contains("Bold")) positions.add(text)
            }
        }.apply { startPage = 1; endPage = 1 }.getText(doc)
        return positions
    }
}
