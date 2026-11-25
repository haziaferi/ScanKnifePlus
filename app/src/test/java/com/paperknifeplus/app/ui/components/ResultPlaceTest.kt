package com.paperknifeplus.app.ui.components

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** R8 and rule 14: the result card's line under the file name, its size and the folder it is in, as far as Android reports them. */
@RunWith(RobolectricTestRunner::class)
class ResultPlaceTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Reports a size for the document ids in [SIZES], and no size column value for the rest, as a document provider would. */
    class SizedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(SIZES[DocumentsContract.getDocumentId(uri)])) }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(SizedProvider::class.java, STORAGE)
        Robolectric.setupContentProvider(SizedProvider::class.java, DOWNLOADS)
    }

    private fun doc(authority: String, id: String) = DocumentsContract.buildDocumentUri(authority, id)

    @Test
    fun `the phone's storage names the folder from the document's path`() {
        assertEquals("Download", resultFolder(doc(STORAGE, "primary:Download/report.pdf")))
        assertEquals("Scans", resultFolder(doc(STORAGE, "primary:Documents/Scans/report.pdf")))
        assertEquals("on a memory card too", "Documents", resultFolder(doc(STORAGE, "1A2B-3C4D:Documents/report.pdf")))
        assertEquals("inside the app's folder tree", "Scans", resultFolder(DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(STORAGE, "primary:Documents/Scans"), "primary:Documents/Scans/report.pdf")))
        assertEquals("a Downloads path", "Download", resultFolder(doc(DOWNLOADS, "raw:/storage/emulated/0/Download/report.pdf")))
    }

    @Test
    fun `no folder where the provider does not report one, so no id or path ever shows`() {
        assertNull("a file at the top of the storage", resultFolder(doc(STORAGE, "primary:report.pdf")))
        assertNull("a Downloads id", resultFolder(doc(DOWNLOADS, "msf:123")))
        assertNull(resultFolder(doc(DOWNLOADS, "123")))
        assertNull("a cloud provider", resultFolder(doc("com.example.cloud.documents", "folder/report.pdf")))
        assertNull("not a document URI", resultFolder(Uri.parse("content://$STORAGE/report.pdf")))
        assertNull(resultFolder(Uri.parse("file:///storage/emulated/0/Download/report.pdf")))
    }

    @Test
    fun `the size is what the provider reports, and 0 or none is unknown`() {
        val resolver = context.contentResolver
        assertEquals(ResultPlace(1_200_000, "Download"), resultPlace(resolver, doc(STORAGE, "primary:Download/report.pdf")))
        assertEquals(ResultPlace(null, "Download"), resultPlace(resolver, doc(STORAGE, "primary:Download/empty.pdf")))
        assertEquals(ResultPlace(null, null), resultPlace(resolver, Uri.parse("content://com.example.missing/report.pdf")))
    }

    private fun showResult(uri: Uri) {
        rule.setContent {
            SuccessView(
                message = "Compression complete", subMessage = "Optimized", result = ToolResult(uri, "report.pdf"),
                onDone = {}, onProcessMore = {}, onOpen = {}
            )
        }
        rule.waitForIdle()
    }

    /** Every text on screen, one entry per Text. */
    private fun lines(): List<String> = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    private val size get() = Formatter.formatShortFileSize(context, 1_200_000)

    @Test
    fun `the card shows the size and the folder under the file name`() {
        showResult(doc(STORAGE, "primary:Download/report.pdf"))
        rule.waitUntil(5_000) { "$size · Download" in lines() }
        val name = rule.onNode(hasText("report.pdf"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val line = rule.onNode(hasText("$size · Download"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val sub = rule.onNode(hasText("Optimized"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertFalse("the line is under the name and above the sub-message", line.top < name.bottom || sub.top < line.bottom)
    }

    @Test
    fun `the card shows the size alone when the folder is not reported, never the id`() {
        showResult(doc(DOWNLOADS, "msf:123"))
        rule.waitUntil(5_000) { size in lines() }
        assertFalse(lines().any { "msf" in it || "·" in it })
    }

    @Test
    fun `the card shows no line when neither is reported`() {
        showResult(Uri.parse("content://com.example.missing/report.pdf"))
        // The read runs off the main thread, which the rule's idling does not wait for: give it the time the other cases take.
        runCatching { rule.waitUntil(1_000) { lines().any { "·" in it || size in it } } }
        rule.onNode(hasText("report.pdf"), useUnmergedTree = true).assertExists()
        assertFalse(lines().any { "·" in it || size in it })
    }

    private companion object {
        const val STORAGE = "com.android.externalstorage.documents"
        const val DOWNLOADS = "com.android.providers.downloads.documents"
        val SIZES = mapOf("primary:Download/report.pdf" to 1_200_000L, "msf:123" to 1_200_000L, "primary:Download/empty.pdf" to 0L)
    }
}
