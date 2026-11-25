package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.scan.SaveDialog
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.assertOneChoice
import com.paperknifeplus.app.testing.assertTargetsFit
import com.paperknifeplus.app.testing.stacked
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The choices R2 puts on [SegmentedChoice] in L6, by screen; [labels] are their segments' labels in the app's language. */
enum class SegmentedPlace(val labels: (Context) -> List<String>) {
    FORMAT({ IMAGE_FORMATS }),
    QUALITY({ it.strings(R.string.pdf2img_quality_standard, R.string.pdf2img_quality_hd, R.string.pdf2img_quality_ultra) }),
    IMAGES_PAGE_SIZE({ it.strings(R.string.img2pdf_original, R.string.img2pdf_a4_paper) }),
    TOOL_LANGUAGE({ it.strings(R.string.scan_ocr_english, R.string.scan_ocr_italian) }),
    TOOL_READING({ it.strings(R.string.scan_ocr_automatic, R.string.scan_ocr_tables, R.string.scan_ocr_receipts) }),
    DIALOG_PAGE_SIZE({ it.strings(R.string.scan_size_a4, R.string.scan_size_letter, R.string.scan_size_legal, R.string.scan_size_fit) }),
    DIALOG_LANGUAGE({ TOOL_LANGUAGE.labels(it) }),
    DIALOG_READING({ TOOL_READING.labels(it) })
}

private fun Context.strings(vararg ids: Int) = ids.map(::getString)

private const val ROW = false
private const val STACKED = true

/**
 * L6's choices on screen at the configuration's text size, language and width, in a window tall enough that nothing scrolls: every choice
 * one selectable group of radio buttons with one selected, every hit area at least T2 and none overlapping another ([assertTargetsFit]), and
 * each choice one row or stacked as expected. The expectations are wt/_briefs/layout/l6-measure/fit.txt's verdicts, measured in the app's font
 * against the width each screen gives, where Robolectric draws the same: its font runs narrower than the 6T's, so a few close cases draw one
 * row here that stack on the phone, and those are left out where they occur.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so the labels have their widths.
abstract class ChoiceLayout {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** [content] at the configuration's text size, the content window's too, in the app's theme. */
    protected fun show(content: @Composable () -> Unit) = rule.setContent {
        val scale = LocalConfiguration.current.fontScale
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) { PaperKnifePlusTheme { content() } }
    }

    /** The checks above for [places] on the screen shown as [where]; [expected] says which are one row ([ROW]) and which stack. */
    protected fun check(where: String, expected: Map<SegmentedPlace, Boolean>, vararg places: SegmentedPlace) {
        rule.waitForIdle()
        rule.assertTargetsFit(rule.density, where)
        val failures = mutableListOf<String>()
        for (place in places) {
            val segments = rule.assertOneChoice(place.labels(context))
            val stacks = stacked(segments)
            val width = segments.first().parent!!.boundsInRoot.width / rule.density.density
            // The layout each run drew, for the report beside fit.txt's figures.
            println("L6 $where $place: ${if (stacks) "stacked" else "one row"} in ${"%.0f".format(width)} dp")
            val want = expected[place] ?: continue
            if (want != stacks) failures += "$place should be ${if (want) "stacked" else "one row"}"
        }
        assertEquals("$where: ${failures.joinToString()}", emptyList<String>(), failures)
    }
}

/**
 * The three tools' choices ([ChoiceLayout]) at the width, language and text size a subclass gives, against [expected]. The scanner's Save
 * dialog is walked here too, for its targets; its widths are held in [SaveDialogLayoutTest].
 */
abstract class SegmentedLayout(private val expected: Map<SegmentedPlace, Boolean>) : ChoiceLayout() {
    private val name = javaClass.simpleName

    @Composable
    private fun ToolScreen(id: String, uri: Uri?) = ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> }))

    /** The tool [id] with a 3-page file loaded, once its [first] choice shows. */
    private fun configuring(id: String, first: SegmentedPlace) {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "segmented-$id.pdf")
        show { ToolScreen(id, uri) }
        val label = first.labels(context).first()
        rule.waitUntil("$id shows its options", 60_000) { rule.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `PDF to images' format and quality`() {
        configuring("pdf2img", SegmentedPlace.FORMAT)
        check("$name PDF to images", expected, SegmentedPlace.FORMAT, SegmentedPlace.QUALITY)
    }

    @Test
    fun `Images to PDF's page size`() {
        val bitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val images = (1..2).map { TestPdfs.write(context.cacheDir, png, "segmented-photo-$it.png") }
        val output = Uri.fromFile(File(context.cacheDir, "segmented-images.pdf"))
        show {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output, picked = images)) { ToolScreen("img2pdf", null) }
        }
        rule.onNode(hasText(context.getString(R.string.tool_pick_images)) and hasClickAction()).performClick()
        rule.waitUntil("the images show", 60_000) {
            rule.onAllNodes(hasContentDescription(context.getString(R.string.action_remove_image, 1))).fetchSemanticsNodes().isNotEmpty()
        }
        check("$name Images to PDF", expected, SegmentedPlace.IMAGES_PAGE_SIZE)
    }

    @Test
    fun `Make searchable's language and reading`() {
        configuring("searchable", SegmentedPlace.TOOL_LANGUAGE)
        check("$name Make searchable", expected, SegmentedPlace.TOOL_LANGUAGE, SegmentedPlace.TOOL_READING)
    }

    @Test
    fun `the Save dialog's page size, language and reading`() {
        show { SaveDialog(onSave = {}, onDismiss = {}) }
        check("$name the Save dialog", emptyMap(), SegmentedPlace.DIALOG_PAGE_SIZE, SegmentedPlace.DIALOG_LANGUAGE, SegmentedPlace.DIALOG_READING)
    }
}

// The tools give a choice the screen's width less 40 dp: 344 dp on the 6T (384 dp), 280 dp on a 320 dp phone.

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h2400dp")
class SegmentedLayoutTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to ROW
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h2400dp", fontScale = 1.3f)
class SegmentedLayoutLargerTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to ROW
    )
)

// Quality (360 dp of 344 in the 6T's font) draws one row here.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h2400dp", fontScale = 2f)
class SegmentedLayoutLargeTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h2400dp")
class SegmentedLayoutSmallPhoneDefaultTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h2400dp", fontScale = 1.3f)
class SegmentedLayoutSmallPhoneLargerTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h2400dp", fontScale = 2f)
class SegmentedLayoutSmallPhoneTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to STACKED, SegmentedPlace.QUALITY to STACKED, SegmentedPlace.IMAGES_PAGE_SIZE to STACKED,
        SegmentedPlace.TOOL_LANGUAGE to ROW, SegmentedPlace.TOOL_READING to STACKED
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it-w384dp-h2400dp")
class SegmentedLayoutItalianTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to ROW
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it-w384dp-h2400dp", fontScale = 1.3f)
class SegmentedLayoutItalianLargerTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

// Quality (360 dp of 344 in the 6T's font) draws one row here.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it-w384dp-h2400dp", fontScale = 2f)
class SegmentedLayoutItalianLargeTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it-w320dp-h2400dp")
class SegmentedLayoutItalianSmallPhoneDefaultTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it-w320dp-h2400dp", fontScale = 1.3f)
class SegmentedLayoutItalianSmallPhoneLargerTextTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to ROW, SegmentedPlace.QUALITY to ROW, SegmentedPlace.IMAGES_PAGE_SIZE to ROW, SegmentedPlace.TOOL_LANGUAGE to ROW,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

// Language (286 dp of 280 in the 6T's font) draws one row here.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it-w320dp-h2400dp", fontScale = 2f)
class SegmentedLayoutItalianSmallPhoneTest : SegmentedLayout(
    mapOf(
        SegmentedPlace.FORMAT to STACKED, SegmentedPlace.QUALITY to STACKED, SegmentedPlace.IMAGES_PAGE_SIZE to STACKED,
        SegmentedPlace.TOOL_READING to STACKED
    )
)

/**
 * The scanner's Save dialog ([ChoiceLayout]). Robolectric's dialog window has none of the phone's margins, so its content is the window less
 * 48 dp: a 320 dp window gives the 272 dp the dialog measured on the 6T, a 280 dp window about the 232 dp it has on a 320 dp phone.
 */
@RunWith(RobolectricTestRunner::class)
class SaveDialogLayoutTest : ChoiceLayout() {
    private fun dialog(where: String, expected: Map<SegmentedPlace, Boolean>) {
        show { SaveDialog(onSave = {}, onDismiss = {}) }
        check(where, expected, SegmentedPlace.DIALOG_PAGE_SIZE, SegmentedPlace.DIALOG_LANGUAGE, SegmentedPlace.DIALOG_READING)
    }

    @Test
    @Config(qualifiers = "w320dp-h2400dp")
    fun `as on the 6T at the default text size`() = dialog(
        "the dialog, 6T 1.0x",
        mapOf(SegmentedPlace.DIALOG_PAGE_SIZE to ROW, SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "w320dp-h2400dp", fontScale = 1.3f)
    fun `as on the 6T at 1_3x`() = dialog(
        "the dialog, 6T 1.3x",
        mapOf(SegmentedPlace.DIALOG_PAGE_SIZE to STACKED, SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "w320dp-h2400dp", fontScale = 2f)
    fun `as on the 6T at 2x`() = dialog(
        "the dialog, 6T 2.0x",
        mapOf(SegmentedPlace.DIALOG_PAGE_SIZE to STACKED, SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "it-w320dp-h2400dp")
    fun `as on the 6T in Italian at the default text size`() = dialog(
        "the dialog, 6T IT 1.0x", mapOf(SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "it-w320dp-h2400dp", fontScale = 1.3f)
    fun `as on the 6T in Italian at 1_3x`() = dialog(
        "the dialog, 6T IT 1.3x", mapOf(SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    // Language (286 dp of 272 in the 6T's font) draws one row here.
    @Test
    @Config(qualifiers = "it-w320dp-h2400dp", fontScale = 2f)
    fun `as on the 6T in Italian at 2x`() = dialog("the dialog, 6T IT 2.0x", mapOf(SegmentedPlace.DIALOG_READING to STACKED))

    @Test
    @Config(qualifiers = "w280dp-h2400dp")
    fun `as on a small phone at the default text size`() = dialog(
        "the dialog, 320 dp 1.0x",
        mapOf(SegmentedPlace.DIALOG_PAGE_SIZE to STACKED, SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "w280dp-h2400dp", fontScale = 1.3f)
    fun `as on a small phone at 1_3x`() = dialog(
        "the dialog, 320 dp 1.3x",
        mapOf(SegmentedPlace.DIALOG_PAGE_SIZE to STACKED, SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "w280dp-h2400dp", fontScale = 2f)
    fun `as on a small phone at 2x`() = dialog(
        "the dialog, 320 dp 2.0x",
        mapOf(SegmentedPlace.DIALOG_PAGE_SIZE to STACKED, SegmentedPlace.DIALOG_LANGUAGE to STACKED, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "it-w280dp-h2400dp")
    fun `as on a small phone in Italian at the default text size`() = dialog(
        "the dialog, 320 dp IT 1.0x", mapOf(SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "it-w280dp-h2400dp", fontScale = 1.3f)
    fun `as on a small phone in Italian at 1_3x`() = dialog(
        "the dialog, 320 dp IT 1.3x", mapOf(SegmentedPlace.DIALOG_LANGUAGE to ROW, SegmentedPlace.DIALOG_READING to STACKED)
    )

    @Test
    @Config(qualifiers = "it-w280dp-h2400dp", fontScale = 2f)
    fun `as on a small phone in Italian at 2x`() = dialog(
        "the dialog, 320 dp IT 2.0x", mapOf(SegmentedPlace.DIALOG_LANGUAGE to STACKED, SegmentedPlace.DIALOG_READING to STACKED)
    )
}
