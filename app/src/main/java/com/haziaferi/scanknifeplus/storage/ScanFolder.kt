package com.haziaferi.scanknifeplus.storage

import android.util.Log
import androidx.annotation.WorkerThread
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.export.ExportNames
import com.haziaferi.scanknifeplus.storage.FolderNames.SCAN_SOURCES
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone
import org.json.JSONObject

/** A file at the top level of the user's folder. */
data class FolderFile(val id: String, val name: String, val size: Long, val modified: Long)

/** A scan in `Scan sources/`: [name] is its folder there, [pdf] the top-level `<name>.pdf` if there is one. */
data class FolderScan(val name: String, val folderId: String, val modified: Long, val pdf: FolderFile?)

/** What History shows, read from the folder; a folder that cannot be listed is [Unreadable], never an empty listing. */
sealed interface FolderListing {
    /**
     * [files] are the visible top-level files, newest first (PDFs and other tool outputs, kept `.lost-` versions included); [scans] the scans
     * in `Scan sources/`, newest first; [conflicts] the Syncthing conflict copies at the top level that are not resolved yet.
     */
    data class Listed(val files: List<FolderFile>, val scans: List<FolderScan>, val conflicts: List<FolderFile>) : FolderListing

    data object Unreadable : FolderListing
}

/** A resolved sync conflict over [path] (relative to the folder); [keptAs] is the name the losing version was kept under, if it differed. */
data class ConflictReport(val path: String, val keptAs: String?)

/** The outcome of a write to the folder. */
sealed interface WriteResult {
    /** Written as [name]; [conflicts] were found and resolved on the way, to be reported to the user once. */
    data class Written(val name: String, val conflicts: List<ConflictReport>) : WriteResult

    /** The folder cannot be reached: the user has to choose it again. Nothing was lost; the working copy is unchanged. */
    data object FolderUnavailable : WriteResult

    /** The folder is there but the write failed; it can be tried again. */
    data object Failed : WriteResult
}

/** The outcome of [ScanFolder.copyIn]. */
sealed interface CopyInResult {
    /** Working copy [id] in the scan library holds the scan; [conflicts] as in [WriteResult.Written]. */
    data class Ready(val id: String, val conflicts: List<ConflictReport>) : CopyInResult

    /** The record names files that are not in the folder yet, as while a sync is still running. */
    data class Incomplete(val missing: List<String>) : CopyInResult

    /** No such scan, or no readable record in its folder. */
    data object NotFound : CopyInResult

    data object FolderUnavailable : CopyInResult

    data object Failed : CopyInResult
}

/** The outcome of [ScanFolder.migrate]: the working copies [written] to the folder and those that [failed]. */
data class Migration(val written: List<String>, val failed: List<String>, val folderUnavailable: Boolean)

/** Makes document [id]'s PDF and hands it to [deliver], as ScanPdfExporter.writePdf does; returns false if no PDF was delivered. */
fun interface PdfMaker {
    fun make(id: String, deliver: (File) -> Boolean): Boolean
}

/**
 * The scans and tool outputs in the user's folder: finished PDFs at the top level, and each scan's editable sources (exactly what a scan
 * library document folder holds) in `Scan sources/<name>/`. The app edits a working copy in the private scan library ([library], under
 * [libraryRoot]) and writes it back here when done; [copies] records which scan each working copy belongs to.
 *
 * Rules for the folder: a write never replaces a file in one step. New files are written as `<name>.tmp` and renamed; a replaced file
 * (`document.json`, a PDF) keeps its previous version as `<name>.bak`. Syncthing conflict copies of `document.json` are merged and those of
 * PDFs settled by age, the losing version always kept as `<name>.lost-<time>`. Nothing here deletes a user's file on its own initiative:
 * only page files an edit made obsolete go, and only when no other version could still name them. Every method blocks (one store call per
 * folder listing) and all of them run one at a time.
 */
class ScanFolder(
    private val store: FolderStore,
    private val library: ScanLibrary,
    private val libraryRoot: File,
    private val copies: WorkingCopies,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    /** History's view of the folder: the root and `Scan sources/` are listed, two queries; scan folders are not opened. */
    @WorkerThread
    fun listing(): FolderListing = try {
        val top = store.list(store.rootId)
        val topFiles = top.filter { !it.isFolder && !FolderNames.isHidden(it.name) }
        val (conflicts, files) = topFiles.partition { FolderNames.conflictOf(it.name) != null }
        val byName = files.associateBy { it.name }
        val scans = top.sourcesFolder()?.let { sources ->
            store.list(sources.id).filter { it.isFolder && !FolderNames.isHidden(it.name) }
                .map { FolderScan(it.name, it.id, it.modified, byName["${it.name}$PDF"]?.let(::fileOf)) }
        }.orEmpty()
        FolderListing.Listed(files.map(::fileOf).sortedByDescending { it.modified }, scans.sortedByDescending { it.modified }, conflicts.map(::fileOf))
    } catch (e: IOException) {
        Log.w(TAG, "Cannot list the folder", e)
        FolderListing.Unreadable
    }

    /**
     * Resolves every Syncthing conflict copy of a top-level PDF and of a scan's `document.json`; opens every scan folder, so it is slow on a
     * large folder. Returns what it resolved; on a failure it stops there and returns what it resolved so far (the rest waits for next time).
     */
    @WorkerThread
    fun resolveConflicts(): List<ConflictReport> = synchronized(LOCK) {
        val reports = mutableListOf<ConflictReport>()
        try {
            val top = store.list(store.rootId)
            resolvePdfConflicts(top, reports)
            val sources = top.sourcesFolder() ?: return reports
            for (scan in store.list(sources.id).filter { it.isFolder && !FolderNames.isHidden(it.name) }) {
                resolveRecordConflicts(scan.id, store.list(scan.id), scan.name, reports)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Resolving conflicts stopped", e)
        }
        reports
    }

    /**
     * Copies folder scan [name] into a new working copy (or returns the one it already has), resolving conflicts in its folder first. The copy
     * is assembled in a hidden folder of the library and renamed into place, so the library never sees half of it.
     */
    @WorkerThread
    fun copyIn(name: String): CopyInResult = synchronized(LOCK) {
        if (!FolderNames.isSafe(name) || FolderNames.isHidden(name)) return CopyInResult.NotFound
        copies.idOf(name)?.let { if (library.document(it) != null) return CopyInResult.Ready(it, emptyList()) }
        try {
            val sources = store.list(store.rootId).sourcesFolder() ?: return CopyInResult.NotFound
            val folder = store.list(sources.id).firstOrNull { it.isFolder && it.name == name } ?: return CopyInResult.NotFound
            val reports = mutableListOf<ConflictReport>()
            val entries = resolveRecordConflicts(folder.id, store.list(folder.id), name, reports)
            val record = recordOf(entries) ?: return CopyInResult.NotFound
            val byName = entries.filter { !it.isFolder }.associateBy { it.name }
            val needed = record.doc.pages.flatMap { it.files }.distinct()
            val missing = needed.filter { it !in byName }
            if (missing.isNotEmpty()) return CopyInResult.Incomplete(missing)

            val staging = ScanFiles.newFolder(libraryRoot, WorkingCopies.IMPORT_PREFIX) ?: return CopyInResult.Failed
            try {
                for (file in needed) {
                    val entry = byName.getValue(file)
                    val copied = store.openRead(entry.id).use { input -> FileOutputStream(File(staging, file)).use { input.copyTo(it) } }
                    if (copied != entry.size) throw IOException("$file: read $copied of ${entry.size} bytes")
                }
                // Byte for byte, so an unchanged working copy hashes the same as the folder's record and counts as written.
                File(staging, ScanLibrary.RECORD).writeBytes(record.bytes)
                val id = FolderNames.free(name, isFolder = true) { File(libraryRoot, it).exists() }
                if (!staging.renameTo(File(libraryRoot, id))) throw IOException("Cannot move the copy of $name into the library")
                val hash = WorkingCopies.sha256(record.bytes)
                copies.put(id, Binding(name, base = hash, confirmed = hash))
                CopyInResult.Ready(id, reports)
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Copying $name in failed", e)
            if (store.isAvailable()) CopyInResult.Failed else CopyInResult.FolderUnavailable
        }
    }

    /**
     * Writes working copy [id] back to the folder: page files it lacks, then the record, then (with a [pdf] maker) `<name>.pdf` at the top
     * level. A new scan gets a free name from its display name. If the folder's record changed since the working copy last saw it, the two
     * are merged (see [mergeRecords]) instead of one replacing the other. Only once everything is written does the working copy count as
     * confirmed, which lets the next launch sweep remove it.
     */
    @WorkerThread
    fun writeBack(id: String, pdf: PdfMaker? = null): WriteResult = synchronized(LOCK) {
        val doc = library.document(id) ?: return WriteResult.Failed
        val workingHash = WorkingCopies.recordHash(library, id) ?: return WriteResult.Failed
        val binding = copies.get(id)
        try {
            val reports = mutableListOf<ConflictReport>()
            val top = store.list(store.rootId)
            val sources = top.sourcesFolder() ?: createFolderExact(store.rootId, SCAN_SOURCES)
            val scans = store.list(sources.id)
            val name = binding?.name ?: FolderNames.free(ExportNames.exportFileName(doc.displayName, doc.id), isFolder = true) { n ->
                scans.any { it.name.equals(n, ignoreCase = true) } || top.any { it.name.equals("$n$PDF", ignoreCase = true) }
            }
            // Saved first, so a retry after a failure writes to the same folder instead of starting a second one.
            if (binding == null) copies.put(id, Binding(name, base = null, confirmed = null))
            val folder = scans.firstOrNull { it.isFolder && it.name == name } ?: createFolderExact(sources.id, name)

            var entries = resolveRecordConflicts(folder.id, store.list(folder.id), name, reports)
            val damaged = entries.firstOrNull { it.name == ScanLibrary.RECORD && !it.isFolder }?.takeIf { parse(it) == null }
            if (damaged != null) {
                // Never written over: a record this app cannot read may be a newer version's.
                keepLost(folder.id, damaged, entries, "$SCAN_SOURCES/$name/${ScanLibrary.RECORD}", reports)
                entries = store.list(folder.id)
            }
            val folderRecord = recordOf(entries)

            val byName = entries.filter { !it.isFolder }.associateBy { it.name }
            for (file in doc.pages.flatMap { it.files }.distinct()) {
                val local = library.file(id, file)
                val existing = byName[file]
                if (existing != null) {
                    // Page files are write-once under unique names, so one of the same name is the same page; a different size is not.
                    if (existing.size != local.length()) throw IOException("$name/$file exists with other content")
                    continue
                }
                writeNew(folder.id, file, entries) { out -> local.inputStream().use { it.copyTo(out) } }
            }
            entries = store.list(folder.id)

            val unchangedInFolder = folderRecord == null || folderRecord.hash == binding?.base
            val record = if (unchangedInFolder) {
                doc
            } else {
                val merge = mergeRecords(doc, folderRecord!!.doc, entries.mapTo(HashSet()) { it.name })
                if (merge.loserLostSomething) {
                    val loserBytes = if (merge.loser === doc) jsonBytes(doc) else folderRecord.bytes
                    val lost = lostNameIn(entries, ScanLibrary.RECORD)
                    writeNew(folder.id, lost, entries) { it.write(loserBytes) }
                    reports += ConflictReport("$SCAN_SOURCES/$name/${ScanLibrary.RECORD}", lost)
                    entries = store.list(folder.id)
                }
                merge.merged
            }
            val recordBytes = jsonBytes(record)
            replaceFile(folder.id, ScanLibrary.RECORD, entries) { it.write(recordBytes) }

            if (unchangedInFolder && folderRecord != null && entries.none { FolderNames.isLost(it.name) }) {
                // Only files the replaced record named and the new one does not; with a kept version around, that one may still need them.
                val keep = record.pages.flatMapTo(HashSet()) { it.files }
                val obsolete = folderRecord.doc.pages.flatMap { it.files }.filter { it !in keep }.toSet()
                store.list(folder.id).filter { it.name in obsolete && !it.isFolder }.forEach { store.delete(it.id) }
            }

            if (pdf != null) writePdf(id, name, pdf)
            // After a merge the folder holds pages this working copy lacks; keeping the old base makes the next write-back merge again
            // rather than write over them.
            val base = if (unchangedInFolder) WorkingCopies.sha256(recordBytes) else folderRecord!!.hash
            copies.put(id, Binding(name, base, confirmed = workingHash))
            WriteResult.Written(name, reports)
        } catch (e: IOException) {
            Log.w(TAG, "Writing $id back failed", e)
            if (store.isAvailable()) WriteResult.Failed else WriteResult.FolderUnavailable
        }
    }

    /**
     * Moves the private scan library into the folder, all at once: the working copies with changes the folder lacks (at first, every scan the
     * app has) are fixed first, then each is written back with its PDF, creating new files only. The private copies stay until the launch
     * sweep finds them confirmed written, so a migration that stops halfway loses nothing and simply runs again. Run it when the folder is
     * first chosen, with no scan session open.
     */
    @WorkerThread
    fun migrate(pdf: PdfMaker?): Migration = synchronized(LOCK) {
        val snapshot = library.documents().map { it.id }.filter { hasChanges(it) }
        val written = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((i, id) in snapshot.withIndex()) {
            when (writeBack(id, pdf)) {
                is WriteResult.Written -> written += id
                WriteResult.Failed -> failed += id
                WriteResult.FolderUnavailable -> return Migration(written, failed + snapshot.drop(i), folderUnavailable = true)
            }
        }
        Migration(written, failed, folderUnavailable = false)
    }

    /** True if working copy [id] holds something the folder does not have yet. */
    @WorkerThread
    fun hasChanges(id: String): Boolean {
        val confirmed = copies.get(id)?.confirmed ?: return true
        return WorkingCopies.recordHash(library, id) != confirmed
    }

    /**
     * Puts a copy of a tool's output, which the user has already saved elsewhere, at the top level of the folder as [name] (or `<base>-2<ext>`
     * and so on if taken), read from [open]. Creates a new file only.
     */
    @WorkerThread
    fun copyOutput(name: String, open: () -> InputStream): WriteResult = synchronized(LOCK) {
        if (!FolderNames.isSafe(name) || FolderNames.isHidden(name)) return WriteResult.Failed
        try {
            val top = store.list(store.rootId)
            val free = FolderNames.free(name) { n -> top.any { it.name.equals(n, ignoreCase = true) } }
            writeNew(store.rootId, free, top) { out -> open().use { it.copyTo(out) } }
            WriteResult.Written(free, emptyList())
        } catch (e: IOException) {
            Log.w(TAG, "Copying the output $name failed", e)
            if (store.isAvailable()) WriteResult.Failed else WriteResult.FolderUnavailable
        }
    }

    // Everything below runs under LOCK.

    private fun writePdf(id: String, name: String, pdf: PdfMaker) {
        var failure: IOException? = null
        val delivered = pdf.make(id) { file ->
            try {
                replaceFile(store.rootId, "$name$PDF", store.list(store.rootId)) { out -> file.inputStream().use { it.copyTo(out) } }
                true
            } catch (e: IOException) {
                failure = e
                false
            }
        }
        if (!delivered) throw failure ?: IOException("No PDF for $id")
    }

    /** Settles conflict copies of top-level PDFs: the newer version keeps the name, the other is kept as `.lost-`. */
    private fun resolvePdfConflicts(top: List<FolderEntry>, reports: MutableList<ConflictReport>) {
        var entries = top
        for (copy in top.filter { !it.isFolder && FolderNames.conflictOf(it.name)?.name?.endsWith(PDF, ignoreCase = true) == true }) {
            val original = FolderNames.conflictOf(copy.name)!!.name
            val main = entries.firstOrNull { it.name == original && !it.isFolder }
            if (main == null) {
                renameExact(copy, original) // the copy is the only version
            } else {
                val lost = lostNameIn(entries, original)
                if (copy.modified > main.modified) {
                    renameExact(main, lost)
                    renameExact(copy, original)
                } else {
                    renameExact(copy, lost)
                }
                reports += ConflictReport(original, lost)
            }
            entries = store.list(store.rootId)
        }
    }

    /**
     * Merges conflict copies of `document.json` in scan folder [folderId] ([name] in `Scan sources/`) into it, one at a time; a copy that
     * cannot be read is kept as `.lost-`. Returns the folder's entries as they are afterwards.
     */
    private fun resolveRecordConflicts(folderId: String, start: List<FolderEntry>, name: String, reports: MutableList<ConflictReport>): List<FolderEntry> {
        var entries = start
        val path = "$SCAN_SOURCES/$name/${ScanLibrary.RECORD}"
        for (copy in start.filter { !it.isFolder && FolderNames.conflictOf(it.name)?.name == ScanLibrary.RECORD }) {
            val other = parse(copy)
            val main = recordOf(entries)
            when {
                other == null -> keepLost(folderId, copy, entries, path, reports)
                main == null -> {
                    entries.firstOrNull { it.name == ScanLibrary.RECORD && !it.isFolder }?.let { keepLost(folderId, it, entries, path, reports) }
                    renameExact(copy, ScanLibrary.RECORD)
                }
                else -> {
                    val merge = mergeRecords(main.doc, other.doc, entries.mapTo(HashSet()) { it.name })
                    var keptAs: String? = null
                    if (merge.loserLostSomething) {
                        keptAs = lostNameIn(entries, ScanLibrary.RECORD)
                        if (merge.loser === other.doc) renameExact(copy, keptAs) else writeNew(folderId, keptAs, entries) { it.write(main.bytes) }
                        entries = store.list(folderId)
                    }
                    if (merge.merged != main.doc) replaceFile(folderId, ScanLibrary.RECORD, entries) { it.write(jsonBytes(merge.merged)) }
                    // Merged in whole, so the copy has nothing left to keep.
                    if (keptAs == null || merge.loser !== other.doc) store.delete(copy.id)
                    reports += ConflictReport(path, keptAs)
                }
            }
            entries = store.list(folderId)
        }
        return entries
    }

    /** Renames [entry] to its `.lost-` name and reports it. */
    private fun keepLost(folderId: String, entry: FolderEntry, entries: List<FolderEntry>, path: String, reports: MutableList<ConflictReport>) {
        val original = FolderNames.conflictOf(entry.name)?.name ?: entry.name
        val lost = lostNameIn(entries, original)
        renameExact(entry, lost)
        reports += ConflictReport(path, lost)
        Log.i(TAG, "Kept a version of $path as $lost in $folderId")
    }

    private fun lostNameIn(entries: List<FolderEntry>, name: String): String =
        FolderNames.free(FolderNames.lostName(name, clock(), timeZone)) { n -> entries.any { it.name.equals(n, ignoreCase = true) } }

    private class Record(val doc: ScanDocument, val bytes: ByteArray) {
        val hash: String = WorkingCopies.sha256(bytes)
    }

    /**
     * The scan record among [entries]: `document.json`, else (a write interrupted between its renames) the complete `.tmp`, else the `.bak`.
     * Null if none of them can be read.
     */
    private fun recordOf(entries: List<FolderEntry>): Record? {
        fun named(n: String) = entries.firstOrNull { it.name == n && !it.isFolder }
        named(ScanLibrary.RECORD)?.let { return parse(it) }
        return named(ScanLibrary.RECORD + FolderNames.TMP)?.let(::parse) ?: named(ScanLibrary.RECORD + FolderNames.BAK)?.let(::parse)
    }

    /** Reads a record, or null if it is too large, not one, or names a file that could point outside its folder. */
    private fun parse(entry: FolderEntry): Record? {
        if (entry.size > MAX_RECORD_BYTES) return null
        return try {
            val bytes = store.openRead(entry.id).use { it.readBytes() }
            val doc = ScanDocument.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
            if (doc.pages.any { p -> p.files.any { !FolderNames.isSafe(it) || FolderNames.isHidden(it) || it == ScanLibrary.RECORD } }) null
            else Record(doc, bytes)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null // absurdly nested JSON
        }
    }

    /** Writes [name] as a new file: written in full as `<name>.tmp` (our own leftover of that name is replaced), then renamed. */
    private fun writeNew(folderId: String, name: String, entries: List<FolderEntry>, write: (OutputStream) -> Unit): FolderEntry {
        val (tmp, size) = writeTmp(folderId, name, entries, write)
        return checkSize(renameExact(tmp, name), size)
    }

    /**
     * Replaces [name] (or creates it): the new version is written as `<name>.tmp`, the current one renamed to `<name>.bak` (dropping the
     * previous `.bak`), and the new one renamed into place. SAF cannot rename over a file, so this is as close to atomic as it gets; a crash
     * in between leaves the `.tmp` or the `.bak`, both of which readers fall back on.
     */
    private fun replaceFile(folderId: String, name: String, entries: List<FolderEntry>, write: (OutputStream) -> Unit): FolderEntry {
        val (tmp, size) = writeTmp(folderId, name, entries, write)
        entries.firstOrNull { it.name == name && !it.isFolder }?.let { current ->
            entries.firstOrNull { it.name == name + FolderNames.BAK }?.let { store.delete(it.id) }
            renameExact(current, name + FolderNames.BAK)
        }
        return checkSize(renameExact(tmp, name), size)
    }

    private fun writeTmp(folderId: String, name: String, entries: List<FolderEntry>, write: (OutputStream) -> Unit): Pair<FolderEntry, Long> {
        val tmpName = name + FolderNames.TMP
        entries.firstOrNull { it.name == tmpName && !it.isFolder }?.let { store.delete(it.id) }
        val tmp = store.createFile(folderId, tmpName)
        if (tmp.name != tmpName) {
            store.delete(tmp.id)
            throw IOException("$tmpName was created as ${tmp.name}")
        }
        try {
            val counter = CountingStream(store.openWrite(tmp.id))
            counter.use(write)
            return tmp to counter.count
        } catch (e: Exception) {
            try {
                store.delete(tmp.id)
            } catch (ignored: IOException) {
            }
            throw e as? IOException ?: IOException("Writing $tmpName failed", e)
        }
    }

    private fun checkSize(entry: FolderEntry, size: Long): FolderEntry {
        if (entry.size != size) throw IOException("${entry.name} holds ${entry.size} of $size bytes")
        return entry
    }

    /** Renames [entry] to [name], which the caller saw free; throws if the store had to pick another name because it was taken meanwhile. */
    private fun renameExact(entry: FolderEntry, name: String): FolderEntry {
        val renamed = store.rename(entry.id, name)
        if (renamed.name != name) throw IOException("${entry.name} became ${renamed.name}, not $name")
        return renamed
    }

    private fun createFolderExact(parentId: String, name: String): FolderEntry {
        val created = store.createFolder(parentId, name)
        if (created.name != name) throw IOException("Folder $name was created as ${created.name}")
        return created
    }

    private fun List<FolderEntry>.sourcesFolder(): FolderEntry? = firstOrNull { it.isFolder && it.name == SCAN_SOURCES }

    private fun fileOf(e: FolderEntry) = FolderFile(e.id, e.name, e.size, e.modified)

    private fun jsonBytes(doc: ScanDocument): ByteArray = doc.toJson().toString(2).toByteArray(Charsets.UTF_8)

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private companion object {
        const val TAG = "ScanFolder"
        const val PDF = ".pdf"
        const val MAX_RECORD_BYTES = 1L shl 20

        /** One lock for every instance, as two over the same folder would race on its names. */
        val LOCK = Any()
    }
}
