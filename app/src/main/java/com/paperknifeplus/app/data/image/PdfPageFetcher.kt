package com.paperknifeplus.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.paperknifeplus.app.ui.components.BitmapPool
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "PdfPageFetcher"

/** One page to render. A data class, so [PdfPageKeyer] keys every field; [maxWidthPx] caps the output width for small cells. */
data class PdfPageRequest(
    val uri: Uri,
    val pageIndex: Int,
    val password: String? = null,
    val scale: Float = 1.0f,
    val rotation: Int = 0,
    val priority: Int = 0, // 0: prefetch, 1: the page in view
    val maxWidthPx: Int? = null
)

/** The most one rendered page may take as ARGB_8888; larger requests are scaled down, as drawing a bitmap over 100 MiB throws. */
const val MAX_RENDER_BYTES: Long = 64L * 1024 * 1024

/**
 * The largest scale up to [requestedScale] at which a [pageWidthPt] x [pageHeightPt] page renders, as floor(w * s) x floor(h * s) pixels at
 * 4 bytes each, within [maxBytes]. Both axes share the scale, so the aspect ratio is kept.
 */
fun clampRenderScale(pageWidthPt: Float, pageHeightPt: Float, requestedScale: Float, maxBytes: Long = MAX_RENDER_BYTES): Float {
    fun bytes(scale: Float) =
        floor(pageWidthPt * scale).toLong().coerceAtLeast(1) * floor(pageHeightPt * scale).toLong().coerceAtLeast(1) * 4
    if (pageWidthPt <= 0f || pageHeightPt <= 0f || bytes(requestedScale) <= maxBytes) return requestedScale
    var scale = sqrt(maxBytes / (4.0 * pageWidthPt * pageHeightPt)).toFloat()
    while (bytes(scale) > maxBytes) scale = Math.nextDown(scale)
    return scale
}

/** The scale a page of [pageWidthPt] x [pageHeightPt] (as displayed) is rendered at: [scale], capped by [maxWidthPx] and the memory budget. */
fun effectiveRenderScale(pageWidthPt: Float, pageHeightPt: Float, scale: Float, maxWidthPx: Int?): Float {
    val capped = if (maxWidthPx != null && pageWidthPt > 0f) min(scale, maxWidthPx / pageWidthPt) else scale
    return clampRenderScale(pageWidthPt, pageHeightPt, capped)
}

/**
 * Thread-safe pool of up to [perKey] renderers per key, for the [maxKeys] keys used last; each renderer serves one caller at a time, and
 * callers wait while all of a key's are busy. An evicted key's idle renderers close at once, busy ones when they are released.
 */
class RendererPool<K : Any, R : Any>(
    private val maxKeys: Int,
    private val perKey: Int,
    private val open: (K) -> R?,
    private val close: (R) -> Unit
) {
    private class Slot<R>(val renderer: R) {
        var inUse = true
        var evicted = false
    }

    private class Gate(permits: Int) {
        val permits = Semaphore(permits)
        var users = 0
    }

    private val lock = Any()
    private val entries = LinkedHashMap<K, MutableList<Slot<R>>>(16, 0.75f, true)
    private val gates = HashMap<K, Gate>()

    /** Runs [block] with a renderer for [key], opening one if none is idle; null if none could be opened. Blocking while opening. */
    suspend fun <T> use(key: K, block: suspend (R) -> T): T? {
        val gate = synchronized(lock) { gates.getOrPut(key) { Gate(perKey) }.also { it.users++ } }
        try {
            return gate.permits.withPermit {
                val slot = acquire(key) ?: return@withPermit null
                try {
                    block(slot.renderer)
                } finally {
                    release(slot)
                }
            }
        } finally {
            synchronized(lock) { if (--gate.users == 0) gates.remove(key) }
        }
    }

    /** Open renderers held by the pool, lent or idle (not counting evicted ones still lent out). */
    internal fun openCount(): Int = synchronized(lock) { entries.values.sumOf { it.size } }

    internal fun keys(): List<K> = synchronized(lock) { entries.keys.toList() }

    private fun acquire(key: K): Slot<R>? {
        synchronized(lock) {
            entries[key]?.firstOrNull { !it.inUse }?.let {
                it.inUse = true
                return it
            }
        }
        // Opened outside the lock; the gate keeps a key below perKey renderers, so the slot always fits.
        val slot = Slot(open(key) ?: return null)
        val evicted = mutableListOf<R>()
        synchronized(lock) {
            val list = entries[key] ?: mutableListOf<Slot<R>>().also {
                val iterator = entries.entries.iterator()
                while (entries.size >= maxKeys && iterator.hasNext()) {
                    val (_, slots) = iterator.next()
                    iterator.remove()
                    slots.forEach { if (it.inUse) it.evicted = true else evicted += it.renderer }
                }
                entries[key] = it
            }
            list += slot
        }
        evicted.forEach(::closeQuietly)
        return slot
    }

    private fun release(slot: Slot<R>) {
        val closeNow = synchronized(lock) {
            slot.inUse = false
            slot.evicted
        }
        if (closeNow) closeQuietly(slot.renderer)
    }

    private fun closeQuietly(renderer: R) {
        try {
            close(renderer)
        } catch (e: Exception) {
            Log.w(TAG, "Could not close a renderer", e)
        }
    }
}

/** A PdfRenderer and the descriptor it reads; closing the renderer closes the descriptor too, the explicit close is a safety net. */
class NativeRenderer(val renderer: PdfRenderer, private val pfd: ParcelFileDescriptor) {
    fun close() {
        try {
            renderer.close()
        } finally {
            pfd.close()
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): NativeRenderer? = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                try {
                    NativeRenderer(PdfRenderer(pfd), pfd)
                } catch (e: Exception) {
                    pfd.close()
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native renderer cannot open $uri", e)
            null
        }
    }
}

/** The native renderers for unencrypted PDFs: up to 4 per URI, for the 10 URIs used last. */
object NativeRendererPool {
    @Volatile
    private var pool: RendererPool<Uri, NativeRenderer>? = null

    private fun pool(context: Context): RendererPool<Uri, NativeRenderer> = pool ?: synchronized(this) {
        val app = context.applicationContext
        pool ?: RendererPool<Uri, NativeRenderer>(10, 4, { NativeRenderer.open(app, it) }, NativeRenderer::close).also { pool = it }
    }

    /** Runs [block] with a renderer for [uri], waiting while all of its renderers are busy; null if the file cannot be opened natively. */
    suspend fun <T> use(context: Context, uri: Uri, block: suspend (PdfRenderer) -> T): T? = pool(context).use(uri) { block(it.renderer) }
}

/**
 * Thread-safe pool of open documents for the [maxEntries] keys used last, each used by one holder at a time (PDFBox documents are not
 * thread-safe); an evicted document is closed once its last holder is done. Loads run outside the lock, and of two concurrent loads of one
 * key the first published is kept and the other closed.
 */
class DocumentPool<K : Any, D : Any>(private val maxEntries: Int, private val close: (D) -> Unit) {
    private class Entry<D>(val document: D) {
        val mutex = Mutex()
        var refCount = 0
        var lastUsed = 0L
        var evicted = false
    }

    private val lock = Any()
    private val entries = HashMap<K, Entry<D>>()
    private var clock = 0L

    /** Runs [block] with the document for [key], loading it with [load] if needed; null if [load] returns null. Blocking while loading. */
    suspend fun <T> withDocument(key: K, load: () -> D?, block: suspend (D) -> T): T? {
        val entry = acquire(key, load) ?: return null
        try {
            return entry.mutex.withLock { block(entry.document) }
        } finally {
            release(entry)
        }
    }

    internal fun size(): Int = synchronized(lock) { entries.size }

    private fun acquire(key: K, load: () -> D?): Entry<D>? {
        synchronized(lock) { entries[key]?.let { return it.also { it.refCount++ } } }
        val loaded = load() ?: return null
        val toClose = mutableListOf<D>()
        val entry = synchronized(lock) {
            val existing = entries[key]
            val entry = if (existing != null) {
                toClose += loaded
                existing
            } else {
                while (entries.size >= maxEntries) {
                    val (oldKey, old) = entries.entries.minBy { it.value.lastUsed }
                    entries.remove(oldKey)
                    old.evicted = true
                    if (old.refCount == 0) toClose += old.document
                }
                Entry(loaded).also {
                    it.lastUsed = ++clock
                    entries[key] = it
                }
            }
            entry.refCount++
            entry
        }
        toClose.forEach(::closeQuietly)
        return entry
    }

    private fun release(entry: Entry<D>) {
        val closeNow = synchronized(lock) {
            entry.lastUsed = ++clock
            --entry.refCount == 0 && entry.evicted
        }
        if (closeNow) closeQuietly(entry.document)
    }

    private fun closeQuietly(document: D) {
        try {
            close(document)
        } catch (e: Exception) {
            Log.w(TAG, "Could not close a document", e)
        }
    }
}

/** PDFBox documents for password-protected pages and the PDFBox fallback, keyed by URI and password. */
object PdDocumentPool {
    private data class Key(val uri: Uri, val password: String?)

    private val pool = DocumentPool<Key, PDDocument>(3) { it.close() }

    /** Runs [block] with the open document; null if it cannot be loaded (missing, wrong password, not a PDF). */
    suspend fun <T> withDocument(context: Context, uri: Uri, password: String?, block: suspend (PDDocument) -> T): T? =
        withContext(Dispatchers.IO) {
            pool.withDocument(Key(uri, password), { load(context, uri, password) }, block)
        }

    private fun load(context: Context, uri: Uri, password: String?): PDDocument? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // The parsed file is buffered in temp files rather than the heap, so large PDFs fit.
            val memory = MemoryUsageSetting.setupTempFileOnly()
            if (password != null) PDDocument.load(stream, password, memory) else PDDocument.load(stream, memory)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot load $uri", e)
        null
    }
}

// Caps concurrent renders across all documents; taken after the renderer or document, so a waiter never holds a permit.
private val renderSemaphore = Semaphore(4)

class PdfPageFetcher(
    private val context: Context,
    private val data: PdfPageRequest
) : Fetcher {

    private fun applyRotation(source: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return source

        val matrix = android.graphics.Matrix()
        matrix.postRotate(rotation.toFloat())

        val newWidth = if (rotation % 180 != 0) source.height else source.width
        val newHeight = if (rotation % 180 != 0) source.width else source.height

        val rotated = BitmapPool.get(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rotated)

        // Rotate about the source centre, then move it to the new centre.
        matrix.preTranslate(-source.width / 2f, -source.height / 2f)
        matrix.postTranslate(newWidth / 2f, newHeight / 2f)

        canvas.drawBitmap(source, matrix, null)
        BitmapPool.put(source)
        return rotated
    }

    private fun result(bitmap: Bitmap, scale: Float): DrawableResult {
        val finalBitmap = if (data.rotation != 0) applyRotation(bitmap, data.rotation) else bitmap
        return DrawableResult(
            drawable = finalBitmap.toDrawable(context.resources),
            isSampled = data.scale < 1.0f || scale < data.scale,
            dataSource = DataSource.MEMORY
        )
    }

    /** Renders with the platform renderer; null to fall back to PDFBox (the page is past the native page count). */
    private suspend fun renderNative(renderer: PdfRenderer): DrawableResult? {
        if (data.pageIndex >= renderer.pageCount) return null
        return renderSemaphore.withPermit {
                if (!currentCoroutineContext().isActive) throw CancellationException()
            renderer.openPage(data.pageIndex).use { page ->
                val scale = effectiveRenderScale(page.width.toFloat(), page.height.toFloat(), data.scale, data.maxWidthPx)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = BitmapPool.get(width, height, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                result(bitmap, scale)
            }
        }
    }

    private suspend fun renderPdfBox(document: PDDocument): DrawableResult? {
        if (data.pageIndex !in 0 until document.numberOfPages) return null
        val page = document.getPage(data.pageIndex)
        val box = page.cropBox
        val rotated = page.rotation % 180 != 0
        val scale = effectiveRenderScale(if (rotated) box.height else box.width, if (rotated) box.width else box.height, data.scale, data.maxWidthPx)
        return renderSemaphore.withPermit {
                if (!currentCoroutineContext().isActive) throw CancellationException()
            result(PDFRenderer(document).renderImage(data.pageIndex, scale, ImageType.RGB), scale)
        }
    }

    override suspend fun fetch(): FetchResult? = withContext(if (data.priority > 0) Dispatchers.IO else Dispatchers.Default) {
        try {
            // Native first; PDFBox only for passwords, files pdfium cannot open, or pages it does not see.
            if (data.password == null) {
                NativeRendererPool.use(context, data.uri) { renderNative(it) }?.let { return@withContext it }
            }
            if (!isActive) return@withContext null
            PdDocumentPool.withDocument(context, data.uri, data.password) { renderPdfBox(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot render page ${data.pageIndex} of ${data.uri}", e)
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<PdfPageRequest> {
        override fun create(data: PdfPageRequest, options: Options, imageLoader: ImageLoader): Fetcher {
            return PdfPageFetcher(context, data)
        }
    }
}
