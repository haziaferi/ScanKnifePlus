package com.haziaferi.scanknifeplus.scan

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Where the document scanner keeps its files. Everything lives in app-private storage: the scan library under `files/scans/`, and copies made
 * for sharing under `cache/shared/`. These are the only two folders the FileProvider exposes (res/xml/scan_file_paths.xml).
 */
object ScanFiles {
    /** The scan library root; created on first use. */
    fun libraryDir(context: Context): File = File(context.filesDir, "scans").apply { mkdirs() }

    /** Folder for copies handed to other apps when sharing; safe to clear at any time. */
    fun shareDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }

    /**
     * Where camera shots wait between the shutter and the library. Only this folder is cleared after a scan session, unlike OpenScan, which
     * wiped the whole cache directory (and with it every other tool's cached files).
     */
    fun stagingDir(context: Context): File = File(context.cacheDir, "scan-staging").apply { mkdirs() }

    /**
     * Work folders for PDF exports: re-encoded page copies and the PDF being written. Separate from [stagingDir] so ending a scan session cannot
     * pull files out from under an export.
     */
    fun exportDir(context: Context): File = File(context.cacheDir, "scan-export").apply { mkdirs() }

    /**
     * Creates a new, empty folder in [parent] named [prefix] plus a random suffix, or returns null if it cannot. mkdir is atomic, so two callers
     * never get the same folder. (java.nio.file.Files.createTempDirectory would do this, but needs API 26 and minSdk is 24.)
     */
    fun newFolder(parent: File, prefix: String): File? {
        parent.mkdirs()
        repeat(10) {
            val folder = File(parent, prefix + UUID.randomUUID())
            if (folder.mkdir()) return folder
        }
        return null
    }

    /** Deletes everything staged, trying every entry even after a failure; returns false if something could not be deleted. */
    fun clearStaging(context: Context): Boolean = stagingDir(context).listFiles().orEmpty().map { it.deleteRecursively() }.all { it }

    /** The FileProvider authority declared in the manifest (`${applicationId}.fileprovider`). */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** A content URI other apps can read once granted; throws IllegalArgumentException for a file outside the two shared folders. */
    fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)
}
