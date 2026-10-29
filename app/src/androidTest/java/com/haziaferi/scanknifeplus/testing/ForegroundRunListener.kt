package com.haziaferi.scanknifeplus.testing

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * Keeps an empty activity on screen, and the screen on, during every device test. Some OEM builds (OxygenOS 14's HANS on the 9 Pro)
 * freeze an app process that shows no window a few seconds after it leaves the foreground, which stalls any longer test; most device
 * tests open no activity of their own. Opened per test because the runner finishes every activity when a test ends. Registered as the
 * runner's `listener` in app/build.gradle.kts.
 */
class ForegroundRunListener : RunListener() {
    private var scenario: ActivityScenario<ComponentActivity>? = null

    override fun testStarted(description: Description?) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
            .onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    override fun testFinished(description: Description?) {
        scenario?.close()
        scenario = null
    }
}
