package com.paperknifeplus.app.ui.components

import android.util.Log
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.util.IdentityHashMap

private const val TAG = "InvisibleText"

/** Text rendering mode 3: glyphs neither filled, stroked nor clipped, as OCR layers draw them. */
private const val INVISIBLE = 3

private val SHOWS = setOf("Tj", "TJ", "'", "\"")

/** Operators after which the next glyph's position no longer depends on how far earlier glyphs advanced. */
private val NEW_LINES = setOf("BT", "ET", "Td", "TD", "Tm", "T*", "'", "\"")

/** The owner of a stream that more than one page, or something besides a page's content, can reach. */
private const val SHARED = -1

/** One operator with the operands before it. */
private class Op(val operands: List<Any>, val operator: Operator)

/** The parts of the graphics state that decide whether a show is dropped; a form starts with its caller's. */
private data class TextState(val mode: Int, val font: COSDictionary?)

private val PAGE_START = TextState(mode = 0, font = null)

/**
 * Removes the invisible text (shown in rendering mode 3, as OCR layers are) from pages of [document], so a new layer does not double it;
 * every other operator stays, as does text shown in any other mode. Besides page content it strips the form XObjects that only that page's
 * content can reach (OCRmyPDF draws its layer in one); a form another page, an annotation or a nested form of theirs can reach is left alone.
 */
internal class InvisibleTextStripper(private val document: PDDocument) {
    /** Each stream the pages' resources reach, with the index of the only page that does, or [SHARED]. Built once, at the first need. */
    private val owners: IdentityHashMap<COSStream, Int> by lazy(::findOwners)

    /**
     * Strips page [index]: its content, and the forms it draws that are its own. Returns whether any text was removed; content or a form
     * that cannot be read stays as it was. Blocking.
     */
    fun strip(index: Int): Boolean {
        val page = document.getPage(index)
        if (!page.hasContents()) return false
        return try {
            val forms = Forms(index)
            // The page's streams are read as one, since the text state carries over from one to the next.
            val kept = withoutInvisibleText(parse(page.contents.use { it.readBytes() }), page.resources?.cosObject, PAGE_START, forms::draw)
            if (kept != null) {
                val stream = PDStream(document)
                stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(kept) }
                page.setContents(stream)
            }
            forms.write() || kept != null
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the old invisible text of page ${index + 1}", e)
            false
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Could not remove the old invisible text of page ${index + 1}", e)
            false
        }
    }

    /** The forms page [page] draws, each stripped for the state its caller draws it in. */
    private inner class Forms(private val page: Int) {
        private val states = IdentityHashMap<COSStream, MutableSet<TextState>>()
        private val stripped = IdentityHashMap<COSStream, List<Any>>()
        private val drawing = identitySetOf<COSStream>()

        /** Strips [form], drawn in [state] by content whose resources are [callerResources]; nested forms in turn. */
        fun draw(form: COSStream?, state: TextState, callerResources: COSDictionary?) {
            if (form == null || owners[form] != page) {
                Log.d(TAG, "A form page ${page + 1} draws is shared or cannot be found; it is left as it is")
                return
            }
            val seen = states.getOrPut(form) { mutableSetOf() }
            if (form in drawing || !seen.add(state)) return
            drawing += form
            try {
                // A form without resources uses its caller's, as PDF 1.1 allowed.
                val resources = form.getDictionaryObject(COSName.RESOURCES) as? COSDictionary ?: callerResources
                val kept = withoutInvisibleText(parse(form.createInputStream().use { it.readBytes() }), resources, state, ::draw)
                // A form drawn in different states would need a copy for each, so it stays as it is.
                if (kept != null && seen.size == 1) stripped[form] = kept else stripped.remove(form)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the old invisible text of a form on page ${page + 1}", e)
            } finally {
                drawing -= form
            }
        }

        /** Writes the stripped forms into their streams, keeping their dictionaries; returns whether there were any. */
        fun write(): Boolean {
            for ((form, tokens) in stripped) {
                form.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
                form.removeItem(COSName.DECODE_PARMS)
                form.removeItem(COSName.DL)
            }
            return stripped.isNotEmpty()
        }
    }

    private fun findOwners(): IdentityHashMap<COSStream, Int> {
        val owners = IdentityHashMap<COSStream, Int>()
        fun tag(root: COSBase?, owner: Int) = reachableStreams(root).forEach { stream ->
            owners[stream] = owners[stream]?.let { if (it == owner) it else SHARED } ?: owner
        }
        document.pages.forEachIndexed { index, page ->
            tag(page.resources?.cosObject, index)
            // Annotations, thumbnails and the rest of the page dictionary: whatever they reach is not the page content's alone.
            for (key in page.cosObject.keySet()) {
                if (key != COSName.RESOURCES && key != COSName.PARENT) tag(page.cosObject.getItem(key), SHARED)
            }
        }
        return owners
    }

    /** The streams [root] reaches, not counting what is reached through other pages or the page tree. */
    private fun reachableStreams(root: COSBase?): Set<COSStream> {
        val seen = identitySetOf<COSBase>()
        val streams = identitySetOf<COSStream>()
        val pending = ArrayDeque<COSBase>()
        root?.let(pending::add)
        while (pending.isNotEmpty()) {
            val value = pending.removeLast().let { if (it is COSObject) it.`object` else it } ?: continue
            if (!seen.add(value)) continue
            when (value) {
                is COSDictionary -> {
                    val type = value.getCOSName(COSName.TYPE)
                    if (type == COSName.PAGE || type == COSName.PAGES) continue
                    if (value is COSStream) streams += value
                    value.keySet().filter { it != COSName.PARENT }.forEach { key -> value.getItem(key)?.let(pending::add) }
                }
                is COSArray -> value.forEach { it?.let(pending::add) }
                else -> {}
            }
        }
        return streams
    }
}

private fun parse(bytes: ByteArray): List<Any> = PDFStreamParser(bytes).run {
    parse()
    tokens
}

/**
 * [tokens] without the shows that run in rendering mode 3 (tracked through q and Q from [start]) in a font of [resources] that is not
 * Type 3, or null when there are none. A ' or " becomes the line moves it makes. An invisible show that a kept show on the same line follows
 * stays, so no visible glyph moves. Each form drawn goes to [onForm] (null when it cannot be found) with the state it is drawn in.
 */
private fun withoutInvisibleText(
    tokens: List<Any>,
    resources: COSDictionary?,
    start: TextState,
    onForm: (COSStream?, TextState, COSDictionary?) -> Unit,
): List<Any>? {
    fun resource(kind: COSName, name: Any?): COSBase? =
        (resources?.getDictionaryObject(kind) as? COSDictionary)?.getDictionaryObject(name as? COSName ?: return null)

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
    var state = start
    val saved = ArrayDeque<TextState>()
    ops.forEachIndexed { i, op ->
        when (op.operator.name) {
            "q" -> saved.addLast(state)
            "Q" -> saved.removeLastOrNull()?.let { state = it }
            "Tr" -> state = state.copy(mode = (op.operands.singleOrNull() as? COSNumber)?.intValue() ?: -1)
            "Tf" -> state = state.copy(font = resource(COSName.FONT, op.operands.firstOrNull()) as? COSDictionary)
            // A font an ExtGState sets (or may set: one that cannot be found) is not looked up, so its text stays.
            "gs" -> if ((resource(COSName.EXT_G_STATE, op.operands.firstOrNull()) as? COSDictionary)?.containsKey(COSName.FONT) != false) {
                state = state.copy(font = null)
            }
            "Do" -> {
                val xobject = resource(COSName.XOBJECT, op.operands.firstOrNull())
                if (xobject !is COSStream) onForm(null, state, resources)
                else if (xobject.getCOSName(COSName.SUBTYPE) == COSName.FORM) onForm(xobject, state, resources)
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
