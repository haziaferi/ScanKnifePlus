package com.haziaferi.scanknifeplus.storage

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * A minimal DocumentsProvider over a folder, behaving like AOSP's FileSystemProvider where SafFolderStore depends on it: ids are paths
 * under "root", taken names become `name (n).ext`, renames never replace, and octet-stream files keep the name they are given.
 */
class TestDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { add(this, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (crash) throw NullPointerException("provider bug")
        val children = fileOf(parentDocumentId).listFiles()
            ?: if (emptyForMissing) return MatrixCursor(projection ?: COLUMNS) else throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: COLUMNS).apply { children.forEach { add(this, "$parentDocumentId/${it.name}") } }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val file = unique(parent, displayName, isDir)
        if (if (isDir) !file.mkdir() else !file.createNewFile()) throw FileNotFoundException(displayName)
        return "$parentDocumentId/${file.name}"
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        val target = unique(file.parentFile!!, displayName, file.isDirectory)
        if (!file.renameTo(target)) throw FileNotFoundException(documentId)
        return documentId.substringBeforeLast('/') + "/" + target.name
    }

    override fun deleteDocument(documentId: String) {
        if (!fileOf(documentId).deleteRecursively()) throw FileNotFoundException(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")

    private fun add(cursor: MatrixCursor, id: String) {
        val file = fileOf(id)
        if (!file.exists()) throw FileNotFoundException(id)
        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, id)
        row.add(Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
        row.add(Document.COLUMN_SIZE, file.length())
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(
            Document.COLUMN_FLAGS,
            when {
                file.isDirectory -> if (readOnly) 0 else Document.FLAG_DIR_SUPPORTS_CREATE
                else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME
            },
        )
    }

    private fun fileOf(id: String): File {
        require(id == ROOT || id.startsWith("$ROOT/")) { "Unknown document $id" }
        return File(base, id.removePrefix(ROOT))
    }

    private fun unique(parent: File, name: String, isDir: Boolean): File {
        var file = File(parent, name)
        var n = 1
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        while (file.exists()) {
            file = File(parent, if (dot > 0) "${name.substring(0, dot)} (${n++})${name.substring(dot)}" else "$name (${n++})")
        }
        return file
    }

    companion object {
        const val AUTHORITY = "com.haziaferi.scanknifeplus.test.documents"
        const val ROOT = "root"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** The folder the provider serves; set by [install]. */
        lateinit var base: File

        /** Answer a folder that is gone with no children, as some providers do, instead of an error. */
        var emptyForMissing = false

        /** Throw a provider's own bug from every listing. */
        var crash = false

        /** Report folders as not accepting new files, as a read-only volume does. */
        var readOnly = false

        /** Registers a provider serving [folder] with Robolectric's ContentResolver. */
        fun install(folder: File) {
            base = folder
            emptyForMissing = false
            crash = false
            readOnly = false
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info)
        }
    }
}
