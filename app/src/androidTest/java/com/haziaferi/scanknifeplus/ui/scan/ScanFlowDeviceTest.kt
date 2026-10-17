package com.haziaferi.scanknifeplus.ui.scan

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.haziaferi.scanknifeplus.scan.camera.CameraStatus
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The scan flow on the phone's camera: it opens, a shot in each mode reaches Review, and pausing the screen releases the camera. */
@RunWith(AndroidJUnit4::class)
class ScanFlowDeviceTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var model: ScanFlowModel

    @Before
    fun open() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { it.setContent { PaperKnifePlusTheme { ScanFlow(onClose = {}, onSaved = {}) } } }
        model = waitFor("the flow") {
            var flow: ScanFlowModel? = null
            scenario.onActivity { flow = ViewModelProvider(it)[ScanFlowHolder::class.java].model }
            flow
        }!!
        waitFor("the camera to open") { main { model.camera.ready }.takeIf { it } }
        // Only the shutter takes shots here: an auto-capture of whatever the phone sees would add pages the counts do not expect.
        main { if (model.autoCapture) model.toggleAutoCapture() }
    }

    @After
    fun discard() {
        main { if (!model.autoCapture) model.toggleAutoCapture() }
        var left = false
        main { model.leave(keep = false) { left = true } }
        waitFor("the session to end") { main { left }.takeIf { it } }
        scenario.close()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 15_000, value: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            value()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what; camera ${main { model.camera }}, session ${main { model.sessionState }}")
    }

    /** Presses the shutter once the camera is ready and waits until the shot is stored. */
    private fun shoot() {
        val before = main { model.sessionState.pages.size }
        waitFor("the camera to be ready for a shot") { main { model.camera.ready && !model.camera.capturing }.takeIf { it } }
        main { model.shutter() }
        waitFor("the shot to be stored") { main { model.sessionState.pages.size > before && model.sessionState.pending == 0 }.takeIf { it } }
    }

    @Test
    fun singlePageShotOpensReview() {
        shoot()
        assertEquals(ScanPhase.REVIEW, main { model.phase })
        assertEquals(1, main { model.pages.size })
        waitFor("the camera to close in Review") { main { model.camera.status == CameraStatus.IDLE }.takeIf { it } }
    }

    @Test
    fun multiPageKeepsCapturingUntilDone() {
        main { model.chooseMode(CaptureMode.MULTI) }
        shoot()
        shoot()
        assertEquals(ScanPhase.SCANNER, main { model.phase })
        main { model.review() }
        assertEquals(ScanPhase.REVIEW, main { model.phase })
        assertEquals(2, main { model.pages.size })
    }

    @Test
    fun bookShotStoresTwoPagesWithTheirOwnOriginals() {
        main { model.chooseMode(CaptureMode.BOOK) }
        shoot()
        main { model.review() }
        val pages = main { model.pages }
        assertEquals(2, pages.size)
        assertTrue(pages.all { it.original != null })
        assertEquals(2, pages.mapNotNull { it.original }.distinct().size)
    }

    @Test
    fun pausingReleasesTheCameraAndResumingReopensIt() {
        scenario.moveToState(Lifecycle.State.STARTED)
        waitFor("the camera to close on pause") { main { model.camera.status == CameraStatus.CLOSED }.takeIf { it } }
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("the camera to reopen after a pause") { main { model.camera.ready }.takeIf { it } }
        // Stopped, the preview's surface goes too, and the camera must get a new one when the screen comes back.
        scenario.moveToState(Lifecycle.State.CREATED)
        waitFor("the camera to close on stop") { main { model.camera.status == CameraStatus.CLOSED }.takeIf { it } }
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("the camera to reopen after a stop") { main { model.camera.ready }.takeIf { it } }
        shoot()
        assertEquals(ScanPhase.REVIEW, main { model.phase })
    }
}
