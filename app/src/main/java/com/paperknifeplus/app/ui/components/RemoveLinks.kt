package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What [removeLinks] took out. [links]: link annotations, and actions that open a website, an app or another file on their own. [scripts]:
 * the named scripts, and scripts that run on their own. An action that several triggers run counts once.
 */
internal data class LinkRemoval(val links: Int, val scripts: Int) {
    /** Nothing to remove, so nothing to save. */
    val isEmpty: Boolean get() = links == 0 && scripts == 0
}

/** The action types that open a website (URI), an app (Launch) or another file (GoToR): links, when a trigger runs them on its own. */
private val LINK_ACTIONS = listOf("URI", "Launch", "GoToR").map { COSName.getPDFName(it) }

// The triggers that fire without the user acting: the catalog's document events, a page's open and close, and a widget's page triggers.
private val DOCUMENT_TRIGGERS = listOf("WC", "WS", "DS", "WP", "DP").map { COSName.getPDFName(it) }
private val PAGE_TRIGGERS = listOf("O", "C").map { COSName.getPDFName(it) }
private val WIDGET_TRIGGERS = listOf("PO", "PC", "PV", "PI").map { COSName.getPDFName(it) }

private fun COSDictionary.isScript() = getCOSName(COSName.S) == COSName.JAVA_SCRIPT
private fun COSDictionary.isLink() = getCOSName(COSName.S) in LINK_ACTIONS

/**
 * Removes every link annotation (whatever its action) from every page, the named scripts, and the scripts (JavaScript actions) and links
 * (URI, Launch and GoToR actions) of every trigger that fires on its own, those chained by /Next included: the OpenAction, the catalog's
 * document events, page open and close, and a widget's page triggers. What a user fires stays (field keystroke, format, validate and
 * calculate scripts, focus and mouse actions, bookmarks), and so do other action types and annotations. A removed link loses its action
 * too, as the structure tree may still point at it. Blocking.
 */
internal fun removeLinks(document: PDDocument): LinkRemoval {
    val removed = identitySetOf<COSDictionary>()
    fun clear(owner: COSDictionary, key: COSName) {
        val action = owner.getDictionaryObject(key) ?: return
        val kept = without(action, { it.isScript() || it.isLink() }, removed)
        if (kept == null) owner.removeItem(key) else if (kept !== action) owner.setItem(key, kept)
    }
    fun clearTriggers(owner: COSDictionary, keys: List<COSName>) {
        val triggers = owner.getDictionaryObject(COSName.AA) as? COSDictionary ?: return
        for (key in keys) clear(triggers, key)
        if (triggers.size() == 0) owner.removeItem(COSName.AA)
    }

    var links = 0
    for (page in document.pages) {
        clearTriggers(page.cosObject, PAGE_TRIGGERS)
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
        for (i in annots.size() - 1 downTo 0) {
            val annotation = annots.getObject(i) as? COSDictionary ?: continue
            when (annotation.getNameAsString(COSName.SUBTYPE)) {
                "Link" -> {
                    annots.remove(i)
                    annotation.removeItem(COSName.A)
                    annotation.removeItem(COSName.DEST)
                    links++
                }
                "Widget" -> clearTriggers(annotation, WIDGET_TRIGGERS)
            }
        }
    }

    var scripts = 0
    val catalog = document.documentCatalog.cosObject
    val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary
    names?.getDictionaryObject(COSName.JAVA_SCRIPT)?.let {
        scripts += nameTreeLeaves(it)
        names.removeItem(COSName.JAVA_SCRIPT)
    }
    clear(catalog, COSName.OPEN_ACTION)
    clearTriggers(catalog, DOCUMENT_TRIGGERS)
    val (ran, opened) = removed.partition { it.isScript() }
    return LinkRemoval(links + opened.size, scripts + ran.size)
}

/**
 * [action] (an action dictionary, a /Next array of them, or a destination) without the actions that [removes] picks, those chained by /Next
 * included, which are added to [removed]; null when no action is left. The kept actions run in the same order, chained again as copies, so
 * a bookmark or field that shares one keeps its own chain. An action met twice (a shared or looping /Next) is taken once.
 */
private fun without(action: COSBase, removes: (COSDictionary) -> Boolean, removed: MutableSet<COSDictionary>): COSBase? {
    // The chain in the order it runs: each action, then its /Next, depth first.
    val chain = mutableListOf<COSDictionary>()
    val seen = identitySetOf<COSDictionary>()
    fun walk(node: COSBase?) {
        if (node is COSArray) {
            for (i in 0 until node.size()) walk(node.getObject(i))
        } else if (node is COSDictionary && seen.add(node)) {
            chain += node
            walk(node.getDictionaryObject(COSName.NEXT))
        }
    }
    walk(action)
    val (dropped, kept) = chain.partition(removes)
    if (dropped.isEmpty()) return action
    removed += dropped
    val copies = kept.map { COSDictionary(it).apply { removeItem(COSName.NEXT) } }
    val first = copies.firstOrNull() ?: return null
    if (copies.size > 1) first.setItem(COSName.NEXT, COSArray().apply { addAll(copies.drop(1)) })
    return first
}

/** How many name/value pairs the name tree [node] holds, through its /Kids. */
private fun nameTreeLeaves(node: COSBase?, seen: MutableSet<COSDictionary> = identitySetOf()): Int {
    val dict = node as? COSDictionary ?: return 0
    if (!seen.add(dict)) return 0
    val own = ((dict.getDictionaryObject(COSName.NAMES) as? COSArray)?.size() ?: 0) / 2
    val kids = dict.getDictionaryObject(COSName.KIDS) as? COSArray ?: return own
    return own + (0 until kids.size()).sumOf { nameTreeLeaves(kids.getObject(it), seen) }
}

/** [block] on [input] opened with [password], parsed through temp files in the cache so that a large PDF does not exhaust the heap. Blocking. */
private fun <T> withPdf(context: Context, input: Uri, password: String?, block: (PDDocument) -> T): T =
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, password.orEmpty(), MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use(block)
    }

/**
 * What [removeLinksPdf] would take out of [input], opened with [password]: [removeLinks] run once on a copy that is never saved, so a
 * screen can show the counts before anything is saved. Throws on failure, InvalidPasswordException for a wrong password.
 */
internal suspend fun countLinkRemoval(context: Context, input: Uri, password: String?): LinkRemoval = withContext(Dispatchers.IO) {
    withPdf(context, input, password, ::removeLinks)
}

/**
 * Writes [input], opened with [password], to [output] without the links and scripts [removeLinks] takes out, as protected as the source
 * ([sameProtection]). Returns what was removed; throws on failure.
 */
internal suspend fun removeLinksPdf(context: Context, input: Uri, output: Uri, password: String?): LinkRemoval = withContext(Dispatchers.IO) {
    withPdf(context, input, password) { document ->
        val protection = document.sameProtection(password.orEmpty())
        val removal = removeLinks(document)
        protection?.let(document::protect)
        // Like Metadata, this tool only takes things out, so it adds no default author.
        saveAndFlush(context, document, output, applyDefaults = false)
        removal
    }
}
