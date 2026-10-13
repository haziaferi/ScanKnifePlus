package com.haziaferi.scanknifeplus.storage

import androidx.annotation.WorkerThread
import java.io.InputStream
import java.io.OutputStream

/** One child of a folder, as one listing returns it. [id] is the store's handle for it; [modified] is epoch ms, or 0 when unknown. */
data class FolderEntry(val id: String, val name: String, val isFolder: Boolean, val size: Long, val modified: Long)

/**
 * The user's folder as the storage layer sees it: folders and files reached by opaque ids, with SAF's rules for every implementation, so
 * code tested over [FileFolderStore] behaves the same over [SafFolderStore]. Nothing is ever replaced: [createFile] and [rename] give a
 * different name (`name (1).ext`, as Android's providers do) when the one asked for is taken, and callers check the name they got. Every
 * method blocks and throws IOException on failure; a folder that cannot be listed throws, it never lists as empty.
 */
interface FolderStore {
    /** The id of the folder the user chose. */
    val rootId: String

    /** True if the folder can still be reached and written to; false after the permission is revoked or the folder or its storage is gone. */
    @WorkerThread
    fun isAvailable(): Boolean

    /** The children of folder [folderId], in no particular order; one query per call. */
    @WorkerThread
    fun list(folderId: String): List<FolderEntry>

    /** Creates a subfolder of [parentId]; check the returned name, which differs if [name] is taken. */
    @WorkerThread
    fun createFolder(parentId: String, name: String): FolderEntry

    /** Creates an empty file in [parentId] exactly named [name] unless that is taken (see [FolderStore]); its type follows from the name. */
    @WorkerThread
    fun createFile(parentId: String, name: String): FolderEntry

    @WorkerThread
    fun openRead(id: String): InputStream

    /** Opens a file this layer just created for writing. */
    @WorkerThread
    fun openWrite(id: String): OutputStream

    /** Renames [id] without ever replacing another entry; returns it as it now is, possibly with a new id and a different name. */
    @WorkerThread
    fun rename(id: String, name: String): FolderEntry

    @WorkerThread
    fun delete(id: String)
}
