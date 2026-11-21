package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream

/** Image to PDF on its screen: running out of memory while converting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageToPdfViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `running out of memory while converting shows the failure and removes the file the save created`() {
        val (output, created) = TestPdfs.createdDocument(context, "huge-images.pdf")
        // Opening the output runs out of memory, as embedding large images can.
        Shadows.shadowOf(context.contentResolver).registerOutputStreamSupplier(output) { throw OutOfMemoryError("test") }
        val bitmap = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val image = TestPdfs.write(context.cacheDir, png, "photo.png")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output, picked = listOf(image))) {
                ImageToPdfView(onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.onNode(hasText("Choose images") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { shown(CREATE) }

        rule.onNodeWithText(CREATE).performClick()
        // Reading the screen runs the main thread's queue, where the failure handler shows its message and goes back to the images.
        rule.waitUntil(30_000) { shown(CREATE) && ShadowToast.getTextOfLatestToast() == TestPdfs.OUT_OF_MEMORY }
        assertFalse("the created file is removed", created.exists())
    }

    private companion object {
        const val CREATE = "Create PDF from 1 images"
    }
}
