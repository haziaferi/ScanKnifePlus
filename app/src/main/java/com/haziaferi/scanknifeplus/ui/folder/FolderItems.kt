package com.haziaferi.scanknifeplus.ui.folder

import android.net.Uri
import com.haziaferi.scanknifeplus.storage.FolderListing
import com.haziaferi.scanknifeplus.storage.FolderNames
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.paperknifeplus.app.ui.components.SCAN_ENTRY_TOOL
import java.net.URLConnection

/** What a History row of the folder is: a scan's PDF (or a scan whose PDF is not there), any other top-level file, or a conflict copy. */
enum class FolderKind { SCAN, TOOL, CONFLICT }

/**
 * One History row of the folder. [uri] opens or shares it (null for a scan whose PDF is missing); [original] is, for a conflict copy, the
 * file it is a copy of, if that is still in the folder; [originalName] its name either way.
 */
data class FolderItem(
    val key: String,
    val name: String,
    val kind: FolderKind,
    val size: Long?,
    val modified: Long,
    val uri: Uri?,
    val originalName: String? = null,
    val original: FolderItem? = null,
) {
    /** The file's type, from its extension, as the share sheet and the reader need it. */
    val mime: String get() = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
}

/** The name of the file [name] is a conflict copy of: a kept `.lost-` version or a Syncthing conflict copy; null for any other file. */
fun originalOf(name: String): String? =
    FolderNames.conflictOf(name) ?: FolderNames.lostOriginal(name)

/**
 * History's rows for [listing], newest first: top-level files (a scan's PDF counts as a scan), scans in `Scan sources/` without a PDF, and
 * conflict copies with their originals. [uriOf] gives the content URI of a file id.
 */
fun folderItems(listing: FolderListing.Listed, uriOf: (String) -> Uri): List<FolderItem> {
    val scanPdfs = listing.scans.mapNotNull { it.pdf?.id }.toSet()
    val files = (listing.files + listing.conflicts).map { f ->
        val original = originalOf(f.name)
        val kind = when {
            original != null -> FolderKind.CONFLICT
            f.id in scanPdfs -> FolderKind.SCAN
            else -> FolderKind.TOOL
        }
        FolderItem(f.id, f.name, kind, f.size, f.modified, uriOf(f.id), original)
    }
    val byName = files.filter { it.kind != FolderKind.CONFLICT }.associateBy { it.name }
    val withOriginals = files.map { item -> item.originalName?.let { item.copy(original = byName[it]) } ?: item }
    val withoutPdf = listing.scans.filter { it.pdf == null }.map { FolderItem(it.folderId, it.name, FolderKind.SCAN, null, it.modified, null) }
    return (withOriginals + withoutPdf).sortedByDescending { it.modified }
}

/** The empty tool screens' recent list: the first [limit] of [items] (newest first) that are PDFs to open, conflict copies left out. */
fun folderPdfs(items: List<FolderItem>, limit: Int = 4): List<FolderItem> =
    items.filter { it.kind != FolderKind.CONFLICT && it.uri != null && it.mime == "application/pdf" }.take(limit)

/** The empty tool screens' recent PDFs: the folder's, or with [kept] those kept in the app while no folder is listed. */
data class RecentPdfs(val items: List<FolderItem>, val kept: Boolean = false)

/**
 * The empty tool screens' recent list for [history]: the folder's newest PDFs while it is listed; with no folder chosen, or one that can't
 * be reached, the newest PDFs kept in the app among [saved], opened by [uriOf] (their FileProvider URI). Null while the folder is read.
 */
fun recentPdfs(history: HistoryFolder?, saved: List<SavedEntry>, uriOf: (SavedEntry) -> Uri?, limit: Int = 4): RecentPdfs? {
    val items = history?.items
    return when {
        items != null -> RecentPdfs(folderPdfs(items, limit))
        history == null || history.unreadable -> RecentPdfs(keptPdfs(saved, uriOf, limit), kept = true)
        else -> null
    }
}

/** The first [limit] of [saved] (newest first) that are finished PDFs kept in the app, as the folder's rows look. */
fun keptPdfs(saved: List<SavedEntry>, uriOf: (SavedEntry) -> Uri?, limit: Int = 4): List<FolderItem> =
    saved.asSequence().filter { it.inApp }.mapNotNull { e ->
        val kind = if (e.tool == SCAN_ENTRY_TOOL) FolderKind.SCAN else FolderKind.TOOL
        uriOf(e)?.let { FolderItem(e.id, e.name, kind, e.size, e.time, it) }
    }.filter { it.mime == "application/pdf" }.take(limit).toList()

/** History's view of the folder named [name]: [items] is null while it is read, and [unreadable] when it cannot be listed or must be chosen again. */
data class HistoryFolder(val name: String?, val items: List<FolderItem>?, val unreadable: Boolean = false)
