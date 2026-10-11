package com.paperknifeplus.app.data.image

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfImageLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val options = Options(context)
    private val keyer = PdfPageKeyer()
    private val base = PdfPageRequest(Uri.parse("content://docs/a.pdf"), pageIndex = 0, password = null, scale = 0.6f)

    private fun key(request: PdfPageRequest) = keyer.key(request, options)

    @Test
    fun `keys differ by page, scale, rotation, password and document`() {
        val variants = listOf(
            base,
            base.copy(pageIndex = 1),
            base.copy(scale = 1.2f),
            base.copy(rotation = 90),
            base.copy(password = "one"),
            base.copy(password = "two"),
            base.copy(uri = Uri.parse("content://docs/b.pdf")),
        )
        assertEquals(variants.size, variants.map(::key).toSet().size)
    }

    @Test
    fun `priority does not change the key and the password is not in it`() {
        assertEquals(key(base.copy(priority = 0)), key(base.copy(priority = 1)))
        assertEquals(key(base.copy(password = "pw", priority = 0)), key(base.copy(password = "pw", priority = 1)))
        assertFalse(key(base.copy(password = "hunter2")).contains("hunter2"))
        assertNotEquals(key(base), key(base.copy(password = "")))
    }

    @Test
    fun `there is one loader per process`() {
        assertSame(PdfImageLoader.get(context), PdfImageLoader.get(context))
    }
}
