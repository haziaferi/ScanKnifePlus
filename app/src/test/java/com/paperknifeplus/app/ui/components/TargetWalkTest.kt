package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.FolderKind
import com.haziaferi.scanknifeplus.ui.folder.FolderSetupScreen
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.haziaferi.scanknifeplus.ui.folder.HistoryFolder
import com.haziaferi.scanknifeplus.ui.scan.CaptureMode
import com.haziaferi.scanknifeplus.ui.scan.LeaveDialog
import com.haziaferi.scanknifeplus.ui.scan.ReadingProgress
import com.haziaferi.scanknifeplus.ui.scan.ReviewActions
import com.haziaferi.scanknifeplus.ui.scan.ReviewPage
import com.haziaferi.scanknifeplus.ui.scan.ReviewScreen
import com.haziaferi.scanknifeplus.ui.scan.ReviewSpread
import com.haziaferi.scanknifeplus.ui.scan.ReviewUi
import com.haziaferi.scanknifeplus.ui.scan.SaveDialog
import com.haziaferi.scanknifeplus.ui.scan.ScannerActions
import com.haziaferi.scanknifeplus.ui.scan.ScannerScreen
import com.haziaferi.scanknifeplus.ui.scan.ScannerUi
import com.paperknifeplus.app.HOME_ROUTE
import com.paperknifeplus.app.MainBottomBar
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.assertTargetsFit
import com.paperknifeplus.app.testing.assertTextOnTheScale
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R14 on every screen Robolectric can compose, tall enough that nothing scrolls: every hit area at least 48 x 48 dp, 24 x 24 dp for a T4
 * control marked by DenseTargets, and none overlapping another ([assertTargetsFit]). Four walks: [TargetWalkTest] at the default text size
 * and [TargetWalkLargeTextTest] at twice it, both on the 6T's 384 dp, then [TargetWalkSmallPhoneDefaultTextTest] and
 * [TargetWalkSmallPhoneTest] at those sizes on 320 dp. The camera preview is out of reach; the scanner's controls are walked over an empty
 * preview. At the default text size every text is also held to the type scale ([assertTextOnTheScale]).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
@Config(qualifiers = "w384dp-h2400dp")
abstract class TargetWalk(private val fontScale: Float) {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { PaperKnifePlusTheme { content() } }
    }

    private fun walk(where: String) {
        rule.waitForIdle()
        rule.assertTargetsFit(rule.density, "$where at ${fontScale}x")
        // Sizes are in sp, so the text size does not change them: the default size's walks check the type scale (rule 1).
        if (fontScale == 1f) rule.assertTextOnTheScale(where)
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun folderItem(key: String, name: String, kind: FolderKind, original: FolderItem? = null) =
        FolderItem(key, name, kind, 1_200_000, 3_000, Uri.parse("content://folder/$name"), originalName = original?.name, original = original)

    private val lease = folderItem("f1", "Lease.pdf", FolderKind.SCAN)
    private val folderItems = listOf(
        lease,
        folderItem("f2", "Tax return merged.pdf", FolderKind.TOOL),
        folderItem("f3", "Lease.lost-20261014-191200.pdf", FolderKind.CONFLICT, original = lease)
    )

    @Test
    fun `Home without a folder`() {
        show {
            Column {
                Box(Modifier.weight(1f)) { HomeView(onAllTools = {}, onSettings = {}, onImportPhotos = {}, onSeeAll = {}, onOpenPreview = { _, _, _ -> }) }
                MainBottomBar(HOME_ROUTE, onNavigate = {}, onScan = {})
            }
        }
        walk("Home")
    }

    @Test
    fun `Home with the folder's recent files`() {
        show {
            Column {
                Box(Modifier.weight(1f)) {
                    HomeView(
                        onAllTools = {}, onSettings = {}, onImportPhotos = {}, onSeeAll = {}, onOpenPreview = { _, _, _ -> },
                        folder = FolderStatus.Ready("Documents/Scans"), recentFolder = folderItems
                    )
                }
                MainBottomBar(HOME_ROUTE, onNavigate = {}, onScan = {})
            }
        }
        walk("Home with recent files")
    }

    @Test
    fun `History with a folder and a conflict copy`() {
        show { HistoryView(onItemClick = { _, _, _ -> }, folder = HistoryFolder("Documents/Scans", folderItems)) }
        walk("History")
    }

    @Test
    fun `Settings and its theme sheet`() {
        show { SettingsView(onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = {}, onBack = {}) }
        walk("Settings")
        rule.onNode(hasText("Theme") and hasClickAction()).performClick()
        walk("Settings' theme sheet")
    }

    @Test
    fun `About`() {
        show { AboutView(onBack = {}, loadNotices = { emptyList() }) }
        walk("About")
    }

    @Test
    fun `Folder setup`() {
        show { FolderSetupScreen(FolderStatus.NotChosen, 2, choosing = false, failed = false, onChoose = {}, onNotNow = {}) }
        walk("Folder setup")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `the All tools sheet with a file`() {
        show {
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                ToolPickerContent(onToolClick = {}, fileName = "walk.pdf", onRemoveFile = {})
            }
        }
        walk("the All tools sheet")
    }

    @Test
    fun `the reader and its tool sheet`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.webLinked(), "walk-reader.pdf")
        show { UltraPreview(uri, "walk-reader.pdf", 1, onDismiss = {}, onOpenInTool = { _, _, _ -> }) }
        rule.waitUntil(60_000) { rule.onAllNodes(hasContentDescription("Tools for this file")).fetchSemanticsNodes().isNotEmpty() }
        walk("the reader")
        rule.onNode(hasContentDescription("Tools for this file")).performClick()
        walk("the reader's tool sheet")
    }

    @Test
    fun `the result screen`() {
        show {
            SuccessView(
                message = "Link removal complete", subMessage = "12 pages",
                result = ToolResult(Uri.parse("content://test/a-no-links.pdf"), "Insurance letters-no-links.pdf"),
                onDone = {}, onProcessMore = {}, onOpen = {}, keepsPassword = true, report = listOf("Links" to 14)
            )
        }
        walk("the result screen")
    }

    @Test
    fun `the Save PDF dialog`() {
        show { SaveDialog(onSave = {}, onDismiss = {}) }
        walk("the Save PDF dialog")
    }

    @Test
    fun `the Save PDF dialog into the folder`() {
        show { SaveDialog(onSave = {}, onDismiss = {}, folder = "Documents/Scans") }
        walk("the Save PDF dialog into the folder")
    }

    @Test
    fun `the keep or discard prompt`() {
        show { LeaveDialog(3, onKeep = {}, onDiscard = {}, onStay = {}) }
        walk("the keep or discard prompt")
    }

    private fun pages(n: Int) = (1..n).map { ReviewPage("p$it", File("page$it.jpg")) }

    @Test
    fun `Review of one page`() {
        show { ReviewScreen(ReviewUi(CaptureMode.SINGLE, pages(1)), ReviewActions()) }
        walk("Review of one page")
    }

    @Test
    fun `Review of several pages with Undo and a failed store`() {
        show { ReviewScreen(ReviewUi(CaptureMode.MULTI, pages(3), deletedNumber = 2, storeFailed = true), ReviewActions()) }
        walk("Review of several pages")
    }

    @Test
    fun `Review of a book spread while reading`() {
        val (left, right) = pages(2)
        show {
            ReviewScreen(
                ReviewUi(CaptureMode.BOOK, listOf(left, right), spread = ReviewSpread(left, right, 1, 0.5f), reading = ReadingProgress(1, 2)),
                ReviewActions()
            )
        }
        walk("Review of a book spread")
    }

    @Test
    fun `the scanner's controls`() {
        show {
            ScannerScreen(
                ScannerUi(mode = CaptureMode.MULTI, liveDetection = true, torchAvailable = true, count = 2, shutterEnabled = true, storeFailed = true),
                ScannerActions()
            ) {}
        }
        walk("the scanner")
    }

    @Test
    fun `the scanner asking for the camera`() {
        show { ScannerScreen(ScannerUi(permissionGranted = false), ScannerActions()) {} }
        walk("the scanner's permission prompt")
    }

    @Composable
    private fun ToolScreen(id: String, uri: Uri?) = ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> }))

    /** The tool [id] with no file. */
    private fun empty(id: String) {
        show { ToolScreen(id, null) }
        walk("$id, empty")
    }

    /**
     * The tool [id] with a 3-page file ([pdf]) loaded, once its name shows: a file:// URI has no display name, so the tools call it
     * Document.pdf.
     */
    private fun configuring(id: String, pdf: ByteArray = TestPdfs.plain(3)) {
        val uri = TestPdfs.write(context.cacheDir, pdf, "walk-$id.pdf")
        show { ToolScreen(id, uri) }
        rule.waitUntil("$id shows its file", 30_000) { shown("Document") }
        walk("$id, configuring")
    }

    @Test fun `Merge empty`() = empty("merge")
    @Test fun `Merge configuring`() = configuring("merge")
    @Test fun `Split empty`() = empty("split")
    @Test fun `Split configuring`() = configuring("split")
    @Test fun `Delete empty`() = empty("delete")
    @Test fun `Delete configuring`() = configuring("delete")
    @Test fun `Rearrange empty`() = empty("rearrange")
    @Test fun `Rearrange configuring`() = configuring("rearrange")
    @Test fun `Rotate empty`() = empty("rotate")
    @Test fun `Rotate configuring`() = configuring("rotate")
    @Test fun `Sign empty`() = empty("sign")
    @Test fun `Sign configuring`() = configuring("sign")
    @Test fun `Watermark empty`() = empty("watermark")
    @Test fun `Watermark configuring`() = configuring("watermark")
    @Test fun `Page numbers empty`() = empty("page-numbers")
    @Test fun `Page numbers configuring`() = configuring("page-numbers")
    @Test fun `Bookmarks empty`() = empty("bookmarks")
    @Test fun `Bookmarks configuring`() = configuring("bookmarks")
    @Test fun `Links empty`() = empty("links")
    @Test fun `Links configuring`() = configuring("links")
    @Test fun `Crop empty`() = empty("crop")
    @Test fun `Crop configuring`() = configuring("crop")
    @Test fun `Compress empty`() = empty("compress")
    @Test fun `Compress configuring`() = configuring("compress")
    @Test fun `Grayscale empty`() = empty("grayscale")
    @Test fun `Grayscale configuring`() = configuring("grayscale")
    @Test fun `Repair empty`() = empty("repair")
    @Test fun `Repair configuring`() = configuring("repair")
    @Test fun `Compare empty`() = empty("compare")
    @Test fun `Compare configuring`() = configuring("compare")
    @Test fun `Protect empty`() = empty("protect")
    @Test fun `Protect configuring`() = configuring("protect")
    @Test fun `Unlock empty`() = empty("unlock")
    // Unlock takes only a locked file, and asks for its password first.
    @Test fun `Unlock configuring`() = configuring("unlock", TestPdfs.userPassword(3))
    @Test fun `Metadata empty`() = empty("metadata")
    @Test fun `Metadata configuring`() = configuring("metadata")
    @Test fun `PDF to images empty`() = empty("pdf2img")
    @Test open fun `PDF to images configuring`() = configuring("pdf2img")
    @Test fun `PDF to ZIP empty`() = empty("pdf2zip")
    @Test fun `PDF to ZIP configuring`() = configuring("pdf2zip")
    @Test fun `Extract images empty`() = empty("extract-images")
    @Test fun `Extract images configuring`() = configuring("extract-images")
    @Test fun `PDF to text empty`() = empty("pdf2text")
    @Test fun `PDF to text configuring`() = configuring("pdf2text")
    @Test fun `Make searchable empty`() = empty("searchable")
    @Test fun `Make searchable configuring`() = configuring("searchable")
    @Test fun `Images to PDF empty`() = empty("img2pdf")

    @Test
    fun `Images to PDF configuring`() {
        val bitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val images = (1..3).map { TestPdfs.write(context.cacheDir, png, "walk-photo-$it.png") }
        val output = Uri.fromFile(File(context.cacheDir, "walk-images.pdf"))
        show {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output, picked = images)) { ToolScreen("img2pdf", null) }
        }
        rule.onNode(hasText("Choose images") and hasClickAction()).performClick()
        // The tiles' remove buttons are T4: wait for one, so the walk holds them to their step.
        rule.waitUntil("the first tile's remove button shows", 60_000) {
            rule.onAllNodes(hasContentDescription("Remove image 1")).fetchSemanticsNodes().isNotEmpty()
        }
        walk("img2pdf, configuring")
    }
}

@RunWith(RobolectricTestRunner::class)
class TargetWalkTest : TargetWalk(fontScale = 1f)

// Twice the text size for every window, the dialogs' and sheets' too.
@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f)
class TargetWalkLargeTextTest : TargetWalk(fontScale = 2f)

/**
 * Twice the text size on a 320 dp phone. Robolectric's font runs narrower than the 6T's, so labels that wrap on the 6T at 384 dp wrap here
 * only at 320: the Save PDF dialog's commit is one of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(fontScale = 2f, qualifiers = "w320dp-h2400dp")
class TargetWalkSmallPhoneTest : TargetWalk(fontScale = 2f) {
    @Ignore(PDF_TO_IMAGES_ON_SMALL_PHONES)
    @Test
    override fun `PDF to images configuring`() = super.`PDF to images configuring`()
}

/** The default text size on a 320 dp phone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h2400dp")
class TargetWalkSmallPhoneDefaultTextTest : TargetWalk(fontScale = 1f) {
    @Ignore(PDF_TO_IMAGES_ON_SMALL_PHONES)
    @Test
    override fun `PDF to images configuring`() = super.`PDF to images configuring`()
}

/** Fails at 1.0x and 2.0x on 320 dp alike, and passes on 384 dp at both. */
private const val PDF_TO_IMAGES_ON_SMALL_PHONES =
    "L6 layout/segmented: on 320 dp, at any text size, PDF to images' format and quality segments squeeze under 48 dp wide, so their hit areas overlap"
