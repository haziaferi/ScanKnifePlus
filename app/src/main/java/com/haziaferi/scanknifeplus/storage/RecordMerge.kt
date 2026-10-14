package com.haziaferi.scanknifeplus.storage

import com.haziaferi.scanknifeplus.scan.library.ScanDocument

/** The outcome of merging two versions of a scan record: the [merged] record, and whether the [loser] holds anything [merged] lacks. */
data class RecordMerge(val merged: ScanDocument, val loser: ScanDocument, val loserLostSomething: Boolean)

/**
 * Merges two versions of one scan's `document.json`: the newer ([ScanDocument.modified]; [first] on a tie) keeps its name, page order and
 * page states, and pages only the other has are appended if all their files are in [present]. The result says whether the loser holds
 * anything the merge dropped, so its record can be kept beside the merged one.
 */
fun mergeRecords(first: ScanDocument, second: ScanDocument, present: Set<String>): RecordMerge {
    val (winner, loser) = if (second.modified > first.modified) second to first else first to second
    val winnerIds = winner.pages.mapTo(HashSet()) { it.id }
    val added = loser.pages.filter { it.id !in winnerIds && it.files.all { f -> f in present } }
    val merged = winner.copy(
        created = minOf(winner.created, loser.created),
        modified = maxOf(winner.modified, loser.modified),
        pages = winner.pages + added,
    )
    // The loser adds nothing if its pages all appear, unchanged and in the same order, in the result (pages it lacks are the winner's news).
    val loserPages = loser.pages.toSet()
    val lost = loser.name != merged.name || merged.pages.filter { it in loserPages } != loser.pages
    return RecordMerge(merged, loser, lost)
}
