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
import com.haziaferi.scanknifeplus.scanner.live.FrameAdapter
import com.haziaferi.scanknifeplus.scanner.live.LiveDetectionWorker
import com.haziaferi.scanknifeplus.scanner.live.MicrosClock
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
import org.junit.Assert.assertEquals
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

    /**
     * Every upright photo detected five ways: the shipped detector one-shot (`new`) and on a simulated live frame (`newLive`), the detector
     * before the OSS-DocumentScanner port the same two ways (`old`, `oldLive`), and the hybrid (the old candidates ranked by their score).
     */
    @Test fun detection() {
        val dir = workDir()
        val json = StringBuilder("{\n")
        for ((index, row) in inventory(dir).withIndex()) {
            val image = readRgba(File(dir, "upright/${row.id}.png"))
            StoredImage.flattenOntoWhite(image.pixels)
            val parts = ArrayList<String>()
            fun run(key: String, detect: () -> Quad?) {
                val t0 = System.nanoTime()
                val quad = detect()
                parts += "\"$key\": ${result(quad, (System.nanoTime() - t0) / 1e6)}"
            }
            run("new") { (DocumentDetector.detectFromRgba(image.pixels, image.width, image.height) as? DetectionResult.Success)?.quad }
            run("old") { oldDetect(image) }
            run("newLive") { liveDetect(image) { gray, w, h -> DocumentDetector.detectQuadFromGrayscale(gray, w, h) } }
            run("oldLive") { liveDetect(image) { gray, w, h -> Contours.pickBestQuad(oldCandidates(gray, w, h), w, h) } }
            run("hybrid") { hybridDetect(image) }
            if (index > 0) json.append(",\n")
            json.append(" \"${row.id}\": {${parts.joinToString(", ")}}")
            println("[bench] detection ${row.id}")
        }
        File(dir, "detect-ours.json").writeText(json.append("\n}\n").toString())
    }

    /**
     * Guards speed work on the detector: every photo through every path (one-shot on B, G, R; live on 640 x 480 and 1280 x 960 frames, with
     * no previous quad and with the found quad fed back), with every corner written at full precision. The first run records
     * detector-reference.tsv in the work folder; later runs fail on any quad that differs from it. Delete the file to record again.
     */
    @Test fun detectorIdentity() {
        val dir = workDir()
        val lines = ArrayList<String>()
        fun record(id: String, path: String, quad: Quad?) {
            lines += "$id\t$path\t${quad?.points?.joinToString(" ") { "${it.x} ${it.y}" } ?: "none"}"
        }
        for (row in inventory(dir)) {
            val image = readRgba(File(dir, "upright/${row.id}.png"))
            StoredImage.flattenOntoWhite(image.pixels)
            record(row.id, "one-shot", (DocumentDetector.detectFromRgba(image.pixels, image.width, image.height) as? DetectionResult.Success)?.quad)
            for ((fw, fh) in listOf(640 to 480, 1280 to 960)) {
                val (w, h) = FrameAdapter.downsampledSize(fw, fh)!!
                val gray = FrameAdapter.grayscaleFromYPlane(landscapeLuma(image, fw, fh), fw, fw, fh)!!
                val quad = DocumentDetector.detectQuadFromGrayscale(gray, w, h)
                record(row.id, "live ${fw}x$fh", quad)
                record(row.id, "live ${fw}x$fh previous", DocumentDetector.detectQuadFromGrayscale(gray, w, h, quad))
            }
        }
        val reference = File(dir, "detector-reference.tsv")
        if (!reference.isFile) {
            reference.writeText(lines.joinToString("\n", postfix = "\n"))
            println("[bench] identity: recorded ${lines.size} quads")
            return
        }
        val expected = reference.readLines().filter { it.isNotBlank() }
        val differing = expected.zip(lines).filter { (e, a) -> e != a }.map { it.first.substringBeforeLast('\t') }
        println("[bench] identity: ${lines.size} quads, ${differing.size} differ")
        assertEquals("paths", expected.size, lines.size)
        assertEquals("quads that differ from detector-reference.tsv", emptyList<String>(), differing)
    }

    /**
     * JVM time per live frame, FrameAdapter's reduction included, for the shipped detector and the old one, on luminance frames made from
     * the photos at CameraX's 640 x 480 analysis size and at 1280 x 960. Each frame is detected [TIMED_REPEATS] times in a row through a
     * worker that keeps its previous quad, as a held camera would; the first [WARMUP_PASSES] passes over all frames are not counted.
     * Writes live-time.json. PC time, not device time.
     */
    @Test fun liveFrameTime() {
        val dir = workDir()
        val sizes = listOf(640 to 480, 1280 to 960)
        val frames = inventory(dir).map { row ->
            val image = readRgba(File(dir, "upright/${row.id}.png"))
            sizes.map { (w, h) -> landscapeLuma(image, w, h) }
        }
        val json = StringBuilder("{\n \"frames\": ${frames.size}, \"repeats\": $TIMED_REPEATS,\n \"sizes\": {\n")
        for ((s, size) in sizes.withIndex()) {
            val (fw, fh) = size
            val (w, h) = FrameAdapter.downsampledSize(fw, fh)!!
            val times = mapOf("new" to ArrayList<Double>(), "old" to ArrayList())
            for (pass in 0 until WARMUP_PASSES + 1) {
                for (frame in frames.map { it[s] }) {
                    val newWorker = LiveDetectionWorker()
                    var oldPrevious: Quad? = null
                    repeat(TIMED_REPEATS) {
                        var t0 = System.nanoTime()
                        newWorker.process(FrameAdapter.grayscaleFromYPlane(frame, fw, fw, fh)!!, w, h)
                        val newMs = (System.nanoTime() - t0) / 1e6
                        t0 = System.nanoTime()
                        val gray = FrameAdapter.grayscaleFromYPlane(frame, fw, fw, fh)!!
                        oldPrevious = Contours.pickBestQuad(oldCandidates(gray, w, h), w, h, oldPrevious) ?: oldPrevious
                        val oldMs = (System.nanoTime() - t0) / 1e6
                        if (pass >= WARMUP_PASSES) {
                            times.getValue("new") += newMs
                            times.getValue("old") += oldMs
                        }
                    }
                }
            }
            if (s > 0) json.append(",\n")
            json.append("  \"${fw}x$fh\": {")
            json.append(times.entries.joinToString(", ") { (k, v) -> "\"$k\": ${stats(v)}" })
            json.append("}")
            println("[bench] live ${fw}x$fh: new ${stats(times.getValue("new"))}, old ${stats(times.getValue("old"))}")
        }
        File(dir, "live-time.json").writeText(json.append("\n }\n}\n").toString())
    }

    private fun stats(v: List<Double>): String {
        val s = v.sorted()
        fun q(p: Double) = s[((s.size - 1) * p).roundToInt()]
        return "{\"median\": ${"%.2f".format(Locale.ROOT, q(0.5))}, \"p90\": ${"%.2f".format(Locale.ROOT, q(0.9))}, " +
            "\"max\": ${"%.2f".format(Locale.ROOT, s.last())}}"
    }

    /** A sensor-native (landscape) luminance frame of [w] x [h] from a photo, turned a quarter if the photo is portrait, nearest sampling. */
    private fun landscapeLuma(image: RgbaImage, w: Int, h: Int): ByteArray {
        val portrait = image.height > image.width
        val out = ByteArray(w * h)
        val p = image.pixels
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = if (portrait) (h - 1 - y) * image.width / h else x * image.width / w
                val sy = if (portrait) x * image.height / w else y * image.height / h
                val i = (sy * image.width + sx) * 4
                out[y * w + x] = dartRound(0.2126 * (p[i].toInt() and 0xFF) + 0.7152 * (p[i + 1].toInt() and 0xFF) +
                    0.0722 * (p[i + 2].toInt() and 0xFF)).toByte()
            }
        }
        return out
    }

    /** The detector before the port: OpenScan's candidates ([oldCandidates]) ranked by [Contours.pickBestQuad], at its 700 px working size. */
    private fun oldDetect(image: RgbaImage): Quad? {
        val (w, h, gray) = workingGray(image)
        return Contours.pickBestQuad(oldCandidates(gray, w, h), w, h)?.scaled(image.width.toDouble() / w, image.height.toDouble() / h)
    }

    /**
     * The live path on a photo: shrunk to CameraX's 640 x 480 analysis frame (as luminance), reduced by [FrameAdapter.grayscaleFromYPlane]
     * as LiveScanPipeline does, then detected by [detect]; the quad comes back in photo coordinates.
     */
    private fun liveDetect(image: RgbaImage, detect: (ByteArray, Int, Int) -> Quad?): Quad? {
        val fs = 640.0 / max(image.width, image.height)
        val fw = dartRound(image.width * fs)
        val fh = dartRound(image.height * fs)
        val frame = EdgeDetection.rgbaToGrayscale(DocumentDetector.downscaleRgba(image.pixels, image.width, image.height, fw, fh), fw, fh)
        val (w, h) = FrameAdapter.downsampledSize(fw, fh)!!
        return detect(FrameAdapter.grayscaleFromYPlane(frame, fw, fw, fh)!!, w, h)?.scaled(image.width.toDouble() / w, image.height.toDouble() / h)
    }

    private fun workingGray(image: RgbaImage): Triple<Int, Int, ByteArray> {
        val longest = max(image.width, image.height).toDouble()
        val scale = if (longest > DocumentDetector.DETECTION_MAX_DIMENSION) DocumentDetector.DETECTION_MAX_DIMENSION / longest else 1.0
        val w = max(1, dartRound(image.width * scale))
        val h = max(1, dartRound(image.height * scale))
        return Triple(w, h, EdgeDetection.rgbaToGrayscale(DocumentDetector.downscaleRgba(image.pixels, image.width, image.height, w, h), w, h))
    }

    /** The old detector's candidate pool: three Sobel-Otsu masks, dilated, quads from their largest components. */
    private fun oldCandidates(gray: ByteArray, w: Int, h: Int): List<Quad> = oldCandidatesByMask(gray, w, h).flatten()

    private fun oldCandidatesByMask(gray: ByteArray, w: Int, h: Int): List<List<Quad>> {
        val magnitude = EdgeDetection.sobelMagnitude(EdgeDetection.gaussianBlur3(gray, w, h), w, h)
        val base = EdgeDetection.otsuThreshold(magnitude)
        return THRESHOLD_MULTIPLIERS.map { multiplier ->
            val binary = EdgeDetection.threshold(magnitude, dartRound(base * multiplier).coerceIn(0, 255))
            Contours.findDocumentQuadCandidates(EdgeDetection.dilate(binary, w, h, 4), w, h)
        }
    }

    /**
     * The old detector's candidate pool ([oldCandidatesByMask]), ranked by OSS-DocumentScanner's getContourSortFactor instead of our
     * clustering score: area + weight * (1 - maxCos), with their per-mask weights (3000000, then one less per mask) and the area converted
     * to their 300 px working scale so the two terms keep their balance.
     */
    private fun hybridDetect(image: RgbaImage): Quad? {
        val (w, h, gray) = workingGray(image)
        val areaToTheirs = (OSS_WORKING_EDGE / max(w, h).toDouble()).let { it * it }
        var best: Quad? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for ((mask, candidates) in oldCandidatesByMask(gray, w, h).withIndex()) {
            val weight = 3_000_000 - mask
            for (quad in candidates) {
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
     * Synthetic quad sequences at 30 fps through our [AutoCaptureDetector] (real defaults, virtual [MicrosClock]) and through
     * [AutoScanHandlerDouble] (their defaults: distance 50 px, pre-delay 1000 ms, duration 1000 ms) on two analysis frame sizes. Seeded and
     * deterministic. Writes autocapture.json.
     */
    @Test fun autoCapture() {
        val dir = workDir()
        val results = SCENARIOS.map { scenario ->
            val ours = Tally()
            val theirs = Tally()
            val theirs2 = Tally()
            for (run in 0 until RUNS) {
                val frames = scenario.frames(Random(1000L * SCENARIOS.indexOf(scenario) + run))
                ours.add(runOurs(frames), scenario.scoreFromFrame)
                theirs.add(runTheirs(frames, FRAME_SIZES[0]), scenario.scoreFromFrame)
                theirs2.add(runTheirs(frames, FRAME_SIZES[1]), scenario.scoreFromFrame)
            }
            scenario to listOf(ours, theirs, theirs2)
        }
        val json = StringBuilder("{\n")
        json.append(" \"method\": ${quote(AUTO_METHOD)},\n")
        json.append(" \"frames\": [\"${FRAME_SIZES[0].first}x${FRAME_SIZES[0].second}\", \"${FRAME_SIZES[1].first}x${FRAME_SIZES[1].second}\"],\n")
        json.append(" \"summary\": ${quote(summary(results))},\n")
        json.append(" \"scenarios\": [\n")
        json.append(
            results.joinToString(",\n") { (s, t) ->
                "  {\"name\": ${quote(s.name)}, \"shouldCapture\": ${s.shouldCapture}, \"runs\": $RUNS, \"ours\": ${t[0].json()}, " +
                    "\"theirs\": ${t[1].json()}, \"theirs2\": ${t[2].json()}}"
            },
        )
        File(dir, "autocapture.json").writeText(json.append("\n ]\n}\n").toString())
    }

    /** Capture times (ms from the scenario start) of our detector; the caller's [AutoCaptureDetector.notifyCaptured] follows each capture. */
    private fun runOurs(frames: List<Quad?>): List<Double> {
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
        return "held steady, median time to capture: ScanKnife+ ${steady(0)} ms, theirs ${steady(1)} ms (${steady(2)} ms on the larger frame). " +
            "Runs with a capture where none should happen (of $no): ScanKnife+ ${falseRuns(0)}, theirs ${falseRuns(1)} (${falseRuns(2)}). " +
            "Runs without a capture where one should happen (of $yes): ScanKnife+ ${lostRuns(0)}, theirs ${lostRuns(1)} (${lostRuns(2)})."
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
        /** The old detector's THRESHOLD_MULTIPLIERS, in the same order. */
        val THRESHOLD_MULTIPLIERS = doubleArrayOf(0.7, 1.0, 1.3)

        /** Their one-shot path's working long edge (resizeThreshold 300). */
        const val OSS_WORKING_EDGE = 300.0
        const val WARMUP_PASSES = 2
        const val TIMED_REPEATS = 5
        const val A4_300_WIDTH = 2480
        const val A4_300_HEIGHT = 3508
        const val RUNS = 20
        const val FPS = 30
        const val FRAME_MICROS = 1_000_000.0 / FPS
        const val SECONDS = 6
        const val STEADY = "held steady"

        /** Portrait analysis frames: CameraX's default 640x480 analysis size, and twice that for the threshold's sensitivity. */
        val FRAME_SIZES = listOf(480 to 640, 960 to 1280)

        const val AUTO_METHOD = "Synthetic detections at 30 fps for 6 s, 20 seeded runs per scenario. A page fills about 60% x 70% of a portrait " +
            "frame with a slight perspective; every frame adds Gaussian corner noise of 0.2% of the frame (detector jitter). Ours gets the quads " +
            "in normalised coordinates, as LiveScanController feeds AutoCaptureDetector (without QuadSmoother in between), and calls notifyCaptured " +
            "after each capture. Theirs gets the same quads in analysis-frame pixels, quantised like their 200 px live detection; it captures at " +
            "most once per document until the document is lost. Times are from the scenario start (from the stop for the sliding page); " +
            "`captures per run` counts every capture in 6 s. `Should capture`: yes = a capture is wanted, no = any capture is a false capture, " +
            "- = either is acceptable."

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

        private fun shake(amplitude: Double) = seq { i, r ->
            // Physiological tremor: about 8-9 Hz, independent phase per axis, amplitude as a fraction of the frame.
            val t = i / FPS.toDouble()
            BASE.shift(amplitude * sin(2 * PI * 8 * t + 1.3), amplitude * sin(2 * PI * 9 * t + 0.4)).jitter(r)
        }

        private fun drift(perFrame: Double) = seq { i, r -> BASE.shift(-0.1 + perFrame * i, 0.0).jitter(r).ifInFrame() }

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
            Scenario("hand shake 2%", true, frames = shake(0.02)),
            Scenario("hand shake 4%", true, frames = shake(0.04)),
            Scenario("slow drift 0.1%/frame (3%/s)", null, frames = drift(0.001)),
            Scenario("drift 0.5%/frame (15%/s)", false, frames = drift(0.005)),
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
            Scenario("held steady, 20% random dropouts", true, frames = dropouts(0.20)),
            Scenario("held steady, one dropout every 15 frames", true, frames = seq { i, r -> BASE.jitter(r).takeIf { i % 15 != 14 } }),
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
