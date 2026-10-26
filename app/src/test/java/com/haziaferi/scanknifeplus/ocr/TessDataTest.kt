package com.haziaferi.scanknifeplus.ocr

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TessDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "tessdata") }

    /** Serves each file as [content]'s bytes for it, counting opens; [failOn] throws partway through that file's copy. */
    private class FakeSource(val content: Map<String, ByteArray>, val failOn: String? = null) : TessData.Source {
        val opened = mutableListOf<String>()

        override fun size(name: String): Long = content.getValue(name).size.toLong()

        override fun open(name: String): InputStream {
            opened += name
            val bytes = content.getValue(name)
            if (name != failOn) return ByteArrayInputStream(bytes)
            return object : InputStream() {
                var left = bytes.size / 2
                override fun read(): Int = if (left-- > 0) 1 else throw IOException("disk gone")
            }
        }
    }

    private fun data(vararg sizes: Int) = TessData.FILES.zip(sizes.toList()).associate { (name, size) -> name to ByteArray(size) { (it % 7).toByte() } }

    @Test
    fun `installs every file and then leaves files of the right size alone`() {
        val source = FakeSource(data(10, 20, 30))
        assertTrue(TessData.install(dir, source))
        TessData.FILES.forEach { assertArrayEquals(source.content.getValue(it), File(dir, it).readBytes()) }
        source.opened.clear()
        assertTrue(TessData.install(dir, source))
        assertEquals(emptyList<String>(), source.opened)
    }

    @Test
    fun `a file whose size differs from the asset is replaced`() {
        // File.renameTo replaces an existing file on Android and Linux, not on a Windows host.
        assumeTrue(File.separatorChar == '/')
        assertTrue(TessData.install(dir, FakeSource(data(10, 20, 30))))
        val update = FakeSource(data(10, 25, 30))
        assertTrue(TessData.install(dir, update))
        assertEquals(listOf("ita.traineddata"), update.opened)
        assertEquals(25L, File(dir, "ita.traineddata").length())
    }

    @Test
    fun `a failed copy keeps the old file and leaves no temp file`() {
        assertTrue(TessData.install(dir, FakeSource(data(10, 20, 30))))
        val old = File(dir, "osd.traineddata").readBytes()
        assertFalse(TessData.install(dir, FakeSource(data(10, 20, 40), failOn = "osd.traineddata")))
        assertArrayEquals(old, File(dir, "osd.traineddata").readBytes())
        assertEquals(TessData.FILES.sorted(), dir.list()!!.sorted())
    }

    @Test
    fun `a temp file left by a killed process is cleared`() {
        dir.mkdirs()
        File(dir, "eng.traineddata.tmp").writeBytes(ByteArray(5))
        assertTrue(TessData.install(dir, FakeSource(data(10, 20, 30))))
        assertEquals(TessData.FILES.sorted(), dir.list()!!.sorted())
    }
}
