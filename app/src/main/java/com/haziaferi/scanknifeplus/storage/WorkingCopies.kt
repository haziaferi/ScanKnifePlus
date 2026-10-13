package com.haziaferi.scanknifeplus.storage

import android.util.Log
import androidx.annotation.WorkerThread
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

/**
 * What ties a working copy in the private scan library to its scan in the user's folder: [name] is its folder under `Scan sources/` (and
 * the stem of its PDF); [base] the SHA-256 of the folder's `document.json` as the working copy last saw it, so a change made meanwhile (by
 * another device) is merged rather than overwritten; [confirmed] the SHA-256 of the working copy's own record when everything in it was last
 * confirmed written to the folder, or null if it has changes the folder lacks.
 */
data class Binding(val name: String, val base: String?, val confirmed: String?)

/**
 * The [Binding] of each working copy, one small JSON file per working copy in [dir] (app-private, outside the scan library so its sweep and
 * listing never see them), and the launch sweep that removes working copies the folder already holds. Blocking.
 */
class WorkingCopies(private val dir: File) {
    @WorkerThread
    fun get(id: String): Binding? = synchronized(LOCK) {
        val f = fileOf(id) ?: return null
        return try {
            val json = JSONObject(f.readText())
            Binding(json.getString("name"), json.optStringOrNull("base"), json.optStringOrNull("confirmed"))
        } catch (e: Exception) {
            null // missing or damaged: the working copy counts as unbound, which keeps it
        }
    }

    /** The id of the working copy bound to folder scan [name], if there is one. */
    @WorkerThread
    fun idOf(name: String): String? = synchronized(LOCK) {
        dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(JSON) }.map { it.removeSuffix(JSON) }.firstOrNull { get(it)?.name == name }
    }

    /** Saves [binding] for working copy [id]: written beside its file, then renamed into place. Throws IOException. */
    @WorkerThread
    fun put(id: String, binding: Binding) = synchronized(LOCK) {
        val f = fileOf(id) ?: throw IOException("Unsafe working copy id $id")
        dir.mkdirs()
        val json = JSONObject().put("name", binding.name).put("base", binding.base ?: JSONObject.NULL)
            .put("confirmed", binding.confirmed ?: JSONObject.NULL)
        val tmp = File(dir, f.name + FolderNames.TMP)
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // rename(2) replaces on Android; a host filesystem that will not rename over a file (Windows, in unit tests) needs it removed first.
        if (!tmp.renameTo(f) && !(f.delete() && tmp.renameTo(f))) throw IOException("Cannot save the binding of $id")
    }

    @WorkerThread
    fun remove(id: String) = synchronized(LOCK) { fileOf(id)?.delete() }

    /**
     * The launch sweep. Removes each working copy whose record is still exactly the one confirmed written to the folder (so it holds nothing
     * the folder lacks), with its binding; bindings whose working copy is gone; and what an interrupted write in private storage left: binding
     * temp files and `.import-` folders in [libraryRoot]. Run it at launch, before any scan session or copy-in starts: a working copy that an
     * open session uses but has not changed yet would also go. Never touches the user's folder. Returns how many items it removed.
     */
    @WorkerThread
    fun sweep(library: ScanLibrary, libraryRoot: File): Int = synchronized(LOCK) {
        var removed = 0
        for (f in dir.listFiles().orEmpty()) {
            if (f.name.endsWith(FolderNames.TMP)) {
                if (f.delete()) removed++
                continue
            }
            if (!f.name.endsWith(JSON)) continue
            val id = f.name.removeSuffix(JSON)
            val binding = get(id) ?: continue
            if (library.document(id) == null) {
                if (f.delete()) removed++
                continue
            }
            val confirmed = binding.confirmed ?: continue
            if (recordHash(library, id) != confirmed) continue
            if (library.delete(id)) {
                removed++
                f.delete()
            }
        }
        for (staging in libraryRoot.listFiles().orEmpty()) {
            if (staging.isDirectory && staging.name.startsWith(IMPORT_PREFIX) && staging.deleteRecursively()) removed++
        }
        Log.i(TAG, "Launch sweep removed $removed")
        removed
    }

    private fun fileOf(id: String): File? = if (FolderNames.isSafe(id)) File(dir, id + JSON) else null

    companion object {
        private const val JSON = ".json"
        private const val TAG = "WorkingCopies"
        private val LOCK = Any()

        /** Prefix of the hidden folders a copy-in assembles a working copy in before renaming it into the library. */
        internal const val IMPORT_PREFIX = ".import-"

        /** The SHA-256 of working copy [id]'s record file, or null if it cannot be read. */
        internal fun recordHash(library: ScanLibrary, id: String): String? = try {
            sha256(library.file(id, ScanLibrary.RECORD).readBytes())
        } catch (e: Exception) {
            null
        }

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    }
}
