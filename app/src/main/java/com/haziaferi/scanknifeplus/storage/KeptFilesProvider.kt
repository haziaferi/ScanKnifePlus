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
 * is local only, with no Recent and no search, so kept files never surface in another app's Recent view unasked.
 *
 * A document is named by its row's id, a UUID never reused, so a grant another app was given for one file never opens a later file of the
 * same name, and no id can be guessed; the root's own document is [ROOT_DOC], which no row id can be.
 */
class KeptFilesProvider : DocumentsProvider() {
    // The SavedFiles this provider hears, and the kept rows the picker was last told of.
    @Volatile
    private var heard: SavedFiles? = null

    @Volatile
    private var told: List<String>? = null

    // Listens from the start: the system picker keeps each provider's roots until told they changed, so a provider that listened only
    // once queried would leave the picker showing no root after the first file is kept (or a stale one after the move). Cheap: it makes
    // the SavedFiles object and reads nothing.
    override fun onCreate(): Boolean {
        files()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = context!!
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val kept = kept()
        if (kept.isNotEmpty()) {
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, context.applicationInfo.icon)
                .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, context.getString(R.string.kept_root_summary))
                // Only the kept files' own types, so a picker for images leaves the root out instead of listing every file greyed.
                .add(Root.COLUMN_MIME_TYPES, kept.map { (entry, file) -> mimeOf(entry, file) }.distinct().sorted().joinToString("\n"))
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

    /** The finished file kept in the app whose row is [documentId], or FileNotFoundException for any other id. */
    private fun keptFile(documentId: String): Pair<SavedEntry, File> =
        kept().firstOrNull { it.first.id == documentId } ?: throw FileNotFoundException(documentId)

    /** The finished files kept in the app, held ones included, whose file is there; newest first, one row per file. */
    private fun kept(): List<Pair<SavedEntry, File>> {
        val files = files()
        return files.entries().filter { it.inApp }
            .mapNotNull { e -> files.fileOf(e)?.takeIf { it.isFile }?.let { e to it } }
            .distinctBy { it.second.name }
    }

    // Heard once per SavedFiles (one per process in the app). The picker is told only when the kept rows change (a save starting, a
    // picker save or a size noted leave them as they are), and with no network sync asked.
    private fun files(): SavedFiles {
        val context = context!!
        val files = SavedFiles.of(context)
        if (heard !== files) {
            synchronized(this) {
                if (heard !== files) {
                    val authority = authority(context)
                    files.listen { all ->
                        val now = all.filter { it.inApp }.map { it.id }
                        if (now != told) {
                            told = now
                            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null, false)
                            context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, ROOT_DOC), null, false)
                        }
                    }
                    heard = files
                }
            }
        }
        return files
    }

    private fun add(cursor: MatrixCursor, entry: SavedEntry, file: File) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, entry.id)
            .add(Document.COLUMN_DISPLAY_NAME, entry.name.ifBlank { file.name })
            .add(Document.COLUMN_MIME_TYPE, mimeOf(entry, file))
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, 0)
    }

    private fun mimeOf(entry: SavedEntry, file: File): String =
        URLConnection.guessContentTypeFromName(entry.name.ifBlank { file.name }) ?: "application/octet-stream"

    companion object {
        const val ROOT_ID = "kept"

        /** The root's document: not a UUID, so never a kept file's. */
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
