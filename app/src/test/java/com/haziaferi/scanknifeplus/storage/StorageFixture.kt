package com.haziaferi.scanknifeplus.storage

import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/**
 * A private scan library, a user folder over [FileFolderStore] and the [ScanFolder] between them, with a settable clock. Page files get
 * distinct contents, so a copied file can be told from another.
 */
class StorageFixture(val folderStore: (File) -> FolderStore = ::FileFolderStore) {
    val base: File = Files.createTempDirectory("storage").toFile()
    val libraryRoot = File(base, "scans").apply { mkdirs() }
    val folderRoot = File(base, "folder").apply { mkdirs() }
    val bindingsDir = File(base, "bindings")
    var now = 1_791_590_400_000L // 2026-10-10T00:00:00Z
    val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private var shots = 0

    private val capture = CaptureWriter { _, _, page, original ->
        shots++
        page.writeText("page $shots")
        original?.writeText("original $shots")
        StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
    }
    val library = ScanLibrary(libraryRoot, clock = { now }, timeZone = utc, capture = capture)
    val copies = WorkingCopies(bindingsDir)
    val store = folderStore(folderRoot)
    val folder = ScanFolder(store, library, libraryRoot, copies, clock = { now }, timeZone = utc)

    /** PDFs whose content names the document and its page count, so a test can tell which version was written. */
    val pdfs = PdfMaker { id, deliver ->
        val doc = library.document(id) ?: return@PdfMaker false
        val file = File(base, "out.pdf").apply { writeText("pdf of ${doc.displayName} with ${doc.pages.size} pages") }
        deliver(file)
    }

    /** A new working copy named [name] with [pages] pages (each with an original). */
    fun scan(name: String?, pages: Int): ScanDocument {
        val doc = library.create(name)
        repeat(pages) { library.addCapture(doc.id, ImageSource { ByteArray(0).inputStream() }, null, keepOriginal = true) }
        return library.document(doc.id)!!
    }

    fun file(path: String) = File(folderRoot, path)

    /** Every file under the user's folder with its content, for checking that nothing there changed. */
    fun folderSnapshot(): Map<String, String> =
        folderRoot.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folderRoot).invariantSeparatorsPath to it.readText() }

    fun names(path: String = ""): Set<String> = File(folderRoot, path).list().orEmpty().toSet()

    fun cleanUp() {
        base.deleteRecursively()
    }
}
