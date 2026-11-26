package com.paperknifeplus.app.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.storage.SavedFiles
import java.io.File
import java.io.FileNotFoundException
import java.net.URLConnection
import org.robolectric.Robolectric

/**
 * The FileProvider's `saved/` path, as the app's provider serves it on a phone, for Robolectric on any host: FileProvider joins its paths
 * with '/', so on a Windows host it never finds the files. [install] puts it in place of the app's provider for this test.
 */
class SavedFilesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File = SavedFiles.of(context!!).fileOf(uri) ?: throw FileNotFoundException("$uri")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.parseMode(mode))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        val file = fileOf(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            if (file.isFile) addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else if (it == OpenableColumns.DISPLAY_NAME) file.name else null })
        }
    }

    override fun getType(uri: Uri): String? = URLConnection.guessContentTypeFromName(uri.lastPathSegment)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = if (fileOf(uri).delete()) 1 else 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        /** Serves the app's `saved/` files at the app's FileProvider authority for the rest of this test. */
        fun install(context: Context) {
            Robolectric.setupContentProvider(SavedFilesProvider::class.java, ScanFiles.authority(context))
        }

        /** Deletes the files kept in the app and their index, so no later test finds them (it may get this test's data folder). */
        fun forget(context: Context) {
            val files = SavedFiles.of(context)
            files.dir.deleteRecursively()
            context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("saved-index.json") }.forEach { it.delete() }
        }
    }
}
