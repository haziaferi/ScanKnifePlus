package com.haziaferi.scanknifeplus.scan.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A session storing real camera-sized JPEGs and gallery picks through the real capture pipeline, on a device. */
@RunWith(AndroidJUnit4::class)
class ScanSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // A private root, so the test never touches the app's real library.
    private val root = File(context.cacheDir, "session-test-${System.nanoTime()}")

    // Picks are served through the app's own FileProvider, from a uniquely named folder so no real share copy is touched.
    private val picks = ScanFiles.newFolder(ScanFiles.shareDir(context), "import-test-")!!

    @After
    fun cleanUp() {
        root.deleteRecursively()
        shotDir.deleteRecursively()
        picks.deleteRecursively()
    }

    // The session works in its own folder inside the app's real staging folder, as in the app; nothing else in there is touched.
    private val shotDir = ScanFiles.newFolder(ScanFiles.stagingDir(context), "session-test-")!!

    private fun shot(name: String, color: Int): File = File(shotDir, name).also { file ->
        Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.let { b ->
            file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
    }

    @Test
    fun shotsBecomePagesInOrderAndTheSessionFolderIsDeleted() {
        val library = ScanLibrary(root)
        val session = ScanSession(library, shotDir, defaultFilter = "Grayscale")
        val half = Quad(Pt(0.0, 0.0), Pt(0.5, 0.0), Pt(0.5, 1.0), Pt(0.0, 1.0))
        val shots = listOf(shot("a.jpg", Color.RED), shot("b.jpg", Color.BLUE), shot("c.jpg", Color.GREEN))
        assertTrue(session.capture(shots[0], half))
        assertTrue(session.capture(shots[1], null))
        assertTrue(session.capture(shots[2], null))
        assertTrue(session.undoLast())
        val result = session.finish()

        val doc = library.document(result.documentId!!)!!
        assertEquals(2, result.pagesAdded)
        assertEquals(2, doc.pages.size)
        assertTrue(doc.pages.all { it.filter == "Grayscale" && it.original != null })
        // The first page was cropped to the left half; the second kept the whole frame.
        val first = ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, doc.pages[0].image)))!!
        val second = ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, doc.pages[1].image)))!!
        assertTrue("$first", first.width < first.height / 1.5)
        assertTrue("$second", Math.abs(second.width * 4 - second.height * 3) <= 8)
        shots.forEach { assertFalse(it.exists()) }
        assertFalse(shotDir.exists())
    }

    private fun uri(file: File): Uri = ScanFiles.contentUri(context, file)

    /** A landscape JPEG, red on the left and blue on the right, whose EXIF says to show it turned 90 degrees clockwise. */
    private fun turnedJpeg(): File = File(picks, "turned.jpg").also { file ->
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        for (x in 0 until 400) for (y in 0 until 300) b.setPixel(x, y, if (x < 200) Color.RED else Color.BLUE)
        file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        b.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()) }.saveAttributes()
    }

    /** A plain grey 8-bit RGB PNG of [width] x [height], streamed so a size far beyond the test's heap can be written. */
    private fun bigPng(width: Int, height: Int): File = File(picks, "big.png").also { file ->
        fun DataOutputStream.chunk(type: String, data: ByteArray) {
            writeInt(data.size)
            val body = type.toByteArray(Charsets.US_ASCII) + data
            write(body)
            writeInt(CRC32().apply { update(body) }.value.toInt())
        }
        val idat = File(picks, "idat").also { raw ->
            val row = ByteArray(1 + width * 3) { if (it == 0) 0 else 128.toByte() }
            DeflaterOutputStream(raw.outputStream().buffered()).use { out -> repeat(height) { out.write(row) } }
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val header = java.io.ByteArrayOutputStream().also { h ->
                DataOutputStream(h).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray()
            out.chunk("IHDR", header)
            out.chunk("IDAT", idat.readBytes())
            out.chunk("IEND", ByteArray(0))
        }
        idat.delete()
    }

    private fun rgbAt(file: File, fx: Double, fy: Double): IntArray {
        val image = ImageCodec.decodeScaled(ImageSource.of(file), 0)!!
        val i = ((fy * image.height).toInt() * image.width + (fx * image.width).toInt()) * 4
        return IntArray(3) { image.pixels[i + it].toInt() and 0xFF }
    }

    private fun assertColor(expected: Int, actual: IntArray, label: String) {
        val e = intArrayOf(Color.red(expected), Color.green(expected), Color.blue(expected))
        assertTrue("$label: expected ${e.toList()}, got ${actual.toList()}", e.indices.all { abs(e[it] - actual[it]) < 60 })
    }

    private fun awaitIdle(session: ScanSession) {
        val until = System.currentTimeMillis() + 60_000
        while (session.state.value.pending > 0) {
            check(System.currentTimeMillis() < until) { "the queue never drained" }
            Thread.sleep(10)
        }
    }

    @Test
    fun galleryPicksAreStoredUprightAndCappedWhileBadPicksFailAlone() {
        val library = ScanLibrary(root)
        val session = ScanSession(library, shotDir)
        val text = File(picks, "notes.txt").apply { writeText("not an image") }
        val gone = File(picks, "gone.jpg").apply { writeText("x") }
        val uris = listOf(uri(turnedJpeg()), uri(text), uri(bigPng(8000, 10000)), uri(gone))
        gone.delete() // picked, then deleted before it was read
        assertTrue(session.import(context.contentResolver, uris))
        awaitIdle(session)
        assertEquals(listOf(ShotFailure(1, ShotOrigin.IMPORT), ShotFailure(3, ShotOrigin.IMPORT)), session.state.value.failures)
        val result = session.finish()

        val doc = library.document(result.documentId!!)!!
        assertEquals(2, result.pagesAdded)
        // Turned upright: the photo's left (red) becomes the top.
        val turned = library.file(doc.id, doc.pages[0].image)
        assertEquals(PageSize(300, 400), ImageCodec.orientedSize(ImageSource.of(turned)))
        assertColor(Color.RED, rgbAt(turned, 0.5, 0.2), "top of the turned pick")
        assertColor(Color.BLUE, rgbAt(turned, 0.5, 0.8), "bottom of the turned pick")
        // An 80-megapixel pick is stored at the page cap, with its original at the original cap.
        assertEquals(PageSize(1920, 2400), ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, doc.pages[1].image))))
        assertEquals(PageSize(2560, 3200), ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, doc.pages[1].original!!))))
        assertTrue(File(picks, "turned.jpg").exists()) // picks are only read
    }

    @Test
    fun aFailedPickCanBeRetriedOnceItIsReadable() {
        val library = ScanLibrary(root)
        val session = ScanSession(library, shotDir)
        val late = File(picks, "late.jpg")
        assertTrue(session.import(context.contentResolver, listOf(uri(late))))
        awaitIdle(session)
        turnedJpeg().renameTo(late)
        assertTrue(session.retry(session.state.value.failures.single()))
        awaitIdle(session)
        assertTrue(session.state.value.failures.isEmpty())
        assertEquals(1, session.finish().pagesAdded)
    }

    /**
     * Writes a [width] x [height] HEIC, red on the left and blue on the right, turned by [rotation] degrees in its container, with the platform
     * HEVC encoder (as HeifWriter does); false if this device cannot.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun heic(file: File, width: Int, height: Int, rotation: Int): Boolean {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: return false
        val codec = MediaCodec.createByCodecName(name)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val input = codec.dequeueInputBuffer(5_000_000)
            if (input < 0) return false
            val image = codec.getInputImage(input) ?: return false
            // BT.601 limited range: red is (82, 90, 240) and blue (41, 240, 110) in Y, U, V.
            val yuv = arrayOf(intArrayOf(82, 41), intArrayOf(90, 240), intArrayOf(240, 110))
            for ((p, plane) in image.planes.withIndex()) {
                val sub = if (p == 0) 1 else 2
                for (y in 0 until height / sub) for (x in 0 until width / sub) {
                    val value = yuv[p][if (x * sub < width / 2) 0 else 1]
                    plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                }
            }
            codec.queueInputBuffer(input, 0, width * height * 3 / 2, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            var track = -1
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 5_000_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat.apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC) }
                        track = muxer.addTrack(out)
                        muxer.setOrientationHint(rotation)
                        muxer.start()
                        started = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) {
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return started
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                }
            }
        } catch (e: Exception) {
            return false
        } finally {
            codec.release()
            try {
                if (started) muxer.stop()
            } catch (e: Exception) {
                // An unfinished file fails the assumption below.
            }
            muxer.release()
        }
    }

    /** Whether the platform decoder can read [file]'s size, i.e. it holds an image. */
    private fun decodes(file: File): Boolean =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outWidth > 0

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P) // HEIF decoding and muxing
    fun aHeicPickIsStoredUpright() {
        val file = File(picks, "photo.heic")
        // Decoded too: the 9 Pro's encoder starts the muxer, then writes a container with no image in it.
        assumeTrue("this device cannot encode a HEIC", heic(file, 512, 256, 90) && decodes(file))
        val library = ScanLibrary(root)
        val session = ScanSession(library, shotDir)
        assertTrue(session.import(context.contentResolver, listOf(uri(file))))
        awaitIdle(session)
        assertTrue("${session.state.value.failures}", session.state.value.failures.isEmpty())
        val result = session.finish()
        val page = library.file(result.documentId!!, library.document(result.documentId!!)!!.pages.single().image)
        // Turned upright by its container's rotation, once: the image's left (red) becomes the top.
        assertEquals(PageSize(256, 512), ImageCodec.orientedSize(ImageSource.of(page)))
        assertColor(Color.RED, rgbAt(page, 0.5, 0.2), "top of the HEIC page")
        assertColor(Color.BLUE, rgbAt(page, 0.5, 0.8), "bottom of the HEIC page")
    }
}
