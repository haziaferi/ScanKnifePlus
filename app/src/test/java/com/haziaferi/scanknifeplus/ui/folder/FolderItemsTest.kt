package com.haziaferi.scanknifeplus.ui.folder

import android.net.Uri
import com.haziaferi.scanknifeplus.storage.FolderFile
import com.haziaferi.scanknifeplus.storage.FolderListing
import com.haziaferi.scanknifeplus.storage.FolderScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderItemsTest {
    private fun uri(id: String) = Uri.parse("content://folder/$id")

    @Test
    fun `History's rows are the folder's files by kind, newest first, with conflict copies tied to their originals`() {
        val lease = FolderFile("f1", "Lease.pdf", 100, 3)
        val listing = FolderListing.Listed(
            files = listOf(FolderFile("f2", "merged.pdf", 200, 5), FolderFile("f3", "Lease.lost-20261014-191200.pdf", 90, 4), lease),
            scans = listOf(FolderScan("Lease", "s1", 3, lease), FolderScan("Draft", "s2", 6, null)),
            conflicts = listOf(FolderFile("f4", "notes.sync-conflict-20261010-101010-ABCDEFG.txt", 5, 1)),
        )
        val items = folderItems(listing, ::uri)

        assertEquals(
            listOf("Draft", "merged.pdf", "Lease.lost-20261014-191200.pdf", "Lease.pdf", "notes.sync-conflict-20261010-101010-ABCDEFG.txt"),
            items.map { it.name }
        )
        assertEquals(listOf(FolderKind.SCAN, FolderKind.TOOL, FolderKind.CONFLICT, FolderKind.SCAN, FolderKind.CONFLICT), items.map { it.kind })
        assertNull("a scan without its PDF opens nothing", items[0].uri)
        assertEquals(uri("f1"), items[2].original!!.uri)
        assertEquals("notes.txt", items[4].originalName)
        assertNull("the original is not in the folder", items[4].original)
        assertEquals("application/pdf", items[1].mime)
    }

    @Test
    fun `a conflict copy's original is its name without the conflict mark`() {
        assertEquals("Lease.pdf", originalOf("Lease.lost-20261014-191200.pdf"))
        assertEquals("Lease.pdf", originalOf("Lease.lost-20261014-191200-2.pdf"))
        assertEquals("document.json", originalOf("document.lost-20261014-191200.json"))
        assertEquals("a.pdf", originalOf("a.sync-conflict-20261010-101010-ABCDEFG.pdf"))
        assertNull(originalOf("Lease.pdf"))
        assertNull(originalOf("lost-and-found.pdf"))
    }
}
