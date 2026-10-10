package com.haziaferi.scanknifeplus.scan

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Where the document scanner keeps its files, all in app-private storage: the scan library under `files/scans/` and its work folders under
 * `cache/`. The FileProvider exposes only the share copies in `cache/shared/` (res/xml/scan_file_paths.xml).
 */
object ScanFiles {
    private const val SHARE_FOLDER = "shared"
    private const val STAGING_FOLDER = "scan-staging"
    private const val EXPORT_FOLDER = "scan-export"

    /**
     * The names of the scanner's folders under the cache directory ([shareDir], [stagingDir], [exportDir]), for reporting cache use. The scanner
     * cleans these itself; deleting them while a scan session or export runs breaks it.
     */
    val CACHE_FOLDERS: Set<String> = setOf(SHARE_FOLDER, STAGING_FOLDER, EXPORT_FOLDER)

    /** The scan library root; created on first use. */
    fun libraryDir(context: Context): File = File(context.filesDir, "scans").apply { mkdirs() }

    /** Folder for copies handed to other apps when sharing; safe to clear at any time. */
    fun shareDir(context: Context): File = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }

    /**
     * Where camera shots wait between the shutter and the library. Each scan session works in its own folder in here and deletes only that one
     * when it ends (ScanSession), unlike OpenScan, which wiped the whole cache directory (and with it every other tool's cached files).
     */
    fun stagingDir(context: Context): File = File(context.cacheDir, STAGING_FOLDER).apply { mkdirs() }

    /** Work folders for PDF exports; separate from [stagingDir] so ending a scan session cannot pull files out from under an export. */
    fun exportDir(context: Context): File = File(context.cacheDir, EXPORT_FOLDER).apply { mkdirs() }

    /**
     * Creates a new, empty folder in [parent] named [prefix] plus a random suffix, or returns null if it cannot. mkdir is atomic, so two callers
     * never get the same folder (java.nio.file's createTempDirectory needs API 26).
     */
    fun newFolder(parent: File, prefix: String): File? {
        parent.mkdirs()
        repeat(10) {
            val folder = File(parent, prefix + UUID.randomUUID())
            if (folder.mkdir()) return folder
        }
        return null
    }

    /** The FileProvider authority declared in the manifest (`${applicationId}.fileprovider`). */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** A content URI other apps can read once granted; throws IllegalArgumentException for a file outside [shareDir]. */
    fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)
}
