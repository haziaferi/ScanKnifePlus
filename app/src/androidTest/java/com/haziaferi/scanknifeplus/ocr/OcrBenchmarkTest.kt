package com.haziaferi.scanknifeplus.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import com.googlecode.tesseract.android.TessBaseAPI.PageSegMode
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scanner.cv.DetectionResult
import com.haziaferi.scanknifeplus.scanner.cv.DocumentDetector
import com.haziaferi.scanknifeplus.scanner.cv.PerspectiveCrop
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OCR benchmark, not a pass/fail test: tessdata_fast vs tessdata_best at 300 and 400 DPI on the page images in `ocr-bench/in/<set>/`
 * (see docs/ocr-bench/README.md). Skipped when no images were pushed, so a normal device run stays fast.
 *
 * Cropped images are scaled to the target DPI. Raw camera photos (`<page>.photo.jpg`) go through the scanner's own one-shot detection and
 * perspective warp at full resolution, straight to the target DPI, as the planned OCR copy would. Per page and run it writes the recognised
 * text, a words.tsv (word, confidence, box) and one metrics row into the additional test output directory, which the Android Gradle plugin
 * pulls to `app/build/outputs/connected_android_test_additional_output/`; pages that cannot be prepared go to failures.tsv instead.
 *
 * The `field*` methods are field mode: real photos without ground truth in `ocr-bench/field/<set>/`, with orientation detection first.
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun fast300() = bench("fast", 300)

    @Test fun fast400() = bench("fast", 400)

    @Test fun best300() = bench("best", 300)

    @Test fun best400() = bench("best", 400)

    /** Page segmentation modes on the two-column page only: 3 (the default), 1 (with orientation detection), 4 and 6 as references. */
    @Test fun layoutPsm() = forEachPage(onlyPage = LAYOUT_PAGE) { set, out, name, image, page ->
        for (model in MODELS) for (dpi in DPIS) for (psm in LAYOUT_PSMS) {
            if (name.dpi != null && name.dpi != dpi) continue
            run(File(out, "${set.name}/psm"), "$model-$dpi-psm$psm", set.name, name, image, page, model, dpi, psm)
        }
    }

    private fun bench(model: String, dpi: Int) = forEachPage { set, out, name, image, page ->
        if (name.dpi == null || name.dpi == dpi) run(File(out, set.name), "$model-$dpi", set.name, name, image, page, model, dpi, PSM_DEFAULT)
    }

    private fun forEachPage(onlyPage: String? = null, block: (File, File, PageName, File, Page) -> Unit) {
        val root = inputRoot()
        assumeTrue("no page images pushed to ocr-bench/in", root != null)
        val pages = readPages(File(root!!, "pages.tsv"))
        val out = File(outputRoot(), "ocr-bench")
        for (set in File(root, "in").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            for (image in set.listFiles().orEmpty().sortedBy { it.name }) {
                val name = PageName.parse(image.name) ?: continue
                if (onlyPage != null && name.page != onlyPage) continue
                block(set, out, name, image, pages[name.page] ?: Page("eng+ita", "a4"))
            }
        }
    }

    /**
     * One page through one configuration, written to `[table]/[run]/`: the text, the words, and rows in runs.tsv or failures.tsv. Tables live
     * per configuration so that test methods run as separate instrumentations (see run.sh) never overwrite each other's.
     */
    private fun run(table: File, run: String, set: String, name: PageName, image: File, page: Page, model: String, dpi: Int, psm: Int) {
        val paper = name.paper ?: page.paper
        val input = if (name.photo) photoAt(image, paper, dpi) else croppedAt(image, paper, dpi, if (name.dpi != null) "render" else "cropped")
        val label = "$set/${name.page} $run"
        val dir = File(table, run).apply { mkdirs() }
        if (input is Input.Failed) {
            appendRow(File(dir, "failures.tsv"), "set\tpage\tmodel\tdpi\tpsm\treason", "$set\t${name.page}\t$model\t$dpi\t$psm\t${input.reason}")
            Log.w(TAG, "$label: ${input.reason}")
            return
        }
        val r = recognise(installModel(model), page.lang, input as Input.Ready, dpi, psm)
        File(dir, "${name.page}.txt").writeText(r.text + "\n")
        File(dir, "${name.page}.words.tsv").writeText(r.words)
        appendRow(
            File(dir, "runs.tsv"),
            "set\tpage\tmodel\tdpi\tpsm\tlang\tsource\tsource_dpi\twidth\theight\tinit_ms\trecognise_ms\tnative_base_kb\tnative_peak_kb\t" +
                "pss_base_kb\tpss_peak_kb\tvm_hwm_kb\tmean_conf\twords\tflavor",
            "$set\t${name.page}\t$model\t$dpi\t$psm\t${page.lang}\t${input.source}\t${input.sourceDpi.roundToInt()}\t${input.width}\t" +
                "${input.height}\t${r.initMs}\t${r.recogniseMs}\t${r.nativeBaseKb}\t${r.nativePeakKb}\t${r.pssBaseKb}\t${r.pssPeakKb}\t${r.hwmKb}\t" +
                "${r.meanConfidence}\t${r.wordCount}\t${r.flavor}",
        )
        Log.i(TAG, "$label: ${r.recogniseMs} ms, native peak ${r.nativePeakKb} KB")
    }

    /** Inputs pushed by run.sh into the app's external files dir before the install (the install keeps them; the uninstall removes them). */
    private fun inputRoot(): File? = context.getExternalFilesDir(null)?.let { File(it, "ocr-bench") }
        ?.takeIf { File(it, "in").listFiles().orEmpty().any { set -> set.listFiles().orEmpty().any { PageName.parse(it.name) != null } } }

    /** The only place whose files outlive the uninstall at the end of the run: the plugin pulls it before uninstalling. */
    private fun outputRoot(): File = File(
        requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) { "run through Gradle (additional test output)" },
    )

    /** Copies the model's traineddata from the test APK's assets into `files/tess/<model>/tessdata/`, the layout Tesseract expects. */
    private fun installModel(model: String): String {
        val files = instrumentation.context.assets.list("tessdata/$model").orEmpty()
        check(files.isNotEmpty()) { "no tessdata/$model in the test APK: run docs/ocr-bench/fetch_tessdata.py before building" }
        val dir = File(context.filesDir, "tess/$model/tessdata").apply { mkdirs() }
        for (file in files) {
            val target = File(dir, file)
            if (target.isFile) continue
            instrumentation.context.assets.open("tessdata/$model/$file").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return dir.parentFile!!.absolutePath
    }

    private class Page(val lang: String, val paper: String)

    private fun readPages(file: File): Map<String, Page> = if (!file.isFile) emptyMap() else file.readLines().drop(1).mapNotNull { line ->
        line.split('\t').takeIf { it.size >= 3 }?.let { it[0] to Page(it[1], it[2]) }
    }.toMap()

    private sealed interface Input {
        /** 8-bit grey at the target DPI; [sourceDpi] is the resolution the page had before scaling (below the target means upsampled). */
        class Ready(val grey: ByteArray, val width: Int, val height: Int, val source: String, val sourceDpi: Double) : Input

        class Failed(val reason: String) : Input
    }

    /** An image already cropped to the sheet: scaled so the paper's physical size maps to [dpi], long edge to long edge. */
    private fun croppedAt(image: File, paper: String, dpi: Int, source: String): Input {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val src = BitmapFactory.decodeFile(image.path, options) ?: return Input.Failed("cannot decode")
        val (w, h) = targetSize(src.width, src.height, paper, dpi)
        val sourceDpi = max(src.width, src.height) / (PAPER_MM.getValue(paper).second / MM_PER_INCH)
        val scaled = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true).also { src.recycle() }
        val grey = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            scaled.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) grey[y * w + x] = luma(row[x] shr 16 and 0xff, row[x] shr 8 and 0xff, row[x] and 0xff)
        }
        scaled.recycle()
        return Input.Ready(grey, w, h, source, sourceDpi)
    }

    /**
     * A raw camera photo, the planned OCR copy: decoded upright at full resolution, flattened onto white and detected as the scanner does
     * ([DocumentDetector.detectFromRgba]), then warped by [PerspectiveCrop.warpToPage] capped at the target's long edge and resampled to the
     * exact page size in grey. Never throws: an undetected page or a failed decode or warp comes back as [Input.Failed].
     */
    private fun photoAt(image: File, paper: String, dpi: Int): Input = try {
        val decoded = ImageCodec.decodeScaled(ImageSource.of(image), Int.MAX_VALUE) ?: return Input.Failed("cannot decode")
        StoredImage.flattenOntoWhite(decoded.pixels)
        when (val found = DocumentDetector.detectFromRgba(decoded.pixels, decoded.width, decoded.height)) {
            is DetectionResult.NotFound -> Input.Failed("page not detected in ${decoded.width}x${decoded.height}")
            is DetectionResult.Failure -> Input.Failed("detection failed: ${found.message}")
            is DetectionResult.Success -> {
                val natural = PerspectiveCrop.outputSize(found.quad)
                val (w, h) = targetSize(natural.width, natural.height, paper, dpi)
                val warped = PerspectiveCrop.warpToPage(decoded, found.quad, max(w, h)) ?: return Input.Failed("warp failed")
                val sourceDpi = max(natural.width, natural.height) / (PAPER_MM.getValue(paper).second / MM_PER_INCH)
                val source = if (natural.width > natural.height) "photo-landscape" else "photo"
                Input.Ready(greyResized(warped, w, h), w, h, source, sourceDpi)
            }
        }
    } catch (e: OutOfMemoryError) {
        Input.Failed("out of memory")
    }

    /** The paper's size in pixels at [dpi], oriented like a [width] x [height] image. */
    private fun targetSize(width: Int, height: Int, paper: String, dpi: Int): Pair<Int, Int> {
        val (shortMm, longMm) = PAPER_MM[paper] ?: error("unknown paper $paper")
        val shortPx = (shortMm / MM_PER_INCH * dpi).roundToInt()
        val longPx = (longMm / MM_PER_INCH * dpi).roundToInt()
        return if (height >= width) shortPx to longPx else longPx to shortPx
    }

    /** Bilinear resample of [src] to [width] x [height] grey (pixel centres aligned); the warp is already at or below that size. */
    private fun greyResized(src: RgbaImage, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        val sx = src.width.toDouble() / width
        val sy = src.height.toDouble() / height
        val p = src.pixels
        fun lumaAt(x: Int, y: Int): Int {
            val i = (y * src.width + x) * 4
            return luma(p[i].toInt() and 0xff, p[i + 1].toInt() and 0xff, p[i + 2].toInt() and 0xff).toInt() and 0xff
        }
        for (y in 0 until height) {
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, src.height - 1.0)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, src.height - 1)
            val ty = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, src.width - 1.0)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, src.width - 1)
                val tx = fx - x0
                val top = lumaAt(x0, y0) * (1 - tx) + lumaAt(x1, y0) * tx
                val bottom = lumaAt(x0, y1) * (1 - tx) + lumaAt(x1, y1) * tx
                out[y * width + x] = (top * (1 - ty) + bottom * ty).roundToInt().toByte()
            }
        }
        return out
    }

    /** BT.601 luma, rounded. */
    private fun luma(r: Int, g: Int, b: Int): Byte = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()

    private class Result(
        val text: String, val words: String, val initMs: Long, val recogniseMs: Long, val nativeBaseKb: Long, val nativePeakKb: Long,
        val pssBaseKb: Long, val pssPeakKb: Long, val hwmKb: Long, val meanConfidence: Int, val wordCount: Int, val flavor: String,
    )

    /** Blocking: one fresh Tesseract instance per page so each memory baseline is clean. */
    private fun recognise(dataPath: String, lang: String, input: Input.Ready, dpi: Int, psm: Int): Result {
        val tess = TessBaseAPI()
        try {
            val initStart = SystemClock.elapsedRealtime()
            check(tess.init(dataPath, lang, TessBaseAPI.OEM_LSTM_ONLY)) { "init $lang from $dataPath failed" }
            val initMs = SystemClock.elapsedRealtime() - initStart
            tess.setVariable("user_defined_dpi", dpi.toString())
            tess.setPageSegMode(psm)
            System.gc()
            resetPeakRss()
            val sampler = PeakSampler().apply { start() }
            val start = SystemClock.elapsedRealtime()
            tess.setImage(input.grey, input.width, input.height, 1, input.width)
            val text = tess.getUTF8Text().orEmpty()
            val recogniseMs = SystemClock.elapsedRealtime() - start
            sampler.finish()
            val (words, count) = words(tess)
            return Result(
                text, words, initMs, recogniseMs, sampler.nativeBaseKb, sampler.nativePeakKb, sampler.pssBaseKb, sampler.pssPeakKb, peakRssKb(),
                tess.meanConfidence(), count, tess.libraryFlavor,
            )
        } finally {
            tess.recycle()
        }
    }

    /** words.tsv rows: block, paragraph and line indices (for reflowing), confidence, box (left, top, right, bottom) and the word. */
    private fun words(tess: TessBaseAPI): Pair<String, Int> {
        val sb = StringBuilder("block\tpara\tline\tconf\tleft\ttop\tright\tbottom\ttext\n")
        var count = 0
        val iter = tess.resultIterator ?: return sb.toString() to 0
        try {
            iter.begin()
            var block = -1
            var para = -1
            var line = -1
            do {
                if (iter.isAtBeginningOf(PageIteratorLevel.RIL_BLOCK)) block++
                if (iter.isAtBeginningOf(PageIteratorLevel.RIL_PARA)) para++
                if (iter.isAtBeginningOf(PageIteratorLevel.RIL_TEXTLINE)) line++
                val word = iter.getUTF8Text(PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                if (word.isEmpty()) continue
                val box = iter.getBoundingBox(PageIteratorLevel.RIL_WORD)
                sb.append("$block\t$para\t$line\t${"%.2f".format(Locale.ROOT, iter.confidence(PageIteratorLevel.RIL_WORD))}\t")
                    .append("${box[0]}\t${box[1]}\t${box[2]}\t${box[3]}\t$word\n")
                count++
            } while (iter.next(PageIteratorLevel.RIL_WORD))
        } finally {
            iter.delete()
        }
        return sb.toString() to count
    }

    private fun appendRow(file: File, header: String, row: String) {
        file.parentFile?.mkdirs()
        if (!file.isFile) file.writeText(header + "\n")
        file.appendText(row + "\n")
    }

    /** Samples the native heap every 10 ms and PSS every 250 ms (Debug.getPss reads smaps, which is too slow to poll faster). */
    private class PeakSampler : Thread("ocr-bench-sampler") {
        val nativeBaseKb = Debug.getNativeHeapAllocatedSize() / 1024
        val pssBaseKb = Debug.getPss()
        @Volatile var nativePeakKb = nativeBaseKb
        @Volatile var pssPeakKb = pssBaseKb
        @Volatile private var running = true

        override fun run() {
            var tick = 0
            while (running) {
                nativePeakKb = maxOf(nativePeakKb, Debug.getNativeHeapAllocatedSize() / 1024)
                if (tick++ % 25 == 0) pssPeakKb = maxOf(pssPeakKb, Debug.getPss())
                SystemClock.sleep(10)
            }
        }

        fun finish() {
            running = false
            join()
            nativePeakKb = maxOf(nativePeakKb, Debug.getNativeHeapAllocatedSize() / 1024)
            pssPeakKb = maxOf(pssPeakKb, Debug.getPss())
        }
    }

    /** Writing 5 to clear_refs resets VmHWM (Linux 4.0+), so the next read is the peak RSS of this recognition alone. */
    private fun resetPeakRss() {
        runCatching { File("/proc/self/clear_refs").writeText("5") }
    }

    private fun peakRssKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong()
    }.getOrDefault(-1)

    /**
     * `<page>[.<paper>][.photo][.<dpi>dpi].<png|jpg>`: `photo` marks a raw camera photo to detect and warp; a declared DPI means the image is
     * already at that DPI and is used only for it.
     */
    private data class PageName(val page: String, val paper: String?, val photo: Boolean, val dpi: Int?) {
        companion object {
            private val PATTERN = Regex("""^([^.]+)(?:\.(a4|letter|legal))?(\.photo)?(?:\.(\d+)dpi)?\.(?:png|jpe?g)$""", RegexOption.IGNORE_CASE)

            fun parse(file: String): PageName? = PATTERN.matchEntire(file)?.groupValues?.let { g ->
                PageName(g[1], g[2].ifEmpty { null }?.lowercase(), g[3].isNotEmpty(), g[4].ifEmpty { null }?.toInt())
            }
        }
    }

    // Field mode: real photos without ground truth, pushed by `run.sh --field` into `ocr-bench/field/<set>/` with an optional field.tsv
    // (page, langs, long_mm). Output goes to `ocr-field/<set>/` in the additional test output, one file set per page, so the per-chunk
    // instrumentations of run.sh never overwrite each other.

    /** Detection only: where the scanner's one-shot detection puts the quad on each photo, with previews to judge it. */
    @Test fun fieldDetect() = forEachFieldPhoto { set, out, page, image, _ ->
        val dir = File(out, "detect").apply { mkdirs() }
        val start = SystemClock.elapsedRealtime()
        val decoded = ImageCodec.decodeScaled(ImageSource.of(image), Int.MAX_VALUE)
        if (decoded == null) {
            writeTable(File(dir, "$page.detect.tsv"), DETECT_HEADER, "$set\t$page\tdecode failed\t\t\t\t\t\t")
            return@forEachFieldPhoto
        }
        StoredImage.flattenOntoWhite(decoded.pixels)
        val found = DocumentDetector.detectFromRgba(decoded.pixels, decoded.width, decoded.height)
        val ms = SystemClock.elapsedRealtime() - start
        val quad = (found as? DetectionResult.Success)?.quad
        val natural = quad?.let { PerspectiveCrop.outputSize(it) }
        writeTable(
            File(dir, "$page.detect.tsv"), DETECT_HEADER,
            "$set\t$page\t${outcome(found)}\t${decoded.width}\t${decoded.height}\t${quad?.let(::quadText).orEmpty()}\t" +
                "${quad?.let { "%.1f".format(Locale.ROOT, 100 * quadArea(it) / (decoded.width.toDouble() * decoded.height)) }.orEmpty()}\t" +
                "${natural?.let { "${it.width}x${it.height}" }.orEmpty()}\t$ms",
        )
        writePreview(rgbaPreview(decoded, quad), File(dir, "$page.quad.jpg"))
        if (quad != null) PerspectiveCrop.warpToPage(decoded, quad, PREVIEW_EDGE)?.let { writePreview(rgbaPreview(it, null), File(dir, "$page.warp.jpg")) }
    }

    @Test fun fieldFast300() = field("fast", 300)

    @Test fun fieldFast400() = field("fast", 400)

    @Test fun fieldBest300() = field("best", 300)

    @Test fun fieldBest400() = field("best", 400)

    /**
     * Per photo: detect and warp to [dpi] (from the manifest's physical long edge), Tesseract OSD on the warped grey page, rotate as OSD says,
     * then recognise in every manifest language; pages OSD rotated are also recognised unrotated (primary language) for comparison.
     */
    private fun field(model: String, dpi: Int) = forEachFieldPhoto { set, out, page, image, entry ->
        val dir = File(out, "$model-$dpi").apply { mkdirs() }
        val prepStart = SystemClock.elapsedRealtime()
        val prepared = fieldPhotoAt(image, entry.longMm, dpi, entry.frame)
        val prepMs = SystemClock.elapsedRealtime() - prepStart
        if (prepared !is FieldInput.Ready) {
            writeTable(File(dir, "$page.failures.tsv"), "set\tpage\tmodel\tdpi\treason", "$set\t$page\t$model\t$dpi\t${(prepared as FieldInput.Failed).reason}")
            Log.w(TAG, "field $set/$page $model-$dpi: ${prepared.reason}")
            return@forEachFieldPhoto
        }
        val dataPath = installModel(model)
        val osd = orientation(dataPath, prepared.input, dpi)
        val rotate = osd.result?.rotateClockwise ?: 0
        val upright = if (rotate == 0) prepared.input else rotated(prepared.input, rotate)
        if (model == "fast" && dpi == 300) writePreview(greyPreview(upright), File(out, "detect/$page.upright.jpg"))
        val rows = StringBuilder()
        fun once(lang: String, input: Input.Ready, variant: String) {
            val r = recognise(dataPath, lang, input, dpi, PSM_DEFAULT)
            val stem = if (variant == "upright") "$page.$lang" else "$page.$lang.$variant"
            File(dir, "$stem.txt").writeText(r.text + "\n")
            File(dir, "$stem.words.tsv").writeText(r.words)
            val o = osd.result
            rows.append(
                "$set\t$page\t$model\t$dpi\t$lang\t$variant\t${if (entry.frame) "frame" else "quad"}\t${entry.longMm}\t" +
                    "${prepared.photoWidth}x${prepared.photoHeight}\t${prepared.quad?.let(::quadText).orEmpty()}\t" +
                    "${"%.1f".format(Locale.ROOT, prepared.quadAreaPct)}\t${prepared.naturalWidth}x${prepared.naturalHeight}\t" +
                    "${prepared.sourceDpi.roundToInt()}\t${input.width}\t${input.height}\t$prepMs\t${osd.ms}\t${o?.degrees ?: ""}\t$rotate\t" +
                    "${o?.confidence ?: ""}\t${o?.script ?: ""}\t${o?.scriptConfidence ?: ""}\t${osd.error.orEmpty()}\t${r.initMs}\t${r.recogniseMs}\t" +
                    "${r.nativeBaseKb}\t${r.nativePeakKb}\t${r.pssBaseKb}\t${r.pssPeakKb}\t${r.hwmKb}\t${r.meanConfidence}\t${r.wordCount}\t${r.flavor}\n",
            )
            Log.i(TAG, "field $set/$page $model-$dpi $lang $variant: ${r.recogniseMs} ms, ${r.wordCount} words")
        }
        for (lang in entry.langs) once(lang, upright, "upright")
        if (rotate != 0) once(entry.langs.first(), prepared.input, "norot")
        writeTable(File(dir, "$page.runs.tsv"), FIELD_RUNS_HEADER, rows.toString().trimEnd('\n'))
    }

    /** [frame]: recognise the whole photo instead of the detected quad (for photos where detection picks the wrong region). */
    private class FieldEntry(val langs: List<String>, val longMm: Double, val frame: Boolean)

    private fun forEachFieldPhoto(block: (String, File, String, File, FieldEntry) -> Unit) {
        val root = context.getExternalFilesDir(null)?.let { File(it, "ocr-bench/field") }
        val sets = root?.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        assumeTrue("no field photos pushed to ocr-bench/field", sets.any { set -> set.listFiles().orEmpty().any(::isFieldPhoto) })
        val out = File(outputRoot(), "ocr-field")
        for (set in sets) {
            val manifest = readFieldManifest(File(set, "field.tsv"))
            for (image in set.listFiles().orEmpty().filter(::isFieldPhoto).sortedBy { it.name }) {
                val page = image.name.substringBefore('.')
                block(set.name, File(out, set.name), page, image, manifest[page] ?: FieldEntry(listOf("ita"), PAPER_MM.getValue("a4").second, false))
            }
        }
    }

    private fun isFieldPhoto(file: File): Boolean = file.isFile && FIELD_PHOTO.matches(file.name)

    /**
     * field.tsv: page, langs (comma-separated, the first is the primary), input (`quad` or `frame`), physical long edge in mm of what that
     * input covers; more columns (covers, label) are for the report only.
     */
    private fun readFieldManifest(file: File): Map<String, FieldEntry> = if (!file.isFile) emptyMap() else file.readLines().drop(1).mapNotNull { line ->
        line.split('\t').takeIf { it.size >= 4 && it[0].isNotBlank() }?.let {
            it[0] to FieldEntry(it[1].split(',').map(String::trim), it[3].toDouble(), it[2].trim() == "frame")
        }
    }.toMap()

    private sealed interface FieldInput {
        class Ready(
            val input: Input.Ready, val photoWidth: Int, val photoHeight: Int, val quad: Quad?, val quadAreaPct: Double, val naturalWidth: Int,
            val naturalHeight: Int, val sourceDpi: Double,
        ) : FieldInput

        class Failed(val reason: String) : FieldInput
    }

    /**
     * Like [photoAt], but the page size comes from the quad's own aspect and a physical long edge, not from a paper format. With [frame] the
     * whole upright photo is used instead, without detection.
     */
    private fun fieldPhotoAt(image: File, longMm: Double, dpi: Int, frame: Boolean): FieldInput = try {
        val decoded = ImageCodec.decodeScaled(ImageSource.of(image), Int.MAX_VALUE) ?: return FieldInput.Failed("cannot decode")
        StoredImage.flattenOntoWhite(decoded.pixels)
        val longPx = (longMm / MM_PER_INCH * dpi).roundToInt()
        if (frame) {
            val scale = longPx.toDouble() / max(decoded.width, decoded.height)
            val w = (decoded.width * scale).roundToInt()
            val h = (decoded.height * scale).roundToInt()
            val sourceDpi = max(decoded.width, decoded.height) / (longMm / MM_PER_INCH)
            return FieldInput.Ready(
                Input.Ready(greyResized(decoded, w, h), w, h, "frame", sourceDpi), decoded.width, decoded.height, null, 100.0, decoded.width,
                decoded.height, sourceDpi,
            )
        }
        when (val found = DocumentDetector.detectFromRgba(decoded.pixels, decoded.width, decoded.height)) {
            is DetectionResult.NotFound -> FieldInput.Failed("page not detected in ${decoded.width}x${decoded.height}")
            is DetectionResult.Failure -> FieldInput.Failed("detection failed: ${found.message}")
            is DetectionResult.Success -> {
                val natural = PerspectiveCrop.outputSize(found.quad)
                val scale = longPx.toDouble() / max(natural.width, natural.height)
                val w = (natural.width * scale).roundToInt()
                val h = (natural.height * scale).roundToInt()
                val warped = PerspectiveCrop.warpToPage(decoded, found.quad, max(w, h)) ?: return FieldInput.Failed("warp failed")
                val sourceDpi = max(natural.width, natural.height) / (longMm / MM_PER_INCH)
                FieldInput.Ready(
                    Input.Ready(greyResized(warped, w, h), w, h, "photo", sourceDpi), decoded.width, decoded.height, found.quad,
                    100 * quadArea(found.quad) / (decoded.width.toDouble() * decoded.height), natural.width, natural.height, sourceDpi,
                )
            }
        }
    } catch (e: OutOfMemoryError) {
        FieldInput.Failed("out of memory")
    }

    private class Osd(val result: TessOsd.Result?, val error: String?, val ms: Long)

    /** Tesseract OSD (legacy engine, osd.traineddata) on the warped grey page; one fresh instance, timed without the init. */
    private fun orientation(dataPath: String, input: Input.Ready, dpi: Int): Osd {
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataPath, "osd", TessBaseAPI.OEM_TESSERACT_ONLY)) return Osd(null, "init osd failed", 0)
            tess.setVariable("user_defined_dpi", dpi.toString())
            tess.setPageSegMode(PageSegMode.PSM_OSD_ONLY)
            val start = SystemClock.elapsedRealtime()
            tess.setImage(input.grey, input.width, input.height, 1, input.width)
            val (result, error) = TessOsd.detect(tess)
            return Osd(result, error, SystemClock.elapsedRealtime() - start)
        } finally {
            tess.recycle()
        }
    }

    /** [input] turned clockwise by [degrees] (90, 180 or 270). */
    private fun rotated(input: Input.Ready, degrees: Int): Input.Ready {
        val w = input.width
        val h = input.height
        val src = input.grey
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val v = src[y * w + x]
            when (degrees) {
                90 -> out[x * h + (h - 1 - y)] = v
                180 -> out[(h - 1 - y) * w + (w - 1 - x)] = v
                270 -> out[(w - 1 - x) * h + y] = v
                else -> error("rotation $degrees")
            }
        }
        val swap = degrees != 180
        return Input.Ready(out, if (swap) h else w, if (swap) w else h, input.source, input.sourceDpi)
    }

    private fun outcome(found: DetectionResult): String = when (found) {
        is DetectionResult.Success -> "found"
        is DetectionResult.NotFound -> "not found"
        is DetectionResult.Failure -> "failed: ${found.message}"
    }

    private fun quadText(q: Quad): String = q.points.joinToString(" ") { "${it.x.roundToInt()},${it.y.roundToInt()}" }

    /** Shoelace area of the quad in square pixels. */
    private fun quadArea(q: Quad): Double {
        val p = q.points
        return abs(p.indices.sumOf { i -> p[i].x * p[(i + 1) % 4].y - p[(i + 1) % 4].x * p[i].y }) / 2
    }

    private fun writeTable(file: File, header: String, rows: String) {
        file.parentFile?.mkdirs()
        file.writeText(header + "\n" + rows + "\n")
    }

    /** A box-averaged ARGB copy of [src] with the long edge at most [PREVIEW_EDGE], the [quad] drawn on it in red. */
    private fun rgbaPreview(src: RgbaImage, quad: Quad?): Bitmap {
        val f = (max(src.width, src.height) + PREVIEW_EDGE - 1) / PREVIEW_EDGE
        val w = src.width / f
        val h = src.height / f
        val argb = IntArray(w * h)
        val p = src.pixels
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val i = ((y * f + dy) * src.width + x * f + dx) * 4
                r += p[i].toInt() and 0xff
                g += p[i + 1].toInt() and 0xff
                b += p[i + 2].toInt() and 0xff
            }
            val n = f * f
            argb[y * w + x] = (0xff shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        if (quad != null) {
            val paint = Paint().apply {
                color = Color.RED
                strokeWidth = 4f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            val pts = quad.points.map { (it.x / f).toFloat() to (it.y / f).toFloat() }
            val canvas = Canvas(bitmap)
            for (i in pts.indices) canvas.drawLine(pts[i].first, pts[i].second, pts[(i + 1) % 4].first, pts[(i + 1) % 4].second, paint)
        }
        return bitmap
    }

    private fun greyPreview(input: Input.Ready): Bitmap {
        val f = (max(input.width, input.height) + PREVIEW_EDGE - 1) / PREVIEW_EDGE
        val w = input.width / f
        val h = input.height / f
        val argb = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until f) for (dx in 0 until f) sum += input.grey[(y * f + dy) * input.width + x * f + dx].toInt() and 0xff
            val v = sum / (f * f)
            argb[y * w + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun writePreview(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
    }

    private companion object {
        const val TAG = "OcrBenchmark"
        const val PREVIEW_EDGE = 1600
        val FIELD_PHOTO = Regex("""^[^.]+\.photo\.(jpe?g|png)$""", RegexOption.IGNORE_CASE)
        const val DETECT_HEADER = "set\tpage\toutcome\tphoto_w\tphoto_h\tquad\tquad_area_pct\tnatural\tdetect_ms"
        const val FIELD_RUNS_HEADER = "set\tpage\tmodel\tdpi\tlang\tvariant\tinput\tlong_mm\tphoto\tquad\tquad_area_pct\tnatural\tsource_dpi\twidth\theight\t" +
            "prep_ms\tosd_ms\tosd_deg\trotate\tosd_conf\tscript\tscript_conf\tosd_error\tinit_ms\trecognise_ms\tnative_base_kb\tnative_peak_kb\t" +
            "pss_base_kb\tpss_peak_kb\tvm_hwm_kb\tmean_conf\twords\tflavor"
        const val MM_PER_INCH = 25.4
        const val LAYOUT_PAGE = "e-layout"
        val PSM_DEFAULT = PageSegMode.PSM_AUTO
        val LAYOUT_PSMS = intArrayOf(PageSegMode.PSM_AUTO, PageSegMode.PSM_AUTO_OSD, PageSegMode.PSM_SINGLE_COLUMN, PageSegMode.PSM_SINGLE_BLOCK)
        val MODELS = listOf("fast", "best")
        val DPIS = intArrayOf(300, 400)
        val PAPER_MM = mapOf("a4" to (210.0 to 297.0), "letter" to (215.9 to 279.4), "legal" to (215.9 to 355.6))
    }
}
