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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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

    /** A file picker the user picks [picked] in, and a save picker the user picks [saved] in. */
    private fun pickerReturning(picked: Uri, saved: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, if (contract is ActivityResultContracts.CreateDocument) saved else picked)
            }
        }
    }

    private val output = File(context.cacheDir, "split-view-out.pdf").apply { delete() }

    private fun startSplit(uri: Uri, password: String, picked: Uri = uri) {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(picked, Uri.fromFile(output))) {
                SplitView(initialUri = uri, initialPassword = password, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
    }

    private fun waitForPages(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Selects every page, extracts them, and waits for the result. */
    private fun extractAll(pages: Int) {
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText(if (pages == 1) "Extract 1 page" else "Extract $pages pages").performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Split complete")).fetchSemanticsNodes().isNotEmpty() }
    }

    // Tall enough that the first page's tile shows over the pinned bar, which now has its save line, and so is drawn.
    @Config(qualifiers = "h800dp")
    @Test
    fun `opened with an encrypted file and its password, Split previews its decrypted copy, as after the prompt`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-split.pdf")
        startSplit(uri, TestPdfs.USER_PASSWORD)

        waitForPages("0 / 2 pages selected")
        rule.onNodeWithText("Password required").assertDoesNotExist()
        val copy = context.cacheDir.listFiles().orEmpty().single { it.name.startsWith("decrypted_") }
        rule.waitUntil(10_000) { thumbnailCached(Uri.fromFile(copy), null) }
    }

    @Test
    fun `after CHANGE the next file keeps no password and is saved without the old one`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-change.pdf")
        // An owner password only: the old password is neither, so saving with it would fail.
        val next = TestPdfs.write(context.cacheDir, TestPdfs.ownerOnly(pages = 1), "owner-only-change.pdf")
        startSplit(locked, TestPdfs.USER_PASSWORD, picked = next)
        waitForPages("0 / 2 pages selected")
        rule.onNodeWithText(PASSWORD_LINE).assertExists()

        rule.onNodeWithText("Change").performClick()
        rule.onNodeWithText("Choose PDF").performClick()
        waitForPages("0 / 1 page selected")
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        extractAll(1)
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        PDDocument.load(output).use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun `a file unlocked at the prompt after Change starts with no page selected`() {
        val first = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "split-selected-first.pdf")
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "split-then-locked.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(locked, Uri.fromFile(output))) {
                SplitView(initialUri = first, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        waitForPages("0 / 3 pages selected")
        rule.onNodeWithText("Select all").performClick()
        waitForPages("3 / 3 pages selected")

        rule.onNodeWithText("Change").performClick()
        rule.onNodeWithText("Choose PDF").performClick()
        waitForPages("Password required")
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        // Not the first file's 3 pages out of this one's 2.
        waitForPages("0 / 2 pages selected")
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
    fun `a password handed over for a file that opens without one is dropped, so its pages count, render and are saved without it`() {
        // An owner password only: the handed one is neither, so opening with it would fail.
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.ownerOnly(pages = 2), "handed-owner-only-split.pdf")
        startSplit(uri, "not-needed")
        waitForPages("0 / 2 pages selected")
        rule.waitUntil(10_000) { thumbnailCached(uri, null) }
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        extractAll(2)
        PDDocument.load(output).use { assertEquals(2, it.numberOfPages) }
    }

    private companion object {
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
    }
}
