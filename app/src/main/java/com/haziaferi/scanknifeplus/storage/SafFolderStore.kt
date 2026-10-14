package com.haziaferi.scanknifeplus.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * A [FolderStore] over the SAF tree the user picked ([treeUri], with a persisted read and write grant), through DocumentsContract and the
 * ContentResolver rather than DocumentFile, which makes one call per child. Files are created as `application/octet-stream` so providers
 * keep the name as given (with a real type they may append an extension); their type follows from the extension once renamed into place.
 */
class SafFolderStore(private val resolver: ContentResolver, val treeUri: Uri) : FolderStore {
    override val location: String = treeUri.toString()

    override val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** The content URI of entry [id], for opening it in another app or a viewer. */
    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    override fun isAvailable(): Boolean {
        val granted = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
        if (!granted) return false
        return try {
            query(uri(rootId), arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS))?.use { c ->
                c.moveToFirst() && c.getString(0) == Document.MIME_TYPE_DIR && (c.getInt(1) and Document.FLAG_DIR_SUPPORTS_CREATE) != 0
            } ?: false
        } catch (e: Exception) {
            false // a provider that is gone throws all kinds of runtime exceptions; any of them means the folder cannot be used
        }
    }

    override fun list(folderId: String): List<FolderEntry> = provider("list $folderId") {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val cursor = query(children, PROJECTION) ?: throw IOException("No listing for $folderId")
        val entries = cursor.use { c -> buildList { while (c.moveToNext()) add(entryOf(c)) } }
        // Some providers answer a folder they can no longer reach with no rows; empty only counts once the folder itself answers.
        if (entries.isEmpty() && !entry(uri(folderId)).isFolder) throw IOException("$folderId is not a folder")
        entries
    }

    override fun createFolder(parentId: String, name: String): FolderEntry = create(parentId, Document.MIME_TYPE_DIR, name)

    override fun createFile(parentId: String, name: String): FolderEntry = create(parentId, OCTET_STREAM, name)

    private fun create(parentId: String, mimeType: String, name: String): FolderEntry = provider("create $name") {
        // Before API 29 a failure returns null instead of throwing.
        val created = DocumentsContract.createDocument(resolver, uri(parentId), mimeType, name) ?: throw IOException("Cannot create $name")
        entry(created)
    }

    override fun openRead(id: String): InputStream = provider("read $id") {
        resolver.openInputStream(uri(id)) ?: throw IOException("Cannot read $id")
    }

    override fun openWrite(id: String): OutputStream = provider("write $id") {
        SyncingStream(resolver.openFileDescriptor(uri(id), "w") ?: throw IOException("Cannot write $id"))
    }

    /**
     * Syncs the descriptor before closing, so a `.tmp` is on storage before it is renamed into place and a crash cannot leave a renamed but
     * empty file. A pipe or a provider's virtual file cannot be synced; there the size check before the rename is the only guard.
     */
    private class SyncingStream(pfd: ParcelFileDescriptor) : ParcelFileDescriptor.AutoCloseOutputStream(pfd) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                fd.sync()
            } catch (e: SyncFailedException) {
                // not a plain file: nothing to sync
            }
            super.close()
        }
    }

    override fun sizeOf(id: String): Long = provider("measure $id") {
        val stat = resolver.openFileDescriptor(uri(id), "r")?.use { it.statSize } ?: throw IOException("Cannot open $id")
        // A descriptor that is not a plain file (a pipe from a cloud provider) has no size; count the bytes instead.
        if (stat >= 0) stat else openRead(id).use(::countBytes)
    }

    private fun countBytes(input: InputStream): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
        }
    }

    override fun rename(id: String, name: String): FolderEntry = provider("rename $id") {
        // Before API 29 a failure returns null instead of throwing; a provider whose ids do not change gets the old URI back.
        val renamed = DocumentsContract.renameDocument(resolver, uri(id), name) ?: throw IOException("Cannot rename $id")
        entry(renamed)
    }

    override fun delete(id: String) = provider("delete $id") {
        if (!DocumentsContract.deleteDocument(resolver, uri(id))) throw IOException("Cannot delete $id")
    }

    /** The entry [documentUri] points at, as it is now. */
    private fun entry(documentUri: Uri): FolderEntry {
        val cursor = query(documentUri, PROJECTION) ?: throw FileNotFoundException("$documentUri")
        return cursor.use { c -> if (c.moveToFirst()) entryOf(c) else throw FileNotFoundException("$documentUri") }
    }

    // The Bundle form where it exists, which is what every provider receives over IPC anyway (and what Robolectric passes on to one).
    private fun query(uri: Uri, projection: Array<String>): Cursor? =
        if (Build.VERSION.SDK_INT >= 26) resolver.query(uri, projection, null, null) else resolver.query(uri, projection, null, null, null)

    private fun entryOf(c: Cursor): FolderEntry = FolderEntry(
        id = c.getString(0),
        name = c.getString(1) ?: "",
        isFolder = c.getString(2) == Document.MIME_TYPE_DIR,
        size = if (c.isNull(3)) null else c.getLong(3),
        modified = if (c.isNull(4)) 0 else c.getLong(4),
    )

    /**
     * Runs [block], turning any runtime exception into IOException: a provider reports a revoked grant, a removed card or its own crash as
     * whatever exception it likes, and every caller must see those as a failed folder operation, never as a crash.
     */
    private inline fun <T> provider(what: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        throw IOException("Cannot $what", e)
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val OCTET_STREAM = "application/octet-stream"
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
