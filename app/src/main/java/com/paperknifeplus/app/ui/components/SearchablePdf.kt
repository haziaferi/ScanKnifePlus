package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.haziaferi.scanknifeplus.ocr.OcrEngine
import com.haziaferi.scanknifeplus.ocr.OcrImages
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.TesseractOcrEngine
import com.haziaferi.scanknifeplus.scan.export.PdfTextLayer
import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.export.PdfImageRect
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SearchablePdf"
private const val POINTS_PER_INCH = 72.0
private const val MM_PER_INCH = 25.4

/** Photos have no physical size, so they are read as a page with A4's long edge, as the scanner reads a Fit page. */
internal const val PHOTO_LONG_EDGE_MM = 297.0

/** How Make PDF searchable reads: in [language], with the [reading] layout, and whether pages that already have text are read again ([redo]). */
internal data class SearchableOptions(val language: OcrLanguage, val reading: OcrReading, val redo: Boolean = false)

/**
 * What a run did to a document of [pages] pages: how many were [read] and got a text layer, how many were [kept] unchanged because they
 * already had text, and the numbers (from 1) of the pages that look [handwritten].
 */
internal data class SearchableResult(val pages: Int, val read: Int, val kept: Int, val handwritten: List<Int>)

/** Page images of one PDF, for reading. */
internal interface PageRenders : Closeable {
    /** Writes page [index] as displayed (its cropBox, /Rotate applied), [width] x [height] on white, losslessly to [dest]; false if it cannot. Blocking. */
    fun write(index: Int, width: Int, height: Int, dest: File): Boolean
}

/** [PageRenders] from the platform renderer, as Grayscale and Redact rasterize: a protected file is opened through a decrypted cache copy. */
internal class NativePageRenders(context: Context, uri: Uri, password: String?) : PageRenders {
    private val rasterizer = NativePageRasterizer(context, uri, password)

    override fun write(index: Int, width: Int, height: Int, dest: File): Boolean {
        val bitmap = try {
            rasterizer.render(index, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Page ${index + 1} could not be rendered", e)
            return false
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Page ${index + 1} could not be rendered", e)
            return false
        }
        return try {
            FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: IOException) {
            Log.w(TAG, "Page ${index + 1} could not be written", e)
            false
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = rasterizer.close()
}

/**
 * The transform from the displayed page (points from its lower-left corner, y up) to user space: [displayToPdf] as a matrix, so a whole layer
 * drawn as the page is seen lands on it whatever its cropBox origin and /Rotate.
 */
internal fun PageBox.displayMatrix(): Matrix = when (rotation) {
    90 -> Matrix(0f, 1f, -1f, 0f, left + width, bottom)
    180 -> Matrix(-1f, 0f, 0f, -1f, left + width, bottom + height)
    270 -> Matrix(0f, -1f, 1f, 0f, left, bottom + height)
    else -> Matrix(1f, 0f, 0f, 1f, left, bottom)
}

/** The displayed page's long edge in millimetres, its real size. */
internal fun PageBox.longEdgeMm(): Double = max(displayWidth, displayHeight) / POINTS_PER_INCH * MM_PER_INCH

/**
 * The size a page is rendered at for reading: [TesseractOcrEngine.DPI] of a page whose long edge is [longEdgeMm], in the displayed page's
 * shape. It is the size the engine scales every page to, so the engine's own scaling leaves the render as it is.
 */
internal fun readingSize(box: PageBox, longEdgeMm: Double): PageSize {
    // Only the shape counts here, taken to a thousandth of a point so the short edge does not drift by a pixel.
    val width = (box.displayWidth * ASPECT_SCALE).roundToInt().coerceAtLeast(1)
    val height = (box.displayHeight * ASPECT_SCALE).roundToInt().coerceAtLeast(1)
    return OcrImages.targetSize(width, height, longEdgeMm, TesseractOcrEngine.DPI)
}

private const val ASPECT_SCALE = 1000f

/** Whether page [index] of [document] already has text: PDFTextStripper finds some that is not blank. A page it cannot read counts as without. */
internal fun pageHasText(document: PDDocument, index: Int): Boolean = try {
    PDFTextStripper().apply {
        startPage = index + 1
        endPage = index + 1
    }.getText(document).isNotBlank()
} catch (e: Exception) {
    Log.w(TAG, "Could not look for text on page ${index + 1}", e)
    false
}

/** The cache folder a run keeps its page images and the photos' PDF in; [clearSearchableWork] empties what a stopped process left there. */
internal fun searchableWorkDir(context: Context): File = File(context.cacheDir, "searchable").apply { mkdirs() }

/** Deletes what earlier runs left in [searchableWorkDir]; call before a run starts, never during one. */
internal fun clearSearchableWork(context: Context) {
    searchableWorkDir(context).listFiles()?.forEach { it.delete() }
}

/**
 * Writes [source], opened with [password] (empty for none), to [output] with the recognised text of its pages as an invisible layer over
 * them; its own content stays as it is, and a protected file stays as protected ([sameProtection]). A page that already has text is copied
 * unchanged unless [options] say to redo it: then its old invisible text ([InvisibleTextStripper]) gives way to the new layer, and its visible
 * text stays. Each page is rendered at 200 DPI of its real size ([longEdgeMm] overrides it, for photos) by [renders] and read by one engine
 * from [openEngine], closed at the end. Between pages it waits while [running] is false (the app in the background), after the page being
 * read; cancelling cancels that read too. [onPage] gets each page as its reading starts (number from 1, total). Throws on failure, and
 * nothing is written then or on cancel. It runs on [dispatcher].
 */
internal suspend fun makeSearchable(
    context: Context,
    source: Uri,
    password: String,
    output: Uri,
    options: SearchableOptions,
    openEngine: () -> OcrEngine,
    running: StateFlow<Boolean>,
    longEdgeMm: Double? = null,
    renders: (Uri, String?) -> PageRenders = { uri, pass -> NativePageRenders(context, uri, pass) },
    onPage: (Int, Int) -> Unit = { _, _ -> },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): SearchableResult = withContext(dispatcher) {
    val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
    context.contentResolver.requireInputStream(source).use { input ->
        PDDocument.load(input, password, memory).use { document ->
            val protection = document.sameProtection(password)
            val result = renders(source, if (document.isEncrypted) password else null).use { pages ->
                SearchableReader(context, document, pages, options, longEdgeMm).read(openEngine, running, onPage)
            }
            protection?.let(document::protect)
            saveAndFlush(context, document, output)
            result
        }
    }
}

/**
 * [makeSearchable] for photos: a new PDF with one page per readable photo in [images], upright and placed as Images to PDF places them (Fit),
 * each read as a page with A4's long edge. Throws if none could be read.
 */
internal suspend fun makePhotosSearchable(
    context: Context,
    images: List<Uri>,
    output: Uri,
    options: SearchableOptions,
    openEngine: () -> OcrEngine,
    running: StateFlow<Boolean>,
    renders: (Uri, String?) -> PageRenders = { uri, pass -> NativePageRenders(context, uri, pass) },
    onPage: (Int, Int) -> Unit = { _, _ -> },
): SearchableResult = withContext(Dispatchers.IO) {
    val pdf = File.createTempFile("photos", ".pdf", searchableWorkDir(context))
    try {
        convertImagesToPdf(context, images, Uri.fromFile(pdf), "Fit") { _, _ -> }
        makeSearchable(context, Uri.fromFile(pdf), "", output, options, openEngine, running, PHOTO_LONG_EDGE_MM, renders, onPage)
    } finally {
        pdf.delete()
    }
}

/** One run's reading of [document]'s pages into text layers; see [makeSearchable]. */
private class SearchableReader(
    private val context: Context,
    private val document: PDDocument,
    private val pages: PageRenders,
    private val options: SearchableOptions,
    private val longEdgeMm: Double?,
) {
    private val engine = AtomicReference<OcrEngine?>()
    private var font: PDFont? = null
    private val stripper by lazy { InvisibleTextStripper(document) }

    /** The resources dictionaries, and /Font dictionaries in them, that more than one page uses, as the document was loaded. */
    private val sharedResources by lazy {
        val seen = identitySetOf<COSDictionary>()
        val shared = identitySetOf<COSDictionary>()
        for (page in document.pages) {
            val resources = page.resources?.cosObject ?: continue
            if (!seen.add(resources)) shared += resources
            else (resources.getDictionaryObject(COSName.FONT) as? COSDictionary)?.let { if (!seen.add(it)) shared += it }
        }
        shared
    }

    suspend fun read(openEngine: () -> OcrEngine, running: StateFlow<Boolean>, onPage: (Int, Int) -> Unit): SearchableResult =
        coroutineScope {
            val total = document.numberOfPages
            var read = 0
            var kept = 0
            val handwritten = mutableListOf<Int>()
            // Tesseract blocks its thread while it reads, so a cancel reaches it through the engine rather than the coroutine.
            val finished = AtomicBoolean(false)
            // Started undispatched: a watcher still waiting to be dispatched when the run is cancelled would never run its finally block.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) engine.get()?.cancel()
                }
            }
            try {
                for (index in 0 until total) {
                    ensureActive()
                    if (!options.redo && pageHasText(document, index)) {
                        kept++
                        continue
                    }
                    running.first { it }
                    onPage(index + 1, total)
                    val ocr = readPage(index, openEngine) { isActive }
                    ensureActive()
                    if (ocr == null) continue
                    read++
                    if (ocr.looksHandwritten) handwritten += index + 1
                    if (ocr.words.isNotEmpty()) drawLayer(index, ocr)
                }
                SearchableResult(total, read, kept, handwritten)
            } finally {
                finished.set(true)
                watcher.cancel()
                engine.getAndSet(null)?.let { e -> withContext(NonCancellable) { e.close() } }
            }
        }

    /** Page [index] rendered and read, or null when it could not be or the run stopped being [active]; its image is deleted either way. Blocking. */
    private fun readPage(index: Int, openEngine: () -> OcrEngine, active: () -> Boolean): OcrPage? {
        val box = PageBox.of(document.getPage(index))
        val longEdge = longEdgeMm ?: box.longEdgeMm()
        val size = readingSize(box, longEdge)
        val image = File.createTempFile("page", ".png", searchableWorkDir(context))
        return try {
            if (!pages.write(index, size.width, size.height, image)) return null
            val e = engine.get() ?: openEngine().also { engine.set(it) }
            // A cancel that came while the engine was opening had nothing to cancel yet.
            if (!active()) return null
            e.read(image, longEdge, options.language, options.reading)
        } catch (e: Exception) {
            Log.w(TAG, "Page ${index + 1} could not be read", e)
            null
        } finally {
            image.delete()
        }
    }

    /**
     * Appends [ocr]'s words to page [index] as an invisible layer drawn as the page is displayed, over the whole displayed page; when redoing,
     * the page's old invisible text goes first, so searches and copies do not find it twice.
     */
    private fun drawLayer(index: Int, ocr: OcrPage) {
        val page = document.getPage(index)
        val shared = sharedResources
        if (options.redo) stripper.strip(index)
        ownFonts(page, shared)
        val box = PageBox.of(page)
        val f = font ?: PdfTextLayer.loadFont(document).also { font = it }
        document.appendTo(page).use { stream ->
            stream.transform(box.displayMatrix())
            PdfTextLayer.draw(stream, f, ocr, PdfImageRect(0.0, 0.0, box.displayWidth.toDouble(), box.displayHeight.toDouble()))
        }
    }

    /**
     * Gives [page] a /Font dictionary of its own before its layer adds a font there, when it inherits its resources or shares them, or their
     * /Font dictionary, with another page ([shared]); other pages' resources stay as they are.
     */
    private fun ownFonts(page: PDPage, shared: Set<COSDictionary>) {
        val resources = page.resources?.cosObject ?: return
        val fonts = resources.getDictionaryObject(COSName.FONT) as? COSDictionary
        if (!page.cosObject.containsKey(COSName.RESOURCES) || resources in shared) page.resources = PDResources(resources.copyWithOwn(COSName.FONT))
        else if (fonts != null && fonts in shared) resources.setItem(COSName.FONT, COSDictionary(fonts))
    }
}
