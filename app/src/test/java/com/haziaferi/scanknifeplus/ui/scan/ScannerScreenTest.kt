package com.haziaferi.scanknifeplus.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `without the camera permission the scanner asks for it`() {
        show(ScannerUi(permissionGranted = false))
        rule.onNodeWithText("Camera access needed").assertIsDisplayed()
        rule.onNodeWithText("Allow camera").performClick()
        assertEquals(listOf("allow"), calls)
    }

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

/** [content] at [fontScale] times the system text size. */
@Composable
internal fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
}
