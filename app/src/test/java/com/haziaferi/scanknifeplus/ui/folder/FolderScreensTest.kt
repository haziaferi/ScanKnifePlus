package com.haziaferi.scanknifeplus.ui.folder

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Folder setup and Home's folder bar. */
@RunWith(RobolectricTestRunner::class)
class FolderScreensTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun setup(
        status: FolderStatus = FolderStatus.NotChosen,
        scans: Int = 0,
        failed: Boolean = false,
        onChoose: () -> Unit = {},
        onNotNow: () -> Unit = {}
    ) = rule.setContent { FolderSetupScreen(status, scans, choosing = false, failed = failed, onChoose = onChoose, onNotNow = onNotNow) }

    @Test
    fun `setup explains the folder, says the scans on the phone will be copied, and offers Choose folder and Not now`() {
        var chosen = 0
        var later = 0
        setup(scans = 3, onChoose = { chosen++ }, onNotNow = { later++ })
        rule.onNodeWithText("Choose where your documents live").assertExists()
        rule.onNodeWithText("Nothing is uploaded by the app", substring = true).assertExists()
        rule.onNodeWithText("Scan sources/ · pages you can edit later").assertExists()
        rule.onNodeWithText("The 3 scans already on this phone will be copied into the folder. Existing files are never overwritten.").assertExists()
        rule.onNodeWithText("Choose folder").performClick()
        rule.onNodeWithText("Not now · save each file with the picker").performClick()
        assertEquals(1, chosen)
        assertEquals(1, later)
    }

    @Test
    fun `without scans on the phone there is no copy notice`() {
        setup(scans = 0)
        rule.onNodeWithText("will be copied", substring = true).assertDoesNotExist()
    }

    @Test
    fun `with a folder set, the way out keeps it, and a refused folder says why`() {
        setup(status = FolderStatus.Ready("Documents/Scans"), scans = 1, failed = true)
        rule.onNodeWithText("Documents/Scans").assertExists()
        rule.onNodeWithText("The 1 scan already on this phone", substring = true).assertExists()
        rule.onNodeWithText("Keep the current folder").assertExists()
        rule.onNodeWithText("ScanKnife+ can't save into that folder. Choose a folder it can write to.").assertExists()
    }

    @Test
    fun `while a picked folder is adopted Choose folder waits`() {
        rule.setContent { FolderSetupScreen(FolderStatus.NotChosen, 0, choosing = true, failed = false, onChoose = {}, onNotNow = {}) }
        rule.onNodeWithText("Choose folder").assertIsNotEnabled()
    }

    @Test
    fun `Choose folder opens the system folder picker for a lasting read and write grant, and Not now ends setup for good`() {
        val folder = AppFolder(rule.activity, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        var done = 0
        rule.setContent { FolderSetup(folder, onDone = { done++ }) }
        rule.onNodeWithText("Choose folder").performClick()
        val request = shadowOf(rule.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, request.action)
        val wanted = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(wanted, request.flags and wanted)
        assertEquals("a picker that has not answered ends nothing", 0, done)

        assertTrue(folder.offersSetup)
        rule.onNodeWithText("Not now · save each file with the picker").performClick()
        assertEquals(1, done)
        assertFalse(folder.offersSetup)
    }

    @Test
    fun `the folder bar says where files go, or what to do, and opens setup`() {
        val status = mutableStateOf<FolderStatus>(FolderStatus.Ready("Documents/Scans"))
        var opened = 0
        rule.setContent { FolderBar(status.value, onClick = { opened++ }) }
        rule.onNodeWithText("Saving to Documents/Scans").assertHasClickAction()
        rule.onNodeWithText("Change").assertExists()
        rule.onNodeWithText("Saving to Documents/Scans").performClick()
        assertEquals(1, opened)

        status.value = FolderStatus.NotChosen
        rule.onNodeWithText("Choose a folder").assertExists()
        rule.onNodeWithText("Change").assertDoesNotExist()

        status.value = FolderStatus.ChooseAgain("Documents/Scans")
        rule.onNodeWithText("Can't reach Documents/Scans").assertExists()
        rule.onNodeWithText("Choose again").assertExists()

        status.value = FolderStatus.ChooseAgain(null)
        rule.onNodeWithText("Can't reach your folder").assertExists()

        status.value = FolderStatus.Copying("Documents/Scans", 3)
        rule.onNodeWithText("Copying 3 scans into Documents/Scans…").assertExists()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
    fun `at 2x text the folder bar wraps its text and keeps Change beside it`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                FolderBar(FolderStatus.Ready("Documents/Scans/Receipts and letters"), onClick = {})
            }
        }
        val text = rule.onNodeWithText("Saving to Documents/Scans/Receipts and letters", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val change = rule.onNodeWithText("Change", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Change overlaps the text", text.right <= change.left)
        assertTrue("the text wraps instead of clipping", text.height > change.height)
    }
}
