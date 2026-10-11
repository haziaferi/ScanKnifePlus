package com.paperknifeplus.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.ui.components.SessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pkg: String get() = context.packageName
    private var controller: ActivityController<MainActivity>? = null

    /** Serves PDFs from the cache folder by name, with a display name, as a file manager's provider would. */
    class PdfProvider : ContentProvider() {
        override fun onCreate() = true
        private fun file(uri: Uri) = File(context!!.cacheDir, uri.lastPathSegment!!)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(uri.lastPathSegment, file(uri).length())) }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(PdfProvider::class.java, AUTHORITY)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        SessionManager.clearHistory()
    }

    private fun pdf(name: String): Uri {
        TestPdfs.write(context.cacheDir, TestPdfs.plain(2), name)
        return Uri.parse("content://$AUTHORITY/$name")
    }

    private fun view(uri: Uri, type: String? = "application/pdf") =
        Intent(Intent.ACTION_VIEW).setClass(context, MainActivity::class.java).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun launch(intent: Intent = Intent(context, MainActivity::class.java)) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("splash")).fetchSemanticsNodes().isEmpty() }
    }

    private fun shows(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = rule.waitUntil(10_000) { shows(text) }

    @Test
    fun `pdfUriFrom takes VIEW intents for PDFs only`() {
        val content = Uri.parse("content://com.example.files/document/7")
        assertEquals(content, pdfUriFrom(Intent(Intent.ACTION_VIEW).setDataAndType(content, "application/pdf")))
        val byName = Uri.parse("content://com.example.files/docs/Report.PDF")
        assertEquals(byName, pdfUriFrom(Intent(Intent.ACTION_VIEW, byName)))
        assertNull(pdfUriFrom(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.example.files/a.png"), "image/png")))
        assertNull(pdfUriFrom(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.example.files/document/7"))))
        assertNull(pdfUriFrom(Intent(Intent.ACTION_SEND).setDataAndType(content, "application/pdf")))
        assertNull(pdfUriFrom(Intent(Intent.ACTION_VIEW)))
        assertNull(pdfUriFrom(null))
    }

    @Test
    fun `only other apps' content URIs are accepted`() {
        assertFalse(isExternalContentUri(Uri.parse("file:///data/data/$pkg/files/scans/x.pdf"), pkg))
        assertFalse(isExternalContentUri(Uri.parse("content://$pkg.fileprovider/shared/x.pdf"), pkg))
        assertFalse(isExternalContentUri(Uri.parse("content://$pkg.androidx-startup/x"), pkg))
        assertFalse(isExternalContentUri(Uri.parse("content://$pkg/x.pdf"), pkg))
        assertFalse(isExternalContentUri(Uri.parse("x.pdf"), pkg))
        assertTrue(isExternalContentUri(Uri.parse("content://com.android.providers.downloads.documents/document/1"), pkg))
        assertTrue(isExternalContentUri(Uri.parse("content://${pkg}x.provider/document/1"), pkg))
    }

    @Test
    fun `a second Open with switches the reader to the new file`() {
        launch(view(pdf("first.pdf")))
        waitFor("first.pdf")

        val second = view(pdf("second.pdf"))
        controller!!.newIntent(second)
        assertEquals(second, controller!!.get().intent)
        waitFor("second.pdf")
        assertFalse(shows("first.pdf"))
    }

    @Test
    fun `a private file handed to the activity is not opened`() {
        val private = File(context.filesDir, "scans/x.pdf").apply { parentFile!!.mkdirs(); writeBytes(TestPdfs.plain()) }
        launch(view(Uri.fromFile(private)))
        rule.waitForIdle()
        assertFalse(shows("PDF VIEWER"))
    }

    @Test
    @Config(qualifiers = "w400dp-h2000dp") // Tall enough for the Home grid to compose its Compress card.
    fun `an open tool survives recreation and the splash does not replay`() {
        launch()
        rule.onAllNodesWithText("Compress").onFirst().performClick()
        waitFor("OPTIMIZE PDF SIZE")

        controller = controller!!.recreate()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasTestTag("splash")).fetchSemanticsNodes().isEmpty())
        assertTrue(shows("OPTIMIZE PDF SIZE"))
    }

    @Test
    fun `a closed Open with file does not reopen after recreation`() {
        launch(view(pdf("handed.pdf")))
        waitFor("handed.pdf")
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Back")).onFirst().performClick()
        rule.waitUntil(10_000) { !shows("PDF VIEWER") }

        controller = controller!!.recreate()
        rule.waitForIdle()
        assertFalse(shows("PDF VIEWER"))
    }

    @Test
    fun `an open reader survives recreation`() {
        launch(view(pdf("kept.pdf")))
        waitFor("kept.pdf")
        controller = controller!!.recreate()
        waitFor("kept.pdf")
    }

    private companion object {
        const val AUTHORITY = "com.example.files.test"
    }
}
