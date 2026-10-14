package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [removeLinks] took out: link annotations, and document-level JavaScript actions and named scripts. */
internal data class LinkRemoval(val links: Int, val scripts: Int)

private val JAVASCRIPT = COSName.getPDFName("JavaScript")

/**
 * Removes every link annotation (whatever its action: URI, Launch, GoTo, GoToR, JavaScript or a plain destination) from the pages at
 * [pages], or from all pages when null, and every document-level script: the named JavaScript tree, and JavaScript actions run when the
 * file opens (OpenAction) or on the document events of the catalog's /AA. Other annotations, including form fields, stay. A removed link
 * loses its action too, as the structure tree may still point at it. Blocking.
 */
internal fun removeLinks(document: PDDocument, pages: Collection<Int>? = null): LinkRemoval {
    var links = 0
    document.pages.forEachIndexed { index, page ->
        if (pages != null && index !in pages) return@forEachIndexed
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return@forEachIndexed
        for (i in annots.size() - 1 downTo 0) {
            val annotation = annots.getObject(i) as? COSDictionary ?: continue
            if (annotation.getNameAsString(COSName.SUBTYPE) != "Link") continue
            annots.remove(i)
            annotation.removeItem(COSName.A)
            annotation.removeItem(COSName.DEST)
            links++
        }
    }

    var scripts = 0
    val catalog = document.documentCatalog.cosObject
    val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary
    names?.getDictionaryObject(JAVASCRIPT)?.let {
        scripts += nameTreeLeaves(it)
        names.removeItem(JAVASCRIPT)
    }
    catalog.getDictionaryObject(COSName.OPEN_ACTION)?.let { action ->
        val (kept, removed) = withoutScripts(action)
        scripts += removed
        if (kept == null) catalog.removeItem(COSName.OPEN_ACTION) else if (removed > 0) catalog.setItem(COSName.OPEN_ACTION, kept)
    }
    (catalog.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { triggers ->
        for (key in triggers.keySet().toList()) {
            val (kept, removed) = withoutScripts(triggers.getDictionaryObject(key))
            scripts += removed
            if (kept == null) triggers.removeItem(key) else if (removed > 0) triggers.setItem(key, kept)
        }
        if (triggers.size() == 0) catalog.removeItem(COSName.AA)
    }
    return LinkRemoval(links, scripts)
}

/**
 * [action] (an action dictionary, or an array of them, or a destination) without its JavaScript actions, those chained by /Next
 * included, and how many were removed; the kept part is null when nothing is left.
 */
private fun withoutScripts(action: COSBase?, seen: MutableSet<COSDictionary> = identitySetOf()): Pair<COSBase?, Int> = when (action) {
    is COSDictionary -> {
        if (!seen.add(action)) {
            action to 0
        } else {
            val (next, removedNext) = withoutScripts(action.getDictionaryObject(COSName.NEXT), seen)
            if (action.getCOSName(COSName.S) == JAVASCRIPT) {
                next to removedNext + 1
            } else {
                if (removedNext > 0) {
                    if (next == null) action.removeItem(COSName.NEXT) else action.setItem(COSName.NEXT, next)
                }
                action to removedNext
            }
        }
    }
    is COSArray -> {
        // An array is either a /Next list of actions or an explicit destination, which holds no dictionary with an /S of its own.
        var removed = 0
        for (i in action.size() - 1 downTo 0) {
            val (kept, count) = withoutScripts(action.getObject(i), seen)
            removed += count
            if (count > 0) {
                if (kept == null) action.remove(i) else action.set(i, kept)
            }
        }
        (if (action.size() == 0) null else action) to removed
    }
    else -> action to 0
}

/** How many name/value pairs the name tree [node] holds, through its /Kids. */
private fun nameTreeLeaves(node: COSBase?, seen: MutableSet<COSDictionary> = identitySetOf()): Int {
    val dict = node as? COSDictionary ?: return 0
    if (!seen.add(dict)) return 0
    val own = ((dict.getDictionaryObject(COSName.NAMES) as? COSArray)?.size() ?: 0) / 2
    val kids = dict.getDictionaryObject(COSName.KIDS) as? COSArray ?: return own
    return own + (0 until kids.size()).sumOf { nameTreeLeaves(kids.getObject(it), seen) }
}

/**
 * Writes [input], opened with [password], to [output] without links and document scripts as [removeLinks] does, on [pages] or all pages
 * when null. The copy is as protected as the source with [keepProtection] ([sameProtection]), else not encrypted. Returns what was
 * removed; throws on failure.
 */
internal suspend fun removeLinksPdf(
    context: Context,
    input: Uri,
    output: Uri,
    password: String?,
    pages: Collection<Int>? = null,
    keepProtection: Boolean = false
): LinkRemoval = withContext(Dispatchers.IO) {
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, password.orEmpty()).use { document ->
            val protection = if (keepProtection) document.sameProtection(password.orEmpty()) else null
            if (protection == null && document.isEncrypted) document.isAllSecurityToBeRemoved = true
            val removal = removeLinks(document, pages)
            protection?.let(document::protect)
            saveAndFlush(context, document, output)
            removal
        }
    }
}
