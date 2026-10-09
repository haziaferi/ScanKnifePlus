package com.haziaferi.scanknifeplus.scan.capture

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Encoded image bytes that can be opened more than once (the header and the pixels are read with separate streams). */
fun interface ImageSource {
    fun open(): InputStream

    companion object {
        fun of(file: File) = ImageSource { file.inputStream() }

        fun of(resolver: ContentResolver, uri: Uri) = ImageSource { resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri") }
    }
}

/** An image's stored size and EXIF orientation, read without decoding pixels. */
class ImageHeader(val rawWidth: Int, val rawHeight: Int, val orientation: Int) {
    val swapsAxes: Boolean
        get() = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

    /** The size as displayed, with the EXIF orientation applied. */
    val oriented: PageSize get() = if (swapsAxes) PageSize(rawHeight, rawWidth) else PageSize(rawWidth, rawHeight)
}

/**
 * Decodes and encodes stored pages with the platform codecs, which stand in for OpenScan's engine decoder and its JPEG encoder. Output cannot
 * match OpenScan byte for byte (different codecs), but it follows the same rules: EXIF orientation applied, scaled during the decode so the
 * full-resolution bitmap never exists, never upscaled, and premultiplied 8-bit RGBA so transparency can be flattened onto white. Like OpenScan's
 * decoder, nothing here throws: any failure, including running out of memory, comes back as null or false. All blocking: call off the main thread.
 */
object ImageCodec {
    /** Reads the size and EXIF orientation; null if the bytes cannot be opened or are not an image. */
    fun readHeader(source: ImageSource): ImageHeader? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.open().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) ImageHeader(options.outWidth, options.outHeight, orientation(source)) else null
    } catch (e: Exception) {
        null
    }

    fun orientedSize(source: ImageSource): PageSize? = readHeader(source)?.oriented

    /**
     * Decodes [source] upright and scaled to fit [maxEdge] on its long side (never upscaled), as premultiplied 8-bit RGBA; null if it cannot.
     * Pass [header] when it is already known, so the source is opened only once more.
     */
    fun decodeScaled(source: ImageSource, maxEdge: Int, header: ImageHeader? = readHeader(source)): RgbaImage? {
        if (header == null) return null
        var bitmap: Bitmap? = null
        return try {
            val target = StoredImage.fitted(header.oriented.width, header.oriented.height, maxEdge)

            // The largest power-of-two subsample that still leaves at least the target in both raw axes; the exact size comes from the scale below.
            val targetRawW = if (header.swapsAxes) target.height else target.width
            val targetRawH = if (header.swapsAxes) target.width else target.height
            var sample = 1
            while (header.rawWidth / (sample * 2) >= targetRawW && header.rawHeight / (sample * 2) >= targetRawH) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = source.open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            // ARGB_8888 is only a preference: high bit-depth images (16-bit PNG, 10-bit HEIF) can decode as RGBA_F16 or RGBA_1010102.
            bitmap = replaced(bitmap) { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
            bitmap = transformed(bitmap, header.orientation, target)

            val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(buffer) // ARGB_8888 is laid out R, G, B, A in memory, premultiplied
            RgbaImage(bitmap.width, bitmap.height, buffer.array())
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Writes [image] as a JPEG at [quality]. Alpha is dropped, so flatten it first. The file is written beside [dest] and renamed into place, so
     * [dest] is either the complete new page or untouched. Returns false, leaving no temp file, if anything fails.
     */
    fun writeJpeg(image: RgbaImage, dest: File, quality: Int): Boolean {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        var bitmap: Bitmap? = null
        return try {
            val b = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            b.copyPixelsFromBuffer(ByteBuffer.wrap(image.pixels))
            val written = FileOutputStream(tmp).use { out ->
                b.compress(Bitmap.CompressFormat.JPEG, quality, out).also { if (it) out.fd.sync() }
            }
            written && tmp.renameTo(dest)
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        } finally {
            bitmap?.recycle()
            tmp.delete()
        }
    }

    // Formats without EXIF, and EXIF that cannot be read, count as upright, as in any image viewer.
    private fun orientation(source: ImageSource): Int = try {
        source.open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Applies [transform] and recycles [bitmap] if a new bitmap came back. */
    private inline fun replaced(bitmap: Bitmap, transform: (Bitmap) -> Bitmap): Bitmap {
        val result = transform(bitmap)
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    /** Applies the EXIF orientation, then scales to exactly [target]; recycles every bitmap it replaces. */
    private fun transformed(decoded: Bitmap, orientation: Int, target: PageSize): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        var bitmap = decoded
        if (!matrix.isIdentity) bitmap = replaced(bitmap) { Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true) }
        if (bitmap.width != target.width || bitmap.height != target.height) {
            bitmap = replaced(bitmap) { Bitmap.createScaledBitmap(it, target.width, target.height, true) }
        }
        return bitmap
    }
}
