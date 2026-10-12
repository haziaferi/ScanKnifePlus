package com.haziaferi.scanknifeplus.scan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanPreferencesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `auto-capture is on by default`() {
        assertTrue(ScanPreferences(app).autoCapture)
    }

    @Test
    fun `the auto-capture choice is remembered across instances, in scan_prefs`() {
        ScanPreferences(app).autoCapture = false
        assertFalse(ScanPreferences(app).autoCapture)
        assertFalse(app.getSharedPreferences("scan_prefs", Context.MODE_PRIVATE).getBoolean("auto_capture", true))
        ScanPreferences(app).autoCapture = true
        assertTrue(ScanPreferences(app).autoCapture)
    }
}
