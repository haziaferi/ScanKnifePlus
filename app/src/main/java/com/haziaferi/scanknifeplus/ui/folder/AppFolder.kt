package com.haziaferi.scanknifeplus.ui.folder

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.export.ScanPdfExporter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.export.ExportNames
import com.haziaferi.scanknifeplus.scanner.export.ExportQuality
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.storage.ConflictReport
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.FolderListing
import com.haziaferi.scanknifeplus.storage.FolderNames
import com.haziaferi.scanknifeplus.storage.FolderState
import com.haziaferi.scanknifeplus.storage.Migration
import com.haziaferi.scanknifeplus.storage.PdfMaker
import com.haziaferi.scanknifeplus.storage.SavedEntry
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.haziaferi.scanknifeplus.storage.ScanFolder
import com.haziaferi.scanknifeplus.storage.WriteResult
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.components.SavePlaces
import com.paperknifeplus.app.ui.components.SaveSpot
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What Home's folder bar and Settings say about the user's folder; [name] is null when it cannot be told. */
sealed interface FolderStatus {
    data object NotChosen : FolderStatus

    data class Ready(val name: String?) : FolderStatus

    /** A folder was chosen but cannot be written to: only saving into it waits until it is chosen again. */
    data class ChooseAgain(val name: String?) : FolderStatus

    /** The first copy of the [count] scans on this phone, and the move of the [files] kept in the app, into a newly chosen folder is running. */
    data class Copying(val name: String?, val count: Int, val files: Int = 0) : FolderStatus
}

/** A message the app shows once, as a toast, then clears with [AppFolder.noticeShown]. */
sealed interface FolderNotice {
    /** Sync conflicts were settled; [keptCopies] of them left a conflict copy beside the file. */
    data class Conflicts(val count: Int, val keptCopies: Int) : FolderNotice

    /** Scans [written] into the folder and [failed]; files kept in the app [moved] into it and those [unmoved], which stay in the app. */
    data class Copied(val written: Int, val failed: Int, val moved: Int = 0, val unmoved: Int = 0) : FolderNotice

    /** The copy into the folder stopped because the folder could no longer be reached; [left] scans and [files] kept in the app stay. */
    data class CopyStopped(val left: Int, val files: Int = 0) : FolderNotice
}

/** Whether Folder setup has been answered ("Not now", or a folder chosen), so it no longer opens by itself at launch. */
interface SetupSeen {
    fun get(): Boolean

    fun set()
}

/** [SetupSeen] in the app's preferences, written at once (one small value). */
class PrefsSetupSeen(context: Context) : SetupSeen {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get() = prefs.getBoolean(KEY_SEEN, false)

    override fun set() = prefs.edit(commit = true) { putBoolean(KEY_SEEN, true) }

    private companion object {
        const val PREFS = "folder_setup"
        const val KEY_SEEN = "setup_seen"
    }
}

/** Whether a copy of the scans on this phone into the folder started and did not finish, so the next launch runs it again. */
interface CopyPending {
    fun get(): Boolean

    fun set(pending: Boolean)
}

/** [CopyPending] in the app's preferences, beside [PrefsSetupSeen], written at once. */
class PrefsCopyPending(context: Context) : CopyPending {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get() = prefs.getBoolean(KEY_PENDING, false)

    override fun set(pending: Boolean) = prefs.edit(commit = true) { putBoolean(KEY_PENDING, pending) }

    private companion object {
        const val PREFS = "folder_setup"
        const val KEY_PENDING = "copy_pending"
    }
}

/** [ScanPdfExporter.writePdf] as the storage layer's [PdfMaker], all pages at [size], encrypted for [password] if given, with [text] layers. */
fun ScanPdfExporter.pdfMaker(size: PdfPageSize = PdfPageSize.DEFAULT, password: String? = null, text: Map<String, OcrPage> = emptyMap()): PdfMaker =
    PdfMaker { id, deliver -> writePdf(id, null, ExportQuality.DEFAULT, size, password, text = text, deliver = deliver) != null }

/**
 * The app's view of the user's folder ([FolderAccess]) and of the files it saved ([files]) as Compose state: whether the folder can be used,
 * its name, History's [listing] of it and the [saved] files, and the one-time copy of the scans on this phone, with the move of the files
 * kept in the app, after a folder is chosen ([choose]). It is also where the tools' saves go ([create]). Folder work runs on [io], one call
 * at a time in the storage layer; state is written on [scope]'s thread, the main thread in the app. Reading never waits for a write.
 */
class AppFolder(
    context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val access: FolderAccess = FolderAccess(context),
    private val library: ScanLibrary = ScanLibrary(ScanFiles.libraryDir(context)),
    private val pdf: PdfMaker = ScanPdfExporter(library, ScanFiles.exportDir(context)).pdfMaker(),
    private val setupSeen: SetupSeen = PrefsSetupSeen(context),
    private val copyPending: CopyPending = PrefsCopyPending(context),
    private val sweep: () -> Unit = { access.sweep() },
    private val files: SavedFiles = SavedFiles.of(context),
) : SavePlaces {
    // Completed once the launch sweep has run: WorkingCopies.sweep must finish before any session or copy into the folder starts.
    private val swept = CompletableDeferred<Unit>()
    private val resolver: ContentResolver = context.contentResolver
    private var pruned = 0L

    /** Every file the app saved, newest first, as the index last said ([SavedFiles]); empty until the launch work has read it. */
    var saved by mutableStateOf<List<SavedEntry>>(emptyList())
        private set

    /** How many files are kept in the app, which "Move to folder" moves into a folder once one is chosen. */
    val appFiles: Int get() = saved.count { it.inApp }

    /** Whether Home's folder bar and History offer "Move to folder": files wait in the app and no folder is chosen. */
    val offersMove: Boolean get() = appFiles > 0 && status == FolderStatus.NotChosen

    /** Files kept in the app that the move into a folder still has to take (not those that stay there on purpose). */
    private val movable: Int get() = saved.count { it.inApp && !it.held }

    // Files saved elsewhere into the chosen folder itself: its listing shows them, so History lists them once.
    private var insideFolder by mutableStateOf(emptySet<String>())

    /** The [saved] files History and Home list: finished saves, and none the folder's listing shows already. */
    val listed: List<SavedEntry> get() = saved.filter { it.pending == null && it.id !in insideFolder }

    /** True once the launch found the list of saved files unreadable and rebuilt it from the files kept in the app; said once. */
    var rebuilt by mutableStateOf(false)
        private set

    /** Whether a move into the folder runs in this process, from this holder or an earlier one; History's Delete waits until it ends. */
    val moveRunning: Boolean get() = running

    /** The folder as last checked; null until the first check. */
    var state by mutableStateOf<FolderState?>(if (access.treeUri() == null) FolderState.NotChosen else null)
        private set

    /** The chosen folder's name as the user knows it (`Documents/Scans` on the phone's storage), whether or not it can be reached. */
    var name by mutableStateOf<String?>(null)
        private set

    /** The folder's contents for History; null until listed, and while no folder is ready. */
    var listing by mutableStateOf<FolderListing?>(null)
        private set

    /** How many scans the running copy into the folder takes; null while none runs. */
    var copying by mutableStateOf<Int?>(null)
        private set

    /** How many files kept in the app the running copy moves into the folder. */
    private var moving by mutableIntStateOf(0)

    /** How many scans this phone holds, which a newly chosen folder receives; counted at each [refresh]. */
    var scansOnPhone by mutableStateOf(0)
        private set

    var notice by mutableStateOf<FolderNotice?>(null)
        private set

    private val stopListening = files.listen { list ->
        scope.launch {
            saved = list
            // A tool's output just saved into the folder shows in History's listing, and a file saved elsewhere into it is listed once; the
            // move lists the folder once, when it is done.
            val newest = list.firstOrNull()?.place
            if ((newest == SavedPlace.FOLDER || newest == SavedPlace.ELSEWHERE) && copying == null && state is FolderState.Ready) load(resolve = false)
        }
    }

    val status: FolderStatus get() {
        copying?.let { return FolderStatus.Copying(name, it, moving) }
        return when (state) {
            FolderState.NotChosen -> FolderStatus.NotChosen
            FolderState.ChooseAgain -> FolderStatus.ChooseAgain(name)
            // Not checked yet: a folder was chosen, and it is almost always still there.
            is FolderState.Ready, null -> FolderStatus.Ready(name)
        }
    }

    /** History's view of the folder; null while no folder is chosen, so History shows the [saved] files alone. */
    val history: HistoryFolder? by derivedStateOf {
        when (val s = state) {
            FolderState.NotChosen -> null
            FolderState.ChooseAgain -> HistoryFolder(name, null, unreadable = true)
            null -> HistoryFolder(name, null)
            is FolderState.Ready -> when (val l = listing) {
                FolderListing.Unreadable -> HistoryFolder(name, null, unreadable = true)
                is FolderListing.Listed -> HistoryFolder(name, folderItems(l, s.store::uri))
                null -> HistoryFolder(name, null)
            }
        }
    }

    /** Whether Folder setup opens by itself at launch: no folder chosen, and its "Not now" never tapped. */
    val offersSetup: Boolean get() = access.treeUri() == null && !setupSeen.get()

    /** "Not now" on Folder setup: files are kept in the app, and setup no longer opens by itself. */
    fun dismissSetup() = setupSeen.set()

    /**
     * The launch work, off the main thread: the sweep of working copies the folder already holds, the check of the files kept in the app
     * ([SavedFiles.recover]), then the folder's sync conflicts, reported once, then the first listing, then a copy into the folder that an
     * earlier launch left unfinished, or the move of files kept in the app while the folder could not be reached, and last the check of
     * the files saved elsewhere ([SavedFiles.prune]), which may wait on their providers. Run once per launch; scan sessions and copies into
     * the folder wait for the sweep ([awaitSwept]).
     */
    fun start() {
        scope.launch {
            try {
                withContext(io) { sweep() }
            } finally {
                swept.complete(Unit)
            }
            saved = withContext(io) {
                // A PDF found without its row whose name is a scan's own stays in the app: its scan goes into a folder with a PDF of its own.
                val scanPdfs = library.documents().mapTo(HashSet()) { (ExportNames.exportFileName(it.displayName, it.id) + PDF).lowercase() }
                files.recover { name -> files.partial && FREE_SUFFIX.replace(name, ".pdf").lowercase() in scanPdfs }
                files.entries()
            }
            rebuilt = files.damaged
            load(resolve = true)
            // Create-only, so scans the stopped copy already wrote, or a scan since saved into the folder, are not written twice.
            if ((copyPending.get() || movable > 0) && state is FolderState.Ready) copyScans()
            withContext(io) { files.prune() }
            pruned = System.currentTimeMillis()
        }
    }

    fun rebuiltShown() {
        rebuilt = false
    }

    /** Returns once the launch sweep of [start] has run. */
    suspend fun awaitSwept() = swept.await()

    /**
     * Checks the folder again and lists it; with [resolve], first settles its sync conflicts and reports them. At most once a minute it also
     * drops the files saved elsewhere that can no longer be opened.
     */
    fun refresh(resolve: Boolean = false) {
        scope.launch {
            if (System.currentTimeMillis() - pruned > PRUNE_EVERY_MS) {
                pruned = System.currentTimeMillis()
                withContext(io) { files.prune() }
            }
            load(resolve)
        }
    }

    /**
     * Where a tool's save goes now: a new file at the folder's top level when it is ready, else in the app; when the folder was chosen but
     * cannot be reached, in the app with the notice that it moves there once chosen again, and when it could not make the file or a move into
     * it runs, with the notice that it moves there at the next launch. Null when not even the app can make it. Blocking.
     */
    @WorkerThread
    override fun create(name: String): SaveSpot? = when (val s = access.state()) {
        FolderState.NotChosen -> inApp(name, null)
        FolderState.ChooseAgain -> inApp(name, R.string.tool_saved_in_app)
        // A running move holds the folder for minutes: the save goes in the app at once instead, and moves at the next launch.
        is FolderState.Ready -> if (MOVE_LOCK.isLocked) {
            inApp(name, R.string.tool_saved_in_app_later)
        } else {
            s.folder.createOutput(name)?.let { SaveSpot(s.store.uri(it.id)) } ?: inApp(name, R.string.tool_saved_in_app_later)
        }
    }

    private fun inApp(name: String, notice: Int?): SaveSpot? = files.create(name)?.let { SaveSpot(files.uriOf(it), notice) }

    /**
     * Deletes the file kept in the app that [entry] lists, then its row (History's Delete), once no move runs: a file the move took meanwhile
     * is in the folder and is not deleted. [onDone] says whether it went.
     */
    fun deleteSaved(entry: SavedEntry, onDone: (Boolean) -> Unit = {}) {
        scope.launch { onDone(MOVE_LOCK.withLock { withContext(io) { files.delete(entry.id) } }) }
    }

    /** Stops following the saved files' changes; for the end of the holder. */
    fun close() = stopListening()

    /**
     * Adopts [treeUri] from the folder picker and tells [onResult] whether it could (false keeps the folder as it was: it cannot be written
     * to). Then, with [copyScans], the scans on this phone are copied into it in the background, creating files only; a caller holding a
     * scan in progress passes false and calls [copyScansIn] leaving that scan out. Runs on this object's scope, so leaving the screen that
     * asked does not stop the copy.
     */
    fun choose(treeUri: Uri, copyScans: Boolean = true, onResult: (Boolean) -> Unit) {
        scope.launch {
            val adopted = withContext(io) { access.adopt(treeUri) }
            if (adopted) {
                dismissSetup()
                load(resolve = false)
            }
            onResult(adopted)
            if (adopted && copyScans) copyScans()
        }
    }

    /** Copies the scans on this phone that the folder does not have yet into it, in the background, except those in [skip]; see [choose]. */
    fun copyScansIn(skip: Set<String> = emptySet()) {
        scope.launch { copyScans(skip) }
    }

    /**
     * Whether [uri] is one of the folder's [topIds], the documents at its top level: the only files its listing shows, so a file saved
     * elsewhere into a subfolder of it is still listed as saved elsewhere. False when it cannot tell.
     */
    private fun isTopLevel(tree: Uri, topIds: Set<String>, uri: Uri): Boolean {
        if (uri.authority != tree.authority) return false
        return try {
            DocumentsContract.getDocumentId(uri) in topIds
        } catch (e: IllegalArgumentException) {
            false // not a document URI
        }
    }

    /** Reports sync conflicts settled while writing to the folder. */
    fun report(conflicts: List<ConflictReport>) {
        if (conflicts.isNotEmpty()) notice = FolderNotice.Conflicts(conflicts.size, conflicts.count { it.keptAs != null })
    }

    fun noticeShown() {
        notice = null
    }

    private suspend fun load(resolve: Boolean) {
        val loaded = withContext(io) {
            val s = access.state()
            val reports = if (resolve && s is FolderState.Ready) s.folder.resolveConflicts() else emptyList()
            val tree = access.treeUri()
            val elsewhere = files.entries().filter { it.place == SavedPlace.ELSEWHERE && it.uri != null }
            val inside = if (s is FolderState.Ready && tree != null && elsewhere.isNotEmpty()) {
                val topIds = try {
                    s.store.list(s.store.rootId).filter { !it.isFolder }.mapTo(HashSet()) { it.id }
                } catch (e: IOException) {
                    emptySet()
                }
                elsewhere.filter { isTopLevel(tree, topIds, it.uri!!) }.mapTo(HashSet()) { it.id }
            } else {
                emptySet()
            }
            Loaded(s, tree?.let(::label), (s as? FolderState.Ready)?.folder?.listing(), library.documents().size, reports, inside)
        }
        insideFolder = loaded.inside
        state = loaded.state
        name = loaded.name
        listing = loaded.listing
        scansOnPhone = loaded.scans
        report(loaded.reports)
    }

    private suspend fun copyScans(skip: Set<String> = emptySet()) = MOVE_LOCK.withLock {
        running = true
        try {
            copyScansLocked(skip)
        } finally {
            running = false
        }
    }

    private suspend fun copyScansLocked(skip: Set<String>) = run {
        awaitSwept()
        val ready = state as? FolderState.Ready ?: return@run
        val count = scansOnPhone - skip.size
        val kept = withContext(io) { files.entries() }.filter { it.inApp && !it.held }
        if (count <= 0 && kept.isEmpty()) {
            copyPending.set(false)
            return@run
        }
        copying = count.coerceAtLeast(0)
        moving = kept.count { it.scan == null }
        // Set before the copy, so a copy the system ends with the app is run again at the next launch too.
        copyPending.set(true)
        val move = try {
            withContext(io) { moveIn(ready, skip) }
        } finally {
            copying = null
            moving = 0
        }
        val migration = move.migration
        copyPending.set(migration.folderUnavailable || move.left > 0)
        notice = if (migration.folderUnavailable || move.left > 0) {
            FolderNotice.CopyStopped(migration.failed.size, move.left)
        } else {
            FolderNotice.Copied(migration.written.size, migration.failed.size, move.moved, move.unmoved)
        }
        load(resolve = false)
    }

    /** What [moveIn] did: the scans' [migration], the files kept in the app [moved] and [unmoved], and those [left] when the folder went. */
    private class Move(val migration: Migration, val moved: Int, val unmoved: Int, val left: Int)

    /**
     * Copies the scans on this phone into the folder ([ScanFolder.migrate]), then moves the files kept in the app into it. The newest PDF of
     * a scan kept in the app is the PDF its scan is written back with, so the folder gets it as saved (its page size, password and text), and
     * it goes once written back; one whose scan the folder holds already stays in the app, held. Every other file is copied to the top level
     * and deleted from the app only once the folder holds it whole (size and content); a retry first takes a copy an earlier attempt left
     * there, so none is copied twice. A file that fails stays, row and all, for the next launch, and the move stops when the folder can no
     * longer be reached. Blocking.
     */
    @WorkerThread
    private fun moveIn(ready: FolderState.Ready, skip: Set<String>): Move {
        val kept = files.entries().filter { it.inApp && !it.held }
        val newest = kept.filter { e -> e.scan?.let { library.exists(it) } == true }.groupBy { it.scan!! }.mapValues { (_, rows) -> rows.maxBy { it.time } }
        val pdfOf = newest.mapValues { (_, row) -> files.fileOf(row) }
        val maker = PdfMaker { id, deliver -> pdfOf[id]?.takeIf { it.isFile }?.let(deliver) ?: pdf.make(id, deliver) }
        val migration = ready.folder.migrate(maker, skip)
        for ((scan, row) in newest) {
            when {
                scan in migration.written -> files.moved(row.id, null)
                // Left out because the folder has this scan whole already: this PDF stays in the app, and the move no longer waits on it.
                scan !in skip && scan !in migration.failed && !ready.folder.hasChanges(scan) -> files.hold(row.id)
            }
        }
        val rest = kept - newest.values.toSet()
        if (migration.folderUnavailable) return Move(migration, 0, 0, rest.size)
        var moved = 0
        var unmoved = 0
        for ((i, entry) in rest.withIndex()) {
            val file = files.fileOf(entry)
            if (file == null || !file.isFile) {
                files.delete(entry.id) // nothing left to move: the row goes
                continue
            }
            val name = entry.name.takeIf { FolderNames.isSafe(it) && !FolderNames.isHidden(it) } ?: file.name
            // A copy an earlier attempt made (its name noted, or this file's own name) is taken rather than copied again.
            var there = (listOfNotNull(entry.folderName) + name).distinct().firstNotNullOfOrNull { ready.folder.holds(it, file) }
            if (there == null) {
                when (val result = ready.folder.copyOutput(name) { file.inputStream() }) {
                    is WriteResult.Written -> {
                        files.wrote(entry.id, result.name)
                        there = ready.folder.holds(result.name, file)
                    }
                    WriteResult.FolderUnavailable -> return Move(migration, moved, unmoved, rest.size - i)
                    is WriteResult.Incomplete, WriteResult.Failed -> Unit
                }
            }
            if (there != null) {
                files.moved(entry.id, ready.store.uri(there.id))
                moved++
            } else {
                unmoved++
            }
        }
        return Move(migration, moved, unmoved, 0)
    }

    /** The folder's name as the user knows it: the path for the phone's own storage, else the name its provider reports. Blocking. */
    @WorkerThread
    private fun label(treeUri: Uri): String? {
        val id = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (treeUri.authority == EXTERNAL_STORAGE) id.substringAfter(':', "").takeIf { it.isNotBlank() }?.let { return it }
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        return try {
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            // The Bundle form where it exists, as SafFolderStore queries, which is what a provider receives over IPC anyway.
            (if (Build.VERSION.SDK_INT >= 26) resolver.query(root, projection, null, null) else resolver.query(root, projection, null, null, null))
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.i(TAG, "No name for the folder", e)
            null // the grant is gone or the provider failed: the screens say "your folder"
        }
    }

    private data class Loaded(
        val state: FolderState,
        val name: String?,
        val listing: FolderListing?,
        val scans: Int,
        val reports: List<ConflictReport>,
        val inside: Set<String>,
    )

    private companion object {
        const val TAG = "AppFolder"
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        const val PRUNE_EVERY_MS = 60_000L
        const val PDF = ".pdf"

        /**
         * One move into a folder at a time in the process: the launch's, a choice's, the scan flow's, and an earlier holder's still running
         * after its activity went (the move does not stop with it). History's Delete waits for it too.
         */
        val MOVE_LOCK = Mutex()

        /** Whether [MOVE_LOCK] is held by a move, as state the screens read. */
        var running by mutableStateOf(false)

        /** The `-2` and so on a name in the app may have gained, before its `.pdf`. */
        val FREE_SUFFIX = Regex("-[0-9]+[.]pdf$", RegexOption.IGNORE_CASE)
    }
}

/** Keeps the app's [AppFolder] across configuration changes and starts its launch work once, when first created. */
class FolderHolder(app: Application) : AndroidViewModel(app) {
    val folder = AppFolder(app, viewModelScope, setupSeen = setupSeenOf(app)).also { it.start() }

    override fun onCleared() = folder.close()

    companion object {
        /** Where the app's [SetupSeen] comes from; tests of the whole app replace it, as Robolectric can carry preferences between tests. */
        @VisibleForTesting
        internal var setupSeenOf: (Context) -> SetupSeen = ::PrefsSetupSeen
    }
}
