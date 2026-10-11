package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil.memory.MemoryCache
import com.paperknifeplus.app.data.image.PdfImageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ClearCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(dir: File, path: String) = File(dir, path).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test
    fun `Clear Cache keeps the scanner's folders and deletes everything else`() {
        val cache = context.cacheDir
        val staged = file(cache, "scan-staging/session-x/page.jpg")
        val exporting = file(cache, "scan-export/pdf-x/out.pdf")
        val shared = file(cache, "shared/pdf-x/scan.pdf")
        val decrypted = file(cache, "decrypted_1.pdf")
        val preview = file(cache, "pdf_previews/entry")
        val other = file(cache, "PDFBox123.tmp")
        val external = context.externalCacheDir?.let { file(it, "ext.tmp") }
        val memoryCache = PdfImageLoader.get(context).memoryCache!!
        memoryCache[MemoryCache.Key("page")] = MemoryCache.Value(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))

        assertTrue(clearAppCache(context))

        assertTrue(staged.exists() && exporting.exists() && shared.exists())
        assertFalse(decrypted.exists())
        assertFalse(preview.exists() || preview.parentFile!!.exists())
        assertFalse(other.exists())
        external?.let { assertFalse(it.exists()) }
        assertEquals(0, memoryCache.size)
    }
}
