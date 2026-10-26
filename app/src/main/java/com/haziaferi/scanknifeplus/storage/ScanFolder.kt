package com.haziaferi.scanknifeplus.storage

import android.util.Log
import androidx.annotation.WorkerThread
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.library.StoredRecord
import com.haziaferi.scanknifeplus.scanner.export.ExportNames
import com.haziaferi.scanknifeplus.storage.FolderNames.SCAN_SOURCES
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone

/** A file at the top level of the user's folder; [size] is null when the provider does not report one. */
data class FolderFile(val id: String, val name: String, val size: Long?, val modified: Long)

/** A scan in `Scan sources/`: [name] is its folder there, [pdf] the top-level `<name>.pdf` if there is one. */
data class FolderScan(val name: String, val folderId: String, val modified: Long, val pdf: FolderFile?)

/** What History shows, read from the folder; a folder that cannot be listed is [Unreadable], never an empty listing. */
sealed interface FolderListing {
    /**
     * [files] are the visible top-level files and [scans] the scans in `Scan sources/`, both newest first. [conflicts] are the Syncthing
     * conflict copies at the top level, before [ScanFolder.resolveConflicts] settles them (or for good, for files it does not handle).
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

    /**
     * The record went to the folder as [name], merged with a change made there, but the working copy could not take the merged result, so
     * no PDF was made; write back again. [conflicts] as in [Written].
     */
    data class Incomplete(val name: String, val conflicts: List<ConflictReport>) : WriteResult

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
 * The user's folder: PDFs at the top level and each scan's sources in `Scan sources/<name>/`, written back from working copies in the private
 * [library] that [copies] ties to this folder. Files are written as `.tmp` then renamed, replaced ones kept as `.bak`, conflict losers as
 * `.lost-<time>`, and nothing is deleted but its own leftovers, merged conflict copies and pages an edit made obsolete ([writeBack]). Every
 * method blocks, and they run one at a time across instances.
 */
class ScanFolder(
    private val store: FolderStore,
    private val library: ScanLibrary,
    private val libraryRoot: File,
    private val copies: WorkingCopies,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    /** History's view of the folder, from two queries (the root and `Scan sources/`); scan folders are not opened. */
    @WorkerThread
    fun listing(): FolderListing = synchronized(LOCK) {
        try {
            val top = store.list(store.rootId)
            val visible = top.filter { !it.isFolder && !FolderNames.isHidden(it.name) }
            val (conflicts, files) = visible.partition { FolderNames.conflictOf(it.name) != null }
            val byName = files.associateBy { it.name }
            val scans = top.sourcesFolder()?.let { sources ->
                store.list(sources.id).filter { it.isFolder && !FolderNames.isHidden(it.name) }
                    .map { FolderScan(it.name, it.id, it.modified, byName["${it.name}$PDF"]?.let(::fileOf)) }
            }.orEmpty()
            val newestFirst = files.map(::fileOf).sortedByDescending { it.modified }
            FolderListing.Listed(newestFirst, scans.sortedByDescending { it.modified }, conflicts.map(::fileOf))
        } catch (e: IOException) {
            Log.w(TAG, "Cannot list the folder", e)
            FolderListing.Unreadable
        }
    }

    /**
     * Resolves Syncthing conflict copies of top-level PDFs and of every scan's `document.json`, opening every scan folder. Returns what it
     * resolved, stopping at the first failure; the rest waits for the next call.
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
     * Copies folder scan [name] into a working copy, resolving its conflicts first. An existing working copy is returned as it is if the
     * folder's record has not changed since; otherwise it is reloaded from the folder, or, if it has edits of its own, merged by [writeBack].
     */
    @WorkerThread
    fun copyIn(name: String): CopyInResult = synchronized(LOCK) {
        if (!FolderNames.isSafe(name) || FolderNames.isHidden(name)) return CopyInResult.NotFound
        try {
            val sources = store.list(store.rootId).sourcesFolder() ?: return CopyInResult.NotFound
            val folder = store.list(sources.id).firstOrNull { it.isFolder && it.name == name } ?: return CopyInResult.NotFound
            val reports = mutableListOf<ConflictReport>()
            val entries = resolveRecordConflicts(folder.id, store.list(folder.id), name, reports)
            val record = recordOf(entries) ?: return CopyInResult.NotFound
            val missing = record.doc.pages.flatMap { it.files }.distinct().filter { f -> entries.none { it.name == f && !it.isFolder } }
            if (missing.isNotEmpty()) return CopyInResult.Incomplete(missing)

            // A binding whose working copy is gone is dropped, so it can never lead to a second copy of the same scan.
            val (existing, stale) = copies.idsOf(store.location, name).partition { library.exists(it) }
            stale.forEach { copies.remove(it) }
            if (existing.isNotEmpty()) return refresh(existing.first(), folder.id, entries, record, reports)

            val stored = staged(entries, record.doc.pages.flatMap { it.files }) { staging ->
                library.import(staging, name, record.doc) { pending ->
                    // Bound before the record lands, so no kill in between can leave a working copy that no binding knows about.
                    copies.put(pending.document.id, Binding(store.location, name, record.hash, WorkingCopies.sha256(pending.bytes)))
                }
            }
            CopyInResult.Ready(stored.document.id, reports)
        } catch (e: IOException) {
            Log.w(TAG, "Copying $name in failed", e)
            if (store.isAvailable()) CopyInResult.Failed else CopyInResult.FolderUnavailable
        }
    }

    /**
     * Writes working copy [id] back: missing page files, the record, then (with [pdf]) `<name>.pdf`; a scan new here only creates files. If
     * the folder's record changed meanwhile the two are merged and the working copy reloaded with the result. Page files only the replaced
     * record named are deleted unless there was a merge or a `.lost-` version may need them; the copy counts as confirmed only with its PDF.
     */
    @WorkerThread
    fun writeBack(id: String, pdf: PdfMaker? = null): WriteResult = writeBack(id, pdf, mayDelete = true)

    /**
     * [writeBack], and with [text] the PDF gets a `.txt` beside it of the same base name, made from the document as the PDF shows it and
     * written as the PDF is.
     */
    @WorkerThread
    fun writeBack(id: String, pdf: PdfMaker?, text: ((ScanDocument) -> String)?): WriteResult = writeBack(id, pdf, mayDelete = true, text)

    private fun writeBack(
        id: String,
        pdf: PdfMaker?,
        mayDelete: Boolean,
        text: ((ScanDocument) -> String)? = null,
    ): WriteResult = synchronized(LOCK) {
        // One read of record and bytes: what is written, and later confirmed, is exactly this version, whatever edits come after.
        val working = library.stored(id) ?: return WriteResult.Failed
        val doc = working.document
        val binding = copies.get(id)?.takeIf { it.folder == store.location }
        var pdfName = binding?.pdfName
        var pdfHash = binding?.pdfHash
        try {
            val reports = mutableListOf<ConflictReport>()
            val top = store.list(store.rootId)
            val sources = top.sourcesFolder() ?: createFolderExact(store.rootId, SCAN_SOURCES)
            val scans = store.list(sources.id)
            val name = binding?.name ?: FolderNames.free(ExportNames.exportFileName(doc.displayName, doc.id), isFolder = true) { n ->
                scans.any { it.name.equals(n, ignoreCase = true) } || top.any { it.name.equals("$n$PDF", ignoreCase = true) }
            }
            fun bind(base: String?, confirmed: String?) = copies.put(id, Binding(store.location, name, base, confirmed, pdfName, pdfHash))
            // Saved first, so a retry after a failure writes to the same folder instead of starting a second one.
            if (binding == null) bind(base = null, confirmed = null)
            val folder = scans.firstOrNull { it.isFolder && it.name == name } ?: createFolderExact(sources.id, name)

            var entries = resolveRecordConflicts(folder.id, store.list(folder.id), name, reports)
            val damaged = entries.firstOrNull { it.name == ScanLibrary.RECORD && !it.isFolder }?.takeIf { parse(it) == null }
            if (damaged != null) {
                // Never written over: a record this app cannot read may be a newer version's.
                keepLost(damaged, entries, "$SCAN_SOURCES/$name/${ScanLibrary.RECORD}", reports)
                entries = store.list(folder.id)
            }
            val folderRecord = recordOf(entries)

            for (file in doc.pages.flatMap { it.files }.distinct()) {
                val local = library.file(id, file)
                val existing = entries.firstOrNull { it.name == file && !it.isFolder }
                if (existing != null) {
                    // Page files are write-once under unique names, so one of the same name is the same page; a different size is not.
                    if (sizeOf(existing) != local.length()) throw IOException("$name/$file exists with other content")
                    continue
                }
                writeNew(folder.id, file, entries) { out -> local.inputStream().use { it.copyTo(out) } }
            }
            entries = store.list(folder.id)

            val unchangedInFolder = folderRecord == null || folderRecord.hash == binding?.base
            var merged: ScanDocument? = null
            val recordBytes = if (unchangedInFolder) {
                working.bytes
            } else {
                val merge = mergeRecords(doc, folderRecord.doc, entries.mapTo(HashSet()) { it.name })
                if (merge.loserLostSomething) {
                    val loserBytes = if (merge.loser === doc) working.bytes else folderRecord.bytes
                    val lost = lostNameIn(entries, ScanLibrary.RECORD)
                    writeNew(folder.id, lost, entries) { it.write(loserBytes) }
                    reports += ConflictReport("$SCAN_SOURCES/$name/${ScanLibrary.RECORD}", lost)
                    entries = store.list(folder.id)
                }
                merged = merge.merged
                ScanLibrary.recordBytes(merge.merged)
            }
            replaceFile(folder.id, ScanLibrary.RECORD, entries) { it.write(recordBytes) }
            val base = WorkingCopies.sha256(recordBytes)

            // The binding follows the folder at once, so a later failure (the PDF, say) never makes the next write-back merge with its own
            // output; after a merge, only once the working copy holds the merged record too.
            val current = if (merged == null) {
                bind(base, confirmed = null)
                if (mayDelete && folderRecord != null && entries.none { FolderNames.isLost(it.name) }) {
                    val keep = doc.pages.flatMapTo(HashSet()) { it.files }
                    val obsolete = folderRecord.doc.pages.flatMap { it.files }.filter { it !in keep }.toSet()
                    store.list(folder.id).filter { it.name in obsolete && !it.isFolder }.forEach { store.delete(it.id) }
                }
                working
            } else {
                // Edited during the write-back, or not reloadable: the old base keeps the next write-back merging instead of dropping pages.
                val reloaded = reload(id, working, folder.id, merged) ?: run {
                    bind(folderRecord!!.hash, confirmed = null)
                    return WriteResult.Incomplete(name, reports)
                }
                bind(base, confirmed = null)
                reloaded
            }

            // Built from the working copy as it now is, so after a merge the PDF shows the merged pages.
            if (pdf != null) {
                val (writtenName, writtenHash) = writePdf(id, name, pdf, pdfName, pdfHash)
                pdfName = writtenName
                pdfHash = writtenHash
                bind(base, confirmed = WorkingCopies.sha256(current.bytes))
                text?.let { writeText(writtenName.removeSuffix(PDF) + TXT, it(current.document)) }
            }
            WriteResult.Written(name, reports)
        } catch (e: IOException) {
            Log.w(TAG, "Writing $id back failed", e)
            if (store.isAvailable()) WriteResult.Failed else WriteResult.FolderUnavailable
        }
    }

    /**
     * Writes every working copy this folder does not have in full yet (at first every scan, after a change of folder every scan, and a scan
     * whose record was written but not its PDF) with its PDF, creating files and deleting nothing (a PDF already at a scan's name gets a `-2`
     * name rather than being replaced). The list is fixed first and private copies stay until the launch sweep finds them confirmed, so a
     * migration that stops halfway loses nothing and a second run finishes it. Working copies in [skip] are left out: a scan still open is
     * written by its own [writeBack].
     */
    @WorkerThread
    fun migrate(pdf: PdfMaker?, skip: Set<String> = emptySet()): Migration = synchronized(LOCK) {
        val snapshot = library.documents().map { it.id }
            .filter { id -> id !in skip && copies.get(id)?.takeIf { it.folder == store.location }?.confirmed == null }
        val written = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((i, id) in snapshot.withIndex()) {
            when (writeBack(id, pdf, mayDelete = false)) {
                is WriteResult.Written -> written += id
                is WriteResult.Incomplete, WriteResult.Failed -> failed += id
                WriteResult.FolderUnavailable -> return Migration(written, failed + snapshot.drop(i), folderUnavailable = true)
            }
        }
        Migration(written, failed, folderUnavailable = false)
    }

    /** True if working copy [id] holds something this folder does not have yet. */
    @WorkerThread
    fun hasChanges(id: String): Boolean = synchronized(LOCK) {
        val binding = copies.get(id)?.takeIf { it.folder == store.location } ?: return true
        val stored = library.stored(id) ?: return true
        binding.confirmed != WorkingCopies.sha256(stored.bytes)
    }

    /** Copies a tool output the user already saved elsewhere to the top level as [name] (or `<base>-2<ext>` and so on), creating it only. */
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

    /** [copyIn] for a scan that already has working copy [id]; see there. */
    private fun refresh(id: String, folderId: String, entries: List<FolderEntry>, record: Record, reports: List<ConflictReport>): CopyInResult {
        val binding = copies.get(id) ?: return CopyInResult.Failed
        if (record.hash == binding.base) return CopyInResult.Ready(id, reports)
        val working = library.stored(id) ?: return CopyInResult.Failed
        if (WorkingCopies.sha256(working.bytes) != binding.confirmed) {
            return when (val r = writeBack(id)) {
                is WriteResult.Written -> CopyInResult.Ready(id, reports + r.conflicts)
                is WriteResult.Incomplete -> CopyInResult.Failed
                WriteResult.FolderUnavailable -> CopyInResult.FolderUnavailable
                WriteResult.Failed -> CopyInResult.Failed
            }
        }
        val reloaded = reload(id, working, folderId, record.doc, entries) ?: return CopyInResult.Failed
        copies.put(id, binding.copy(base = record.hash, confirmed = WorkingCopies.sha256(reloaded.bytes)))
        return CopyInResult.Ready(id, reports)
    }

    /**
     * Makes working copy [id] hold [doc] from folder [folderId]: the pages it lacks are copied in and the record replaced, unless the working
     * copy changed since [working] was read. Returns what was written, or null if it changed or a file could not be copied.
     */
    private fun reload(
        id: String,
        working: StoredRecord,
        folderId: String,
        doc: ScanDocument,
        entries: List<FolderEntry> = store.list(folderId),
    ): StoredRecord? {
        val lacking = doc.pages.flatMap { it.files }.distinct().filter { !library.file(id, it).exists() }
        return try {
            staged(entries, lacking) { staging -> library.replace(id, working.bytes, staging, doc) }
        } catch (e: IOException) {
            Log.w(TAG, "Reloading $id failed", e)
            null
        }
    }

    /**
     * Copies [files] from [entries] into a new hidden staging folder in the library, synced, and hands it to [take], which moves them into the
     * library; the staging folder is removed afterwards whatever happens.
     */
    private fun <T> staged(entries: List<FolderEntry>, files: List<String>, take: (File) -> T): T {
        val staging = ScanFiles.newFolder(libraryRoot, WorkingCopies.IMPORT_PREFIX) ?: throw IOException("No staging folder in $libraryRoot")
        try {
            for (file in files) {
                val entry = entries.firstOrNull { it.name == file && !it.isFolder } ?: throw IOException("$file is not in the folder")
                val copied = store.openRead(entry.id).use { input ->
                    FileOutputStream(File(staging, file)).use { out -> input.copyTo(out).also { out.fd.sync() } }
                }
                if (copied != sizeOf(entry)) throw IOException("$file: read $copied of ${sizeOf(entry)} bytes")
            }
            return take(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Writes document [id]'s PDF at the top level and returns its name and SHA-256. It replaces [ownName] only while that file is still
     * exactly the PDF this binding wrote ([ownHash]); otherwise it creates `<name>.pdf` or the next free `-2` name, so a PDF the user put
     * there, or changed, is never replaced.
     */
    private fun writePdf(id: String, name: String, pdf: PdfMaker, ownName: String?, ownHash: String?): Pair<String, String> {
        var failure: IOException? = null
        var written: Pair<String, String>? = null
        val delivered = pdf.make(id) { file ->
            try {
                val hash = file.inputStream().use(WorkingCopies::sha256)
                val top = store.list(store.rootId)
                val own = ownName?.let { n -> top.firstOrNull { it.name == n && !it.isFolder } }
                val copy: (OutputStream) -> Unit = { out -> file.inputStream().use { it.copyTo(out) } }
                val entry = if (own != null && ownHash != null && store.openRead(own.id).use(WorkingCopies::sha256) == ownHash) {
                    replaceFile(store.rootId, own.name, top, copy)
                } else {
                    writeNew(store.rootId, FolderNames.free("$name$PDF") { n -> top.any { it.name.equals(n, ignoreCase = true) } }, top, copy)
                }
                written = entry.name to hash
                true
            } catch (e: IOException) {
                failure = e
                false
            }
        }
        return written.takeIf { delivered } ?: throw failure ?: IOException("No PDF for $id")
    }

    /**
     * Writes [content] at the top level as [name], replacing the one there through `.bak` as the record is replaced: it is the text of the
     * PDF beside it, which this scan's save has just written or replaced.
     */
    private fun writeText(name: String, content: String) {
        val top = store.list(store.rootId)
        val write: (OutputStream) -> Unit = { it.write(content.toByteArray(Charsets.UTF_8)) }
        if (top.any { it.name == name && !it.isFolder }) replaceFile(store.rootId, name, top, write) else writeNew(store.rootId, name, top, write)
    }

    /** Settles conflict copies of top-level PDFs: the newer version keeps the name, the other is kept as `.lost-`. */
    private fun resolvePdfConflicts(top: List<FolderEntry>, reports: MutableList<ConflictReport>) {
        var entries = top
        for (copy in top.filter { !it.isFolder && FolderNames.conflictOf(it.name)?.endsWith(PDF, ignoreCase = true) == true }) {
            val original = FolderNames.conflictOf(copy.name)!!
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
     * Merges conflict copies of `document.json` in scan folder [folderId] ([name] in `Scan sources/`) into it; a copy that cannot be read is
     * kept as `.lost-`. Returns the folder's entries as they are afterwards.
     */
    private fun resolveRecordConflicts(
        folderId: String,
        start: List<FolderEntry>,
        name: String,
        reports: MutableList<ConflictReport>,
    ): List<FolderEntry> {
        var entries = start
        val path = "$SCAN_SOURCES/$name/${ScanLibrary.RECORD}"
        for (copy in start.filter { !it.isFolder && FolderNames.conflictOf(it.name) == ScanLibrary.RECORD }) {
            // A record this app cannot read is set aside first, so the merge below never moves it to .bak and later drops it.
            entries.firstOrNull { it.name == ScanLibrary.RECORD && !it.isFolder && parse(it) == null }?.let {
                keepLost(it, entries, path, reports)
                entries = store.list(folderId)
            }
            val other = parse(copy)
            val main = recordOf(entries)
            when {
                other == null -> keepLost(copy, entries, path, reports)
                main == null -> {
                    entries.firstOrNull { it.name == ScanLibrary.RECORD && !it.isFolder }?.let { keepLost(it, entries, path, reports) }
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
                    if (merge.merged != main.doc) {
                        replaceFile(folderId, ScanLibrary.RECORD, entries) { it.write(ScanLibrary.recordBytes(merge.merged)) }
                    }
                    // Merged in whole, so the copy has nothing left to keep.
                    if (keptAs == null || merge.loser !== other.doc) store.delete(copy.id)
                    reports += ConflictReport(path, keptAs)
                }
            }
            entries = store.list(folderId)
        }
        return entries
    }

    /** Renames [entry] to its `.lost-` name and reports it under [path]. */
    private fun keepLost(entry: FolderEntry, entries: List<FolderEntry>, path: String, reports: MutableList<ConflictReport>) {
        val lost = lostNameIn(entries, FolderNames.conflictOf(entry.name) ?: entry.name)
        renameExact(entry, lost)
        reports += ConflictReport(path, lost)
    }

    private fun lostNameIn(entries: List<FolderEntry>, name: String): String =
        FolderNames.free(FolderNames.lostName(name, clock(), timeZone)) { n -> entries.any { it.name.equals(n, ignoreCase = true) } }

    private class Record(val doc: ScanDocument, val bytes: ByteArray) {
        val hash: String = WorkingCopies.sha256(bytes)
    }

    /**
     * The scan record among [entries]: `document.json`; if it is missing, the `.tmp` a write interrupted between its renames leaves, then the
     * `.bak`; if it cannot be read, the `.bak`, then the `.tmp`.
     */
    private fun recordOf(entries: List<FolderEntry>): Record? {
        fun named(n: String) = entries.firstOrNull { it.name == n && !it.isFolder }?.let(::parse)
        val tmp = ScanLibrary.RECORD + FolderNames.TMP
        val bak = ScanLibrary.RECORD + FolderNames.BAK
        if (entries.any { it.name == ScanLibrary.RECORD && !it.isFolder }) return named(ScanLibrary.RECORD) ?: named(bak) ?: named(tmp)
        return named(tmp) ?: named(bak)
    }

    /** Reads a record as the library would, or null if it is not one or names a file hidden in the folder. */
    private fun parse(entry: FolderEntry): Record? {
        if ((entry.size ?: 0) > ScanLibrary.MAX_RECORD_BYTES) return null
        val bytes = try {
            store.openRead(entry.id).use { readAtMost(it, ScanLibrary.MAX_RECORD_BYTES) }
        } catch (e: IOException) {
            null
        } ?: return null
        val doc = ScanLibrary.parseRecord(bytes) ?: return null
        return if (doc.pages.any { p -> p.files.any(FolderNames::isHidden) }) null else Record(doc, bytes)
    }

    /** All of [input], or null if it holds more than [max] bytes (a provider may not report a size up front). */
    private fun readAtMost(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > max) return null
        }
    }

    /** Writes [name] as a new file: written in full as `<name>.tmp` (our own leftover of that name is replaced), then renamed. */
    private fun writeNew(folderId: String, name: String, entries: List<FolderEntry>, write: (OutputStream) -> Unit): FolderEntry {
        return renameExact(writeTmp(folderId, name, entries, write), name)
    }

    /**
     * Replaces [name] (or creates it): the new version is written as `<name>.tmp`, the current one renamed to `<name>.bak` (dropping the
     * previous `.bak`), and the new one renamed into place. SAF cannot rename over a file; a crash in between leaves a `.tmp` or `.bak`
     * that readers fall back on.
     */
    private fun replaceFile(folderId: String, name: String, entries: List<FolderEntry>, write: (OutputStream) -> Unit): FolderEntry {
        val tmp = writeTmp(folderId, name, entries, write)
        entries.firstOrNull { it.name == name && !it.isFolder }?.let { current ->
            entries.firstOrNull { it.name == name + FolderNames.BAK }?.let { store.delete(it.id) }
            renameExact(current, name + FolderNames.BAK)
        }
        return renameExact(tmp, name)
    }

    /** Writes `<name>.tmp` in full and checks its stored size against the bytes written, measured from the file; deletes it on any failure. */
    private fun writeTmp(folderId: String, name: String, entries: List<FolderEntry>, write: (OutputStream) -> Unit): FolderEntry {
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
            val stored = store.sizeOf(tmp.id)
            if (stored != counter.count) throw IOException("$tmpName holds $stored of ${counter.count} bytes")
            return tmp
        } catch (e: Exception) {
            try {
                store.delete(tmp.id)
            } catch (cleanup: IOException) {
                // The original failure is what matters; a leftover .tmp is hidden and replaced by the next write of this name.
                e.addSuppressed(cleanup)
            }
            throw e as? IOException ?: IOException("Writing $tmpName failed", e)
        }
    }

    private fun sizeOf(entry: FolderEntry): Long = entry.size ?: store.sizeOf(entry.id)

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
        const val TXT = ".txt"

        /** One lock for every instance, as two over the same folder would race on its names. */
        val LOCK = Any()
    }
}
