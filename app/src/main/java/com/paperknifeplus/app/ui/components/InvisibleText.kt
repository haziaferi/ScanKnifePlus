package com.paperknifeplus.app.ui.components

import android.util.Log
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream

private const val TAG = "InvisibleText"

/** Text rendering mode 3: glyphs neither filled, stroked nor clipped, as OCR layers draw them. */
private const val INVISIBLE = 3

private val SHOWS = setOf("Tj", "TJ", "'", "\"")

/** Operators after which the next glyph's position no longer depends on how far earlier glyphs advanced. */
private val NEW_LINES = setOf("BT", "ET", "Td", "TD", "Tm", "T*", "'", "\"")

/** One operator with the operands before it. */
private class Op(val operands: List<Any>, val operator: Operator)

/**
 * Removes the invisible text (shown in rendering mode 3, as OCR layers are) from [page]'s content, so a new layer does not double it; every
 * other operator stays, as does text shown in any other mode. Form XObjects are left alone: they may be shared with other pages, and OCR
 * layers are drawn in page content. Returns whether any was removed; a page whose content cannot be read stays as it was. Blocking.
 */
internal fun stripInvisibleText(document: PDDocument, page: PDPage): Boolean {
    if (!page.hasContents()) return false
    return try {
        // The page's streams are read as one, since the text state carries over from one to the next.
        val tokens = PDFStreamParser(page.contents.use { it.readBytes() }).run {
            parse()
            tokens
        }
        val kept = withoutInvisibleText(tokens, page.resources?.cosObject) ?: return false
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(kept) }
        page.setContents(stream)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not remove the old invisible text", e)
        false
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Could not remove the old invisible text", e)
        false
    }
}

/**
 * [tokens] without the shows that run in rendering mode 3 (tracked through q and Q) in a font of [resources] that is not Type 3, or null when
 * there are none. A ' or " becomes the line moves it makes. An invisible show that a kept show on the same line follows stays, so no visible
 * glyph moves.
 */
private fun withoutInvisibleText(tokens: List<Any>, resources: COSDictionary?): List<Any>? {
    fun resource(kind: COSName, name: Any?): COSDictionary? =
        (resources?.getDictionaryObject(kind) as? COSDictionary)?.getDictionaryObject(name as? COSName ?: return null) as? COSDictionary

    val ops = mutableListOf<Op>()
    val operands = mutableListOf<Any>()
    for (token in tokens) {
        if (token is Operator) {
            ops += Op(operands.toList(), token)
            operands.clear()
        } else {
            operands += token
        }
    }
    val drop = BooleanArray(ops.size)
    var state = TextState(mode = 0, font = null)
    val saved = ArrayDeque<TextState>()
    ops.forEachIndexed { i, op ->
        when (op.operator.name) {
            "q" -> saved.addLast(state)
            "Q" -> saved.removeLastOrNull()?.let { state = it }
            "Tr" -> state = state.copy(mode = (op.operands.singleOrNull() as? COSNumber)?.intValue() ?: -1)
            "Tf" -> state = state.copy(font = resource(COSName.FONT, op.operands.firstOrNull()))
            // A font an ExtGState sets (or may set: one that cannot be found) is not looked up, so its text stays.
            "gs" -> if (resource(COSName.EXT_G_STATE, op.operands.firstOrNull())?.containsKey(COSName.FONT) != false) {
                state = state.copy(font = null)
            }
            // Type 3 glyphs are procedures a viewer may draw whatever the mode, so they are never taken for invisible.
            in SHOWS -> drop[i] = state.mode == INVISIBLE && state.font.let { it != null && it.getCOSName(COSName.SUBTYPE) != COSName.TYPE3 }
        }
    }
    // Backwards, so each invisible show knows whether a kept one on its line comes after it and would shift without its advance.
    var keptAfter = false
    for (i in ops.indices.reversed()) {
        val name = ops[i].operator.name
        if (name in SHOWS) {
            if (drop[i] && keptAfter) drop[i] = false
            if (!drop[i]) keptAfter = true
        }
        if (name in NEW_LINES) keptAfter = false
    }
    if (drop.none { it }) return null
    val out = ArrayList<Any>(tokens.size)
    ops.forEachIndexed { i, op ->
        if (!drop[i]) {
            out.addAll(op.operands)
            out.add(op.operator)
            return@forEachIndexed
        }
        when (op.operator.name) {
            "'" -> out.add(Operator.getOperator("T*"))
            "\"" -> {
                // aw ac string " sets word and character spacing, then moves to the next line.
                if (op.operands.size == 3) {
                    out.addAll(listOf(op.operands[0], Operator.getOperator("Tw"), op.operands[1], Operator.getOperator("Tc")))
                }
                out.add(Operator.getOperator("T*"))
            }
        }
    }
    out.addAll(operands)
    return out
}

/** The parts of the graphics state that decide whether a show is dropped. */
private data class TextState(val mode: Int, val font: COSDictionary?)
