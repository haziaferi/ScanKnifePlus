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

/** Encoded image bytes that can be opened more than once (bounds, EXIF and pixels are each read with a fresh stream). */
fun interface ImageSource {
    fun open(): InputStream

    companion object {
        fun of(file: File) = ImageSource { file.inputStream() }

        fun of(resolver: ContentResolver, uri: Uri) = ImageSource { resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri") }
    }
}

/**
 * Decodes and encodes stored pages with the platform codecs, which stand in for OpenScan's engine decoder and its JPEG encoder. Output cannot
 * match OpenScan byte for byte (different codecs), but it follows the same rules: EXIF orientation applied, scaled during the decode so the
 * full-resolution bitmap never exists, never upscaled, and premultiplied pixels so transparency can be flattened onto white. All blocking: call
 * off the main thread.
 */
object ImageCodec {
    /** The image's size as displayed (EXIF orientation applied), read from the header alone; null if the bytes are not an image. */
    fun orientedSize(source: ImageSource): PageSize? {
        val raw = rawSize(source) ?: return null
        return if (swapsAxes(orientation(source))) PageSize(raw.height, raw.width) else raw
    }

    /** Decodes [source] upright and scaled to fit [maxEdge] on its long side (never upscaled), as premultiplied RGBA; null if it cannot. */
    fun decodeScaled(source: ImageSource, maxEdge: Int): RgbaImage? {
        val raw = rawSize(source) ?: return null
        val orientation = orientation(source)
        val swap = swapsAxes(orientation)
        val target = StoredImage.fitted(if (swap) raw.height else raw.width, if (swap) raw.width else raw.height, maxEdge)

        // The largest power-of-two subsample that still leaves at least the target in both raw axes; the exact size comes from the scale below.
        val targetRawW = if (swap) target.height else target.width
        val targetRawH = if (swap) target.width else target.height
        var sample = 1
        while (raw.width / (sample * 2) >= targetRawW && raw.height / (sample * 2) >= targetRawH) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = source.open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        bitmap = transformed(bitmap, orientation, target)
        return try {
            val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(buffer) // ARGB_8888 is laid out R, G, B, A in memory, premultiplied
            RgbaImage(bitmap.width, bitmap.height, buffer.array())
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Writes [image] as a JPEG at [quality]. Alpha is dropped, so flatten it first. The file is written beside [dest] and renamed into place, so
     * [dest] is either the complete new page or untouched. Returns false (leaving no partial file) if anything fails.
     */
    fun writeJpeg(image: RgbaImage, dest: File, quality: Int): Boolean {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(image.pixels))
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return false
                out.fd.sync()
            }
            tmp.renameTo(dest)
        } catch (e: IOException) {
            false
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
    }

    private fun rawSize(source: ImageSource): PageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.open().use { BitmapFactory.decodeStream(it, null, options) }
        return if (options.outWidth > 0 && options.outHeight > 0) PageSize(options.outWidth, options.outHeight) else null
    }

    // Formats without EXIF, or unreadable EXIF, count as upright, as with any image viewer.
    private fun orientation(source: ImageSource): Int = try {
        source.open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun swapsAxes(orientation: Int) = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE

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
        if (!matrix.isIdentity) {
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (upright !== bitmap) bitmap.recycle()
            bitmap = upright
        }
        if (bitmap.width != target.width || bitmap.height != target.height) {
            val scaled = Bitmap.createScaledBitmap(bitmap, target.width, target.height, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }
}
