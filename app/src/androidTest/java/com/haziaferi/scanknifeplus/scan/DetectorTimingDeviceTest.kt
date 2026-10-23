package com.haziaferi.scanknifeplus.scan

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scanner.cv.Contours
import com.haziaferi.scanknifeplus.scanner.cv.DetectionResult
import com.haziaferi.scanknifeplus.scanner.cv.DocumentDetector
import com.haziaferi.scanknifeplus.scanner.cv.EdgeDetection
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import com.haziaferi.scanknifeplus.scanner.live.FrameAdapter
import com.haziaferi.scanknifeplus.scanner.live.LiveDetectionWorker
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device timing of the outline detector for the scanner benchmark (docs/scanner-bench/README.md), not a pass/fail test: skipped unless the
 * instrumentation argument `scannerBenchTiming` is `true`. Reads the upright photos pushed into the app's external files dir under
 * `scanner-bench/upright/` and times, for the shipped detector and the one before the OSS-DocumentScanner port, the one-shot path on the full
 * photo and the live path (FrameAdapter's reduction plus detection) on luminance frames of 640 x 480 and 1280 x 960 made from it. Each call is
 * warmed up once, then timed [TIMED] times. Writes timing.tsv (per path and size) and frames.tsv (which photos fell back to OpenScan's
 * candidates) into the additional test output, which the Android Gradle plugin pulls to `app/build/outputs/connected_android_test_additional_output/`.
 */
@RunWith(AndroidJUnit4::class)
class DetectorTimingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun timing() {
        assumeTrue("scannerBenchTiming not set", InstrumentationRegistry.getArguments().getString("scannerBenchTiming") == "true")
        val photos = context.getExternalFilesDir(null)?.let { File(it, "scanner-bench/upright") }?.listFiles().orEmpty()
            .filter { it.name.endsWith(".png") }.sortedBy { it.name }
        assertTrue("no photos pushed to scanner-bench/upright", photos.isNotEmpty())
        val out = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "scanner-bench").apply { mkdirs() }

        val times = LinkedHashMap<String, MutableList<Double>>()
        fun timed(key: String, block: () -> Unit) {
            block()
            repeat(TIMED) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                block()
                times.getOrPut(key) { ArrayList() } += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            }
        }

        val frames = StringBuilder("photo\tone_shot_fallback\tlive_640x480_fallback\tlive_1280x960_fallback\n")
        for (photo in photos) {
            val image = ImageCodec.decodeScaled(ImageSource.of(photo), Int.MAX_VALUE) ?: error("cannot decode ${photo.name}")
            StoredImage.flattenOntoWhite(image.pixels)
            val id = photo.name.removeSuffix(".png")

            timed("one-shot new") { DocumentDetector.detectFromRgba(image.pixels, image.width, image.height) }
            timed("one-shot old") { oldOneShot(image) }
            val newQuad = (DocumentDetector.detectFromRgba(image.pixels, image.width, image.height) as? DetectionResult.Success)?.quad
            val row = arrayListOf(id, (newQuad != null && newQuad == oldOneShot(image)).toString())

            for ((fw, fh) in SIZES) {
                val frame = landscapeLuma(image, fw, fh)
                val (w, h) = FrameAdapter.downsampledSize(fw, fh)!!
                val worker = LiveDetectionWorker()
                timed("live ${fw}x$fh new") { worker.process(FrameAdapter.grayscaleFromYPlane(frame, fw, fw, fh)!!, w, h) }
                var previous: Quad? = null
                timed("live ${fw}x$fh old") {
                    previous = oldDetect(FrameAdapter.grayscaleFromYPlane(frame, fw, fw, fh)!!, w, h, previous) ?: previous
                }
                // Without a previous quad, the shipped detector equals the old one exactly when OSS-DocumentScanner's search found nothing.
                val gray = FrameAdapter.grayscaleFromYPlane(frame, fw, fw, fh)!!
                val live = DocumentDetector.detectQuadFromGrayscale(gray, w, h)
                row += (live != null && live == oldDetect(gray, w, h, null)).toString()
            }
            frames.append(row.joinToString("\t")).append('\n')
            Log.i(TAG, "${row.joinToString(" ")}")
        }

        val table = StringBuilder("path\tframes\tcalls\tmedian_ms\tp90_ms\tmax_ms\n")
        for ((key, v) in times) {
            val s = v.sorted()
            fun q(p: Double) = s[((s.size - 1) * p).roundToInt()]
            table.append(String.format(Locale.ROOT, "%s\t%d\t%d\t%.1f\t%.1f\t%.1f\n", key, photos.size, s.size, q(0.5), q(0.9), s.last()))
        }
        File(out, "timing.tsv").writeText(table.toString())
        File(out, "frames.tsv").writeText(frames.toString())
        Log.i(TAG, "\n$table")
    }

    /** The detector before the port, one-shot: the photo shrunk to its 700 px working size (nearest), in grey, then [oldDetect]. */
    private fun oldOneShot(image: RgbaImage): Quad? {
        val scale = (DocumentDetector.DETECTION_MAX_DIMENSION / max(image.width, image.height).toDouble()).coerceAtMost(1.0)
        val w = max(1, (image.width * scale).roundToInt())
        val h = max(1, (image.height * scale).roundToInt())
        val small = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val sy = floor((y.toLong() * image.height).toDouble() / h).toInt().coerceIn(0, image.height - 1)
            for (x in 0 until w) {
                val sx = floor((x.toLong() * image.width).toDouble() / w).toInt().coerceIn(0, image.width - 1)
                System.arraycopy(image.pixels, (sy * image.width + sx) * 4, small, (y * w + x) * 4, 4)
            }
        }
        return oldDetect(EdgeDetection.rgbaToGrayscale(small, w, h), w, h, null)?.scaled(image.width.toDouble() / w, image.height.toDouble() / h)
    }

    /** The detector before the port: OpenScan's three Sobel-Otsu masks, pooled and ranked by [Contours.pickBestQuad]. */
    private fun oldDetect(gray: ByteArray, w: Int, h: Int, previous: Quad?): Quad? {
        val magnitude = EdgeDetection.sobelMagnitude(EdgeDetection.gaussianBlur3(gray, w, h), w, h)
        val base = EdgeDetection.otsuThreshold(magnitude)
        val candidates = OLD_MULTIPLIERS.flatMap { m ->
            val binary = EdgeDetection.threshold(magnitude, (base * m).roundToInt().coerceIn(0, 255))
            Contours.findDocumentQuadCandidates(EdgeDetection.dilate(binary, w, h, 4), w, h)
        }
        return Contours.pickBestQuad(candidates, w, h, previous)
    }

    /** A sensor-native (landscape) Y plane of [w] x [h] from a photo, turned a quarter if the photo is portrait, nearest sampling. */
    private fun landscapeLuma(image: RgbaImage, w: Int, h: Int): ByteArray {
        val portrait = image.height > image.width
        val p = image.pixels
        return ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val sx = if (portrait) (h - 1 - y) * image.width / h else x * image.width / w
            val sy = if (portrait) x * image.height / w else y * image.height / h
            val k = (sy * image.width + sx) * 4
            (0.2126 * (p[k].toInt() and 0xFF) + 0.7152 * (p[k + 1].toInt() and 0xFF) + 0.0722 * (p[k + 2].toInt() and 0xFF)).roundToInt().toByte()
        }
    }

    private companion object {
        const val TAG = "DetectorTiming"
        const val TIMED = 5
        val SIZES = listOf(640 to 480, 1280 to 960)
        val OLD_MULTIPLIERS = doubleArrayOf(0.7, 1.0, 1.3)
    }
}
