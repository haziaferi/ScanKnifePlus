package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Delete, Rearrange, Rotate and Split keep the source's open password, and say so as Links and Crop do: the password line on the file card
 * and on the result card for a protected source, and none for a file that opens without one, even when a password was handed over.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordLineTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val output = File(context.cacheDir, "password-line-out.pdf")

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        output.delete()
    }

    /** A save picker the user picks [output] in. */
    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Uri.fromFile(output))
            }
        }
    }

    private fun show(tool: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { tool() }
    }

    private fun waitFor(text: String) = rule.waitUntil(60_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun locked(pages: Int = 3) = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages), "password-line-locked.pdf")

    private fun plain() = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "password-line-plain.pdf")

    private fun unlockAtPrompt() {
        waitFor("Password required")
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
    }

    /** The saved copy needs the password, and opened with it has [pages] pages. */
    private fun assertSavedWithPassword(pages: Int) {
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output).close() }
        PDDocument.load(output, TestPdfs.USER_PASSWORD).use {
            assertTrue(it.isEncrypted)
            assertEquals(pages, it.numberOfPages)
        }
    }

    @Test
    fun `Delete says a protected source's copy keeps its password, and keeps it`() {
        show { DeleteView(initialUri = locked(), initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("0 / 3 marked for deletion")
        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        rule.onNodeWithText("Enter range").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("1")
        rule.onNodeWithText("Delete 1 pages").performClick()
        waitFor("Delete complete")

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        assertSavedWithPassword(2)
    }

    @Test
    fun `Delete says nothing of a password for a file that opens without one`() {
        show { DeleteView(initialUri = plain(), onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("0 / 2 marked for deletion")
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
    }

    @Test
    fun `Rotate says a protected source's copy keeps its password, and keeps it`() {
        show { RotateView(initialUri = locked(), onBack = {}, onOpenPreview = { _, _, _ -> }) }
        unlockAtPrompt()
        waitFor(SAVE)
        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        rule.onNodeWithText(SAVE).performClick()
        waitFor("Rotate complete")

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        assertSavedWithPassword(3)
    }

    @Test
    fun `Rotate says nothing of a password for a file that opens without one`() {
        show { RotateView(initialUri = plain(), onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor(SAVE)
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
    }

    @Test
    fun `Rearrange says a protected source's copy keeps its password, and keeps it`() {
        show { RearrangeView(initialUri = locked(), initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor(SAVE)
        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        rule.onNodeWithText(SAVE).performClick()
        waitFor("Rearrange complete")

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        assertSavedWithPassword(3)
    }

    @Test
    fun `Rearrange says nothing of a password handed over for a file that opens without one`() {
        show { RearrangeView(initialUri = plain(), initialPassword = "not-needed", onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor(SAVE)
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        rule.onNodeWithText(SAVE).performClick()
        waitFor("Rearrange complete")
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        PDDocument.load(output).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun `Split says a protected source's copy keeps its password, and keeps it`() {
        show { SplitView(initialUri = locked(), initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("0 / 3 pages selected")
        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Extract 3 pages").performClick()
        waitFor("Split complete")

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        assertSavedWithPassword(3)
    }

    @Test
    fun `Split says nothing of a password handed over for a file that opens without one`() {
        show { SplitView(initialUri = plain(), initialPassword = "not-needed", onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("0 / 2 pages selected")
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
    }

    private companion object {
        const val SAVE = "Save PDF"
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
    }
}
