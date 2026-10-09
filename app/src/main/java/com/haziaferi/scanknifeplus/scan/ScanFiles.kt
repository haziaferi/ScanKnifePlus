package com.haziaferi.scanknifeplus.scan

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where the document scanner keeps its files. Everything lives in app-private storage: the scan library under `files/scans/`, and copies made
 * for sharing under `cache/shared/`. These are the only two folders the FileProvider exposes (res/xml/scan_file_paths.xml).
 */
object ScanFiles {
    /** The scan library root; created on first use. */
    fun libraryDir(context: Context): File = File(context.filesDir, "scans").apply { mkdirs() }

    /** Folder for copies handed to other apps when sharing; safe to clear at any time. */
    fun shareDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }

    /** The FileProvider authority declared in the manifest (`${applicationId}.fileprovider`). */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** A content URI other apps can read once granted; throws IllegalArgumentException for a file outside the two shared folders. */
    fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)
}
