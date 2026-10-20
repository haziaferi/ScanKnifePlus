package com.paperknifeplus.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Decision 27d in the reader: a web link tapped inside a PDF asks first, like every other link in the app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderLinkTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun `a web link in a PDF opens only after the user confirms it`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.webLinked(), "web-link.pdf")
        rule.setContent { UltraPreview(uri, "web-link.pdf", 1, onDismiss = {}, onOpenInTool = { _, _, _ -> }) }
        rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription(TestPdfs.WEB_LINK).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithContentDescription(TestPdfs.WEB_LINK).performClick()
        rule.onNodeWithText("Open this link in your browser?").assertExists()
        assertNull("nothing opens before Open", shadowOf(rule.activity).nextStartedActivity)

        rule.onNode(hasText("Open") and hasClickAction()).performClick()
        val started = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(TestPdfs.WEB_LINK, started.dataString)
    }
}
