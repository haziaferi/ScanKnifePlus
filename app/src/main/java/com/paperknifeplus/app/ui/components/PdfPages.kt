package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.StandardSecurityHandler
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

private const val TAG = "PdfPages"

/**
 * Copies [src] from another document onto the end of this one, as [appendPagesFrom] does for a single page: links on it to itself keep
 * working, links to any other page of the source are dropped.
 */
fun PDDocument.appendPageFrom(src: PDPage): PDPage = appendPagesFrom(listOf(src)).single()

/**
 * Copies [pages] of another document, in order, onto the end of this one and returns the copies. [PDDocument.importPage] carries over
 * MediaBox, CropBox and Rotate, and inherited /Resources are set explicitly as PDFBox's Splitter does, so fonts and images survive.
 * References back into the source would make the saved file carry pages that were left out, so they are cut: annotation /P and article
 * beads go, link targets on a copied page are moved to its copy, and links to any other page or to a named destination are dropped.
 */
fun PDDocument.appendPagesFrom(pages: List<PDPage>): List<PDPage> {
    val copies = IdentityHashMap<COSDictionary, PDPage>()
    val imported = pages.map { src ->
        importPage(src).also { page ->
            if (!page.cosObject.containsKey(COSName.RESOURCES)) page.resources = src.resources
            page.cosObject.removeItem(COSName.B)
            copies[src.cosObject] = page
        }
    }
    for (page in imported) {
        for (annotation in page.annotations) {
            if (annotation is PDAnnotationLink) relink(annotation, keepNamed = false) { copies[it] }
            annotation.page = null
        }
    }
    return imported
}

/**
 * Points [link] at [target]'s page for its current one, removing its target when that is null; a named target is removed unless
 * [keepNamed], since the names only exist in the source document.
 */
private fun relink(link: PDAnnotationLink, keepNamed: Boolean, target: (COSDictionary) -> PDPage?) {
    val action = link.action as? PDActionGoTo
    when (val destination = link.destination ?: action?.destination) {
        is PDPageDestination -> {
            val page = destination.page ?: return // A page number, not an object: nothing in the source is reachable through it.
            destination.setPage(target(page.cosObject))
        }
        is PDNamedDestination -> if (!keepNamed) {
            if (link.destination != null) link.destination = null else link.action = null
        }
        else -> {}
    }
}

/**
 * Keeps the pages at [order] (indices into this document) in that order and removes the rest, editing the document in place so its
 * outline, form, named destinations, page labels and other document-level data stay with their pages. Outline items, form fields, named
 * destinations, links and the open action that lead to a removed page are cut (an outline item with kept children stays as a heading), and
 * any other reference to one is removed, so no removed page is written to the saved file. Each kept page keeps its page label.
 */
fun PDDocument.keepPages(order: List<Int>) {
    val all = pages.toList()
    val kept = order.map { all[it] }
    val keptSet = identitySetOf(kept.map { it.cosObject })
    val removed = identitySetOf(all.map { it.cosObject }.filter { it !in keptSet })
    val labels = pageLabelsByIndex(all.size)
    // Resolved while page indices still mean what they did; afterwards they would point at other pages.
    pinPageNumbers(all)
    val outlineTargets = outlineTargets()

    for (page in kept) {
        for (key in INHERITABLE_PAGE_KEYS) {
            if (!page.cosObject.containsKey(key)) inheritedValue(page.cosObject, key)?.let { page.cosObject.setItem(key, it) }
        }
    }
    val root = pages.cosObject
    root.setItem(COSName.KIDS, COSArray().apply { kept.forEach { add(it.cosObject) } })
    root.setInt(COSName.COUNT, kept.size)
    kept.forEach { it.cosObject.setItem(COSName.PARENT, root) }

    if (removed.isNotEmpty()) {
        val removedAnnotations = identitySetOf(
            all.filter { it.cosObject in removed }.flatMap { page -> (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray).dicts() }
        )
        documentCatalog.documentOutline?.let { pruneOutline(it.cosObject, removed, outlineTargets, identitySetOf()) }
        pruneForm(removedAnnotations)
        pruneNamedDestinations(removed)
        (documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION))?.let {
            if (destinationPage(it) in removed) documentCatalog.cosObject.removeItem(COSName.OPEN_ACTION)
        }
        for (page in kept) {
            for (annotation in page.annotations) {
                if (annotation is PDAnnotationLink) relink(annotation, keepNamed = true) { if (it in removed) null else PDPage(it) }
            }
        }
        cutReferences(document.trailer, removed)
    }
    if (labels != null) relabel(order.map { labels[it] })
}

/** Attributes a page inherits from its page tree ancestors, set on each kept page before [keepPages] makes the tree flat. */
private val INHERITABLE_PAGE_KEYS = listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE)

private fun inheritedValue(page: COSDictionary, key: COSName): COSBase? {
    val seen = identitySetOf<COSDictionary>()
    var node = page.getDictionaryObject(COSName.PARENT) as? COSDictionary
    while (node != null && seen.add(node)) {
        node.getDictionaryObject(key)?.let { return it }
        node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
    }
    return null
}

private fun <T> identitySetOf(items: Collection<T> = emptyList()): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(items) }

private fun COSArray?.dicts(): List<COSDictionary> =
    if (this == null) emptyList() else (0 until size()).mapNotNull { getObject(it) as? COSDictionary }

/** The page dictionary an explicit destination array, or a dictionary holding one in /D (a GoTo action or a named destination), targets. */
private fun destinationPage(value: COSBase?): COSDictionary? = when (value) {
    is COSArray -> value.getObject(0) as? COSDictionary
    is COSDictionary -> (value.getDictionaryObject(COSName.D) as? COSArray)?.getObject(0) as? COSDictionary
    else -> null
}

/** Every outline item, depth first; a malformed (cyclic) outline is walked once. */
private fun PDDocument.outlineItems(): List<COSDictionary> {
    val items = mutableListOf<COSDictionary>()
    val seen = identitySetOf<COSDictionary>()
    fun walk(first: COSDictionary?) {
        var item = first
        while (item != null && seen.add(item)) {
            items += item
            walk(item.getDictionaryObject(COSName.FIRST) as? COSDictionary)
            item = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
        }
    }
    val outline = documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES) as? COSDictionary
    walk(outline?.getDictionaryObject(COSName.FIRST) as? COSDictionary)
    return items
}

/**
 * Replaces the page numbers some producers write into destinations (outline items, links, named destinations, the open action) with the
 * page in [all] they mean, so they keep leading there once [keepPages] renumbers the pages. Numbers past the end are left as they are.
 */
private fun PDDocument.pinPageNumbers(all: List<PDPage>) {
    fun pin(destination: COSBase?) {
        when (destination) {
            is COSArray -> {
                val page = (destination.getObject(0) as? COSInteger)?.intValue()?.let(all::getOrNull)
                if (page != null) destination.set(0, page.cosObject)
            }
            is COSDictionary -> pin(destination.getDictionaryObject(COSName.D))
            else -> {}
        }
    }
    val annotations = all.flatMap { page -> (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray).dicts() }
    for (holder in outlineItems() + annotations) {
        pin(holder.getDictionaryObject(COSName.DEST))
        pin(holder.getDictionaryObject(COSName.A))
    }
    val catalog = documentCatalog.cosObject
    pin(catalog.getDictionaryObject(COSName.OPEN_ACTION))
    (catalog.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { dests ->
        dests.keySet().forEach { pin(dests.getDictionaryObject(it)) }
    }
    forEachNamedDestinationLeaf { pairs -> for (i in 1 until pairs.size() step 2) pin(pairs.getObject(i)) }
}

/** The page each outline item leads to, resolved before the page tree changes; null where it leads nowhere or cannot be resolved. */
private fun PDDocument.outlineTargets(): Map<COSDictionary, COSDictionary?> {
    val targets = IdentityHashMap<COSDictionary, COSDictionary?>()
    for (item in outlineItems()) {
        targets[item] = try {
            PDOutlineItem(item).findDestinationPage(this)?.cosObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Malformed destinations, or a page number past the end: unresolved, as the Bookmarks tool shows them.
            Log.w(TAG, "Unresolved outline target", e)
            null
        }
    }
    return targets
}

/**
 * Drops the outline items under [node] whose [targets] page was removed and that have no kept children; one with kept children loses only
 * its target. Relinks the survivors and recounts /Count, keeping each item open or closed as it was.
 */
private fun pruneOutline(
    node: COSDictionary,
    removed: Set<COSDictionary>,
    targets: Map<COSDictionary, COSDictionary?>,
    seen: MutableSet<COSDictionary>
) {
    val survivors = mutableListOf<COSDictionary>()
    var child = node.getDictionaryObject(COSName.FIRST) as? COSDictionary
    while (child != null && seen.add(child)) {
        pruneOutline(child, removed, targets, seen)
        val leadsAway = targets[child] in removed
        if (leadsAway) {
            child.removeItem(COSName.DEST)
            child.removeItem(COSName.A)
        }
        if (!leadsAway || child.containsKey(COSName.FIRST)) survivors += child
        child = child.getDictionaryObject(COSName.NEXT) as? COSDictionary
    }

    val wasClosed = node.getInt(COSName.COUNT, 0) < 0
    if (survivors.isEmpty()) {
        listOf(COSName.FIRST, COSName.LAST, COSName.COUNT).forEach(node::removeItem)
        return
    }
    survivors.forEachIndexed { i, item ->
        item.setItem(COSName.PARENT, node)
        if (i > 0) item.setItem(COSName.PREV, survivors[i - 1]) else item.removeItem(COSName.PREV)
        if (i < survivors.lastIndex) item.setItem(COSName.NEXT, survivors[i + 1]) else item.removeItem(COSName.NEXT)
    }
    node.setItem(COSName.FIRST, survivors.first())
    node.setItem(COSName.LAST, survivors.last())
    val visible = survivors.sumOf { 1 + maxOf(it.getInt(COSName.COUNT, 0), 0) }
    node.setInt(COSName.COUNT, if (wasClosed) -visible else visible)
}

/** Removes the widgets in [removedAnnotations] from the form, and every field left without widgets, also from the calculation order. */
private fun PDDocument.pruneForm(removedAnnotations: Set<COSDictionary>) {
    val form = documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return
    val dropped = identitySetOf(removedAnnotations)
    (form.getDictionaryObject(COSName.FIELDS) as? COSArray)?.let { pruneFields(it, dropped, identitySetOf()) }
    (form.getDictionaryObject(COSName.CO) as? COSArray)?.let { order -> order.dicts().filter { it in dropped }.forEach { order.removeObject(it) } }
}

private fun pruneFields(kids: COSArray, dropped: MutableSet<COSDictionary>, seen: MutableSet<COSDictionary>) {
    for (i in kids.size() - 1 downTo 0) {
        val kid = kids.getObject(i) as? COSDictionary ?: continue
        if (kid !in dropped && seen.add(kid)) {
            val grandKids = kid.getDictionaryObject(COSName.KIDS) as? COSArray
            if (grandKids != null && grandKids.size() > 0) {
                pruneFields(grandKids, dropped, seen)
                if (grandKids.size() == 0) dropped += kid
            }
        }
        if (kid in dropped) kids.remove(i)
    }
}

/** Removes the named destinations, old-style /Dests and the /Names tree alike, that target a removed page. */
private fun PDDocument.pruneNamedDestinations(removed: Set<COSDictionary>) {
    val catalog = documentCatalog.cosObject
    (catalog.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { dests ->
        dests.keySet().toList().forEach { if (destinationPage(dests.getDictionaryObject(it)) in removed) dests.removeItem(it) }
    }
    forEachNamedDestinationLeaf { pairs ->
        for (i in (pairs.size() / 2 - 1) downTo 0) {
            if (destinationPage(pairs.getObject(2 * i + 1)) in removed) {
                pairs.remove(2 * i + 1)
                pairs.remove(2 * i)
            }
        }
    }
}

/** Hands [block] the /Names array (name, destination, name, ...) of each node of the /Names /Dests tree. */
private fun PDDocument.forEachNamedDestinationLeaf(block: (COSArray) -> Unit) {
    val names = documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary
    val tree = names?.getDictionaryObject(COSName.DESTS) as? COSDictionary ?: return
    val seen = identitySetOf<COSDictionary>()
    val nodes = ArrayDeque(listOf(tree))
    while (nodes.isNotEmpty()) {
        val node = nodes.removeFirst()
        if (!seen.add(node)) continue
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let(block)
        nodes.addAll((node.getDictionaryObject(COSName.KIDS) as? COSArray).dicts())
    }
}

/**
 * Removes every reference to a [removed] page reachable from [start]: the key from a dictionary, or a null in its place in an array. A
 * catch-all for what [keepPages] does not prune by name (article beads, structure elements), so removed pages are not written to the file.
 */
private fun cutReferences(start: COSDictionary, removed: Set<COSDictionary>) {
    val seen = identitySetOf<COSBase>()
    val pending = ArrayDeque<COSBase>(listOf(start))
    while (pending.isNotEmpty()) {
        when (val node = pending.removeLast()) {
            is COSDictionary -> for (key in node.keySet().toList()) {
                val value = node.getDictionaryObject(key) ?: continue
                if (value in removed) node.removeItem(key) else if (seen.add(value)) pending += value
            }
            is COSArray -> for (i in 0 until node.size()) {
                val value = node.getObject(i) ?: continue
                if (value in removed) node.set(i, COSNull.NULL) else if (seen.add(value)) pending += value
            }
            else -> {}
        }
    }
}

/** A page's label: [style] (null for no number), [prefix] and the [number] it shows. */
private data class PageLabel(val style: String?, val prefix: String?, val number: Int)

/** The label of each of the first [count] pages, or null when the document has no page labels. */
private fun PDDocument.pageLabelsByIndex(count: Int): List<PageLabel>? {
    val labels = documentCatalog.pageLabels ?: return null
    val starts = labels.pageIndices
    return (0 until count).map { index ->
        val start = starts.floor(index) ?: return@map PageLabel(null, null, 1)
        val range = labels.getPageLabelRange(start)
        PageLabel(range.style, range.prefix, maxOf(1, range.start + index - start))
    }
}

/** Writes page labels that give page i [labels]`[i]`, starting a new range wherever the numbering does not simply continue. */
private fun PDDocument.relabel(labels: List<PageLabel>) {
    val result = PDPageLabels(this)
    labels.forEachIndexed { i, label ->
        val previous = labels.getOrNull(i - 1)
        val continues = previous != null && previous.style == label.style && previous.prefix == label.prefix &&
            (label.style == null || label.number == previous.number + 1)
        if (i == 0 || !continues) {
            result.setLabelItem(i, PDPageLabelRange().apply {
                style = label.style
                prefix = label.prefix
                start = label.number
            })
        }
    }
    documentCatalog.pageLabels = result
}

/**
 * The encryption that keeps a tool's output as protected as this source, opened with [password], with the source's permissions:
 * - AES-256, as Protect uses. AES-256 prepares passwords with SASLprep, which refuses some characters (control characters, for one) that
 *   an older (revision 2-4) file accepts; only then does the output keep the source's key length and RC4 or AES-128 cipher.
 * - The open password stays the same. Opened with the owner password, a revision 2-4 file gives up its user password, which is kept; a
 *   revision 5-6 (AES-256) file cannot, so its owner password then also becomes the open password.
 * - The owner password is kept when [password] was it; otherwise it cannot be read, and a random one keeps the permissions in force.
 * Null for an unencrypted source.
 */
internal fun PDDocument.sameProtection(password: String): StandardProtectionPolicy? {
    if (!isEncrypted) return null
    val source = encryption
    val legacy = source.revision <= 4
    val opener = currentAccessPermission.isOwnerPermission
    val user = if (opener && legacy) recoverUserPassword(password) ?: password else password
    val owner = if (opener) password else UUID.randomUUID().toString()
    return StandardProtectionPolicy(owner, user, AccessPermission(source.permissions)).apply {
        if (legacy && !aes256Accepts(owner, user)) {
            encryptionKeyLength = if (source.length <= 40) 40 else 128
            isPreferAES = source.stdCryptFilterDictionary?.cryptFilterMethod == COSName.AESV2
        } else {
            encryptionKeyLength = 256
        }
    }
}

/** Whether AES-256 encryption takes [owner] and [user], tried on an empty document: SASLprep throws on a password it refuses. */
private fun aes256Accepts(owner: String, user: String): Boolean = try {
    PDDocument().use { probe ->
        probe.protect(StandardProtectionPolicy(owner, user, AccessPermission()).apply { encryptionKeyLength = 256 })
        probe.save(ByteArrayOutputStream())
    }
    true
} catch (e: IllegalArgumentException) {
    false
}

/** The padding revision 2-4 handlers append to a password to make it 32 bytes (ISO 32000-1, 7.6.3.3, Algorithm 2). */
private val PASSWORD_PADDING = intArrayOf(
    0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
    0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A
).map { it.toByte() }.toByteArray()

/**
 * The user password of this revision 2-4 document, recovered from its [ownerPassword] (the owner key holds it, RC4-encrypted with a key
 * derived from the owner password) and checked against the user key; null if that fails.
 */
private fun PDDocument.recoverUserPassword(ownerPassword: String): String? = try {
    val source = encryption
    val keyBytes = source.length / 8
    val handler = StandardSecurityHandler()
    val padded = handler.getUserPassword(ownerPassword.toByteArray(Charsets.ISO_8859_1), source.ownerKey, source.revision, keyBytes)
    val id = (document.documentID?.getObject(0) as? COSString)?.bytes ?: ByteArray(0)
    (0..padded.size).map { padded.copyOf(it) }
        .filter { padded.copyOfRange(it.size, padded.size).contentEquals(PASSWORD_PADDING.copyOf(padded.size - it.size)) }
        .firstOrNull {
            handler.isUserPassword(it, source.userKey, source.ownerKey, source.permissions, id, source.revision, keyBytes, source.isEncryptMetaData)
        }
        ?.toString(Charsets.ISO_8859_1)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Cannot recover the user password", e)
    null
}

/**
 * Opens [input] with [password], lets [edit] change it in place and writes it to [output] as protected as it was ([sameProtection]). Tools
 * pass the file as picked, never its decrypted cache copy, so the encryption can be read. Blocking; throws on failure.
 */
private fun editKeepingProtection(context: Context, input: Uri, password: String, output: Uri, edit: (PDDocument) -> Unit) {
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, password).use { document ->
            val protection = document.sameProtection(password)
            edit(document)
            protection?.let(document::protect)
            saveAndFlush(context, document, output)
        }
    }
}

/** Writes [input] without the pages at [indices] to [output], in place as [keepPages] does and as protected as it was. Throws on failure. */
suspend fun deletePages(context: Context, input: Uri, output: Uri, password: String, indices: Set<Int>) = withContext(Dispatchers.IO) {
    editKeepingProtection(context, input, password, output) { doc -> doc.keepPages((0 until doc.numberOfPages).filter { it !in indices }) }
}

/** Writes [input] with its pages in [order] to [output], in place as [keepPages] does and as protected as it was. Throws on failure. */
suspend fun rearrangePages(context: Context, input: Uri, output: Uri, password: String, order: List<Int>) = withContext(Dispatchers.IO) {
    editKeepingProtection(context, input, password, output) { it.keepPages(order) }
}

/** Writes [input] to [output] with each page index in [rotations] turned by its degrees, as protected as it was. Throws on failure. */
suspend fun rotatePages(context: Context, input: Uri, output: Uri, password: String, rotations: Map<Int, Int>) = withContext(Dispatchers.IO) {
    editKeepingProtection(context, input, password, output) { doc ->
        doc.pages.forEachIndexed { index, page ->
            val rotation = rotations[index] ?: 0
            if (rotation != 0) page.rotation = (page.rotation + rotation) % 360
        }
    }
}

/**
 * Copies the pages of [input] at [indices] that exist, in page order, into a new document as [appendPagesFrom] does and writes it to
 * [output], as protected as [input] was. Returns the number of pages written; throws on failure.
 */
suspend fun extractPages(context: Context, input: Uri, output: Uri, password: String, indices: Collection<Int>): Int = withContext(Dispatchers.IO) {
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, password).use { document ->
            PDDocument().use { target ->
                val kept = indices.filter { it < document.numberOfPages }.sorted()
                target.appendPagesFrom(kept.map { document.getPage(it) })
                document.sameProtection(password)?.let(target::protect)
                saveAndFlush(context, target, output)
                kept.size
            }
        }
    }
}

/**
 * Writes [input] (opened with [unlockPassword] if it has one) to [output] encrypted with AES-256, [newPassword] serving as both user and
 * owner password. Throws on failure; the password may also be rejected with IllegalArgumentException by SASLprep.
 */
suspend fun protectPdf(context: Context, input: Uri, output: Uri, unlockPassword: String?, newPassword: String) = withContext(Dispatchers.IO) {
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, unlockPassword.orEmpty()).use { document ->
            document.protect(StandardProtectionPolicy(newPassword, newPassword, AccessPermission()).apply { encryptionKeyLength = 256 })
            saveAndFlush(context, document, output)
        }
    }
}
