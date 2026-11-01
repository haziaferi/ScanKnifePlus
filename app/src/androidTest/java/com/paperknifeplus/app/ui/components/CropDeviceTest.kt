package com.paperknifeplus.app.ui.components

import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.ThemeMode
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Crop pages on the phone, through the screen: a real finger drag on the frame's top-left corner, then Crop and save PDF, on a file whose
 * first page is turned by /Rotate and whose crop boxes sit away from the media box's origin. PDFBox then reads each page's new crop box,
 * which must be where the frame was drawn. The screen is read through the accessibility tree, as TalkBack reads it.
 */
@RunWith(AndroidJUnit4::class)
class CropDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "crop-device-test")
    private val output = File(dir, "out.pdf")

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        PDFBoxResourceLoader.init(context)
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A save picker the user picks [output] in. */
    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Uri.fromFile(output))
            }
        }
    }

    /** Two 500 x 700 pages whose crop box is [BOX]: the first turned 90 degrees by /Rotate, the second upright. */
    private fun source(): File = File(dir, "source.pdf").also { file ->
        PDDocument().use { doc ->
            for (rotation in listOf(90, 0)) {
                val page = PDPage(PDRectangle(0f, 0f, 500f, 700f))
                page.cropBox = BOX
                page.rotation = rotation
                page.setContents(PDStream(doc, "0 0 0 rg 100 300 200 80 re f".byteInputStream()))
                doc.addPage(page)
            }
            doc.save(file)
        }
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 60_000, value: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what; the screen shows ${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }

    /** Every node of the active window, walked from its root: Compose answers no text search, so the tree is read as TalkBack reads it. */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return found
    }

    /** The node of the active window whose text or content description is [label]. */
    private fun node(label: String): AccessibilityNodeInfo? =
        nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }

    private fun centre(label: String): Pair<Float, Float> {
        val bounds = Rect().also { waitFor(label) { node(label) }.getBoundsInScreen(it) }
        return bounds.exactCenterX() to bounds.exactCenterY()
    }

    private fun value(label: String): Float = waitFor(label) { node(label) }.rangeInfo.current

    private fun touch(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    /** A finger pressed at [x], [y], moved by [dx], [dy] in small steps, then lifted. */
    private fun drag(x: Float, y: Float, dx: Float, dy: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        val steps = 20
        for (i in 1..steps) {
            Thread.sleep(16)
            touch(MotionEvent.ACTION_MOVE, down, x + dx * i / steps, y + dy * i / steps)
        }
        touch(MotionEvent.ACTION_UP, down, x + dx, y + dy)
    }

    /** A finger tapping the middle of the node shown as [label]. */
    private fun tap(label: String) {
        val (x, y) = centre(label)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        touch(MotionEvent.ACTION_UP, down, x, y)
    }

    private fun PDRectangle.edges() = listOf(lowerLeftX, lowerLeftY, upperRightX, upperRightY)

    private fun assertBox(expected: List<Float>, actual: PDRectangle) {
        for ((e, a) in expected.zip(actual.edges())) assertEquals(actual.edges().toString(), e, a, 0.05f)
    }

    @Test
    fun aDraggedFrameCropsARotatedPageAndAnOffsetCropBoxWhereItWasDrawn() {
        val input = Uri.fromFile(source())
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity {
                it.setContent {
                    PaperKnifePlusTheme(themeMode = ThemeMode.LIGHT) {
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                            CropView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
                        }
                    }
                }
            }
            waitFor("the screen") { node(CROP) }
            // The frame starts as the whole page: its edges span the shown page, the rotated first one, 560 x 400 as displayed.
            val (left, middle) = centre("Left edge")
            val right = centre("Right edge").first
            val (centreX, top) = centre("Top edge")
            val bottom = centre("Bottom edge").second
            val width = right - left
            val height = bottom - top
            assertEquals(560f / 400f, width / height, 0.02f)
            // TalkBack reaches the edges, each an adjustable value; the corner handles are for fingers only.
            assertEquals(1f, waitFor("Left edge") { node("Left edge") }.rangeInfo.max, 0f)
            assertEquals(false, waitFor("Top-left corner") { node("Top-left corner") }.isVisibleToUser)

            drag(left, top, width * 0.25f, height * 0.2f)
            // The accessibility tree follows the screen a little later: read the frame once two reads agree.
            fun read() = CropArea.Fractions(value("Left edge"), value("Top edge"), value("Right edge"), value("Bottom edge"))
            val frame = waitFor("the frame to settle") {
                val first = read()
                Thread.sleep(300)
                first.takeIf { it.left > 0f && it == read() }
            }
            // The corner followed the finger: the left edge is drawn where the finger stopped.
            assertEquals(0.25f, frame.left, 0.01f)
            assertEquals(0.2f, frame.top, 0.01f)
            assertEquals(left + width * 0.25f, centre("Left edge").first, 2f)
            assertEquals(1f, frame.right, 0f)
            assertEquals(1f, frame.bottom, 0f)
            assertEquals(middle + height * 0.1f, centre("Left edge").second, 2f)
            assertEquals(centreX + width * 0.125f, centre("Top edge").first, 2f)

            tap(CROP)
            waitFor("the result") { node("Crop complete") }

            PDDocument.load(output).use { doc ->
                // Rotated 90: the displayed left edge is the crop box's bottom (y up 560 across), its displayed top the crop box's left.
                val l = BOX.lowerLeftX
                val b = BOX.lowerLeftY
                assertBox(listOf(l + frame.top * 400, b + frame.left * 560, l + frame.bottom * 400, b + frame.right * 560), doc.getPage(0).cropBox)
                // Upright: 400 x 560, y up from the crop box's bottom.
                val upright = listOf(l + frame.left * 400, b + (1 - frame.bottom) * 560, l + frame.right * 400, b + (1 - frame.top) * 560)
                assertBox(upright, doc.getPage(1).cropBox)
                for (page in doc.pages) assertBox(listOf(0f, 0f, 500f, 700f), page.mediaBox)
            }
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val CROP = "Crop and save PDF"

        /** The source pages' crop box: 400 x 560 points, its lower-left corner at (40, 60) on the 500 x 700 media box. */
        val BOX = PDRectangle(40f, 60f, 400f, 560f)
    }
}
