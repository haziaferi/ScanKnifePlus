package com.haziaferi.scanknifeplus.storage

import android.util.Log
import androidx.annotation.WorkerThread
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * What ties a working copy in the private scan library to its scan in the user's folder ([FolderStore.location]) named [name] under
 * `Scan sources/`. [base] is the SHA-256 of the folder's `document.json` as the working copy last matched it, so a change made elsewhere is
 * merged instead of overwritten; [confirmed] that of the working record whose record and PDF were last both written, or null since a
 * write-back that did not finish with its PDF. [pdfName] and [pdfHash] name the top-level PDF this binding last wrote, the only one it replaces.
 */
data class Binding(
    val folder: String,
    val name: String,
    val base: String?,
    val confirmed: String?,
    val pdfName: String? = null,
    val pdfHash: String? = null,
)

/**
 * The [Binding] of each working copy, one small JSON file per working copy in [dir] (app-private and outside the scan library, so its
 * listing and sweep never see them), and the launch sweep that removes working copies the folder already holds. Every method blocks.
 */
class WorkingCopies(private val dir: File) {
    @WorkerThread
    fun get(id: String): Binding? = synchronized(LOCK) {
        val f = fileOf(id) ?: return null
        return try {
            val json = JSONObject(f.readText())
            Binding(
                json.getString("folder"),
                json.getString("name"),
                json.optStringOrNull("base"),
                json.optStringOrNull("confirmed"),
                json.optStringOrNull("pdfName"),
                json.optStringOrNull("pdfHash"),
            )
        } catch (e: Exception) {
            null // missing or damaged: the working copy counts as unbound, which keeps it
        }
    }

    /** The ids of the working copies bound to scan [name] in folder [folder]; normally one at most. */
    @WorkerThread
    fun idsOf(folder: String, name: String): List<String> = synchronized(LOCK) {
        ids().filter { get(it)?.let { b -> b.folder == folder && b.name == name } == true }
    }

    /** Saves [binding] for working copy [id]: written and synced beside its file, then renamed into place. Throws IOException. */
    @WorkerThread
    fun put(id: String, binding: Binding) = synchronized(LOCK) {
        val f = fileOf(id) ?: throw IOException("Unsafe working copy id $id")
        dir.mkdirs()
        val json = JSONObject().put("folder", binding.folder).put("name", binding.name)
            .put("base", binding.base ?: JSONObject.NULL).put("confirmed", binding.confirmed ?: JSONObject.NULL)
            .put("pdfName", binding.pdfName ?: JSONObject.NULL).put("pdfHash", binding.pdfHash ?: JSONObject.NULL)
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
     * The launch sweep, which never touches the user's folder. It deletes each working copy whose record is still exactly the bytes confirmed
     * written (checked under the library's lock), drops bindings whose working copy folder is gone, and removes binding temp files and
     * `.import-` folders left in [libraryRoot]. Run it at launch before any session or copy-in starts; returns how many items it removed.
     */
    @WorkerThread
    fun sweep(library: ScanLibrary, libraryRoot: File): Int {
        // The library's lock is never taken while this one is held: a copy-in saves a binding from inside the library's lock.
        var removed = 0
        synchronized(LOCK) {
            for (f in dir.listFiles().orEmpty()) {
                if (f.name.endsWith(FolderNames.TMP) && f.delete()) removed++
            }
        }
        for (id in synchronized(LOCK) { ids() }) {
            val binding = get(id) ?: continue
            if (!library.exists(id)) {
                if (remove(id) == true) removed++
                continue
            }
            val confirmed = binding.confirmed ?: continue
            if (library.deleteIf(id) { sha256(it) == confirmed }) {
                removed++
                remove(id)
            }
        }
        for (staging in libraryRoot.listFiles().orEmpty()) {
            if (staging.isDirectory && staging.name.startsWith(IMPORT_PREFIX) && staging.deleteRecursively()) removed++
        }
        Log.i(TAG, "Launch sweep removed $removed")
        return removed
    }

    private fun ids(): List<String> = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(JSON) }.map { it.removeSuffix(JSON) }

    private fun fileOf(id: String): File? = if (FolderNames.isSafe(id)) File(dir, id + JSON) else null

    companion object {
        private const val JSON = ".json"
        private const val TAG = "WorkingCopies"
        private val LOCK = Any()

        /** Prefix of the hidden folders in the scan library where a copy-in assembles files before the library takes them in. */
        internal const val IMPORT_PREFIX = ".import-"

        internal fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** The SHA-256 of everything [input] holds, read in chunks (a PDF can be large). */
        internal fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return hex(digest.digest())
                digest.update(buffer, 0, n)
            }
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    }
}
