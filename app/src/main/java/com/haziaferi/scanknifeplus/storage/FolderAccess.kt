package com.haziaferi.scanknifeplus.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import java.io.File

/** Whether the app can use the user's folder. */
sealed interface FolderState {
    /** No folder chosen yet. */
    data object NotChosen : FolderState

    /** The folder is reachable and writable. */
    data class Ready(val folder: ScanFolder, val store: SafFolderStore) : FolderState

    /**
     * A folder was chosen but cannot be used now (grant revoked, folder deleted or renamed, storage removed): only writing to it is blocked,
     * and the user is asked to choose the folder again. Everything private keeps working.
     */
    data object ChooseAgain : FolderState
}

/**
 * The one folder the user chose with the system folder picker (ACTION_OPEN_DOCUMENT_TREE, read and write, persistable), as in the user's
 * other projects: its grant is persisted, a read-only grant is refused, older tree grants are released, and the grant is checked again on
 * every [state] call. No storage permission is involved.
 */
class FolderAccess(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen folder's tree URI, whether or not it is still usable. */
    fun treeUri(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    /**
     * Adopts [treeUri], as the folder picker returned it, as the folder; returns false (keeping the previous folder) if its grant cannot be
     * persisted with write access or the folder cannot be written to.
     */
    @WorkerThread
    fun adopt(treeUri: Uri): Boolean {
        if (!DocumentsContract.isTreeUri(treeUri)) return false
        try {
            resolver.takePersistableUriPermission(treeUri, READ_WRITE)
        } catch (e: SecurityException) {
            return false // the picker did not grant write access, or not persistably
        }
        if (!SafFolderStore(resolver, treeUri).isAvailable()) {
            if (treeUri != treeUri()) release(treeUri)
            return false
        }
        // Written to disk before the older grants go, so a crash in between never leaves a folder saved without its grant.
        prefs.edit(commit = true) { putString(KEY_TREE, treeUri.toString()) }
        for (p in resolver.persistedUriPermissions) {
            if (p.uri != treeUri && DocumentsContract.isTreeUri(p.uri)) release(p.uri)
        }
        return true
    }

    /** Where the folder stands now; checks the grant and queries the folder. */
    @WorkerThread
    fun state(): FolderState {
        val uri = treeUri() ?: return FolderState.NotChosen
        val store = SafFolderStore(resolver, uri)
        if (!store.isAvailable()) return FolderState.ChooseAgain
        return FolderState.Ready(ScanFolder(store, ScanLibrary(ScanFiles.libraryDir(context)), ScanFiles.libraryDir(context), workingCopies()), store)
    }

    /** The launch sweep over private working copies ([WorkingCopies.sweep]); needs no folder. Run at launch, before any scan session. */
    @WorkerThread
    fun sweep(): Int = workingCopies().sweep(ScanLibrary(ScanFiles.libraryDir(context)), ScanFiles.libraryDir(context))

    private fun workingCopies() = WorkingCopies(File(context.filesDir, BINDINGS_FOLDER))

    private fun release(uri: Uri) {
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // already gone
        }
    }

    private companion object {
        const val PREFS = "scan_folder"
        const val KEY_TREE = "tree_uri"
        const val BINDINGS_FOLDER = "scan-folder"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
