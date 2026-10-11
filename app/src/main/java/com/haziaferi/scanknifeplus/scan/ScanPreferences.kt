package com.haziaferi.scanknifeplus.scan

import android.content.Context

/** The scanner's remembered settings, in the app's private "scan_prefs" SharedPreferences; a scan session reads them when it starts. */
class ScanPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether auto-capture is on (the default) for [com.haziaferi.scanknifeplus.scan.live.LiveScanPipeline.setAutoCaptureEnabled]. */
    var autoCapture: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPTURE, value).apply()

    private companion object {
        const val FILE = "scan_prefs"
        const val KEY_AUTO_CAPTURE = "auto_capture"
    }
}
