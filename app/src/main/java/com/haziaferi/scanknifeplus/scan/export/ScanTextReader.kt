package com.haziaferi.scanknifeplus.scan.export

import android.util.Log
import com.haziaferi.scanknifeplus.ocr.OcrEngine
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.OcrWord
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.library.ScanPage
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How a save reads its pages: in [language], with the [reading] layout, each as a page whose long edge is [longEdgeMm] millimetres. */
data class OcrSettings(val language: OcrLanguage, val reading: OcrReading, val longEdgeMm: Double) {
    companion object {
        /**
         * The long edge OCR assumes for pages saved at [size]: that size's own (A4 297 mm, Letter 279.4, Legal 355.6), and A4's for Fit; to the
         * tenth of a millimetre, so the cache key is the same number however the points were converted.
         */
        fun longEdgeMm(size: PdfPageSize): Double = Math.round(maxOf(size.width, size.height) / POINTS_PER_INCH * MM_PER_INCH * 10) / 10.0

        private const val POINTS_PER_INCH = 72.0
        private const val MM_PER_INCH = 25.4
    }
}

/** The read pages of one save: [pages] in document order and what was [read] for them, by page image name (a page may have nothing). */
class ScanText(val pages: List<ScanPage>, val read: Map<String, OcrPage>) {
    /** The page numbers (from 1) whose text looks handwritten, for the notice after the save. */
    val handwritten: List<Int> get() = pages.mapIndexedNotNull { i, p -> (i + 1).takeIf { read[p.image]?.looksHandwritten == true } }

    /** The .txt copy for [pages] (by default the ones read; a folder save passes the pages its PDF ended up with). */
    fun txt(pages: List<ScanPage> = this.pages): String = join(pages.map { read[it.image] })

    companion object {
        /**
         * Pages in order, each its text without words below [OcrPage.TEXT_FILE_MIN_CONFIDENCE] and a line break, separated by a form feed, the
         * page break pdftotext and Tesseract's own text output use; a page without text still counts, so page numbers stay countable.
         */
        fun join(pages: List<OcrPage?>): String = pages.joinToString(PAGE_BREAK) { page ->
            page?.text(OcrPage.TEXT_FILE_MIN_CONFIDENCE).orEmpty().let { if (it.isEmpty()) it else "$it\n" }
        }

        const val PAGE_BREAK = "\u000C"
    }
}

/**
 * Read pages kept in document [id]'s folder of the scan library, one JSON file per page image, so a save the system stopped resumes where it
 * was. An entry counts only for the same image name (an edit always gives a page a new one), language, reading and long edge. Deleting the
 * document deletes them; a save that succeeds clears them. Blocking, and never throws.
 */
class OcrCache(private val library: ScanLibrary, private val id: String) {
    /** The cached read of page image [image] with [settings], or null. */
    fun get(image: String, settings: OcrSettings): OcrPage? {
        val file = fileOf(image) ?: return null
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val key = json.getJSONObject("key")
            if (key.getString("image") != image || key.getString("language") != settings.language.name ||
                key.getString("reading") != settings.reading.name || key.getDouble("longEdgeMm") != settings.longEdgeMm
            ) {
                null
            } else {
                pageOf(json.getJSONObject("page"))
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    /** Keeps [page] as the read of [image] with [settings], written beside its name then renamed, so a kill never leaves half an entry. */
    fun put(image: String, settings: OcrSettings, page: OcrPage) {
        val file = fileOf(image) ?: return
        val key = JSONObject().put("image", image).put("language", settings.language.name).put("reading", settings.reading.name)
            .put("longEdgeMm", settings.longEdgeMm)
        val tmp = File(file.path + ".tmp")
        try {
            // FileOutputStream never creates the folder, so a document deleted meanwhile stays gone.
            FileOutputStream(tmp).use { it.write(JSONObject().put("key", key).put("page", jsonOf(page)).toString().toByteArray()) }
            if (!tmp.renameTo(file) && !(file.delete() && tmp.renameTo(file))) tmp.delete()
        } catch (e: IOException) {
            Log.w(TAG, "Could not keep the read of $image", e)
            tmp.delete()
        } catch (e: JSONException) {
            Log.w(TAG, "Could not keep the read of $image", e)
        }
    }

    /** Removes every entry of the document, and any half-written one. */
    fun clear() {
        val folder = fileOf("x")?.parentFile ?: return
        folder.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { it.delete() }
    }

    private fun fileOf(image: String): File? = try {
        library.file(id, "$PREFIX$image.json")
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val PREFIX = "ocr_"
        const val TAG = "OcrCache"

        fun jsonOf(page: OcrPage): JSONObject = JSONObject().put("turnedClockwise", page.turnedClockwise).put(
            "words",
            JSONArray(page.words.map { w ->
                JSONArray().put(w.text).put(w.left.toDouble()).put(w.top.toDouble()).put(w.right.toDouble()).put(w.bottom.toDouble())
                    .put(w.confidence.toDouble()).put(w.block).put(w.paragraph).put(w.line)
            }),
        )

        fun pageOf(json: JSONObject): OcrPage {
            val words = json.getJSONArray("words")
            return OcrPage(
                (0 until words.length()).map { i ->
                    val w = words.getJSONArray(i)
                    OcrWord(
                        w.getString(0), w.getDouble(1).toFloat(), w.getDouble(2).toFloat(), w.getDouble(3).toFloat(), w.getDouble(4).toFloat(),
                        w.getDouble(5).toFloat(), w.getInt(6), w.getInt(7), w.getInt(8),
                    )
                },
                json.getInt("turnedClockwise"),
            )
        }
    }
}

/**
 * Reads a save's pages with one engine, opened only if a page is not in the [OcrCache] and closed at the end, every engine call on [io]. Between
 * pages it waits while [running] is false (the app in the background): the page being read finishes first. [stop] ends it.
 */
class ScanTextReader(
    private val library: ScanLibrary,
    private val openEngine: () -> OcrEngine,
    private val io: CoroutineContext,
    private val running: StateFlow<Boolean>,
) {
    private val stopped = MutableStateFlow(false)

    @Volatile private var engine: OcrEngine? = null

    /** Stops the reading: a read in progress is cancelled ([OcrEngine.cancel]) and [read] returns null. Safe from any thread. */
    fun stop() {
        stopped.value = true
        engine?.cancel()
    }

    /**
     * Reads [pages] of document [id] with [settings], reporting each page as it starts to [onPage] (its number from 1, and the total), and
     * keeps each read in the cache as soon as it is done. Returns the reads by page image name, without the pages that could not be read, or
     * null if [stop] was called.
     */
    suspend fun read(id: String, pages: List<ScanPage>, settings: OcrSettings, onPage: (Int, Int) -> Unit): Map<String, OcrPage>? {
        val cache = OcrCache(library, id)
        val read = withContext(io) { pages.mapNotNull { p -> cache.get(p.image, settings)?.let { p.image to it } }.toMap(HashMap()) }
        try {
            for ((i, page) in pages.withIndex()) {
                if (page.image in read) continue
                combine(running, stopped) { run, stop -> run || stop }.first { it }
                if (stopped.value) return null
                onPage(i + 1, pages.size)
                val ocr = withContext(io) { readPage(id, page, settings) }
                if (stopped.value) return null
                if (ocr != null) {
                    read[page.image] = ocr
                    withContext(io) { cache.put(page.image, settings, ocr) }
                }
            }
            return read
        } finally {
            engine?.let { e ->
                engine = null
                withContext(NonCancellable + io) { e.close() }
            }
        }
    }

    private fun readPage(id: String, page: ScanPage, settings: OcrSettings): OcrPage? = try {
        val e = engine ?: openEngine().also { engine = it }
        // A stop that came while the engine was opening had nothing to cancel yet.
        if (stopped.value) null else e.read(library.file(id, page.image), settings.longEdgeMm, settings.language, settings.reading)
    } catch (e: Exception) {
        Log.w(TAG, "Page ${page.id} could not be read", e)
        null
    }

    private companion object {
        const val TAG = "ScanTextReader"
    }
}
