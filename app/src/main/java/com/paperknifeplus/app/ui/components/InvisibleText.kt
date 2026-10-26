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
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.util.IdentityHashMap

private const val TAG = "InvisibleText"

/** Text rendering mode 3: glyphs neither filled, stroked nor clipped, as OCR layers draw them. */
private const val INVISIBLE = 3

private val SHOWS = setOf("Tj", "TJ", "'", "\"")

/** Operators after which the next glyph's position no longer depends on how far earlier glyphs advanced. */
private val NEW_LINES = setOf("BT", "ET", "Td", "TD", "Tm", "T*", "'", "\"")

/** The user of a stream that something besides page content can draw: an annotation, a pattern, a Type 3 glyph, a soft mask. */
private const val ELSEWHERE = -1

/** One operator with the operands before it. */
private class Op(val operands: List<Any>, val operator: Operator)

/** The parts of the graphics state that decide whether a show is dropped; a form starts with its caller's. */
private data class TextState(val mode: Int, val font: COSDictionary?)

private val PAGE_START = TextState(mode = 0, font = null)

/**
 * Removes the invisible text (shown in rendering mode 3, as OCR layers are) from pages of [document], so a new layer does not double it;
 * every other operator stays, as does text shown in any other mode. Besides page content it strips the form XObjects the page draws (OCRmyPDF
 * draws its layer in one): in place when only that page's content draws the form, otherwise in a copy only that page draws, so other pages
 * and annotations keep the original.
 */
internal class InvisibleTextStripper(private val document: PDDocument) {
    private val uses by lazy(::FormUses)

    /**
     * Strips page [index]: its content, and the forms it draws. Returns whether any text was removed; content or a form that cannot be read
     * stays as it was. Blocking.
     */
    fun strip(index: Int): Boolean {
        val page = document.getPage(index)
        if (!page.hasContents()) return false
        return try {
            val uses = uses
            val resources = page.resources?.cosObject
            val forms = Forms(index, uses)
            // The page's streams are read as one, since the text state carries over from one to the next.
            val kept = withoutInvisibleText(parse(page.contents.use { it.readBytes() }), resources, PAGE_START, forms::draw)
            if (kept != null) {
                val stream = PDStream(document)
                stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(kept) }
                page.setContents(stream)
            }
            // Set on the page itself, so resources it inherits stay as they are for the other pages.
            val own = resources?.let(forms::replacement)
            if (own !== resources) page.cosObject.setItem(COSName.RESOURCES, own)
            if (forms.copied) uses.rescan(index)
            forms.stripped || kept != null
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the old invisible text of page ${index + 1}", e)
            false
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Could not remove the old invisible text of page ${index + 1}", e)
            false
        }
    }

    /** The forms page [page] draws, each stripped for the state its caller draws it in, and what the page draws in their place. */
    private inner class Forms(private val page: Int, private val uses: FormUses) {
        private val states = IdentityHashMap<COSStream, MutableSet<Pair<TextState, COSDictionary>>>()
        private val kept = IdentityHashMap<COSStream, List<Any>>()
        private val drawing = identitySetOf<COSStream>()
        private val drawn = IdentityHashMap<COSDictionary, MutableMap<COSName, COSStream>>()
        private val newResources = IdentityHashMap<COSDictionary, COSDictionary>()
        private val newForms = IdentityHashMap<COSStream, COSStream>()

        /** Whether a form lost text. */
        var stripped = false
            private set

        /** Whether a form was copied for this page. */
        var copied = false
            private set

        /** Strips [form], drawn as [name] in [state] by content whose resources are [callerResources]; nested forms in turn. */
        fun draw(name: COSName?, form: COSStream?, state: TextState, callerResources: COSDictionary?) {
            if (name == null || form == null || callerResources == null) {
                Log.d(TAG, "A form page ${page + 1} draws cannot be found; it is left as it is")
                return
            }
            drawn.getOrPut(callerResources) { HashMap() }[name] = form
            // A form without resources uses its caller's, as PDF 1.1 allowed.
            val resources = form.getDictionaryObject(COSName.RESOURCES) as? COSDictionary ?: callerResources
            val seen = states.getOrPut(form) { mutableSetOf() }
            if (form in drawing || !seen.add(state to resources)) return
            drawing += form
            try {
                val tokens = withoutInvisibleText(parse(form.createInputStream().use { it.readBytes() }), resources, state, ::draw)
                // A form drawn in different states, or with different resources, would need a copy for each, so it stays as it is.
                if (tokens != null && seen.size == 1) kept[form] = tokens else kept.remove(form)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the old invisible text of a form on page ${page + 1}", e)
            } finally {
                drawing -= form
            }
        }

        /**
         * [resources], or a copy of it naming this page's versions of the forms drawn from it. A resources dictionary is never changed in
         * place, since other pages or forms may share it or its /XObject dictionary.
         */
        fun replacement(resources: COSDictionary): COSDictionary {
            newResources[resources]?.let { return it }
            // A form that draws itself through these resources keeps drawing the original.
            newResources[resources] = resources
            val forms = drawn[resources].orEmpty()
            val swaps = forms.mapValues { (_, form) -> replacement(form) }.filter { (name, copy) -> copy !== forms[name] }
            val result = if (swaps.isEmpty()) resources else COSDictionary(resources).apply {
                val xobjects = COSDictionary(resources.getDictionaryObject(COSName.XOBJECT) as COSDictionary)
                swaps.forEach { (name, form) -> xobjects.setItem(name, form) }
                setItem(COSName.XOBJECT, xobjects)
            }
            newResources[resources] = result
            return result
        }

        /** [form] stripped in place when only this page draws it, a stripped copy when anything else does too, or [form] if nothing changes. */
        private fun replacement(form: COSStream): COSStream {
            newForms[form]?.let { return it }
            newForms[form] = form
            val own = form.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
            val resources = own?.let(::replacement)
            val tokens = kept[form]
            if (tokens != null) stripped = true
            val result = when {
                tokens == null && resources === own -> form
                uses.drawnOnlyBy(page, form) -> form.also {
                    tokens?.let(form::write)
                    if (resources !== own) form.setItem(COSName.RESOURCES, resources)
                }
                else -> document.document.createCOSStream().also { copy ->
                    copied = true
                    // The same entries, /Resources by reference too unless a form it draws was copied.
                    form.entrySet().forEach { (key, value) -> copy.setItem(key, value) }
                    if (tokens != null) copy.write(tokens)
                    else form.createRawInputStream().use { input -> copy.createRawOutputStream().use(input::copyTo) }
                    if (resources !== own) copy.setItem(COSName.RESOURCES, resources)
                }
            }
            newForms[form] = result
            return result
        }
    }

    /**
     * Which pages draw each form with Do, nested forms included, each name resolved against the resources in effect where it is drawn.
     * Whatever a page reaches besides its content (annotations and the rest of its dictionary), and whatever resources reach outside /XObject
     * (patterns, Type 3 glyphs, soft masks), counts as drawn [ELSEWHERE], since it is not followed. Content that cannot be read counts as
     * drawing every form its resources reach.
     */
    private inner class FormUses {
        private val users = IdentityHashMap<COSStream, MutableSet<Int>>()
        private val byPage = HashMap<Int, Set<COSStream>>()
        /** The forms each form draws itself, given the resources it is drawn with; read once however many pages draw it. */
        private val calls = HashMap<Pair<COSStream, COSDictionary>, List<COSStream>>()
        private val tagged = identitySetOf<COSDictionary>()

        init {
            document.pages.forEachIndexed { index, page ->
                record(index, page)
                for (key in page.cosObject.keySet()) {
                    if (key == COSName.RESOURCES || key == COSName.PARENT) continue
                    reachableStreams(page.cosObject.getItem(key)).forEach { use(it, ELSEWHERE) }
                }
            }
        }

        fun drawnOnlyBy(page: Int, form: COSStream): Boolean = users[form] == setOf(page)

        /** Records page [index] again, after it was given copies of forms. */
        fun rescan(index: Int) {
            byPage.remove(index)?.forEach { users[it]?.remove(index) }
            record(index, document.getPage(index))
        }

        private fun use(stream: COSStream, user: Int) {
            users.getOrPut(stream) { HashSet() } += user
        }

        private fun record(index: Int, page: PDPage) {
            val forms = formsDrawn(page)
            forms.forEach { use(it, index) }
            byPage[index] = forms
        }

        private fun formsDrawn(page: PDPage): Set<COSStream> {
            val resources = page.resources?.cosObject
            if (resources == null || !page.hasContents()) return emptySet()
            val drawn = identitySetOf<COSStream>()
            val pending = ArrayDeque<Pair<COSStream, COSDictionary>>()
            val seen = HashSet<Pair<COSStream, COSDictionary>>()
            fun add(forms: List<COSStream>, callerResources: COSDictionary) = forms.forEach { form ->
                drawn += form
                val formResources = form.getDictionaryObject(COSName.RESOURCES) as? COSDictionary ?: callerResources
                if (seen.add(form to formResources)) pending += form to formResources
            }
            add(called(resources) { page.contents.use { it.readBytes() } }, resources)
            while (pending.isNotEmpty()) {
                val (form, formResources) = pending.removeLast()
                val forms = calls.getOrPut(form to formResources) { called(formResources) { form.createInputStream().use { it.readBytes() } } }
                add(forms, formResources)
            }
            return drawn
        }

        /** The forms the content [read] returns draws from [resources]; every form [resources] reaches when it cannot be read. */
        private fun called(resources: COSDictionary, read: () -> ByteArray): List<COSStream> {
            if (tagged.add(resources)) {
                for (key in resources.keySet()) if (key != COSName.XOBJECT) reachableStreams(resources.getItem(key)).forEach { use(it, ELSEWHERE) }
            }
            val tokens = try {
                parse(read())
            } catch (e: Exception) {
                Log.d(TAG, "Content that cannot be read counts as drawing every form its resources reach", e)
                return reachableStreams(resources).filter { it.isForm() }
            }
            val xobjects = resources.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: return emptyList()
            return tokens.indices.mapNotNull { i ->
                val name = tokens.getOrNull(i - 1) as? COSName
                if (name == null || (tokens[i] as? Operator)?.name != "Do") null
                else (xobjects.getDictionaryObject(name) as? COSStream)?.takeIf { it.isForm() }
            }
        }
    }
}

private fun COSStream.isForm() = getCOSName(COSName.SUBTYPE) == COSName.FORM

/** Replaces this content stream's data with [tokens], keeping its other entries. */
private fun COSStream.write(tokens: List<Any>) {
    createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
    removeItem(COSName.DECODE_PARMS)
    removeItem(COSName.DL)
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

private fun parse(bytes: ByteArray): List<Any> = PDFStreamParser(bytes).run {
    parse()
    tokens
}

/**
 * [tokens] without the shows that run in rendering mode 3 (tracked through q and Q from [start]) in a font of [resources] that is not
 * Type 3, or null when there are none. A ' or " becomes the line moves it makes. An invisible show that a kept show on the same line follows
 * stays, so no visible glyph moves. Each form drawn goes to [onForm] with its name (the form null if it cannot be found) and its drawing state.
 */
private fun withoutInvisibleText(
    tokens: List<Any>,
    resources: COSDictionary?,
    start: TextState,
    onForm: (COSName?, COSStream?, TextState, COSDictionary?) -> Unit,
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
                val name = op.operands.firstOrNull() as? COSName
                val xobject = resource(COSName.XOBJECT, name)
                if (xobject !is COSStream) onForm(name, null, state, resources)
                else if (xobject.isForm()) onForm(name, xobject, state, resources)
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
