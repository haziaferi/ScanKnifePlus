package com.haziaferi.scanknifeplus.scan.library

import org.json.JSONArray
import org.json.JSONObject

/**
 * One page of a scanned document. File names are relative to the document's folder. Mirrors OpenScan's `pages` table: [image] is what the page
 * shows; [original] is the uncropped capture kept for re-cropping; [unfiltered] is the page before any filter, kept so filters never compound;
 * [filter] is the applied filter's name, or null for none.
 */
data class ScanPage(
    val id: String,
    val image: String,
    val original: String? = null,
    val unfiltered: String? = null,
    val filter: String? = null,
) {
    /** Every file this page owns. */
    val files: List<String> get() = listOfNotNull(image, original, unfiltered).distinct()
}

/**
 * A scanned document: a folder in the scan library holding its pages and a `document.json` record. [id] is the folder name and never changes;
 * [name] is the name the user gave it, or null for one they have not named (shown as [id], like OpenScan's generated names).
 */
data class ScanDocument(
    val id: String,
    val name: String?,
    val created: Long,
    val modified: Long,
    val pages: List<ScanPage>,
) {
    val displayName: String get() = name ?: id

    internal fun toJson(): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("id", id)
        .put("name", name ?: JSONObject.NULL)
        .put("created", created)
        .put("modified", modified)
        .put("pages", JSONArray(pages.map { p ->
            JSONObject()
                .put("id", p.id)
                .put("image", p.image)
                .put("original", p.original ?: JSONObject.NULL)
                .put("unfiltered", p.unfiltered ?: JSONObject.NULL)
                .put("filter", p.filter ?: JSONObject.NULL)
        }))

    internal companion object {
        /** Written for future migrations; records are read best-effort whatever their version, since every field read here is required in v1. */
        const val FORMAT_VERSION = 1

        fun fromJson(json: JSONObject): ScanDocument {
            val pages = json.getJSONArray("pages")
            return ScanDocument(
                id = json.getString("id"),
                name = json.optStringOrNull("name"),
                created = json.getLong("created"),
                modified = json.getLong("modified"),
                pages = (0 until pages.length()).map { i ->
                    val p = pages.getJSONObject(i)
                    ScanPage(
                        id = p.getString("id"),
                        image = p.getString("image"),
                        original = p.optStringOrNull("original"),
                        unfiltered = p.optStringOrNull("unfiltered"),
                        filter = p.optStringOrNull("filter"),
                    )
                },
            )
        }

        // optString turns JSON null into the text "null", so nulls are checked explicitly.
        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
