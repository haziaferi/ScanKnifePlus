package com.haziaferi.scanknifeplus.storage

import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import com.haziaferi.scanknifeplus.scan.library.ScanPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordMergeTest {
    private fun page(id: String) = ScanPage(id = id, image = "$id.jpg")
    private fun doc(modified: Long, name: String?, vararg pages: ScanPage) = ScanDocument("d", name, 1, modified, pages.toList())

    @Test
    fun `the newer record wins and the other's extra pages are appended`() {
        val a = page("a")
        val b = page("b")
        val c = page("c")
        val merge = mergeRecords(doc(1, "old", a, b), doc(2, "new", b, c), setOf("a.jpg", "b.jpg", "c.jpg"))
        assertEquals("new", merge.merged.name)
        assertEquals(listOf(b, c, a), merge.merged.pages)
        assertEquals(2, merge.merged.modified)
        assertEquals("old", merge.loser.name)
        assertTrue(merge.loserLostSomething)
    }

    @Test
    fun `a loser whose pages all survive in order loses nothing`() {
        val a = page("a")
        val b = page("b")
        val merge = mergeRecords(doc(1, "n", a), doc(2, "n", a, b), setOf("a.jpg", "b.jpg"))
        assertEquals(listOf(a, b), merge.merged.pages)
        assertFalse(merge.loserLostSomething)
    }

    @Test
    fun `pages whose files are missing are not merged in, and their record is kept`() {
        val a = page("a")
        val merge = mergeRecords(doc(2, "n", a), doc(1, "n", a, page("b")), setOf("a.jpg"))
        assertEquals(listOf(a), merge.merged.pages)
        assertTrue(merge.loserLostSomething)
    }

    @Test
    fun `a page edited on both sides keeps the winner's state, the loser's being kept`() {
        val mine = page("a").copy(filter = "Grey")
        val theirs = page("a").copy(filter = "BW")
        val merge = mergeRecords(doc(1, "n", theirs), doc(2, "n", mine), setOf("a.jpg"))
        assertEquals(listOf(mine), merge.merged.pages)
        assertTrue(merge.loserLostSomething)
    }
}
