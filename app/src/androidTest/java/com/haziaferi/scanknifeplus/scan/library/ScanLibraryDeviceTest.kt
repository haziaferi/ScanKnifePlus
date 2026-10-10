package com.haziaferi.scanknifeplus.scan.library

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The library with the real capture pipeline, plus staging clean-up, on a device. */
@RunWith(AndroidJUnit4::class)
class ScanLibraryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // A private root, so the test never touches the app's real library.
    private val root = File(context.cacheDir, "library-test-${System.nanoTime()}")

    private val otherCache = File(context.cacheDir, "not-staging-${System.nanoTime()}.txt")

    @After
    fun cleanUp() {
        root.deleteRecursively()
        otherCache.delete()
    }

    @Test
    fun aCaptureIsStoredAsAPageWithItsOriginal() {
        val photo = File(ScanFiles.stagingDir(context), "shot.jpg")
        Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }.let { b ->
            photo.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
        val library = ScanLibrary(root)
        val doc = library.create()
        val page = library.addCapture(doc.id, ImageSource.of(photo), null, keepOriginal = true)!!.pages.single()
        assertEquals(PageSize(1200, 1600), ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, page.image))))
        assertEquals(PageSize(1200, 1600), ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, page.original!!))))

        // Clearing the staging area removes the shot and nothing else in the cache.
        otherCache.writeText("keep")
        assertTrue(ScanFiles.clearStaging(context))
        assertFalse(photo.exists())
        assertTrue(otherCache.exists())
    }

    @Test
    fun aSweepRemovesOnlyStaleUnnamedFiles() {
        val photo = File(context.cacheDir, "sweep-shot-${System.nanoTime()}.jpg")
        Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }.let { b ->
            photo.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
        val library = ScanLibrary(root)
        val doc = library.create()
        val page = library.addCapture(doc.id, ImageSource.of(photo), null, keepOriginal = true)!!.pages.single()
        photo.delete()
        val old = System.currentTimeMillis() - ScanLibrary.STALE_MILLIS - 60_000
        val folder = File(root, doc.id)
        folder.listFiles()!!.forEach { assertTrue(it.setLastModified(old)) } // the device's file system keeps the times set
        val stale = File(folder, "1000.jpg").apply { writeText("half") }.also { assertTrue(it.setLastModified(old)) }
        val fresh = File(folder, "1001.jpg").apply { writeText("half") }

        assertEquals(1, library.sweep())
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertEquals(setOf("document.json", fresh.name) + page.files, folder.list()!!.toSet())
    }
}
