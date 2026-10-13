package com.paperknifeplus.app.ui.components

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.paperknifeplus.app.data.image.PdDocumentPool
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.IdentityHashMap
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "ToolUtils"

data class UriDetails(
    val name: String,
    val size: String,
    val sizeBytes: Long = 0
)

data class PdfLink(
    val pageIndex: Int,
    val rect: PDRectangle,
    val url: String
)

object PreferencesManager {
    fun setDefaultAuthor(context: Context, name: String) {
        val prefs = context.getSharedPreferences("pk_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("default_author", name).apply()
    }
    fun getDefaultAuthor(context: Context): String {
        val prefs = context.getSharedPreferences("pk_prefs", Context.MODE_PRIVATE)
        return prefs.getString("default_author", "") ?: ""
    }

    fun setTheme(context: Context, mode: Int) {
        val prefs = context.getSharedPreferences("pk_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("theme_mode", mode).apply()
    }
    fun getTheme(context: Context): Int {
        val prefs = context.getSharedPreferences("pk_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("theme_mode", 0) // 0: System, 1: Light, 2: Dark
    }
}

object LayoutMath {
    fun getFitRect(containerWidth: Float, containerHeight: Float, pageRatio: Float): android.graphics.RectF {
        val containerRatio = containerWidth / containerHeight
        return if (pageRatio > containerRatio) {
            val h = containerWidth / pageRatio
            val top = (containerHeight - h) / 2
            android.graphics.RectF(0f, top, containerWidth, top + h)
        } else {
            val w = containerHeight * pageRatio
            val left = (containerWidth - w) / 2
            android.graphics.RectF(left, 0f, left + w, containerHeight)
        }
    }
}

/** Reusable page bitmaps by size, so scrolling through thumbnails allocates less. Thread-safe. */
object BitmapPool {
    private val pool = mutableMapOf<Triple<Int, Int, Bitmap.Config>, MutableList<Bitmap>>()
    private val maxPoolCount: Int by lazy {
        val memoryClass = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        if (memoryClass > 512) 24 else 8
    }
    private var currentCount = 0

    fun get(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        synchronized(this) {
            val key = Triple(width, height, config)
            val list = pool[key]
            if (list != null && list.isNotEmpty()) {
                val bitmap = list.removeAt(list.size - 1)
                currentCount--
                return bitmap
            }
        }
        return Bitmap.createBitmap(width, height, config)
    }

    fun put(bitmap: Bitmap) {
        if (!bitmap.isMutable) return
        val config = bitmap.config ?: return
        synchronized(this) {
            if (currentCount >= maxPoolCount) {
                val randomKey = pool.keys.firstOrNull() ?: return
                pool[randomKey]?.removeFirstOrNull()?.recycle()
                currentCount--
            }
            val key = Triple(bitmap.width, bitmap.height, config)
            pool.getOrPut(key) { mutableListOf() }.add(bitmap)
            currentCount++
        }
    }
}

@SuppressLint("Range")
fun getUriDetails(context: Context, uri: Uri): UriDetails {
    var name = "Document.pdf"
    var size = "Unknown size"
    var bytes: Long = 0

    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (nameIndex != -1) {
                    val rawName = cursor.getString(nameIndex)
                    if (rawName != null) name = rawName
                }
                if (sizeIndex != -1) {
                    bytes = cursor.getLong(sizeIndex)
                    size = formatSize(bytes)
                }
            }
        }
    } catch (e: Exception) {
        uri.lastPathSegment?.let { name = it }
    }

    if (!name.contains(".")) name += ".pdf"
    return UriDetails(name, size, bytes)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

/** The page count of [uri] opened with [password], read through the shared document pool; 0 if it cannot be opened. */
suspend fun getPageCount(context: Context, uri: Uri, password: String?): Int = try {
    PdDocumentPool.withDocument(context, uri, password) { it.numberOfPages } ?: 0
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Cannot count the pages of $uri", e)
    0
}

private fun loadPdf(context: Context, uri: Uri, password: String?, block: (PDDocument) -> Unit) {
    context.contentResolver.requireInputStream(uri).use { input ->
        (if (password != null) PDDocument.load(input, password) else PDDocument.load(input)).use(block)
    }
}

/**
 * Writes a gray copy of [inputUri], opened with [password], to [outputUri] with [GrayscaleConverter], which keeps text, links, forms, outline and
 * metadata; returns how many pages could only be stored as images. Encryption is removed, as before. Pages are buffered in temp files.
 */
suspend fun performGrayscaleRewrite(context: Context, inputUri: Uri, outputUri: Uri, password: String?, onProgress: (Int, Int) -> Unit): Int =
    withContext(Dispatchers.IO) {
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        context.contentResolver.requireInputStream(inputUri).use { input ->
            PDDocument.load(input, password.orEmpty(), memory).use { document ->
                val encrypted = document.isEncrypted
                if (encrypted) document.isAllSecurityToBeRemoved = true
                NativePageRasterizer(context, inputUri, if (encrypted) password.orEmpty() else null).use { rasterizer ->
                    val fallbacks = GrayscaleConverter(document, rasterizer).convert(onProgress)
                    saveAndFlush(context, document, outputUri)
                    fallbacks
                }
            }
        }
    }

/** Encoded size of [image] plus its soft mask, the bytes it adds to the file. */
private fun encodedSize(image: PDImageXObject): Long =
    image.cosObject.length + ((image.cosObject.getDictionaryObject(COSName.SMASK) as? COSStream)?.length ?: 0L)

/**
 * Re-encodes the images of [document] as JPEG at [quality], scaled by [downscale]. Text and vectors stay as they are; so do 1-bit images
 * and stencil masks (JPEG would blur them and drop a stencil's fill colour) and any image the JPEG would not make smaller.
 */
internal fun compressImages(document: PDDocument, quality: Float, downscale: Float, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
    // Keyed by the image stream itself, so an image shared by several pages or forms gets the same result everywhere.
    val processed = IdentityHashMap<COSStream, PDImageXObject>()

    fun processResources(resources: PDResources?) {
        if (resources == null) return
        for (name in resources.xObjectNames) {
            try {
                when (val xobject = resources.getXObject(name)) {
                    is PDImageXObject -> {
                        val done = processed[xobject.cosObject]
                        if (done != null) {
                            resources.put(name, done)
                            continue
                        }
                        if (xobject.isStencil || xobject.bitsPerComponent == 1) {
                            processed[xobject.cosObject] = xobject
                            continue
                        }
                        var bitmap = xobject.image ?: continue
                        if (downscale < 1.0f) {
                            val w = (bitmap.width * downscale).toInt().coerceAtLeast(1)
                            val h = (bitmap.height * downscale).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                            bitmap.recycle()
                            bitmap = scaled
                        }
                        val newImage = JPEGFactory.createFromImage(document, bitmap, quality)
                        bitmap.recycle()
                        val kept = if (encodedSize(newImage) < encodedSize(xobject)) newImage else xobject
                        resources.put(name, kept)
                        processed[xobject.cosObject] = kept
                    }
                    is PDFormXObject -> processResources(xobject.resources)
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Leaving image $name as it is", e)
            }
        }
    }

    val total = document.numberOfPages
    document.pages.forEachIndexed { i, page ->
        onProgress(i + 1, total)
        processResources(page.resources)
    }
}

suspend fun compressPdf(context: Context, inputUri: Uri, outputUri: Uri, password: String?, level: String, onProgress: (Int, Int) -> Unit) =
    withContext(Dispatchers.IO) {
        loadPdf(context, inputUri, password) { document ->
            if (document.isEncrypted) document.isAllSecurityToBeRemoved = true
            val quality = when (level) { "Extreme" -> 0.4f; "Recommended" -> 0.7f; else -> 0.9f }
            val downscale = when (level) { "Extreme" -> 0.6f; "Recommended" -> 0.8f; else -> 1.0f }
            compressImages(document, quality, downscale, onProgress)
            saveAndFlush(context, document, outputUri)
        }
    }

/** Fit pages show the image at the size it would print at this resolution. */
private const val IMAGE_PAGE_DPI = 300f

/** Quality of JPEGs Image to PDF has to re-encode. */
private const val IMAGE_JPEG_QUALITY = 0.9f

/**
 * Writes one page per image in [imageUris]: on Fit pages the image's size at 300 DPI, on A4 pages centred. Baseline colour JPEGs that are
 * upright or turned a quarter, half or three quarters are embedded byte for byte, without any header carrying GPS, and the turn becomes the
 * page's /Rotate; other JPEGs are decoded upright and re-encoded, and every other format is stored losslessly. Images that cannot be read
 * are skipped. Pages are buffered in temp files. Returns the number of pages written; throws if there are none.
 */
suspend fun convertImagesToPdf(context: Context, imageUris: List<Uri>, outputUri: Uri, pageSize: String, onProgress: (Int, Int) -> Unit): Int =
    withContext(Dispatchers.IO) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
            imageUris.forEachIndexed { index, uri ->
                onProgress(index + 1, imageUris.size)
                val placed = embedImage(context, document, uri)
                if (placed == null) {
                    Log.w(TAG, "Skipping an image that cannot be decoded: $uri")
                    return@forEachIndexed
                }
                addImagePage(document, placed, pageSize == "A4")
            }
            val pages = document.numberOfPages
            if (pages == 0) throw IOException("None of the images could be read")
            saveAndFlush(context, document, outputUri)
            pages
        }
    }

/** An embedded image and the clockwise turn, in degrees, its page needs to show it upright. */
private class PlacedImage(val image: PDImageXObject, val rotation: Int)

private fun embedImage(context: Context, document: PDDocument, uri: Uri): PlacedImage? {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    } ?: return null
    val jpeg = JpegInfo.read(bytes)
    // pdfbox labels every JPEG DeviceRGB, so only 3-component ones can be passed through; mirrored orientations have no /Rotate equivalent.
    if (jpeg != null && jpeg.baseline && jpeg.components == 3) {
        val rotation = when (jpeg.orientation) {
            ExifInterface.ORIENTATION_NORMAL -> 0
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> null
        }
        if (rotation != null) return PlacedImage(JPEGFactory.createFromByteArray(document, withoutGpsSegments(bytes)), rotation)
    }
    val bitmap = ImageCodec.decodeUpright(ImageSource { bytes.inputStream() }) ?: return null
    return try {
        val image = if (jpeg != null) JPEGFactory.createFromImage(document, bitmap, IMAGE_JPEG_QUALITY) else LosslessFactory.createFromImage(document, bitmap)
        PlacedImage(image, 0)
    } finally {
        bitmap.recycle()
    }
}

private fun addImagePage(document: PDDocument, placed: PlacedImage, a4: Boolean) {
    val image = placed.image
    val turned = placed.rotation == 90 || placed.rotation == 270
    // The page is laid out in the image's stored orientation; /Rotate then turns page and image upright together.
    val rect = when {
        !a4 -> PDRectangle(image.width * 72f / IMAGE_PAGE_DPI, image.height * 72f / IMAGE_PAGE_DPI)
        turned -> PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
        else -> PDRectangle.A4
    }
    val page = PDPage(rect)
    page.rotation = placed.rotation
    document.addPage(page)
    val scale = minOf(rect.width / image.width, rect.height / image.height)
    val width = image.width * scale
    val height = image.height * scale
    PDPageContentStream(document, page).use { it.drawImage(image, (rect.width - width) / 2, (rect.height - height) / 2, width, height) }
}

suspend fun convertPdfToImages(
    context: Context,
    pdfUri: Uri,
    outputUri: Uri,
    password: String?,
    selectedPages: List<Int>,
    format: String,
    quality: String,
    onProgress: (Int, Int) -> Unit
) = withContext(Dispatchers.IO) {
    // Protected files are decrypted to a cache copy, which the native renderer can open; the copy is deleted whatever happens.
    val decrypted = if (password != null) decryptToCache(context, pdfUri, password) else null
    try {
        val workingUri = decrypted ?: pdfUri
        context.contentResolver.requireOutputStream(outputUri).use { outputStream ->
            ZipOutputStream(outputStream).use { zipOut ->
                val pfd = context.contentResolver.openFileDescriptor(workingUri, "r") ?: throw IOException("Cannot read $workingUri")
                // PdfRenderer closes the descriptor itself, but not when its constructor throws.
                val opened = try {
                    PdfRenderer(pfd)
                } catch (e: Exception) {
                    pfd.close()
                    throw e
                }
                opened.use { renderer ->
                    val scale = when (quality) { "Ultra HD" -> 3.0f; "HD" -> 2.0f; "Standard" -> 1.5f; else -> 1.0f }
                    val cf = when (format) {
                        "PNG" -> Bitmap.CompressFormat.PNG
                        "WebP" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                        else -> Bitmap.CompressFormat.JPEG
                    }
                    val ext = format.lowercase()

                    selectedPages.forEachIndexed { index, pageIdx ->
                        if (pageIdx < renderer.pageCount) {
                            onProgress(index + 1, selectedPages.size)
                            renderer.openPage(pageIdx).use { page ->
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                                // Both modes, so content marked for screen only or for print only is in the image.
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                                zipOut.putNextEntry(ZipEntry("page_${pageIdx + 1}.$ext"))
                                bitmap.compress(cf, if (format == "PNG") 100 else 90, zipOut)
                                zipOut.closeEntry()
                                bitmap.recycle()
                            }
                        }
                    }
                }
                zipOut.finish()
            }
        }
    } finally {
        deleteDecryptedCopy(context, decrypted)
    }
}

/**
 * Writes [document] to [outputUri]; [applyDefaults] fills a blank author with the default author, and the Metadata tool turns it off so a
 * cleared author stays cleared. The app writes no Creator or Producer of its own. Throws if the output cannot be opened or written, and
 * closes [document] either way (closing again is harmless, so `use {}` is fine).
 */
fun saveAndFlush(context: Context, document: PDDocument, outputUri: Uri, applyDefaults: Boolean = true) {
    try {
        if (applyDefaults) {
            val autoAuthor = PreferencesManager.getDefaultAuthor(context)
            if (autoAuthor.isNotEmpty() && document.documentInformation.author.isNullOrBlank()) document.documentInformation.author = autoAuthor
        }

        // "wt" truncates a reused file and falls back to "w"; "rwt" left 0-byte files on some devices (commit 0461d05).
        context.contentResolver.requireOutputStream(outputUri).use { os ->
            document.save(os)
            os.flush()
            if (os is FileOutputStream) {
                try {
                    os.fd.sync()
                } catch (e: IOException) {
                    Log.w(TAG, "Could not sync $outputUri", e)
                }
            }
        }
    } finally {
        document.close()
    }

    // Lets a MediaStore URI report its new size; document providers reject the update, which is expected.
    try {
        context.contentResolver.update(outputUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        Log.d(TAG, "No MediaStore update for $outputUri", e)
    }
}

suspend fun repairPdf(context: Context, inputUri: Uri, outputUri: Uri, password: String?) = withContext(Dispatchers.IO) {
    loadPdf(context, inputUri, password) { document ->
        document.isAllSecurityToBeRemoved = true
        saveAndFlush(context, document, outputUri)
    }
}

/**
 * Saves [document] as a new `decrypted_*.pdf` in [dir] and returns it. A partly written file is deleted before the failure is rethrown.
 */
internal fun saveDecryptedCopy(document: PDDocument, dir: File): File {
    val file = File.createTempFile("decrypted_", ".pdf", dir)
    try {
        FileOutputStream(file).use { document.save(it) }
        return file
    } catch (e: Throwable) {
        file.delete()
        throw e
    }
}

/**
 * Writes an unencrypted copy of [uri], opened with [password], to the cache folder and returns its file:// URI, or null if it cannot. The
 * native renderer cannot open protected files, so tools decrypt once and work on the copy; delete it with [deleteDecryptedCopy].
 */
suspend fun decryptToCache(context: Context, uri: Uri, password: String): Uri? = withContext(Dispatchers.IO) {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        input.use {
            PDDocument.load(it, password).use { document ->
                document.isAllSecurityToBeRemoved = true
                Uri.fromFile(saveDecryptedCopy(document, context.cacheDir))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot decrypt $uri", e)
        null
    }
}
