package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageKeyer
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplitViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /**
     * Whether the shared loader has cached a first-page thumbnail of [uri] rendered with [password]. Coil starts a thumbnail only once it is
     * drawn, and Robolectric never draws on its own, hence the explicit draw.
     */
    private fun thumbnailCached(uri: Uri, password: String?): Boolean {
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            root.draw(Canvas(Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        }
        val key = PdfPageKeyer().key(PdfPageRequest(uri, 0, password, 0.6f), Options(context))
        return PdfImageLoader.get(context).memoryCache!!.keys.any { it.key == key }
    }

    /** Answers every launch with [result], like a file picker the user picked [result] in. */
    private fun pickerReturning(result: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, result)
            }
        }
    }

    private fun startSplit(uri: Uri, password: String, picked: Uri = uri) {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(picked)) {
                SplitView(initialUri = uri, initialPassword = password, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
    }

    private fun waitForPages(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `previews of the original encrypted file get the password the preview handed over`() {
        val original = Uri.parse("content://docs/locked.pdf")
        val copy = Uri.parse("file:///cache/decrypted_1.pdf")
        assertEquals("pw", splitPreviewPassword(original, null, "pw"))
        assertNull(splitPreviewPassword(copy, copy, "pw"))
        assertNull(splitPreviewPassword(original, null, ""))
    }

    @Test
    fun `opened with an encrypted file and its password, thumbnails render with that password`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-split.pdf")
        startSplit(uri, TestPdfs.USER_PASSWORD)

        waitForPages("0 / 2 pages selected")
        rule.onNodeWithText("Password required").assertDoesNotExist()
        rule.waitUntil(10_000) { thumbnailCached(uri, TestPdfs.USER_PASSWORD) }
    }

    @Test
    fun `after CHANGE the next file is previewed without the old password`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-change.pdf")
        val plain = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "plain-change.pdf")
        startSplit(locked, TestPdfs.USER_PASSWORD, picked = plain)
        waitForPages("0 / 2 pages selected")

        rule.onNodeWithText("Change").performClick()
        rule.onNodeWithText("Choose PDF").performClick()
        waitForPages("0 / 1 page selected")
        rule.waitUntil(10_000) { thumbnailCached(plain, null) }
        assertFalse(thumbnailCached(plain, TestPdfs.USER_PASSWORD))
    }

    @Test
    fun `a wrong password handed over by the reader opens the prompt with its error`() {
        startSplit(TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "handed-wrong-split.pdf"), "wrong")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Incorrect password, try again.").assertExists()
    }

    @Test
    fun `a damaged protected file handed over with its password says it can't be read and goes back to choosing a file`() {
        startSplit(TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "handed-damaged-split.pdf"), TestPdfs.USER_PASSWORD)
        rule.waitUntil(10_000) { ShadowToast.getTextOfLatestToast() == "Can't read this PDF." }
        rule.onNodeWithText("Choose PDF").assertExists()
        rule.onNodeWithText("Password required").assertDoesNotExist()
    }

    @Test
    fun `a password handed over for a file that opens without one is dropped, so its pages count and render without it`() {
        // An owner password only: the handed one is neither, so opening with it would fail.
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.ownerOnly(pages = 2), "handed-owner-only-split.pdf")
        startSplit(uri, "not-needed")
        waitForPages("0 / 2 pages selected")
        rule.waitUntil(10_000) { thumbnailCached(uri, null) }
        assertFalse(thumbnailCached(uri, "not-needed"))
    }
}
