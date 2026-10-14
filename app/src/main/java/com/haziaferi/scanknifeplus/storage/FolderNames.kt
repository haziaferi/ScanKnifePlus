package com.haziaferi.scanknifeplus.storage

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** The names the storage layer reads and makes in the user's folder. */
object FolderNames {
    /** The visible subfolder holding each scan's editable sources, one folder per scan. */
    const val SCAN_SOURCES = "Scan sources"

    /** Suffix of a file being written; it is renamed to its real name once complete. */
    const val TMP = ".tmp"

    /** Suffix the previous version of a replaced file is kept under, as in the user's other projects. */
    const val BAK = ".bak"

    /** Marks a version that lost a sync conflict: `<base>.lost-<yyyyMMdd-HHmmss><ext>`, so it still opens as what it is. */
    const val LOST = ".lost-"

    // Syncthing's conflictName: the base without its extension, ".sync-conflict-", the time, "-", a 7-character device id, then the extension.
    private val CONFLICT = Regex("""^(.+)\.sync-conflict-\d{8}-\d{6}-[A-Z0-9]{7}(\.[^.]*)?$""")

    private val LOST_NAME = Regex("""\.lost-\d{8}-\d{6}(-\d+)?(\.[^.]*)?$""")

    /**
     * Names never shown or read as documents: dot names (Syncthing's `.stfolder`, `.stversions`, `.stignore` and `.syncthing.*.tmp`), its
     * Windows temp names `~syncthing~*.tmp`, and the `.tmp` and `.bak` files of atomic writes.
     */
    fun isHidden(name: String): Boolean =
        name.isEmpty() || name.startsWith(".") || name.startsWith("~syncthing~") || name.endsWith(TMP) || name.endsWith(BAK)

    /** The name of the file [name] is a Syncthing conflict copy of, or null if it is not one. */
    fun conflictOf(name: String): String? = CONFLICT.matchEntire(name)?.let { m -> m.groupValues[1] + m.groupValues[2] }

    /** True for a version kept by conflict handling. */
    fun isLost(name: String): Boolean = LOST_NAME.containsMatchIn(name)

    /** The name the losing version of [name] is kept under at [epochMillis]: `scan.lost-20261013-142501.pdf`. */
    fun lostName(name: String, epochMillis: Long, timeZone: TimeZone): String {
        val (base, ext) = split(name)
        return "$base$LOST${stamp(epochMillis, timeZone)}$ext"
    }

    /**
     * The first of [name], `<base>-2<ext>`, `<base>-3<ext>`… for which [taken] is false: a fixed numbering, never a provider's `(1)` name.
     * [isFolder] keeps dots in a folder name.
     */
    fun free(name: String, isFolder: Boolean = false, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val (base, ext) = if (isFolder) name to "" else split(name)
        var n = 2
        while (taken("$base-$n$ext")) n++
        return "$base-$n$ext"
    }

    /** A single path segment that cannot point anywhere else: not empty, not `.` or `..`, no separators. */
    fun isSafe(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun stamp(epochMillis: Long, timeZone: TimeZone): String {
        val c = GregorianCalendar(timeZone, Locale.ROOT).apply { timeInMillis = epochMillis }
        return String.format(
            Locale.ROOT, "%04d%02d%02d-%02d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        )
    }
}
