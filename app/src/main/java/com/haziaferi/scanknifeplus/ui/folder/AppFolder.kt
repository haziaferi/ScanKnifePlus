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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.export.ScanPdfExporter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.export.ExportQuality
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.storage.ConflictReport
import com.haziaferi.scanknifeplus.storage.FolderAccess
import com.haziaferi.scanknifeplus.storage.FolderListing
import com.haziaferi.scanknifeplus.storage.FolderState
import com.haziaferi.scanknifeplus.storage.PdfMaker
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Home's folder bar and Settings say about the user's folder; [name] is null when it cannot be told. */
sealed interface FolderStatus {
    data object NotChosen : FolderStatus

    data class Ready(val name: String?) : FolderStatus

    /** A folder was chosen but cannot be written to: only saving into it waits until it is chosen again. */
    data class ChooseAgain(val name: String?) : FolderStatus

    /** The first copy of the scans on this phone into a newly chosen folder is running. */
    data class Copying(val name: String?, val count: Int) : FolderStatus
}

/** A message the app shows once, as a toast, then clears with [AppFolder.noticeShown]. */
sealed interface FolderNotice {
    /** Sync conflicts were settled; [keptCopies] of them left a conflict copy beside the file. */
    data class Conflicts(val count: Int, val keptCopies: Int) : FolderNotice

    data class Copied(val written: Int, val failed: Int) : FolderNotice

    /** The copy into the folder stopped because the folder could no longer be reached; [left] scans were not copied. */
    data class CopyStopped(val left: Int) : FolderNotice
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

/** [ScanPdfExporter.writePdf] as the storage layer's [PdfMaker], all pages at [size], encrypted for [password] if given. */
fun ScanPdfExporter.pdfMaker(size: PdfPageSize = PdfPageSize.DEFAULT, password: String? = null): PdfMaker =
    PdfMaker { id, deliver -> writePdf(id, null, ExportQuality.DEFAULT, size, password, deliver = deliver) != null }

/**
 * The app's view of the user's folder ([FolderAccess]) as Compose state: whether it can be used, its name, History's [listing] of it, and the
 * one-time copy of the scans on this phone after a folder is chosen ([choose]). Folder work runs on [io], one call at a time in the storage
 * layer; state is written on [scope]'s thread, the main thread in the app. Reading never waits for a write to be possible.
 */
class AppFolder(
    context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val access: FolderAccess = FolderAccess(context),
    private val library: ScanLibrary = ScanLibrary(ScanFiles.libraryDir(context)),
    private val pdf: PdfMaker = ScanPdfExporter(library, ScanFiles.exportDir(context)).pdfMaker(),
    private val setupSeen: SetupSeen = PrefsSetupSeen(context),
    private val sweep: () -> Unit = { access.sweep() },
) {
    // Completed once the launch sweep has run: WorkingCopies.sweep must finish before any session or copy into the folder starts.
    private val swept = CompletableDeferred<Unit>()
    private val resolver: ContentResolver = context.contentResolver

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

    /** How many scans this phone holds, which a newly chosen folder receives; counted at each [refresh]. */
    var scansOnPhone by mutableStateOf(0)
        private set

    var notice by mutableStateOf<FolderNotice?>(null)
        private set

    val status: FolderStatus get() {
        copying?.let { return FolderStatus.Copying(name, it) }
        return when (state) {
            FolderState.NotChosen -> FolderStatus.NotChosen
            FolderState.ChooseAgain -> FolderStatus.ChooseAgain(name)
            // Not checked yet: a folder was chosen, and it is almost always still there.
            is FolderState.Ready, null -> FolderStatus.Ready(name)
        }
    }

    /** History's view of the folder; null while no folder is chosen, so History shows this session's outputs. */
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

    /** "Not now" on Folder setup: each file is saved with the picker, and setup no longer opens by itself. */
    fun dismissSetup() = setupSeen.set()

    /**
     * The launch work, off the main thread: the sweep of working copies the folder already holds, then the folder's sync conflicts, reported
     * once, then the first listing. Run once per launch; scan sessions and copies into the folder wait for the sweep ([awaitSwept]).
     */
    fun start() {
        scope.launch {
            try {
                withContext(io) { sweep() }
            } finally {
                swept.complete(Unit)
            }
            load(resolve = true)
        }
    }

    /** Returns once the launch sweep of [start] has run. */
    suspend fun awaitSwept() = swept.await()

    /** Checks the folder again and lists it; with [resolve], first settles its sync conflicts and reports them. */
    fun refresh(resolve: Boolean = false) {
        scope.launch { load(resolve) }
    }

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
            Loaded(s, access.treeUri()?.let(::label), (s as? FolderState.Ready)?.folder?.listing(), library.documents().size, reports)
        }
        state = loaded.state
        name = loaded.name
        listing = loaded.listing
        scansOnPhone = loaded.scans
        report(loaded.reports)
    }

    private suspend fun copyScans(skip: Set<String> = emptySet()) {
        awaitSwept()
        val ready = state as? FolderState.Ready ?: return
        val count = scansOnPhone - skip.size
        if (count <= 0) return
        copying = count
        val migration = try {
            withContext(io) { ready.folder.migrate(pdf, skip) }
        } finally {
            copying = null
        }
        notice = if (migration.folderUnavailable) {
            FolderNotice.CopyStopped(migration.failed.size)
        } else {
            FolderNotice.Copied(migration.written.size, migration.failed.size)
        }
        load(resolve = false)
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

    private data class Loaded(val state: FolderState, val name: String?, val listing: FolderListing?, val scans: Int, val reports: List<ConflictReport>)

    private companion object {
        const val TAG = "AppFolder"
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}

/** Keeps the app's [AppFolder] across configuration changes and starts its launch work once, when first created. */
class FolderHolder(app: Application) : AndroidViewModel(app) {
    val folder = AppFolder(app, viewModelScope, setupSeen = setupSeenOf(app)).also { it.start() }

    companion object {
        /** Where the app's [SetupSeen] comes from; tests of the whole app replace it, as Robolectric can carry preferences between tests. */
        @VisibleForTesting
        internal var setupSeenOf: (Context) -> SetupSeen = ::PrefsSetupSeen
    }
}
