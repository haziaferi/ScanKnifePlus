package com.haziaferi.scanknifeplus.ui.scan

import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The scan flow's portrait hold gives back the orientation from before the first lock, even after the lock recreated the activity. */
@RunWith(RobolectricTestRunner::class)
class HoldPortraitTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    // Holding composes the hold; letting go takes the composition out of the window, which disposes it at once rather than at a later frame.
    private fun ActivityScenario<ComponentActivity>.show(holding: Boolean) = onActivity { a ->
        val holder = ViewModelProvider(a)[ScanFlowHolder::class.java]
        if (holding) a.setContent { HoldPortrait(a, holder) } else a.setContentView(View(a))
    }

    private fun ActivityScenario<ComponentActivity>.orientation(): Int {
        shadowOf(Looper.getMainLooper()).idle()
        var o = 0
        onActivity { o = it.requestedOrientation }
        return o
    }

    @Test
    fun `the orientation from before the lock comes back after a recreation`() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR }
            scenario.show(holding = true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, scenario.orientation())

            scenario.recreate()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            scenario.show(holding = true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, scenario.orientation())

            scenario.show(holding = false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, scenario.orientation())
        }
    }
}
