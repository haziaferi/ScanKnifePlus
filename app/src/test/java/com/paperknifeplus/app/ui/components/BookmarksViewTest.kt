package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.robolectric.annotation.GraphicsMode

/** Bookmarks on its screen: unlocking a protected file. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookmarksViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** Whether the file card's thumbnail of [source] with [password] is cached; Coil only loads what is drawn, and Robolectric never draws. */
    private fun thumbnailCached(source: Uri, password: String): Boolean {
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            root.draw(Canvas(Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        }
        val key = PdfPageKeyer().key(PdfPageRequest(source, 0, password, THUMBNAIL_SCALE), Options(context))
        return PdfImageLoader.get(context).memoryCache!!.keys.any { it.key == key }
    }

    @Test
    fun `unlocking a protected file parses it twice, the thumbnail reusing what the password check read`() {
        val source = TestPdfs.CountedSource(context, TestPdfs.userPassword(pages = 2), "counted-bookmarks.pdf")
        rule.setContent { BookmarksView(initialUri = source.uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("0 bookmarks")).fetchSemanticsNodes().isNotEmpty() }

        rule.waitUntil(30_000) { thumbnailCached(source.uri, TestPdfs.USER_PASSWORD) }
        // Once to find that it needs a password, once to check the password and read the outline.
        assertEquals(2, source.opens)
    }
}
