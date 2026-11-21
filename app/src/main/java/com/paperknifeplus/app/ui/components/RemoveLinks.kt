package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What removing links takes out with some options ([FoundLinks.removal]), or took out ([removeLinksPdf]). [links]: link annotations, and
 * actions that open a website, an app or another file, or send form data, on their own. [scripts]: the named scripts, and scripts that run
 * on their own, a rendition's own script included. An action that several triggers run counts once.
 */
internal data class LinkRemoval(val links: Int, val scripts: Int) {
    /** Nothing to remove, so nothing to save. */
    val isEmpty: Boolean get() = links == 0 && scripts == 0
}

/**
 * Which link annotations [removeLinks] takes out: those on [pages] (0-based indices; every page when null), and with [keepInside] only those
 * that leave the file ([leavesFile]). What runs on its own goes from the whole file whatever these say.
 */
internal data class LinkOptions(val pages: Set<Int>? = null, val keepInside: Boolean = false) {
    /** Whether a link annotation on page [index] goes; [leaves] says whether it leaves the file. */
    fun takes(index: Int, leaves: Boolean): Boolean = (pages == null || index in pages) && (leaves || !keepInside)
}

/**
 * What [removeLinks] found in a file, whatever its options: on each page, the link annotations that stay inside the file ([inside]) and
 * those that leave it ([leaving]), and what runs on its own ([automatic]), which goes from the whole file. A screen counts once and sums
 * this for each choice ([removal]).
 */
internal data class FoundLinks(val inside: List<Int>, val leaving: List<Int>, val automatic: LinkRemoval) {
    val pageCount: Int get() = inside.size

    /** Nothing to remove whatever the options. */
    val isEmpty: Boolean get() = removal(LinkOptions()).isEmpty

    /** What [removeLinks] with [options] takes out. */
    fun removal(options: LinkOptions): LinkRemoval {
        val links = inside.indices.sumOf { page ->
            (if (options.takes(page, leaves = false)) inside[page] else 0) + (if (options.takes(page, leaves = true)) leaving[page] else 0)
        }
        return automatic.copy(links = automatic.links + links)
    }
}

/**
 * The action types that open a website (URI), an app (Launch) or another file (GoToR), or send form data to a web address (SubmitForm):
 * links, when a trigger runs them on its own.
 */
private val LINK_ACTIONS = listOf("URI", "Launch", "GoToR", "SubmitForm").map { COSName.getPDFName(it) }
private val RENDITION = COSName.getPDFName("Rendition")

private val GO_TO = COSName.getPDFName("GoTo")
private val NAMED = COSName.getPDFName("Named")

/** The Named actions that go to a page of the file; any other (Print, a menu command such as AcroSendMail:SendMail) leaves it. */
private val PAGE_COMMANDS = listOf("NextPage", "PrevPage", "FirstPage", "LastPage").map { COSName.getPDFName(it) }

/** Whether [action] stays inside the file: it goes to a place in it (GoTo), or to its next, previous, first or last page (Named). */
private fun goesToPage(action: COSDictionary): Boolean = when (action.getCOSName(COSName.S)) {
    GO_TO -> true
    NAMED -> action.getCOSName(COSName.N) in PAGE_COMMANDS
    else -> false
}

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
 * Whether the link annotation [link] leaves the file: an action the user fires on it (its /A, or a mouse or focus trigger of its /AA), or one
 * chained after it by /Next, does not go to a page of it ([goesToPage]), an unknown one included. A plain destination (/Dest), or no action,
 * stays inside.
 */
private fun leavesFile(link: COSDictionary): Boolean {
    val triggers = (link.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { aa ->
        aa.keySet().filter { it !in ANNOTATION_TRIGGERS }.mapNotNull { aa.getDictionaryObject(it) }
    }
    val fired = listOfNotNull(link.getDictionaryObject(COSName.A)) + triggers.orEmpty()
    return fired.any { action -> chainOf(action).any { !goesToPage(it) } }
}

/**
 * Removes the link annotations [options] choose, the named scripts, and the scripts and links of every trigger that fires on its own, those
 * chained by /Next included, as [cleared] decides: the OpenAction, the catalog's document events, page open and close, and every
 * annotation's page triggers, on every page whatever [options] say. What a user fires stays (field keystroke, format, validate and calculate
 * scripts, focus and mouse actions, bookmarks), and so do other action types and annotations. A removed link that no page keeps also loses
 * its /A and /Dest, as the structure tree may still point at it; the triggers a user fires on it (its /AA) stay in its dictionary, which no
 * page holds any more. Returns what it found, every page and kind, so its [FoundLinks.removal] of [options] is what it took out. Blocking.
 */
internal fun removeLinks(document: PDDocument, options: LinkOptions = LinkOptions()): FoundLinks {
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

    // Pages that share one /Annots array get their own copies before any changes, so each page counts and loses only its own links.
    val arrays = identitySetOf<COSArray>()
    for (page in document.pages) {
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
        if (!arrays.add(annots)) page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { for (i in 0 until annots.size()) add(annots.get(i)) })
    }

    val inside = mutableListOf<Int>()
    val leaving = mutableListOf<Int>()
    // A link's action is dropped only after every page is read, so a link shared by a page outside the range is read and kept whole.
    val removed = identitySetOf<COSDictionary>()
    val kept = identitySetOf<COSDictionary>()
    for ((index, page) in document.pages.withIndex()) {
        inside += 0
        leaving += 0
        clearTriggers(page.cosObject, PAGE_TRIGGERS)
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
        for (i in annots.size() - 1 downTo 0) {
            val annotation = annots.getObject(i) as? COSDictionary ?: continue
            clearTriggers(annotation, ANNOTATION_TRIGGERS)
            if (annotation.getNameAsString(COSName.SUBTYPE) != "Link") continue
            val leaves = leavesFile(annotation)
            if (leaves) leaving[index]++ else inside[index]++
            if (options.takes(index, leaves)) {
                annots.remove(i)
                removed += annotation
            } else {
                kept += annotation
            }
        }
    }
    for (link in removed) {
        if (link in kept) continue
        link.removeItem(COSName.A)
        link.removeItem(COSName.DEST)
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
    val automatic = LinkRemoval(taken.values.count { it == Taken.LINK }, scripts + taken.values.count { it == Taken.SCRIPT })
    return FoundLinks(inside, leaving, automatic)
}

/** The actions of [action] (an action dictionary, a /Next array of them, or a destination) in the order they run: each, then its /Next. */
private fun chainOf(action: COSBase): List<COSDictionary> {
    val chain = mutableListOf<COSDictionary>()
    // A shared or looping /Next is followed once.
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
    return chain
}

/**
 * [action] (an action dictionary, a /Next array of them, or a destination) with each action in it, those chained by /Next included, as
 * [clear] leaves it, and what each lost added to [taken]; null when no action is left. When anything changed, the actions left run in the
 * same order, chained again as copies, so a bookmark or field that shares one keeps its own chain. An action met twice (a shared or looping
 * /Next) is taken once.
 */
private fun without(action: COSBase, clear: (COSDictionary) -> Pair<COSDictionary?, Taken?>, taken: MutableMap<COSDictionary, Taken>): COSBase? {
    val chain = chainOf(action)
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

/**
 * What [input], opened with [password], holds for [removeLinksPdf] to take out: [removeLinks] run once on a copy that is never saved, so a
 * screen can show the counts for every choice before anything is saved, without reading the file again. A wrong or missing password is
 * told from a file that cannot be read as [checkPassword] does.
 */
internal suspend fun findLinks(context: Context, input: Uri, password: String?): PasswordCheck<FoundLinks> =
    checkPassword(context, input, password) { removeLinks(it) }

/**
 * Writes [input], opened with [password], to [output] without the links and scripts [removeLinks] takes out with [options], as protected as
 * the source ([editKeepingProtection]). Returns what was removed; throws on failure.
 */
internal suspend fun removeLinksPdf(context: Context, input: Uri, output: Uri, password: String?, options: LinkOptions): LinkRemoval =
    withContext(Dispatchers.IO) {
        editKeepingProtection(context, input, password.orEmpty(), output) { removeLinks(it, options).removal(options) }
    }
