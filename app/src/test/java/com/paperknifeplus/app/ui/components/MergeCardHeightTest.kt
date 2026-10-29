package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageKeyer
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A Merge card is as tall as its text (at least 100 dp), whatever the size of the thumbnail bitmap drawn in it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Drawing the window, so Coil loads the thumbnail.
@Config(qualifiers = "mdpi") // Density 1: a page thumbnail at scale 0.4 is 337 px, so it would make the card 337 dp tall.
class MergeCardHeightTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun thumbnailCached(uri: Uri): Boolean {
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            root.draw(Canvas(Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        }
        val key = PdfPageKeyer().key(PdfPageRequest(uri, 0, null, 0.4f), Options(context))
        return PdfImageLoader.get(context).memoryCache!!.keys.any { it.key == key }
    }

    @Test
    fun `a loaded thumbnail does not make the card taller`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "card.pdf")
        val file = MergeFile(1, uri, "card.pdf", "1 KB", isLocked = false, pageCount = 1)
        rule.setContent {
            Column {
                MergeFileListItem(
                    file, 0, Color.Red, PdfImageLoader.get(context),
                    onDelete = {}, onUnlock = {}, onClick = {}, onMoveUp = {}, onMoveDown = {}, isFirst = true, isLast = true
                )
                Text("below the card")
            }
        }
        rule.waitUntil(30_000) { thumbnailCached(uri) }
        rule.waitForIdle()
        assertEquals(100f, rule.onNodeWithText("below the card").fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }
}
