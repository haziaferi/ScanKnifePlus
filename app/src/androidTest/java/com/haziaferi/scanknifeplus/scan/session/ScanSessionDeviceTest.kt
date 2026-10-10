package com.haziaferi.scanknifeplus.scan.session

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A session storing real camera-sized JPEGs through the real capture pipeline, on a device. */
@RunWith(AndroidJUnit4::class)
class ScanSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // A private root, so the test never touches the app's real library.
    private val root = File(context.cacheDir, "session-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun shot(name: String, color: Int): File = File(ScanFiles.stagingDir(context), name).also { file ->
        Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.let { b ->
            file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
    }

    @Test
    fun shotsBecomePagesInOrderAndStagingIsCleared() {
        val library = ScanLibrary(root)
        val session = ScanSession(library, defaultFilter = "Grayscale", clearStaging = { ScanFiles.clearStaging(context) })
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
        assertTrue(ScanFiles.stagingDir(context).list()!!.isEmpty())
    }
}
