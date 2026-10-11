package com.paperknifeplus.app.ui.components

import android.util.Log
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One outline item as the Bookmarks screen lists it. [pageIndex] is null when its target cannot be resolved to a page of the document;
 * [original] is its position in the file's outline in pre-order, null for an item added on the screen.
 */
data class Bookmark(
    val title: String,
    val pageIndex: Int?,
    val depth: Int = 0,
    val original: Int? = null
)

/** The outline items of [document] in pre-order with their depth. Each item is visited once, so cyclic /Next or /First chains end. */
private fun outlineItems(document: PDDocument): List<Pair<PDOutlineItem, Int>> {
    val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
    val items = mutableListOf<Pair<PDOutlineItem, Int>>()
    fun walk(first: PDOutlineItem?, depth: Int) {
        var item = first
        while (item != null && visited.add(item.cosObject)) {
            items += item to depth
            walk(item.firstChild, depth + 1)
            item = item.nextSibling
        }
    }
    walk(document.documentCatalog.documentOutline?.firstChild, 0)
    return items
}

/** The page [item] leads to, through a page destination, a GoTo action or a named destination; null if none of [document]'s pages. */
private fun pageIndexOf(item: PDOutlineItem, document: PDDocument): Int? = try {
    item.findDestinationPage(document)?.let { document.pages.indexOf(it) }?.takeIf { it >= 0 }
} catch (e: Exception) {
    // Malformed destinations, or a page number past the end: shown as unresolved.
    Log.w("BookmarkOutline", "Unresolved outline target", e)
    null
}

/** Reads [document]'s outline as a flat pre-order list. */
fun readOutline(document: PDDocument): List<Bookmark> = outlineItems(document).mapIndexed { index, (item, depth) ->
    Bookmark(item.title ?: "", pageIndexOf(item, document), depth, index)
}

/** This list without the item at [index] and the items nested under it (the ones right after it that are deeper). */
fun List<Bookmark>.withoutBranch(index: Int): List<Bookmark> {
    val depth = this[index].depth
    var end = index + 1
    while (end < size && this[end].depth > depth) end++
    return subList(0, index) + subList(end, size)
}

/**
 * Edits [document]'s outline to match [bookmarks], read earlier from the same file: original items missing from the list are removed with
 * their children, items added on the screen are appended at the top level, and every other item (nesting, actions, named destinations,
 * colour, style) is left as it is.
 */
fun applyOutline(document: PDDocument, bookmarks: List<Bookmark>) {
    val kept = bookmarks.mapNotNullTo(HashSet()) { it.original }
    outlineItems(document).forEachIndexed { index, (item, _) -> if (index !in kept) unlink(item.cosObject) }

    val added = bookmarks.filter { it.original == null && it.pageIndex != null }
    val outline = document.documentCatalog.documentOutline ?: PDDocumentOutline().also {
        if (added.isEmpty()) return
        document.documentCatalog.documentOutline = it
    }
    for (bookmark in added) {
        outline.addLast(PDOutlineItem().apply {
            title = bookmark.title
            destination = PDPageFitWidthDestination().apply { page = document.getPage(bookmark.pageIndex!!) }
        })
    }
    recount(outline.cosObject, isRoot = true, Collections.newSetFromMap(IdentityHashMap()))
}

private fun COSDictionary.dict(name: COSName): COSDictionary? = getDictionaryObject(name) as? COSDictionary

private fun COSDictionary.setOrRemove(name: COSName, value: COSDictionary?) {
    if (value != null) setItem(name, value) else removeItem(name)
}

/** Takes [item] (and so its children) out of its parent's child list. */
private fun unlink(item: COSDictionary) {
    val parent = item.dict(COSName.PARENT)
    val prev = item.dict(COSName.PREV)
    val next = item.dict(COSName.NEXT)
    if (prev != null) prev.setOrRemove(COSName.NEXT, next) else parent?.setOrRemove(COSName.FIRST, next)
    if (next != null) next.setOrRemove(COSName.PREV, prev) else parent?.setOrRemove(COSName.LAST, prev)
}

/**
 * Rewrites /Count under [node] after items were removed or added and returns how many items show under it when it is open. Per the PDF
 * spec an open item counts its visible descendants, a closed one the negated number it would show, and the outline root its visible items.
 */
private fun recount(node: COSDictionary, isRoot: Boolean, visited: MutableSet<COSDictionary>): Int {
    var visible = 0
    var child = node.dict(COSName.FIRST)
    while (child != null && visited.add(child)) {
        val childOpen = child.getInt(COSName.COUNT, 0) > 0
        val below = recount(child, isRoot = false, visited)
        visible += 1 + if (childOpen) below else 0
        child = child.dict(COSName.NEXT)
    }
    when {
        isRoot -> node.setInt(COSName.COUNT, visible)
        visible == 0 -> node.removeItem(COSName.COUNT)
        node.getInt(COSName.COUNT, 0) > 0 -> node.setInt(COSName.COUNT, visible)
        node.containsKey(COSName.COUNT) -> node.setInt(COSName.COUNT, -visible)
    }
    return visible
}
