package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Links, Remove on its screen: the counts before saving, a file with nothing to remove, a protected file, and Done's report rows. */
@RunWith(RobolectricTestRunner::class)
class LinksViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val output = File(context.cacheDir, "links-view-out.pdf")
    private var suggested: Any? = null

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        SessionManager.clearHistory()
        output.delete()
    }

    /** A save picker the user picks [output] in; it remembers the suggested name. */
    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                suggested = input
                dispatchResult(requestCode, Uri.fromFile(output))
            }
        }
    }

    private fun show(bytes: ByteArray, name: String, password: String? = null): Uri {
        val input = TestPdfs.write(context.cacheDir, bytes, name)
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                LinksView(initialUri = input, initialPassword = password, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        return input
    }

    private fun waitFor(text: String) = rule.waitUntil(60_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** The node read as [label] with [count]: a count tile before saving, a report row on Done. */
    private fun counted(label: String, count: Int): SemanticsNodeInteraction = rule.onNode(hasText(label) and hasText("$count"))

    @Test
    fun `the counts show before saving, and nothing is written until the button`() {
        show(TestPdfs.linksAndScript(), "counted.pdf")
        waitFor(REMOVE)

        counted("links", TestPdfs.SCRIPTED_LINKS).assertExists()
        counted("script that runs when the file opens", 1).assertExists()
        rule.onNodeWithText("Nothing changes until you save. Form fields, comments and bookmarks stay.").assertExists()
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        assertNull(suggested)
        assertFalse(output.exists())
        assertTrue(SessionManager.history.isEmpty())
    }

    @Test
    fun `Done's report rows are what the engine removed`() {
        val bytes = TestPdfs.linksAndScript()
        val expected = PDDocument.load(bytes).use { removeLinks(it) }
        val input = show(bytes, "reported.pdf")
        waitFor(REMOVE)
        rule.onNodeWithText(REMOVE).performClick()
        waitFor("Link removal complete")

        assertEquals(pdfBaseName(getUriDetails(context, input).name) + "-no-links.pdf", suggested)
        counted("Links", expected.links).assertExists()
        counted("Scripts that run when the file opens", expected.scripts).assertExists()
        rule.onNodeWithText("2 pages").assertExists()
        PDDocument.load(output).use { assertEquals(LinkRemoval(0, 0), removeLinks(it)) }
        assertEquals("links", SessionManager.history.single().toolId)
    }

    @Test
    fun `a file with nothing to remove says so, saves nothing and drops the password line`() {
        show(TestPdfs.userPassword(), "nothing.pdf", TestPdfs.USER_PASSWORD)
        waitFor("No links or scripts in this file")

        rule.onNodeWithText("There is nothing to remove, so nothing is saved.").assertExists()
        rule.onNodeWithText(REMOVE).assertDoesNotExist()
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        rule.onNodeWithText("Nothing to remove").assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertNull(suggested)
        assertFalse(output.exists())
        assertTrue(SessionManager.history.isEmpty())
    }

    @Test
    fun `a protected file asks for its password, says the copy keeps it, and the copy opens only with it`() {
        show(TestPdfs.linksAndScript(TestPdfs.USER_PASSWORD), "locked.pdf")
        waitFor("Password required")
        rule.onNode(hasSetTextAction()).performTextInput("wrong")
        rule.onNodeWithText("Unlock").performClick()
        waitFor("Incorrect password, try again.")
        rule.onNode(hasSetTextAction()).performTextReplacement(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        waitFor(REMOVE)

        counted("links", TestPdfs.SCRIPTED_LINKS).assertExists()
        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        rule.onNodeWithText(REMOVE).performClick()
        waitFor("Link removal complete")

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output).close() }
        PDDocument.load(output, TestPdfs.USER_PASSWORD).use {
            assertTrue(it.isEncrypted)
            assertEquals(LinkRemoval(0, 0), removeLinks(it))
        }
    }

    private companion object {
        const val REMOVE = "Remove links and save PDF"
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
    }
}
