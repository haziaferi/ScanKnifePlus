package com.haziaferi.scanknifeplus.scan.export

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanExportTest {
    private val uri = Uri.parse("content://com.haziaferi.scanknifeplus.fileprovider/shared/pdf-1/scan.pdf")

    @Test
    fun `a share intent sends the stream with a read grant through ClipData`() {
        for ((intent, type) in listOf(
            ScanExport.shareIntent(uri, "Scan") to "application/pdf",
            ScanExport.shareIntent(uri, "Scan", mimeType = "image/jpeg") to "image/jpeg",
        )) {
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(type, intent.type)
            @Suppress("DEPRECATION")
            assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals("Scan", intent.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }
}
