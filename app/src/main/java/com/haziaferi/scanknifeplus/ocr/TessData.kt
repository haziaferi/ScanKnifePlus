package com.haziaferi.scanknifeplus.ocr

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Tesseract's language data (tessdata_fast eng, ita and osd, in the APK's assets) installed as real files, which Tesseract needs, in
 * `noBackupFilesDir/tessdata/`: the data is part of the APK, so it is never backed up.
 */
internal object TessData {
    const val DIR = "tessdata"
    val FILES = listOf("eng.traineddata", "ita.traineddata", "osd.traineddata")

    private const val TAG = "TessData"
    private const val TMP = ".tmp"

    /** Where the language data can be read from; the APK's assets in the app, a fake in tests. */
    interface Source {
        /** The file's size in bytes, uncompressed. */
        fun size(name: String): Long

        fun open(name: String): InputStream
    }

    private class Assets(private val assets: AssetManager) : Source {
        // An asset stream's available() is the asset's uncompressed length, also for compressed assets (openFd only works on stored ones).
        override fun size(name: String): Long = assets.open("$DIR/$name").use { it.available().toLong() }

        override fun open(name: String): InputStream = assets.open("$DIR/$name")
    }

    /** Installs the data if needed; Tesseract's data path (the parent of `tessdata/`), or null if it could not. Blocking. */
    fun install(context: Context): File? {
        val root = context.noBackupFilesDir
        return if (install(File(root, DIR), Assets(context.assets))) root else null
    }

    /**
     * Copies each of [FILES] from [source] into [dir] unless a file of the same size is already there, so a data update replaces the old file.
     * Each copy is written to a temp file and renamed over the old one, so Tesseract never sees half a file. Serialised, so two engines opening
     * at once install once. False, leaving the old files in place, if anything fails.
     */
    @Synchronized
    fun install(dir: File, source: Source): Boolean = try {
        val missing = FILES.filter { File(dir, it).length() != source.size(it) }
        if (missing.isNotEmpty()) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            // Left by a process that died mid-copy; nothing else writes here while this holds the lock.
            dir.listFiles { file -> file.name.endsWith(TMP) }?.forEach { it.delete() }
            for (name in missing) copy(source, name, File(dir, name))
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "Installing the OCR language data failed", e)
        false
    }

    private fun copy(source: Source, name: String, target: File) {
        val tmp = File(target.parentFile, target.name + TMP)
        try {
            source.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) throw IOException("Cannot rename $tmp to $target")
        } finally {
            tmp.delete()
        }
    }
}
