package com.paperknifeplus.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import android.app.Activity
import android.content.ClipData
import androidx.lifecycle.ViewModelProvider
import com.haziaferi.scanknifeplus.ui.folder.FolderHolder
import com.haziaferi.scanknifeplus.ui.scan.CaptureMode
import com.haziaferi.scanknifeplus.ui.scan.ScanFlowHolder
import com.haziaferi.scanknifeplus.ui.scan.ScanPhase
import org.robolectric.Shadows.shadowOf
import com.haziaferi.scanknifeplus.ui.folder.PrefsSetupSeen
import com.haziaferi.scanknifeplus.ui.folder.SetupSeen
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.ui.components.PreferencesManager
import com.paperknifeplus.app.ui.components.SessionManager
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.ThemeMode
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

    /** Folder setup's answer, in memory, so no preferences an earlier test wrote can reach this one's activity. */
    private val setup = object : SetupSeen {
        var seen = false
        override fun get() = seen
        override fun set() {
            seen = true
        }
    }

    @Before
    fun setUp() {
        FolderHolder.setupSeenOf = { setup }
        Robolectric.setupContentProvider(PdfProvider::class.java, AUTHORITY)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        SessionManager.clearHistory()
        FolderHolder.setupSeenOf = ::PrefsSetupSeen
    }

    private fun pdf(name: String): Uri {
        TestPdfs.write(context.cacheDir, TestPdfs.plain(2), name)
        return Uri.parse("content://$AUTHORITY/$name")
    }

    private fun view(uri: Uri, type: String? = "application/pdf") =
        Intent(Intent.ACTION_VIEW).setClass(context, MainActivity::class.java).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Launches the app; [setupSeen] stands for an earlier "Not now" on Folder setup, which otherwise opens by itself. */
    private fun launch(intent: Intent = Intent(context, MainActivity::class.java), setupSeen: Boolean = true) {
        setup.seen = setupSeen
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        rule.waitForIdle()
    }

    private fun shows(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The open screen's lazy list: Home scrolls without ScrollToIndex and the pager scrolls sideways. */
    private val verticalList = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

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
    fun `a new Open with file replaces the previous one's unlock prompt`() {
        TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "locked.pdf")
        launch(view(Uri.parse("content://$AUTHORITY/locked.pdf")))
        rule.waitUntil(10_000) { shows("Unlock") }

        controller!!.newIntent(view(pdf("open.pdf")))
        waitFor("open.pdf")
        rule.waitForIdle()
        assertFalse(shows("Unlock"))
    }

    @Test
    fun `a private file handed to the activity is not opened`() {
        val private = File(context.filesDir, "scans/x.pdf").apply { parentFile!!.mkdirs(); writeBytes(TestPdfs.plain()) }
        launch(view(Uri.fromFile(private)))
        rule.waitForIdle()
        assertFalse(shows("PDF viewer"))
    }

    @Test
    fun `an open tool survives recreation`() {
        launch()
        rule.onNode(hasContentDescription("All tools")).performClick()
        openCompress()
        waitFor("Optimize PDF size")

        controller = controller!!.recreate()
        rule.waitForIdle()
        assertTrue(shows("Optimize PDF size"))
    }

    @Test
    fun `the bottom bar switches tabs and Scan opens the scan route`() {
        launch()
        rule.onNode(hasText("Home") and isSelectable()).assertIsSelected()
        rule.onNode(hasText("History") and isSelectable()).performClick()
        waitFor("Tool outputs")
        rule.onNode(hasText("History") and isSelectable()).assertIsSelected()
        rule.onNode(hasText("Home") and isSelectable()).assertIsNotSelected()

        rule.onNode(hasText("Scan") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { !shows("Home") }

        rule.runOnUiThread { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        waitFor("Home")
        rule.onNode(hasText("History") and isSelectable()).assertIsSelected()
    }

    @Test
    fun `a first launch opens Folder setup, and Not now leaves it for Home for good`() {
        launch(setupSeen = false)
        waitFor("Choose where your documents live")
        rule.onNode(hasText("Not now · save each file with the picker")).performClick()
        waitFor("Scan a document")
        rule.onNode(hasText("Choose a folder")).assertExists()

        assertTrue("Not now is remembered", setup.seen)
        controller?.pause()?.stop()?.destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitFor("Scan a document")
        assertFalse(shows("Choose where your documents live"))
    }

    @Test
    fun `a PDF handed over on a first launch opens in the reader, not Folder setup`() {
        launch(view(pdf("handed.pdf")), setupSeen = false)
        waitFor("handed.pdf")
        assertFalse(shows("Choose where your documents live"))
    }

    @Test
    fun `Folder setup opens from the folder bar and from Settings and goes back where it came from`() {
        launch()
        rule.onNode(hasText("Choose a folder")).performClick()
        waitFor("Choose where your documents live")
        rule.runOnUiThread { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        waitFor("Scan a document")

        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Appearance")
        rule.onNode(verticalList).performScrollToNode(hasText("Folder"))
        rule.onNode(hasText("Folder")).performClick()
        rule.onNode(hasText("Choose folder") and hasClickAction()).performClick()
        waitFor("Choose where your documents live")
        rule.runOnUiThread { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        waitFor("Appearance")
    }

    @Test
    fun `Import photos opens the scan flow in Review with the picked images`() {
        launch()
        rule.onNode(hasText("Import photos")).performClick()
        val activity = controller!!.get()
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        val picked = listOf(Uri.parse("content://$AUTHORITY/a.jpg"), Uri.parse("content://$AUTHORITY/b.jpg"))
        val result = Intent().apply {
            clipData = ClipData.newRawUri(null, picked[0]).apply { addItem(ClipData.Item(picked[1])) }
        }
        rule.runOnUiThread { shadowOf(activity).receiveResult(request, Activity.RESULT_OK, result) }
        rule.waitUntil(10_000) { !shows("Home") }
        val model = ViewModelProvider(activity)[ScanFlowHolder::class.java].model!!
        assertEquals(ScanPhase.REVIEW, model.phase)
        assertEquals(CaptureMode.MULTI, model.mode)
    }

    @Test
    fun `Settings opens from the header and About goes back to it`() {
        launch()
        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Appearance")
        rule.onNode(verticalList).performScrollToNode(hasText("About ScanKnife+"))
        rule.onNode(hasText("About ScanKnife+")).performClick()
        rule.waitUntil(10_000) { !shows("Appearance") }
        rule.onAllNodes(hasContentDescription("Back")).onFirst().performClick()
        waitFor("Appearance")
        rule.runOnUiThread { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        waitFor("Scan a document")
    }

    @Test
    fun `All tools offers the last-viewed file as a removable chip`() {
        launch(view(pdf("viewed.pdf")))
        waitFor("viewed.pdf")
        rule.onAllNodes(hasContentDescription("Back")).onFirst().performClick()
        rule.waitUntil(10_000) { !shows("PDF viewer") }

        // Kept, the chip's file opens in the tool.
        rule.onNode(hasContentDescription("All tools")).performClick()
        waitFor("Opens with")
        assertTrue(shows("viewed.pdf"))
        openCompress()
        waitFor("Optimization level")
        rule.runOnUiThread { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        waitFor("Scan a document")

        // Removed, the tool opens without it.
        rule.onNode(hasContentDescription("All tools")).performClick()
        waitFor("Opens with")
        rule.onNode(hasContentDescription("Remove viewed.pdf")).performClick()
        rule.waitUntil(10_000) { !shows("Opens with") }
        openCompress()
        waitFor("Choose PDF")
        rule.waitForIdle()
        assertFalse(shows("Optimization level"))
    }

    private fun openCompress() {
        rule.onNode(verticalList).performScrollToNode(hasText("Compress"))
        rule.onNode(hasText("Compress")).performClick()
    }

    @Test
    fun `the window background is the default palette's Light background`() = assertWindowBackground(AppPalette.STILL_WATER.light.bg.toArgb())

    @Test
    @Config(qualifiers = "night")
    fun `the window background is the default palette's OLED background in night mode`() =
        assertWindowBackground(AppPalette.STILL_WATER.oled.bg.toArgb())

    @Test
    fun `the window background is the stored palette's Light background`() {
        PreferencesManager.setPalette(context, AppPalette.ROSE)
        PreferencesManager.setTheme(context, ThemeMode.LIGHT.stored)
        assertDecorBackground(AppPalette.ROSE.light.bg.toArgb())
    }

    @Test
    fun `the window background is the stored palette's OLED background`() {
        PreferencesManager.setPalette(context, AppPalette.GRAPHITE_SCHOLASTIC)
        PreferencesManager.setTheme(context, ThemeMode.OLED.stored)
        assertDecorBackground(AppPalette.GRAPHITE_SCHOLASTIC.oled.bg.toArgb())
    }

    @Test
    fun `the window background follows a palette and theme change in Settings at once`() {
        try {
            launch()
            val window = controller!!.get().window
            rule.onNode(hasContentDescription("Settings")).performClick()
            waitFor("Appearance")
            rule.onNode(hasText("Colour palette") and hasClickAction()).performClick()
            rule.onNode(hasText("Rose") and hasClickAction()).performClick()
            rule.waitForIdle()
            assertEquals(AppPalette.ROSE.light.bg.toArgb(), (window.decorView.background as ColorDrawable).color)
            rule.onNode(hasText("Theme") and hasClickAction()).performClick()
            rule.onNode(hasText("OLED") and hasClickAction()).performClick()
            rule.waitForIdle()
            assertEquals(AppPalette.ROSE.oled.bg.toArgb(), (window.decorView.background as ColorDrawable).color)
        } finally {
            PreferencesManager.setPalette(context, AppPalette.DEFAULT)
            PreferencesManager.setTheme(context, ThemeMode.SYSTEM.stored)
        }
    }

    private fun assertDecorBackground(color: Int) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(color, (controller!!.get().window.decorView.background as ColorDrawable).color)
    }

    /** The app's theme sets the colour itself (the platform default is a color resource). */
    private fun assertWindowBackground(color: Int) {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        assertEquals(color, (activity.window.decorView.background as ColorDrawable).color)
        val value = TypedValue()
        assertTrue(activity.theme.resolveAttribute(android.R.attr.windowBackground, value, true))
        assertEquals(color, value.data)
        assertEquals(0, value.resourceId)
    }

    @Test
    fun `a closed Open with file does not reopen after recreation`() {
        launch(view(pdf("handed.pdf")))
        waitFor("handed.pdf")
        rule.onAllNodes(hasContentDescription("Back")).onFirst().performClick()
        rule.waitUntil(10_000) { !shows("PDF viewer") }

        controller = controller!!.recreate()
        rule.waitForIdle()
        assertFalse(shows("PDF viewer"))
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
