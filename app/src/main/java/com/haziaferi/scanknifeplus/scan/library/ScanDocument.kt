package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * How a Book mode page was cut from its original: [side] of [spread] (fractions of the original's upright width and height) split [split] of
 * the way along the spread's top and bottom edges, with the book upright once the original is turned clockwise by [turns] (see BookSplit.page).
 * Kept so the split line can be moved later; a page's own later turns are in [ScanPage.quarterTurns].
 */
data class BookCrop(val spread: Quad, val split: Double, val side: BookPage, val turns: Int)

/**
 * One page of a scanned document. File names are relative to the document's folder. Mirrors OpenScan's `pages` table: [image] is what the page
 * shows; [original] is the uncropped capture kept for re-cropping; [unfiltered] is the page before any filter, kept so filters never compound;
 * [filter] is the applied filter's name, or null for none. [quarterTurns] (0 to 3, clockwise) is how far the page is turned from [original], so a
 * re-crop keeps the turn; it stays 0 without an original, whose image already shows any turn. [book] is set on a Book mode page that kept its
 * original, until the page is re-cropped by hand.
 */
data class ScanPage(
    val id: String,
    val image: String,
    val original: String? = null,
    val unfiltered: String? = null,
    val filter: String? = null,
    val quarterTurns: Int = 0,
    val book: BookCrop? = null,
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
                // Only when turned, so records of unturned pages stay as they were and older versions read them unchanged.
                .apply { if (p.quarterTurns != 0) put("quarterTurns", p.quarterTurns) }
                .apply { p.book?.let { put("book", bookJson(it)) } }
        }))

    internal companion object {
        /**
         * Written for future migrations; records are read best-effort whatever their version, since every field read here is required in v1
         * except the optional `quarterTurns` and `book`.
         */
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
                        quarterTurns = p.optInt("quarterTurns", 0).mod(4),
                        book = p.optJSONObject("book")?.let { readBook(it) },
                    )
                },
            )
        }

        private fun bookJson(b: BookCrop): JSONObject = JSONObject()
            .put("spread", JSONArray(b.spread.points.flatMap { listOf(it.x, it.y) }))
            .put("split", b.split)
            .put("side", b.side.name)
            .put("turns", b.turns)

        /** The book crop recorded in [json], or null if it is damaged: the page then reads as a plain page rather than losing the record. */
        private fun readBook(json: JSONObject): BookCrop? = try {
            val s = json.getJSONArray("spread")
            require(s.length() == 8)
            val c = List(4) { Pt(s.getDouble(2 * it), s.getDouble(2 * it + 1)) }
            val split = json.getDouble("split")
            require(split in 0.0..1.0)
            BookCrop(Quad(c[0], c[1], c[2], c[3]), split, BookPage.valueOf(json.getString("side")), json.getInt("turns").mod(4))
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        // optString turns JSON null into the text "null", so nulls are checked explicitly.
        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
