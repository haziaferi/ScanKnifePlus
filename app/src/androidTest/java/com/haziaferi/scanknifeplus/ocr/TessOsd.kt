package com.haziaferi.scanknifeplus.ocr

import com.googlecode.tesseract.android.TessBaseAPI

/**
 * Tesseract's orientation and script detection, which Tesseract4Android does not expose to Java. A small JNI shim
 * (docs/ocr-bench/native/tess_osd.c, built by `run.sh --field` into the git-ignored androidTest jniLibs) calls the C API function
 * `TessBaseAPIDetectOrientationScript` that libtesseract.so exports. The api must be initialised with the `osd` language and hold an image.
 */
internal object TessOsd {
    /** Why the shim could not be loaded, or null when it is there. */
    private val loadError: String? = runCatching { System.loadLibrary("tessosd") }.exceptionOrNull()?.let { it.message ?: it.toString() }

    class Result(val degrees: Int, val confidence: Float, val script: String, val scriptConfidence: Float) {
        /** Clockwise rotation that makes the page upright: Tesseract's own conversion in `GetOsdText` ("Rotate:"). */
        val rotateClockwise: Int get() = (360 - degrees) % 360
    }

    /** The detection, or an error message. */
    fun detect(api: TessBaseAPI): Pair<Result?, String?> {
        loadError?.let { return null to "OSD shim not loaded ($it): run docs/ocr-bench/run.sh --field, which builds it" }
        val handle = TessBaseAPI::class.java.getDeclaredField("mNativeData").apply { isAccessible = true }.getLong(api)
        val parts = nativeDetect(handle).split('\t')
        if (parts[0] == "error" || parts.size < 4) return null to parts.drop(1).joinToString(" ")
        return Result(parts[0].toInt(), parts[1].toFloat(), parts[2], parts[3].toFloat()) to null
    }

    @JvmStatic private external fun nativeDetect(api: Long): String
}
