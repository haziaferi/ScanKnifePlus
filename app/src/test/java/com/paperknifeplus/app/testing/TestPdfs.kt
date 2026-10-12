package com.paperknifeplus.app.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import java.io.ByteArrayOutputStream
import java.io.File

/** In-memory PDFs for Robolectric tests, so no binary fixtures are checked in. Shared by every tool-screen test; edit only additively. */
object TestPdfs {
    const val USER_PASSWORD = "user-secret"
    const val OWNER_PASSWORD = "owner-secret"

    /** Not a PDF at all. */
    val garbage: ByteArray = "this is not a pdf".toByteArray()

    /** A plain, unencrypted PDF with [pages] Letter-sized pages, each holding one filled rectangle. */
    fun plain(pages: Int = 1): ByteArray = build(pages) {}

    /** A PDF that needs [password] to open. */
    fun userPassword(pages: Int = 1, password: String = USER_PASSWORD): ByteArray = build(pages) {
        it.protect(StandardProtectionPolicy(OWNER_PASSWORD, password, AccessPermission()).apply { encryptionKeyLength = 128 })
    }

    /** A PDF with only an owner password: it opens without one, but reports itself as encrypted. */
    fun ownerOnly(pages: Int = 1): ByteArray = build(pages) {
        it.protect(StandardProtectionPolicy(OWNER_PASSWORD, "", AccessPermission()).apply { encryptionKeyLength = 128 })
    }

    /** The font name every page of [inheritedAttributes] uses, and the attributes its Pages root carries. */
    val INHERITED_FONT: COSName = COSName.getPDFName("F1")
    val INHERITED_MEDIA_BOX: PDRectangle = PDRectangle(595f, 842f)
    const val INHERITED_ROTATION = 90

    /**
     * A PDF whose /Resources (font [INHERITED_FONT]), /MediaBox ([INHERITED_MEDIA_BOX]) and /Rotate ([INHERITED_ROTATION]) sit only on the
     * Pages root, as some producers write them, so every page inherits them.
     */
    fun inheritedAttributes(pages: Int = 2): ByteArray = PDDocument().use { doc ->
        repeat(pages) { i ->
            val page = PDPage()
            page.cosObject.removeItem(COSName.MEDIA_BOX)
            page.setContents(PDStream(doc, "BT /F1 12 Tf 72 720 Td (Page ${i + 1}) Tj ET".byteInputStream()))
            doc.addPage(page)
        }
        val font = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE1)
            setItem(COSName.BASE_FONT, COSName.getPDFName("Helvetica"))
        }
        val resources = COSDictionary().apply { setItem(COSName.FONT, COSDictionary().apply { setItem(INHERITED_FONT, font) }) }
        doc.pages.cosObject.apply {
            setItem(COSName.RESOURCES, resources)
            setItem(COSName.MEDIA_BOX, INHERITED_MEDIA_BOX.cosArray)
            setInt(COSName.ROTATE, INHERITED_ROTATION)
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** Text in the uncompressed content stream of [linked]'s last page, to tell whether that page made it into a saved file. */
    const val LINKED_TARGET_MARKER = "LAST-PAGE-ONLY"

    /**
     * A PDF of [pages] (at least 3) pages whose first page holds three links: a /Dest link to page 2, a GoTo-action link to the last page,
     * and a link to the named destination "chapter".
     */
    fun linked(pages: Int = 3): ByteArray = build(pages) { doc ->
        val last = doc.getPage(pages - 1)
        last.setContents(PDStream(doc, "BT /F1 12 Tf 72 720 Td ($LINKED_TARGET_MARKER) Tj ET".byteInputStream()))
        val first = doc.getPage(0)
        val toSecond = PDAnnotationLink().apply { destination = PDPageFitDestination().apply { setPage(doc.getPage(1)) } }
        val toLast = PDAnnotationLink().apply { action = PDActionGoTo().apply { destination = PDPageFitDestination().apply { setPage(last) } } }
        val named = PDAnnotationLink().apply { destination = PDNamedDestination("chapter") }
        first.annotations = listOf(toSecond, toLast, named).onEach {
            it.rectangle = PDRectangle(72f, 72f, 100f, 20f)
            it.page = first
        }
    }

    /** Text in the uncompressed content stream of page n (1-based) of [structured], to tell whether that page made it into a saved file. */
    fun structuredMarker(page: Int) = "STRUCTURED-PAGE-$page"

    /**
     * A 4-page PDF with document-level data tied to its pages (indices 0 to 3):
     * - outline A -> 0, B -> 1 (open, with child B1 -> 2), C -> named "third", D -> 3;
     * - named destinations "first" -> 0 and "third" -> 2 in the /Names tree, and "last" -> 3 in the old-style /Dests;
     * - form fields "f0" (widget on 0), "f2" (merged field and widget on 2) and "group" (widgets on 1 and 2);
     * - page labels i, ii, 1, 2; an open action to page 2; on page 0, a link to page 2 and one to "third".
     */
    fun structured(): ByteArray = PDDocument().use { doc ->
        val pages = (1..4).map { n ->
            PDPage(PDRectangle.LETTER).also {
                it.setContents(PDStream(doc, "BT /F1 12 Tf 72 720 Td (${structuredMarker(n)}) Tj ET".byteInputStream()))
                doc.addPage(it)
            }
        }
        fun fit(index: Int) = PDPageFitDestination().apply { setPage(pages[index]) }
        fun item(title: String, index: Int) = PDOutlineItem().apply {
            this.title = title
            destination = fit(index)
        }

        val b = item("B", 1).apply { addLast(item("B1", 2)) }
        val c = PDOutlineItem().apply {
            title = "C"
            destination = PDNamedDestination("third")
        }
        doc.documentCatalog.documentOutline = PDDocumentOutline().apply {
            listOf(item("A", 0), b, c, item("D", 3)).forEach(::addLast)
            b.openNode()
            openNode()
        }

        val names = PDDestinationNameTreeNode().apply { this.names = mapOf("first" to fit(0), "third" to fit(2)) }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { dests = names }
        doc.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply { setItem("last", fit(3).cosObject) })

        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        fun place(widget: PDAnnotationWidget, index: Int) = widget.apply {
            rectangle = PDRectangle(72f, 100f, 100f, 20f)
            page = pages[index]
            pages[index].annotations = pages[index].annotations + this
        }
        val f0 = PDTextField(form).apply { partialName = "f0" }
        f0.widgets = listOf(place(PDAnnotationWidget(), 0))
        val f2 = PDTextField(form).apply { partialName = "f2" }
        place(f2.widgets.single(), 2)
        val group = PDTextField(form).apply { partialName = "group" }
        group.widgets = listOf(place(PDAnnotationWidget(), 1), place(PDAnnotationWidget(), 2))
        form.fields = listOf(f0, f2, group)

        doc.documentCatalog.pageLabels = PDPageLabels(doc).apply {
            setLabelItem(0, PDPageLabelRange().apply { style = PDPageLabelRange.STYLE_ROMAN_LOWER })
            setLabelItem(2, PDPageLabelRange().apply { style = PDPageLabelRange.STYLE_DECIMAL })
        }
        doc.documentCatalog.openAction = fit(2)
        val links = listOf(PDAnnotationLink().apply { destination = fit(2) }, PDAnnotationLink().apply { destination = PDNamedDestination("third") })
        links.forEach {
            it.rectangle = PDRectangle(72f, 72f, 100f, 20f)
            it.page = pages[0]
        }
        pages[0].annotations = pages[0].annotations + links
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** Writes [bytes] to [dir]/[name] and returns its file:// URI. */
    fun write(dir: File, bytes: ByteArray, name: String = "test.pdf"): Uri =
        Uri.fromFile(File(dir, name).apply { parentFile?.mkdirs(); writeBytes(bytes) })

    /** A content:// URI whose provider exists but hands back no stream, as some document providers do. */
    fun nullStreamUri(): Uri {
        Robolectric.setupContentProvider(NullStreamProvider::class.java, NULL_AUTHORITY)
        return Uri.parse("content://$NULL_AUTHORITY/missing.pdf")
    }

    private const val NULL_AUTHORITY = "com.paperknifeplus.app.test.nullstream"

    private fun build(pages: Int, finish: (PDDocument) -> Unit): ByteArray = PDDocument().use { doc ->
        repeat(pages) { i ->
            val page = PDPage(PDRectangle.LETTER)
            page.setContents(PDStream(doc, "0 0 0 rg 72 ${700 - i} 100 20 re f".byteInputStream()))
            doc.addPage(page)
        }
        finish(doc)
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    class NullStreamProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
}

/**
 * Applies global snapshot writes on this test's main looper. Compose starts its own applier once per JVM on the first test's looper; after
 * Robolectric resets that looper, a snapshot write in a later non-Compose test (e.g. History entries) strands it, and every following
 * Compose test then never goes idle. Put it before the Compose rule: `@get:Rule(order = 0)`.
 */
class ComposeSnapshotPump : ExternalResource() {
    private var handle: ObserverHandle? = null

    override fun before() {
        val handler = Handler(Looper.getMainLooper())
        var scheduled = false
        handle = Snapshot.registerGlobalWriteObserver {
            if (!scheduled) {
                scheduled = true
                handler.post {
                    scheduled = false
                    Snapshot.sendApplyNotifications()
                }
            }
        }
    }

    override fun after() {
        handle?.dispose()
    }
}

/*
 * Compose test rules whose effect coroutines (LaunchedEffect, rememberCoroutineScope) resume on the test's main thread, as they do on Main in
 * the app. Compose 1.6's default unconfined test dispatcher resumes a screen's coroutine on the IO thread its withContext ran on, so it writes
 * UI state there, racing main-thread recomposition; now and then a write was never recomposed and the screen stayed on old state. For tests
 * that wait on a tool screen's file loading; not for screens with endless animations, which a standard test dispatcher never idles.
 */

@OptIn(ExperimentalTestApi::class)
fun mainThreadComposeRule() = createComposeRule(StandardTestDispatcher())

@OptIn(ExperimentalTestApi::class)
inline fun <reified A : ComponentActivity> mainThreadAndroidComposeRule() = createAndroidComposeRule(A::class.java, StandardTestDispatcher())
