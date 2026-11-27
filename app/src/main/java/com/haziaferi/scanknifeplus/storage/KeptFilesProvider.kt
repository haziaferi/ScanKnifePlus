package com.haziaferi.scanknifeplus.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.paperknifeplus.app.R
import java.io.File
import java.io.FileNotFoundException
import java.net.URLConnection

/**
 * The files kept in the app as a location of the system picker (ledger 2026-11-27), so every tool takes them, Merge's multi-select
 * included. Read only: open and copy, never write, rename or delete. Its one root shows only while a finished file is kept in the app, and
 * is local only, with no Recent and no search, so kept files never surface in another app's Recent view unasked. A document is named by
 * its file's name in `saved/`; the root's own document is [ROOT_DOC], which no safe file name can be.
 */
class KeptFilesProvider : DocumentsProvider() {
    // The SavedFiles this provider hears; set on the first query, not in onCreate, which must stay cheap.
    @Volatile
    private var heard: SavedFiles? = null

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = context!!
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (kept().isNotEmpty()) {
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, context.applicationInfo.icon)
                .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, context.getString(R.string.kept_root_summary))
                .add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority(context)))
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (documentId == ROOT_DOC) {
            cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC)
                .add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.app_name))
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, 0)
        } else {
            val (entry, file) = keptFile(documentId)
            add(cursor, entry, file)
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != ROOT_DOC) throw FileNotFoundException(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        for ((entry, file) in kept()) add(cursor, entry, file)
        val context = context!!
        cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(context), ROOT_DOC))
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("$documentId is read only")
        return ParcelFileDescriptor.open(keptFile(documentId).second, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The finished file kept in the app named [documentId], or FileNotFoundException for any other id. */
    private fun keptFile(documentId: String): Pair<SavedEntry, File> {
        if (!FolderNames.isSafe(documentId)) throw FileNotFoundException(documentId)
        return kept().firstOrNull { it.second.name == documentId } ?: throw FileNotFoundException(documentId)
    }

    /** The finished files kept in the app, held ones included, whose file is there; newest first, one row per file. */
    private fun kept(): List<Pair<SavedEntry, File>> {
        val files = files()
        return files.entries().filter { it.inApp }
            .mapNotNull { e -> files.fileOf(e)?.takeIf { it.isFile }?.let { e to it } }
            .distinctBy { it.second.name }
    }

    // Heard once per SavedFiles (one per process in the app): the picker follows each change, so the root comes and goes with the files.
    private fun files(): SavedFiles {
        val context = context!!
        val files = SavedFiles.of(context)
        if (heard !== files) {
            synchronized(this) {
                if (heard !== files) {
                    val authority = authority(context)
                    files.listen {
                        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
                        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, ROOT_DOC), null)
                    }
                    heard = files
                }
            }
        }
        return files
    }

    private fun add(cursor: MatrixCursor, entry: SavedEntry, file: File) {
        val name = entry.name.ifBlank { file.name }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, file.name)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream")
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, 0)
    }

    companion object {
        const val ROOT_ID = "kept"

        /** The root's document: not a safe file name, so never a kept file's. */
        const val ROOT_DOC = "/"

        /** The provider's authority, as the manifest declares it. */
        fun authority(context: Context): String = "${context.packageName}.kept"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
