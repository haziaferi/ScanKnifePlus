package com.haziaferi.scanknifeplus.ui.scan

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.radioButton
import com.paperknifeplus.app.ui.theme.CameraColors
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class ScannerScreenTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = ScannerActions(
        onClose = { calls += "close" },
        onToggleAuto = { calls += "auto" },
        onToggleTorch = { calls += "torch" },
        onMode = { calls += "mode $it" },
        onShutter = { calls += "shutter" },
        onReview = { calls += "review" },
        onAllowCamera = { calls += "allow" },
        onOpenSettings = { calls += "settings" },
        onRetryStore = { calls += "retry" },
        onDismissStore = { calls += "dismiss store" },
        onDismissShot = { calls += "dismiss shot" },
    )

    private fun show(ui: ScannerUi, fontScale: Float = 1f) {
        rule.setContent { Scaled(fontScale) { PaperKnifePlusTheme { ScannerScreen(ui, actions) {} } } }
    }

    @Test
    fun `single page has a shutter that reviews the shot and no page stack or Done`() {
        show(ScannerUi(shutterEnabled = true, liveDetection = true))
        rule.onNodeWithContentDescription("Take photo and review it").performClick()
        rule.onNodeWithText("Done").assertDoesNotExist()
        rule.onNodeWithContentDescription("Review 0 pages").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close scanner").performClick()
        assertEquals(listOf("shutter", "close"), calls)
    }

    @Test
    fun `multi page shows the page stack with its count and Done, both opening Review`() {
        show(ScannerUi(mode = CaptureMode.MULTI, count = 2, shutterEnabled = true, canChangeMode = false))
        rule.onNodeWithContentDescription("Take photo").performClick()
        rule.onNodeWithContentDescription("Review 2 pages").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("shutter", "review", "review"), calls)
    }

    @Test
    fun `the shutter waits for the camera`() {
        show(ScannerUi(shutterEnabled = false))
        rule.onNodeWithContentDescription("Take photo and review it").assertIsNotEnabled()
    }

    @Test
    fun `modes can change only before the first shot`() {
        show(ScannerUi(mode = CaptureMode.SINGLE))
        rule.onNodeWithText("Single page").assertIsSelected()
        rule.onNodeWithText("Book").performClick()
        assertEquals(listOf("mode BOOK"), calls)
    }

    @Test
    fun `after the first shot the other modes are disabled`() {
        show(ScannerUi(mode = CaptureMode.MULTI, count = 1, canChangeMode = false))
        rule.onNodeWithText("Book").assertIsNotEnabled()
        rule.onNodeWithText("Multi page").assertIsSelected()
    }

    @Test
    fun `the holding-steady cue shows only while auto-capture is on and the page is held still`() {
        show(ScannerUi(liveDetection = true, autoCapture = true, holdingSteady = true, torchAvailable = true))
        rule.onNodeWithText("Hold steady").assertIsDisplayed()
        rule.onNodeWithText("Auto on").performClick()
        rule.onNodeWithContentDescription("Torch").performClick()
        assertEquals(listOf("auto", "torch"), calls)
    }

    @Test
    fun `with auto-capture off there is no cue even when the page is still`() {
        show(ScannerUi(liveDetection = true, autoCapture = false, holdingSteady = true))
        rule.onNodeWithText("Auto off").assertIsDisplayed()
        rule.onNodeWithText("Hold steady").assertDoesNotExist()
    }

    @Test
    fun `a dark scene shows the low-light hint over the preview, and the shutter still works`() {
        show(ScannerUi(lowLight = true, shutterEnabled = true))
        rule.onNodeWithText("Low light: more light or the torch helps the scanner find the page.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Take photo and review it").assertIsEnabled().performClick()
        assertEquals(listOf("shutter"), calls)
    }

    @Test
    fun `no low-light hint in a bright scene`() {
        show(ScannerUi(lowLight = false))
        rule.onNodeWithText("Low light: more light or the torch helps the scanner find the page.").assertDoesNotExist()
    }

    @Test
    fun `book mode words the cue for both pages`() {
        show(ScannerUi(mode = CaptureMode.BOOK, liveDetection = true, autoCapture = true, holdingSteady = true))
        rule.onNodeWithText("Hold steady · both pages in view").assertIsDisplayed()
    }

    @Test
    fun `a camera without live detection offers only the manual shutter and no torch without a flash unit`() {
        show(ScannerUi(liveDetection = false, holdingSteady = true))
        rule.onNodeWithText("Auto on").assertDoesNotExist()
        rule.onNodeWithText("Hold steady").assertDoesNotExist()
        rule.onNodeWithContentDescription("Torch").assertDoesNotExist()
    }

    @Test
    fun `a shot that could not be stored offers retry and dismiss`() {
        show(ScannerUi(storeFailed = true, shotFailed = true))
        rule.onNodeWithText("A photo couldn't be saved as a page.").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        rule.onNodeWithText("The photo couldn't be taken. Try again.").assertIsDisplayed()
        val dismiss = rule.onAllNodesWithText("Dismiss")
        dismiss.assertCountEquals(2)
        dismiss[0].performClick()
        dismiss[1].performClick()
        assertEquals(listOf("retry", "dismiss store", "dismiss shot"), calls)
    }

    @Test
    fun `without the camera permission the scanner asks for it`() {
        show(ScannerUi(permissionGranted = false))
        rule.onNodeWithText("Camera access needed").assertIsDisplayed()
        rule.onNodeWithText("Allow camera").performClick()
        assertEquals(listOf("allow"), calls)
    }

    @Test
    fun `a permanently denied permission offers the app's settings instead of a request that cannot show`() {
        show(ScannerUi(permissionGranted = false, permissionBlocked = true))
        rule.onNodeWithText("Allow camera").assertDoesNotExist()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun `TalkBack reads the top row, then the modes, then the cues, then the footer`() {
        show(ScannerUi(liveDetection = true, autoCapture = true, holdingSteady = true, torchAvailable = true, lowLight = true, shutterEnabled = true))
        val labels = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            (node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)?.map { it.text })
                ?.let { labels += it }
            node.children.forEach(::walk)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        val order = listOf(
            "Close scanner", "Auto on", "Torch", "Capture mode", "Single page", "Book", "Hold steady",
            "Low light: more light or the torch helps the scanner find the page.", "Take photo and review it"
        )
        val at = order.map { labels.indexOf(it) }
        assertTrue("every one is read: $labels", at.none { it < 0 })
        assertEquals("in this order: $labels", at.sorted(), at)
    }

    /** The scanner over a stand-in preview laid out as CameraViewfinder lays out its box: 3:4, centred in the whole screen. */
    private var view: View? = null

    private fun showOverPreview(ui: ScannerUi, fontScale: Float) = rule.setContent {
        view = LocalView.current
        Scaled(fontScale) {
            PaperKnifePlusTheme {
                ScannerScreen(ui, actions) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Box(Modifier.aspectRatio(3f / 4f).testTag(PREVIEW)) }
                }
            }
        }
    }

    /**
     * The capture modes' band at [fontScale]: under the top row's controls and above the holding-steady cue, every segment at least T2 and
     * on screen, no hit area overlapping another. Prints how far the band reaches into the 3:4 preview and holds it to [most] dp, if given.
     */
    private fun assertBand(fontScale: Float, most: Float?, statusBar: Dp = 0.dp) {
        showOverPreview(ScannerUi(liveDetection = true, autoCapture = true, holdingSteady = true, torchAvailable = true, shutterEnabled = true), fontScale)
        val dp = rule.density.density
        if (statusBar > 0.dp) {
            rule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (statusBar.value * dp).roundToInt(), 0, 0))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(view!!, insets)
            }
            rule.waitForIdle()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tolerance = 0.5f
        val band = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).fetchSemanticsNode().boundsInRoot
        val segments = listOf(R.string.scan_mode_single, R.string.scan_mode_multi, R.string.scan_mode_book)
            .map { rule.onNode(radioButton(context.getString(it))).fetchSemanticsNode() }
        val top = listOf(
            rule.onNodeWithContentDescription(context.getString(R.string.scan_close)),
            rule.onNode(hasText(context.getString(R.string.scan_auto_on)) and hasClickAction()),
            rule.onNodeWithContentDescription(context.getString(R.string.scan_torch)),
        ).map { it.fetchSemanticsNode().touchBoundsInRoot }
        val cue = rule.onNodeWithText(context.getString(R.string.scan_hold_steady)).fetchSemanticsNode().boundsInRoot
        val preview = rule.onNodeWithTag(PREVIEW).fetchSemanticsNode().boundsInRoot
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        // With the 6T's status bar the stand-in preview must sit where the 6T draws it (136-648 dp), or the overlap means nothing.
        if (statusBar > 0.dp) assertEquals("the preview's top on the 6T", SIX_T_PREVIEW_TOP, preview.top / dp, 1f)

        assertTrue("the band $band starts under the top row ${top.map { it.bottom }}", top.all { band.top >= it.bottom - tolerance })
        assertTrue("the band $band ends above the cue $cue", band.bottom <= cue.top + tolerance)
        val hits = segments.map { it.touchBoundsInRoot }
        for ((i, s) in segments.withIndex()) {
            val hit = hits[i]
            assertTrue("segment $i hit area $hit is T2", hit.width >= 48 * dp - tolerance && hit.height >= 48 * dp - tolerance)
            assertTrue("segment $i is drawn T2", s.boundsInRoot.height >= 48 * dp - tolerance)
            assertTrue("segment $i is on screen", s.boundsInRoot.left >= -tolerance && s.boundsInRoot.right <= width + tolerance)
            for (other in top + hits.drop(i + 1)) {
                val o = hit.intersect(other)
                assertFalse("segment $i $hit overlaps $other", o.width > tolerance && o.height > tolerance)
            }
        }
        val overlap = maxOf(0f, band.bottom - preview.top) / dp
        val screen = "${context.resources.configuration.locales[0].language} ${width / dp} dp wide at ${fontScale}x"
        println("$screen: band ${band.top / dp}-${band.bottom / dp} dp, preview ${preview.top / dp}-${preview.bottom / dp} dp: the band covers $overlap dp")
        if (most != null) assertTrue("the band covers $overlap dp of the preview, at most $most", overlap <= most)
    }

    // The ledger's estimates on the 6T (about 3 dp at 1.0x, 62 dp at 2.0x) with 10 dp of slack; on 320 x 640 dp the figure is only reported.
    // The 6T's scanner is 384 x 784 dp (832 less the 48 dp navigation bar) under a 29 dp status bar: its preview measures 136-648 dp.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w384dp-h784dp")
    fun `on the 6T the modes sit in a band under the top row`() = assertBand(1f, 13f, SIX_T_STATUS_BAR)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w384dp-h784dp", fontScale = 2f)
    fun `on the 6T at twice the text size the modes sit in a band under the top row`() = assertBand(2f, 72f, SIX_T_STATUS_BAR)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "it-w384dp-h784dp")
    fun `in Italian on the 6T the modes sit in a band under the top row`() = assertBand(1f, 13f, SIX_T_STATUS_BAR)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "it-w384dp-h784dp", fontScale = 2f)
    fun `in Italian on the 6T at twice the text size the modes sit in a band under the top row`() = assertBand(2f, 72f, SIX_T_STATUS_BAR)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w320dp-h640dp")
    fun `on a small phone the modes sit in a band under the top row`() = assertBand(1f, null)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
    fun `on a small phone at twice the text size the modes sit in a band under the top row`() = assertBand(2f, null)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "it-w320dp-h640dp")
    fun `in Italian on a small phone the modes sit in a band under the top row`() = assertBand(1f, null)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "it-w320dp-h640dp", fontScale = 2f)
    fun `in Italian on a small phone at twice the text size the modes sit in a band under the top row`() = assertBand(2f, null)

    private lateinit var camera: CameraColors

    /** The Single page scanner showing [ui]'s current value, so a test can change what it shows. */
    private fun showLive(ui: () -> ScannerUi) = rule.setContent {
        PaperKnifePlusTheme {
            camera = LocalScanPalette.current.camera
            ScannerScreen(ui(), actions) {}
        }
    }

    /** Changes state on the main thread and applies it, so the paused test clock's next frame shows it. */
    private fun onUi(change: () -> Unit) = rule.runOnUiThread {
        change()
        Snapshot.sendApplyNotifications()
    }

    /** The shutter's pixels in the middle of its border at [degrees] (clockwise from the right, so the top is -90). */
    private fun border(vararg degrees: Double): List<Color> {
        val pixels = rule.onNodeWithContentDescription("Take photo and review it").captureToImage().toPixelMap()
        val centre = pixels.width / 2f
        val radius = centre - with(rule.density) { 2.dp.toPx() }
        return degrees.map { Math.toRadians(it) }.map { pixels[(centre + radius * cos(it)).toInt(), (centre + radius * sin(it)).toInt()] }
    }

    private fun Color.near(other: Color) = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) < 0.06f

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Drawn, so the ring can be read.
    fun `while the page is held steady a ring fills the shutter's border clockwise from the top, and only then`() {
        var ui by mutableStateOf(ScannerUi(liveDetection = true, autoCapture = true, holdingSteady = true, holdProgress = { 0.5f }, shutterEnabled = true))
        showLive { ui }
        rule.onNodeWithTag(SHUTTER_RING_TAG).assertExists()
        // Just past the top, at the right and at the left: half a hold, clockwise from the top, covers the first two.
        val (topEdge, right, left) = border(-60.0, 0.0, 180.0)
        assertTrue("top $topEdge is the ring", topEdge.near(camera.outline))
        assertTrue("right $right is the ring", right.near(camera.outline))
        assertTrue("left $left is the bare border", left.near(camera.shutter))
        rule.onNodeWithTag(SHUTTER_RING_TAG).performClick()
        assertEquals("the shutter still takes the photo", listOf("shutter"), calls)

        val held = ui
        // Without the cue, and on a dimmed shutter that would not fire, there is no ring.
        for (without in listOf(held.copy(autoCapture = false), held.copy(holdingSteady = false), held.copy(liveDetection = false), held.copy(shutterEnabled = false))) {
            ui = without
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Take photo and review it").assertExists()
            rule.onNodeWithTag(SHUTTER_RING_TAG).assertDoesNotExist()
            assertFalse("$without: no ring on the right", border(0.0).single().near(camera.outline))
            ui = held
            rule.waitForIdle()
            rule.onNodeWithTag(SHUTTER_RING_TAG).assertExists()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Drawn, so the ring can be read.
    fun `an auto-capture runs the ring to full before it clears`() {
        val progress = mutableFloatStateOf(6 / 7f)
        var ui by mutableStateOf(
            ScannerUi(liveDetection = true, autoCapture = true, holdingSteady = true, holdProgress = { progress.floatValue }, shutterEnabled = true)
        )
        showLive { ui }
        // 10 degrees before the top: only a ring past 97% covers it.
        fun closed() = border(-100.0).single().near(camera.outline)
        rule.onNodeWithTag(SHUTTER_RING_TAG).assertExists()
        assertFalse("six sevenths of a hold leave the end open", closed())

        rule.mainClock.autoAdvance = false
        // ScanFlowModel's order as auto-capture fires: the full hold, then the cue ends and the shutter dims for the shot.
        onUi {
            progress.floatValue = 1f
            ui = ui.copy(ringCloses = 1)
        }
        rule.mainClock.advanceTimeByFrame()
        onUi {
            progress.floatValue = 0f
            ui = ui.copy(holdingSteady = false, shutterEnabled = false)
        }
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithTag(SHUTTER_RING_TAG).assertExists()
        assertTrue("the ring is full as the capture fires", closed())
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag(SHUTTER_RING_TAG).assertDoesNotExist()
    }

    @Test
    fun `a hold that breaks empties the ring at once`() {
        var ui by mutableStateOf(ScannerUi(liveDetection = true, autoCapture = true, holdingSteady = true, holdProgress = { 0.8f }, shutterEnabled = true))
        showLive { ui }
        rule.onNodeWithTag(SHUTTER_RING_TAG).assertExists()
        rule.mainClock.autoAdvance = false
        onUi { ui = ui.copy(holdingSteady = false) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(SHUTTER_RING_TAG).assertDoesNotExist()
    }

    /** At twice the text size on a small phone, a store failure's Retry and Dismiss end above the footer, even under both cues. */
    private fun assertBannerButtonsAboveFooter() {
        val ui = ScannerUi(liveDetection = true, autoCapture = true, holdingSteady = true, lowLight = true, storeFailed = true, shutterEnabled = true)
        showOverPreview(ui, 2f)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val footer = rule.onNodeWithTag(SCANNER_FOOTER_TAG).fetchSemanticsNode().boundsInRoot
        for (label in listOf(R.string.scan_retry, R.string.scan_dismiss)) {
            val button = rule.onNode(hasText(context.getString(label)) and hasClickAction()).fetchSemanticsNode().touchBoundsInRoot
            println("${context.getString(label)} ends at ${button.bottom / rule.density.density} dp, the footer starts at ${footer.top / rule.density.density} dp")
            assertTrue("${context.getString(label)} $button ends above the footer $footer", button.bottom <= footer.top + 0.5f)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
    fun `on a small phone at twice the text size a failed page's buttons stay above the footer`() = assertBannerButtonsAboveFooter()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "it-w320dp-h640dp", fontScale = 2f)
    fun `in Italian on a small phone at twice the text size a failed page's buttons stay above the footer`() = assertBannerButtonsAboveFooter()

    @Test
    fun `at twice the text size the footer and the modes stay on screen`() {
        show(ScannerUi(mode = CaptureMode.MULTI, count = 3, shutterEnabled = true, liveDetection = true), fontScale = 2f)
        rule.onNodeWithText("Done").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithContentDescription("Take photo").assertIsDisplayed()
        rule.onNodeWithText("Single page").assertIsDisplayed()
        rule.onNodeWithText("Book").assertIsDisplayed()
        rule.onNodeWithText("Auto on").assertIsDisplayed()
    }
}

private const val PREVIEW = "preview"

/** The 6T's status bar (its close button's top, 41 dp, less the top row's 12 dp padding) and the top of its preview, as measured. */
private val SIX_T_STATUS_BAR = 29.dp
private const val SIX_T_PREVIEW_TOP = 136f

/** [content] at [fontScale] times the system text size. */
@Composable
internal fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
}
