package com.paperknifeplus.app.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
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
