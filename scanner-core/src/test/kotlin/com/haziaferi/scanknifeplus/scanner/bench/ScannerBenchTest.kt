package com.haziaferi.scanknifeplus.scanner.bench

import com.haziaferi.scanknifeplus.scanner.cv.Contours
import com.haziaferi.scanknifeplus.scanner.cv.DetectionResult
import com.haziaferi.scanknifeplus.scanner.cv.DocumentDetector
import com.haziaferi.scanknifeplus.scanner.cv.EdgeDetection
import com.haziaferi.scanknifeplus.scanner.cv.PerspectiveCrop
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.filter.DocumentFilters
import com.haziaferi.scanknifeplus.scanner.live.AutoCaptureDetector
import com.haziaferi.scanknifeplus.scanner.live.MicrosClock
import com.haziaferi.scanknifeplus.scanner.live.QuadSmoother
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.util.Locale
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The JVM half of the scanner benchmark in docs/scanner-bench (README.md there), not a pass/fail test: skipped unless the environment
 * variable `SCANNER_BENCH_DIR` names the work folder `bench.py prepare` filled. Writes its results back into that folder as JSON and PNG.
 */
class ScannerBenchTest {
    private val work: File? = System.getenv("SCANNER_BENCH_DIR")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    private fun workDir(): File {
        assumeTrue("SCANNER_BENCH_DIR not set: scanner benchmark skipped", work != null)
        return work!!
    }

    /** Our one-shot detection and the hybrid (our candidates ranked by their score) on every upright photo. */
    @Test fun detection() {
        val dir = workDir()
        val json = StringBuilder("{\n")
        for ((index, row) in inventory(dir).withIndex()) {
            val image = readRgba(File(dir, "upright/${row.id}.png"))
            StoredImage.flattenOntoWhite(image.pixels)
            val t0 = System.nanoTime()
            val ours = (DocumentDetector.detectFromRgba(image.pixels, image.width, image.height) as? DetectionResult.Success)?.quad
            val oursMs = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            val hybrid = hybridDetect(image)
            val hybridMs = (System.nanoTime() - t1) / 1e6
            if (index > 0) json.append(",\n")
            json.append(" \"${row.id}\": {\"ours\": ${result(ours, oursMs)}, \"hybrid\": ${result(hybrid, hybridMs)}}")
            println("[bench] detection ${row.id}: ours ${ours != null} ${oursMs.roundToInt()} ms, hybrid ${hybrid != null}")
        }
        File(dir, "detect-ours.json").writeText(json.append("\n}\n").toString())
    }

    /**
     * Our detector's own candidate pool (DocumentDetector.detectQuadFromGrayscale at its working size), ranked by OSS-DocumentScanner's
     * getContourSortFactor instead of our clustering score: area + weight * (1 - maxCos), with their per-mask weights (3000000, then one
     * less per mask) and the area converted to their 300 px working scale so the two terms keep their balance.
     */
    private fun hybridDetect(image: RgbaImage): Quad? {
        val longest = max(image.width, image.height).toDouble()
        val scale = if (longest > DocumentDetector.DETECTION_MAX_DIMENSION) DocumentDetector.DETECTION_MAX_DIMENSION / longest else 1.0
        val w = max(1, dartRound(image.width * scale))
        val h = max(1, dartRound(image.height * scale))
        val gray = EdgeDetection.rgbaToGrayscale(DocumentDetector.downscaleRgba(image.pixels, image.width, image.height, w, h), w, h)
        val magnitude = EdgeDetection.sobelMagnitude(EdgeDetection.gaussianBlur3(gray, w, h), w, h)
        val base = EdgeDetection.otsuThreshold(magnitude)
        val areaToTheirs = (OSS_WORKING_EDGE / max(w, h).toDouble()).let { it * it }
        var best: Quad? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for ((mask, multiplier) in THRESHOLD_MULTIPLIERS.withIndex()) {
            val binary = EdgeDetection.threshold(magnitude, dartRound(base * multiplier).coerceIn(0, 255))
            val dilated = EdgeDetection.dilate(binary, w, h, 4)
            val weight = 3_000_000 - mask
            for (quad in Contours.findDocumentQuadCandidates(dilated, w, h)) {
                val score = area(quad) * areaToTheirs + weight * (1 - maxCosine(quad))
                if (score > bestScore) {
                    bestScore = score
                    best = quad
                }
            }
        }
        return best?.scaled(image.width.toDouble() / w, image.height.toDouble() / h)
    }

    /**
     * Every hand-marked photo warped with its label by our PerspectiveCrop (corpus pages to A4 at 300 DPI, the rest to their natural size
     * capped at the A4 long edge), then each of our filters timed on a copy. Writes `pages/<id>/<filter>.png` and filters-ours.json.
     */
    @Test fun pages() {
        val dir = workDir()
        val labels = File(dir, "labels.tsv")
        assumeTrue("no labels.tsv yet: pages skipped", labels.isFile)
        val sets = inventory(dir).associate { it.id to it.set }
        val json = StringBuilder("{\n")
        var first = true
        for (line in labels.readLines().drop(1).filter { it.isNotBlank() }) {
            val (id, coords) = line.split('\t')
            val v = coords.split(' ').map(String::toDouble)
            val quad = Quad(Pt(v[0], v[1]), Pt(v[2], v[3]), Pt(v[4], v[5]), Pt(v[6], v[7]))
            val image = readRgba(File(dir, "upright/$id.png"))
            StoredImage.flattenOntoWhite(image.pixels)
            val warped = if (sets[id] == "corpus") {
                PerspectiveCrop.warp(image, quad, A4_300_WIDTH, A4_300_HEIGHT)
            } else {
                PerspectiveCrop.warpToPage(image, quad, A4_300_HEIGHT)
            } ?: error("warp failed for $id")
            val out = File(dir, "pages/$id").apply { mkdirs() }
            val times = ArrayList<String>()
            for (filter in DocumentFilters.all) {
                val pixels = warped.pixels.copyOf()
                val t0 = System.nanoTime()
                filter.apply(pixels, warped.width, warped.height)
                times += "\"${filter.name}\": ${"%.1f".format(Locale.ROOT, (System.nanoTime() - t0) / 1e6)}"
                writePng(RgbaImage(warped.width, warped.height, pixels), File(out, filter.name.replace("&", "") + ".png"))
            }
            if (!first) json.append(",\n")
            first = false
            json.append(" \"$id\": {${times.joinToString(", ")}}")
            println("[bench] pages $id: ${warped.width}x${warped.height}")
        }
        File(dir, "filters-ours.json").writeText(json.append("\n}\n").toString())
    }

    /**
     * Synthetic quad sequences at 30 fps through our [AutoCaptureDetector] (real defaults, virtual [MicrosClock]) two ways, as the app runs it
     * (every third frame through [QuadSmoother]) and on its own (every raw frame), and through [AutoScanHandlerDouble] (their defaults:
     * distance 50 px, pre-delay 1000 ms, duration 1000 ms) on two analysis frame sizes. Seeded and deterministic. Writes autocapture.json.
     */
    @Test fun autoCapture() {
        val dir = workDir()
        val results = SCENARIOS.map { scenario ->
            val tallies = List(4) { Tally() }
            for (run in 0 until RUNS) {
                val frames = scenario.frames(Random(1000L * SCENARIOS.indexOf(scenario) + run))
                tallies[0].add(runOursAsApp(frames), scenario.scoreFromFrame)
                tallies[1].add(runOursAlone(frames), scenario.scoreFromFrame)
                tallies[2].add(runTheirs(frames, FRAME_SIZES[0]), scenario.scoreFromFrame)
                tallies[3].add(runTheirs(frames, FRAME_SIZES[1]), scenario.scoreFromFrame)
            }
            scenario to tallies
        }
        val json = StringBuilder("{\n")
        json.append(" \"method\": ${quote(AUTO_METHOD)},\n")
        json.append(" \"frames\": [\"${FRAME_SIZES[0].first}x${FRAME_SIZES[0].second}\", \"${FRAME_SIZES[1].first}x${FRAME_SIZES[1].second}\"],\n")
        json.append(" \"summary\": ${quote(summary(results))},\n")
        json.append(" \"scenarios\": [\n")
        json.append(
            results.joinToString(",\n") { (s, t) ->
                "  {\"name\": ${quote(s.name)}, \"shouldCapture\": ${s.shouldCapture}, \"runs\": $RUNS, \"ours\": ${t[0].json()}, " +
                    "\"oursAlone\": ${t[1].json()}, \"theirs\": ${t[2].json()}, \"theirs2\": ${t[3].json()}}"
            },
        )
        File(dir, "autocapture.json").writeText(json.append("\n ]\n}\n").toString())
    }

    /**
     * Capture times (ms from the scenario start) of our detector fed as LiveScanPipeline feeds it: every third frame goes to [QuadSmoother]
     * and every smoothed quad it publishes to the detector; each capture is followed by notifyCaptured and a smoother reset.
     */
    private fun runOursAsApp(frames: List<Quad?>): List<Double> {
        var nowMicros = 0L
        val captures = ArrayList<Double>()
        var captured = false
        val clock = MicrosClock { nowMicros }
        val detector = AutoCaptureDetector(onStable = { captured = true }, clock = clock)
        val smoother = QuadSmoother(clock) { detector.onQuadUpdate(it) }
        for ((i, quad) in frames.withIndex()) {
            if (i % APP_FRAME_STRIDE != APP_FRAME_STRIDE - 1) continue
            nowMicros = (i * FRAME_MICROS).toLong()
            smoother.onRawQuad(quad)
            if (captured) {
                captured = false
                captures += nowMicros / 1000.0
                detector.notifyCaptured()
                smoother.reset()
            }
        }
        return captures
    }

    /** Capture times (ms from the scenario start) of our detector alone on every raw frame; notifyCaptured follows each capture. */
    private fun runOursAlone(frames: List<Quad?>): List<Double> {
        var nowMicros = 0L
        val captures = ArrayList<Double>()
        lateinit var detector: AutoCaptureDetector
        detector = AutoCaptureDetector(onStable = {
            captures += nowMicros / 1000.0
            detector.notifyCaptured()
        }, clock = MicrosClock { nowMicros })
        for ((i, quad) in frames.withIndex()) {
            nowMicros = (i * FRAME_MICROS).toLong()
            detector.onQuadUpdate(quad)
        }
        return captures
    }

    /**
     * Their capture times on a [size] analysis frame: the normalised quad in frame pixels, quantised as their live path quantises it
     * (detection on a 200 px long-edge copy, previewResizeThreshold, with the integer corners scaled back up).
     */
    private fun runTheirs(frames: List<Quad?>, size: Pair<Int, Int>): List<Double> {
        val captures = ArrayList<Double>()
        var now = 0L
        val handler = AutoScanHandlerDouble { captures += now.toDouble() }
        val step = max(size.first, size.second) / 200.0
        for ((i, quad) in frames.withIndex()) {
            now = (i * FRAME_MICROS / 1000).toLong()
            handler.advanceTo(now)
            val points = quad?.points?.map { p ->
                IntPoint((Math.rint(p.x * size.first / step) * step).roundToInt(), (Math.rint(p.y * size.second / step) * step).roundToInt())
            }
            handler.process(points?.let { listOf(it) })
        }
        return captures
    }

    private class Tally {
        var runsCaptured = 0
        val firstMs = ArrayList<Double>()
        var captures = 0
        var runs = 0

        fun add(times: List<Double>, fromFrame: Int) {
            runs++
            val start = fromFrame * FRAME_MICROS / 1000
            captures += times.size
            if (times.isNotEmpty()) {
                runsCaptured++
                firstMs += times.first() - start
            }
        }

        fun median(): Double? = firstMs.sorted().let { if (it.isEmpty()) null else (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

        fun json(): String = "{\"runsCaptured\": $runsCaptured, \"medianMs\": ${median()?.let { "%.0f".format(Locale.ROOT, it) } ?: "null"}, " +
            "\"meanCaptures\": ${"%.3f".format(Locale.ROOT, captures.toDouble() / runs)}}"
    }

    private class Scenario(val name: String, val shouldCapture: Boolean?, val scoreFromFrame: Int = 0, val frames: (Random) -> List<Quad?>)

    private fun summary(results: List<Pair<Scenario, List<Tally>>>): String {
        fun steady(i: Int) = results.first { it.first.name == STEADY }.second[i].median()?.roundToInt()
        fun falseRuns(i: Int) = results.filter { it.first.shouldCapture == false }.sumOf { it.second[i].runsCaptured }
        fun lostRuns(i: Int) = results.filter { it.first.shouldCapture == true }.sumOf { it.second[i].runs - it.second[i].runsCaptured }
        val no = results.filter { it.first.shouldCapture == false }.sumOf { it.second[0].runs }
        val yes = results.filter { it.first.shouldCapture == true }.sumOf { it.second[0].runs }
        fun line(what: String, f: (Int) -> Int?) = "$what: ScanKnife+ ${f(0)} (detector alone ${f(1)}), theirs ${f(2)} (${f(3)} on the larger frame)."
        return line("Held steady, median ms to capture", ::steady) + " " +
            line("Runs with a capture where none should happen (of $no)", ::falseRuns) + " " +
            line("Runs without a capture where one should happen (of $yes)", ::lostRuns)
    }

    private data class Photo(val id: String, val set: String)

    private fun inventory(dir: File): List<Photo> = File(dir, "inventory.tsv").readLines().drop(1).filter { it.isNotBlank() }.map {
        val c = it.split('\t')
        Photo(c[0], c[1])
    }

    private fun result(quad: Quad?, ms: Double): String =
        "{\"quad\": ${quad?.points?.joinToString(", ", "[", "]") { "[${"%.1f".format(Locale.ROOT, it.x)}, ${"%.1f".format(Locale.ROOT, it.y)}]" } ?: "null"}, " +
            "\"ms\": ${"%.1f".format(Locale.ROOT, ms)}}"

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        /** DocumentDetector's private THRESHOLD_MULTIPLIERS, in the same order. */
        val THRESHOLD_MULTIPLIERS = doubleArrayOf(0.7, 1.0, 1.3)

        /** Their one-shot path's working long edge (resizeThreshold 300). */
        const val OSS_WORKING_EDGE = 300.0
        const val A4_300_WIDTH = 2480
        const val A4_300_HEIGHT = 3508
        const val RUNS = 20
        const val FPS = 30
        const val FRAME_MICROS = 1_000_000.0 / FPS
        const val SECONDS = 6
        const val STEADY = "held steady"

        /** Portrait analysis frames: CameraX's default 640x480 analysis size, and twice that for the threshold's sensitivity. */
        val FRAME_SIZES = listOf(480 to 640, 960 to 1280)

        /** LiveScanPipeline sends every third camera frame for detection. */
        const val APP_FRAME_STRIDE = 3

        const val AUTO_METHOD = "Synthetic detections at 30 fps for 6 s, 20 seeded runs per scenario. A page fills about 60% x 70% of a portrait " +
            "frame with a slight perspective; every frame adds Gaussian corner noise of 0.2% of the frame (detector jitter); hand shake is an 8 Hz " +
            "and a 9 Hz sine on the two axes with the given amplitude. ScanKnife+ is fed as the app feeds it: every third frame (10 detections a " +
            "second) through QuadSmoother, whose smoothed quads drive AutoCaptureDetector, in normalised coordinates. `Detector alone` feeds every " +
            "raw frame straight to AutoCaptureDetector (no smoother, so every missed detection restarts it). Ours calls notifyCaptured after each " +
            "capture. Theirs gets every frame in analysis-frame pixels, quantised like their 200 px live detection; it captures at most once per " +
            "document until the document is lost. Times are from the scenario start (from the stop for the sliding page); `captures per run` " +
            "counts every capture in 6 s. `Should capture`: yes = a capture is wanted, no = any capture is a false capture, - = either is acceptable."

        private val BASE = Quad(Pt(0.21, 0.16), Pt(0.79, 0.15), Pt(0.81, 0.86), Pt(0.19, 0.85))
        private val OTHER = Quad(Pt(0.30, 0.25), Pt(0.95, 0.27), Pt(0.93, 0.70), Pt(0.32, 0.72))

        private fun Quad.shift(dx: Double, dy: Double) = Quad(
            Pt(topLeft.x + dx, topLeft.y + dy), Pt(topRight.x + dx, topRight.y + dy),
            Pt(bottomRight.x + dx, bottomRight.y + dy), Pt(bottomLeft.x + dx, bottomLeft.y + dy),
        )

        private fun Quad.jitter(r: Random, sigma: Double = 0.002) = Quad(
            Pt(topLeft.x + r.nextGaussian() * sigma, topLeft.y + r.nextGaussian() * sigma),
            Pt(topRight.x + r.nextGaussian() * sigma, topRight.y + r.nextGaussian() * sigma),
            Pt(bottomRight.x + r.nextGaussian() * sigma, bottomRight.y + r.nextGaussian() * sigma),
            Pt(bottomLeft.x + r.nextGaussian() * sigma, bottomLeft.y + r.nextGaussian() * sigma),
        )

        /** A detector only reports a page that is wholly in frame. */
        private fun Quad.ifInFrame(): Quad? = takeIf { q -> q.points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 } }

        private const val N = SECONDS * FPS

        private fun seq(block: (Int, Random) -> Quad?): (Random) -> List<Quad?> = { r -> List(N) { i -> block(i, r) } }

        private fun shake(amplitude: Double, dropout: Double = 0.0) = seq { i, r ->
            // Physiological tremor: about 8-9 Hz, independent phase per axis, amplitude as a fraction of the frame.
            val t = i / FPS.toDouble()
            val quad = BASE.shift(amplitude * sin(2 * PI * 8 * t + 1.3), amplitude * sin(2 * PI * 9 * t + 0.4)).jitter(r)
            quad.takeIf { dropout == 0.0 || r.nextDouble() >= dropout }
        }

        private fun drift(perSecond: Double) = seq { i, r -> BASE.shift(-0.1 + perSecond * i / FPS, 0.0).jitter(r).ifInFrame() }

        private fun sweep(perFrame: Double) = seq { i, r ->
            // Back and forth across the frame (a triangle wave), the page detected only while wholly in view.
            val span = 1.4
            val p = (perFrame * i) % (2 * span)
            BASE.shift(-0.7 + if (p < span) p else 2 * span - p, 0.0).jitter(r).ifInFrame()
        }

        private fun alternate(every: Int) = seq { i, r -> (if ((i / every) % 2 == 0) BASE else OTHER).jitter(r) }

        private fun dropouts(probability: Double) = seq { _, r -> BASE.jitter(r).takeIf { r.nextDouble() >= probability } }

        private const val SLIDE_FRAMES = 20

        val SCENARIOS = listOf(
            Scenario(STEADY, true, frames = seq { _, r -> BASE.jitter(r) }),
            Scenario("hand shake 0.5% of frame", true, frames = shake(0.005)),
            Scenario("hand shake 1%", true, frames = shake(0.01)),
            Scenario("hand shake 1.5%", true, frames = shake(0.015)),
            Scenario("hand shake 2%", true, frames = shake(0.02)),
            Scenario("hand shake 3%", null, frames = shake(0.03)),
            Scenario("hand shake 4%", null, frames = shake(0.04)),
            Scenario("drift 2% of frame/s", null, frames = drift(0.02)),
            Scenario("drift 3%/s", null, frames = drift(0.03)),
            Scenario("drift 5%/s", false, frames = drift(0.05)),
            Scenario("drift 10%/s", false, frames = drift(0.10)),
            Scenario("drift 15%/s", false, frames = drift(0.15)),
            Scenario("page slides in (3%/frame for 20 frames) then stops", true, SLIDE_FRAMES, seq { i, r ->
                BASE.shift(-0.03 * (SLIDE_FRAMES - min(i, SLIDE_FRAMES)), 0.0).jitter(r).ifInFrame()
            }),
            Scenario("phone sweeping across, 2%/frame", false, frames = sweep(0.02)),
            Scenario("phone sweeping across, 5%/frame", false, frames = sweep(0.05)),
            Scenario("two documents alternating every frame", false, frames = alternate(1)),
            Scenario("two documents alternating every 5 frames", false, frames = alternate(5)),
            Scenario("two documents alternating every 15 frames", null, frames = alternate(15)),
            Scenario("two documents alternating every 45 frames", null, frames = alternate(45)),
            Scenario("held steady, 5% random dropouts", true, frames = dropouts(0.05)),
            Scenario("held steady, 15% random dropouts", true, frames = dropouts(0.15)),
            Scenario("held steady, 20% random dropouts", true, frames = dropouts(0.20)),
            Scenario("held steady, one dropout every 15 frames", true, frames = seq { i, r -> BASE.jitter(r).takeIf { i % 15 != 14 } }),
            Scenario("hand shake 1.5%, 15% random dropouts", true, frames = shake(0.015, 0.15)),
        )

        /** Shoelace area. */
        fun area(q: Quad): Double {
            val p = q.points
            return abs(p.indices.sumOf { i -> p[i].x * p[(i + 1) % 4].y - p[(i + 1) % 4].x * p[i].y }) / 2
        }

        /** findSquares' squareness: the largest |cos| of the four corner angles (the 1e-10 guard included). */
        fun maxCosine(q: Quad): Double {
            val p = q.points
            var m = 0.0
            for (j in 2 until 6) {
                val p1 = p[j % 4]
                val p2 = p[j - 2]
                val p0 = p[(j - 1) % 4]
                val dx1 = p1.x - p0.x
                val dy1 = p1.y - p0.y
                val dx2 = p2.x - p0.x
                val dy2 = p2.y - p0.y
                m = max(m, abs((dx1 * dx2 + dy1 * dy2) / sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)))
            }
            return m
        }

        /** Decodes a PNG into RGBA (stride 4), a row at a time so only one extra copy of the image is ever held. */
        fun readRgba(file: File): RgbaImage {
            val img = ImageIO.read(file) ?: error("cannot read $file")
            val w = img.width
            val h = img.height
            val out = ByteArray(w * h * 4)
            val row = IntArray(w)
            for (y in 0 until h) {
                img.getRGB(0, y, w, 1, row, 0, w)
                for (x in 0 until w) {
                    val argb = row[x]
                    val i = (y * w + x) * 4
                    out[i] = (argb shr 16).toByte()
                    out[i + 1] = (argb shr 8).toByte()
                    out[i + 2] = argb.toByte()
                    out[i + 3] = (argb ushr 24).toByte()
                }
            }
            return RgbaImage(w, h, out)
        }

        fun writePng(image: RgbaImage, file: File) {
            val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
            val bgr = (out.raster.dataBuffer as DataBufferByte).data
            val p = image.pixels
            var i = 0
            var o = 0
            while (i < p.size) {
                bgr[o] = p[i + 2]
                bgr[o + 1] = p[i + 1]
                bgr[o + 2] = p[i]
                i += 4
                o += 3
            }
            check(ImageIO.write(out, "png", file)) { "no PNG writer" }
        }
    }
}
