package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * How a Book mode page was cut from its original, kept so the split line can be moved later: [side] of [spread] (fractions of the original's
 * upright size) split at [split], with the book upright after [turns] clockwise quarter turns (see BookSplit.page). [shot] is the same for both
 * pages of one photo, and null in records written before it existed.
 */
data class BookCrop(val spread: Quad, val split: Double, val side: BookPage, val turns: Int, val shot: String? = null)

/**
 * One page of a scanned document, mirroring OpenScan's `pages` table, with file names relative to the document's folder: [image] is shown,
 * [original] is the uncropped capture for re-crops, [unfiltered] keeps filters from compounding, and [filter] names the applied filter. A re-crop
 * keeps [quarterTurns], the clockwise turn from [original] (0 without one); [book] is set on a Book mode page with an original until it is
 * re-cropped by hand. [quad] is where the page sits in [original] (fractions of its upright size), detected at capture or set by a re-crop, so
 * a later crop starts from it; null for a Book mode page, a page without an original, or one stored uncropped. [checkCrop] marks a camera page
 * stored whole because detection found no outline or the warp failed, until the user crops it.
 */
data class ScanPage(
    val id: String,
    val image: String,
    val original: String? = null,
    val unfiltered: String? = null,
    val filter: String? = null,
    val quarterTurns: Int = 0,
    val book: BookCrop? = null,
    val quad: Quad? = null,
    val checkCrop: Boolean = false,
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
                .apply { p.quad?.let { put("quad", quadJson(it)) } }
                .apply { if (p.checkCrop) put("checkCrop", true) }
        }))

    internal companion object {
        /**
         * Written for future migrations; records are read best-effort whatever their version, since every field read here is required in v1
         * except the optional `quarterTurns`, `book`, `quad` and `checkCrop`.
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
                        quad = p.optJSONArray("quad")?.let { readQuad(it) },
                        checkCrop = p.optBoolean("checkCrop", false),
                    )
                },
            )
        }

        private fun quadJson(q: Quad): JSONArray = JSONArray(q.points.flatMap { listOf(it.x, it.y) })

        /** The quad recorded in [json], or null if it is damaged: the page then reads without one rather than losing the record. */
        private fun readQuad(json: JSONArray): Quad? = try {
            require(json.length() == 8)
            val c = List(4) { Pt(json.getDouble(2 * it), json.getDouble(2 * it + 1)) }
            require(c.all { it.x.isFinite() && it.y.isFinite() })
            Quad(c[0], c[1], c[2], c[3])
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun bookJson(b: BookCrop): JSONObject = JSONObject()
            .put("spread", quadJson(b.spread))
            .put("split", b.split)
            .put("side", b.side.name)
            .put("turns", b.turns)
            .apply { b.shot?.let { put("shot", it) } }

        /** The book crop recorded in [json], or null if it is damaged: the page then reads as a plain page rather than losing the record. */
        private fun readBook(json: JSONObject): BookCrop? = try {
            val s = json.getJSONArray("spread")
            require(s.length() == 8)
            val c = List(4) { Pt(s.getDouble(2 * it), s.getDouble(2 * it + 1)) }
            val split = json.getDouble("split")
            require(split in 0.0..1.0)
            val shot = if (json.isNull("shot")) null else json.getString("shot")
            BookCrop(Quad(c[0], c[1], c[2], c[3]), split, BookPage.valueOf(json.getString("side")), json.getInt("turns").mod(4), shot)
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        // optString turns JSON null into the text "null", so nulls are checked explicitly.
        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
