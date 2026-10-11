package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SplitViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun `previews of the original encrypted file get the password the preview handed over`() {
        val original = Uri.parse("content://docs/locked.pdf")
        val copy = Uri.parse("file:///cache/decrypted_1.pdf")
        assertEquals("pw", splitPreviewPassword(original, null, "pw"))
        assertNull(splitPreviewPassword(copy, copy, "pw"))
        assertNull(splitPreviewPassword(original, null, ""))
    }

    @Test
    fun `opened with an encrypted file and its password, Split selects pages without asking`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-split.pdf")
        rule.setContent { SplitView(initialUri = uri, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        rule.waitUntil(10_000) { rule.onAllNodes(hasText("PAGES SELECTED", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("0 / 2 PAGES SELECTED").assertExists()
        rule.onNodeWithText("Password Required").assertDoesNotExist()
    }
}
