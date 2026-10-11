package com.paperknifeplus.app.ui.components

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Layers
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    /** Reports ZIP for *.zip paths and PDF otherwise, as a document provider would. */
    class TypedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = if (uri.path.orEmpty().endsWith(".zip")) "application/zip" else "application/pdf"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun `recent PDFs open in the reader and ZIP outputs open History`() {
        Robolectric.setupContentProvider(TypedProvider::class.java, "com.paperknifeplus.app.test.typed")
        val pdf = Uri.parse("content://com.paperknifeplus.app.test.typed/merged.pdf")
        val zip = Uri.parse("content://com.paperknifeplus.app.test.typed/pages.zip")
        val history = listOf(
            ActivityEntry("1", "pages.zip", "PDF to ZIP", "3 pages", Icons.Filled.FolderZip, zip, 3),
            ActivityEntry("2", "merged.pdf", "Merge", "2 files", Icons.Filled.Layers, pdf, 4),
        )
        var opened: Uri? = null
        var historyOpened = 0
        rule.setContent { MiniHistoryBar(history, onHistoryClick = { historyOpened++ }, onOpenPreview = { uri, _, _ -> opened = uri }) }

        rule.onNodeWithText("pages.zip").performClick()
        assertNull(opened)
        assertEquals(1, historyOpened)

        rule.onNodeWithText("merged.pdf").performClick()
        assertEquals(pdf, opened)
        assertEquals(1, historyOpened)
    }
}
