package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What [removeLinks] took out. [links]: link annotations, and actions that open a website, an app or another file, or send form data, on
 * their own. [scripts]: the named scripts, and scripts that run on their own, a rendition's own script included. An action that several
 * triggers run counts once.
 */
internal data class LinkRemoval(val links: Int, val scripts: Int) {
    /** Nothing to remove, so nothing to save. */
    val isEmpty: Boolean get() = links == 0 && scripts == 0
}

/**
 * The action types that open a website (URI), an app (Launch) or another file (GoToR), or send form data to a web address (SubmitForm):
 * links, when a trigger runs them on its own.
 */
private val LINK_ACTIONS = listOf("URI", "Launch", "GoToR", "SubmitForm").map { COSName.getPDFName(it) }
private val RENDITION = COSName.getPDFName("Rendition")

// The triggers that fire without the user acting: the catalog's document events, a page's open and close, and an annotation's page triggers.
private val DOCUMENT_TRIGGERS = listOf("WC", "WS", "DS", "WP", "DP").map { COSName.getPDFName(it) }
private val PAGE_TRIGGERS = listOf("O", "C").map { COSName.getPDFName(it) }
private val ANNOTATION_TRIGGERS = listOf("PO", "PC", "PV", "PI").map { COSName.getPDFName(it) }

/** What an action that runs on its own loses, as [LinkRemoval] counts it. */
private enum class Taken { SCRIPT, LINK }

/**
 * What a trigger that fires on its own may still run of [action] (null for nothing), and what it lost (null for nothing): a script or link
 * action goes whole; a rendition loses its own script (/JS), and goes whole when it has no rendition (/R) left to play; any other action
 * stays as it is.
 */
private fun cleared(action: COSDictionary): Pair<COSDictionary?, Taken?> {
    val type = action.getCOSName(COSName.S)
    return when {
        type == COSName.JAVA_SCRIPT -> null to Taken.SCRIPT
        type in LINK_ACTIONS -> null to Taken.LINK
        type == RENDITION && action.getDictionaryObject(COSName.JS) != null -> {
            val rest = if (action.getDictionaryObject(COSName.R) == null) null else COSDictionary(action).apply { removeItem(COSName.JS) }
            rest to Taken.SCRIPT
        }
        else -> action to null
    }
}

/**
 * Removes every link annotation (whatever its action) from every page, the named scripts, and the scripts and links of every trigger that
 * fires on its own, those chained by /Next included, as [cleared] decides: the OpenAction, the catalog's document events, page open and
 * close, and every annotation's page triggers. What a user fires stays (field keystroke, format, validate and calculate scripts, focus and
 * mouse actions, bookmarks), and so do other action types and annotations. A removed link loses its action too, as the structure tree may
 * still point at it. Blocking.
 */
internal fun removeLinks(document: PDDocument): LinkRemoval {
    val taken = IdentityHashMap<COSDictionary, Taken>()
    fun clear(owner: COSDictionary, key: COSName) {
        val action = owner.getDictionaryObject(key) ?: return
        val kept = without(action, ::cleared, taken)
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
                else -> clearTriggers(annotation, ANNOTATION_TRIGGERS)
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
    return LinkRemoval(links + taken.values.count { it == Taken.LINK }, scripts + taken.values.count { it == Taken.SCRIPT })
}

/**
 * [action] (an action dictionary, a /Next array of them, or a destination) with each action in it, those chained by /Next included, as
 * [clear] leaves it, and what each lost added to [taken]; null when no action is left. When anything changed, the actions left run in the
 * same order, chained again as copies, so a bookmark or field that shares one keeps its own chain. An action met twice (a shared or looping
 * /Next) is taken once.
 */
private fun without(action: COSBase, clear: (COSDictionary) -> Pair<COSDictionary?, Taken?>, taken: MutableMap<COSDictionary, Taken>): COSBase? {
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
    val results = chain.map(clear)
    if (results.all { (_, lost) -> lost == null }) return action
    chain.zip(results).forEach { (original, result) -> result.second?.let { taken[original] = it } }
    val copies = results.mapNotNull { (rest, _) -> rest }.map { COSDictionary(it).apply { removeItem(COSName.NEXT) } }
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
