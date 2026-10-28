package com.paperknifeplus.app.ui.components

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageKeyer
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The result card (3b) names the file the picker saved, not the file the tool opened, and draws an encrypted result with its password. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Drawing the window, so Coil loads the thumbnail.
class ToolResultNameTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        OutputProvider.dir = context.cacheDir
        Robolectric.setupContentProvider(OutputProvider::class.java, AUTHORITY)
    }

    private fun pickerReturning(result: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, result)
            }
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(30_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `Watermark's result card shows the saved file's name`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "report.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(Uri.parse("content://$AUTHORITY/$SAVED"))) {
                WatermarkView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(30_000) { rule.onAllNodes(hasContentDescription("Page 1") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        // A one-page file reads "1 page", not "1 pages".
        rule.onNode(SemanticsMatcher("file details") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.endsWith("• 1 page") }
        }).assertExists()
        rule.onNode(hasContentDescription("Page 1") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Add watermark")
        rule.onNodeWithText("Add watermark").performClick()
        rule.onNodeWithText("DRAFT").performClick()
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithText("Save").performClick()
        waitFor("Watermark complete")
        rule.onNodeWithText(SAVED).assertExists()
    }

    /** Whether a first-page thumbnail of [uri] rendered with [password] is cached; Coil only loads what is drawn. */
    private fun thumbnailCached(uri: Uri, password: String?): Boolean {
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            root.draw(Canvas(Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        }
        val key = PdfPageKeyer().key(PdfPageRequest(uri, 0, password, THUMBNAIL_SCALE), Options(context))
        return PdfImageLoader.get(context).memoryCache!!.keys.any { it.key == key }
    }

    @Test
    fun `an encrypted result's card draws its first page with the password`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 1), "protected-result.pdf")
        rule.setContent {
            SuccessView(
                message = "Protect complete", subMessage = "", result = ToolResult(locked, "protected-result.pdf", password = TestPdfs.USER_PASSWORD),
                onDone = {}, onProcessMore = {}, onOpen = {}
            )
        }
        rule.waitUntil(30_000) { thumbnailCached(locked, TestPdfs.USER_PASSWORD) }
    }

    /** Serves files of [dir] under content://[AUTHORITY]/<name>, with the display name a document provider reports. */
    class OutputProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>(uri.lastPathSegment!!, File(dir, uri.lastPathSegment!!).length()))
            }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(dir, uri.lastPathSegment!!), ParcelFileDescriptor.parseMode(mode))

        override fun getType(uri: Uri) = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        companion object {
            lateinit var dir: File
        }
    }

    private companion object {
        const val AUTHORITY = "com.paperknifeplus.app.test.outputs"
        const val SAVED = "report-watermarked-saved.pdf"
    }
}
