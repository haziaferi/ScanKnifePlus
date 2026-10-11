package com.paperknifeplus.app.ui.components

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.Color as AndroidColor
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlinx.coroutines.runBlocking
import java.io.File
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignatureBitmapTest {
    @Test
    fun `the frame fits strokes reaching the bottom-right corner of a large pad`() {
        // A 1100x1050 px pad (300 dp tall at 3.5x); the stroke ends in its bottom-right corner.
        val frame = signatureFrame(left = 100f, top = 80f, right = 1100f, bottom = 1050f, strokePx = 16f)
        val inset = 8f + 16f
        assertEquals(1000 + 2 * inset.toInt(), frame.width)
        assertEquals(970 + 2 * inset.toInt(), frame.height)
        // The stroke's far edge, half a stroke beyond its path, lands inside the bitmap.
        assertTrue(1100f + 8f + frame.dx <= frame.width)
        assertTrue(1050f + 8f + frame.dy <= frame.height)
        assertTrue(100f - 8f + frame.dx >= 0f && 80f - 8f + frame.dy >= 0f)
    }

    @Test
    fun `a single dot still gets a bitmap`() {
        val frame = signatureFrame(5f, 5f, 5f, 5f, strokePx = 0f, padding = 0f)
        assertEquals(1, frame.width)
        assertEquals(1, frame.height)
    }

    @Test
    fun `the rendered signature keeps the end of a stroke drawn at the pad's bottom-right corner`() {
        val stroke = Path().apply {
            moveTo(100f, 80f)
            lineTo(1100f, 1050f)
        }
        val bitmap = renderSignatureBitmap(listOf(stroke to Color.Black))
        val frame = signatureFrame(100f, 80f, 1100f, 1050f, strokePx = 16f)
        assertEquals(frame.width, bitmap.width)
        assertEquals(frame.height, bitmap.height)

        val endX = (1100f + frame.dx).toInt()
        val endY = (1050f + frame.dy).toInt()
        assertEquals(255, AndroidColor.alpha(bitmap.getPixel(endX, endY)))
        assertEquals(255, AndroidColor.alpha(bitmap.getPixel((100f + frame.dx).toInt(), (80f + frame.dy).toInt())))
        assertEquals("corners stay transparent", 0, AndroidColor.alpha(bitmap.getPixel(0, bitmap.height - 1)))
    }

    @Test
    fun `strokes keep their own colours`() {
        val red = Path().apply { moveTo(0f, 0f); lineTo(50f, 0f) }
        val blue = Path().apply { moveTo(0f, 100f); lineTo(50f, 100f) }
        val bitmap = renderSignatureBitmap(listOf(red to Color.Red, blue to Color.Blue))
        val frame = signatureFrame(0f, 0f, 50f, 100f, strokePx = 16f)
        assertEquals(AndroidColor.RED, bitmap.getPixel((25f + frame.dx).toInt(), frame.dy.toInt()))
        assertEquals(AndroidColor.BLUE, bitmap.getPixel((25f + frame.dx).toInt(), (100f + frame.dy).toInt()))
    }
}

/** Sign opened on an encrypted file, as the preview's tool picker opens it: it must ask for the password instead of showing the picker. */
@RunWith(RobolectricTestRunner::class)
class SignViewUnlockTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun `opened with an encrypted file, Sign asks for its password`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "locked-sign.pdf")
        rule.setContent { SignView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Password Required")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Password Required").assertExists()
    }
}

/** Picking or exporting a signature through a provider that refuses with a non-IO exception reports the failure instead of crashing. */
@RunWith(RobolectricTestRunner::class)
class SignatureFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val refusing: Uri by lazy {
        Robolectric.setupContentProvider(RefusingProvider::class.java, AUTHORITY)
        Uri.parse("content://$AUTHORITY/signature.png")
    }

    @Test
    fun `a picked image the provider refuses is a failure, not a crash`() {
        val result = runBlocking { readPickedImage(context, refusing) }
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `an export the provider refuses is reported`() {
        val file = File(context.cacheDir, "sig_export.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(runBlocking { exportSignature(context, file, refusing) }.startsWith("Export failed"))
    }

    class RefusingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = throw SecurityException("no access to $uri")
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "com.paperknifeplus.app.test.refusing"
    }
}
