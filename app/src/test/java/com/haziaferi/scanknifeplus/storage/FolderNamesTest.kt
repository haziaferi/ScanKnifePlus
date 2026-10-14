package com.haziaferi.scanknifeplus.storage

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNamesTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `Syncthing conflict copies are recognised with the file they copy`() {
        assertEquals("document.json", FolderNames.conflictOf("document.sync-conflict-20261010-101010-ABCDEFG.json"))
        assertEquals("my.scan.pdf", FolderNames.conflictOf("my.scan.sync-conflict-20261010-101010-Z2Z2Z2Z.pdf"))
        assertEquals("README", FolderNames.conflictOf("README.sync-conflict-20261010-101010-ABCDEFG"))
        assertNull(FolderNames.conflictOf("document.json"))
        assertNull(FolderNames.conflictOf("document.sync-conflict-2026-ABCDEFG.json"))
    }

    @Test
    fun `sync metadata and write leftovers are hidden, the user's files are not`() {
        listOf(".stfolder", ".stversions", ".stignore", ".syncthing.a.pdf.tmp", "~syncthing~a.pdf.tmp", "a.pdf.tmp", "document.json.bak", "")
            .forEach { assertTrue(it, FolderNames.isHidden(it)) }
        listOf("a.pdf", "Scan sources", "notes.txt", "a.lost-20261010-101010.pdf").forEach { assertFalse(it, FolderNames.isHidden(it)) }
    }

    @Test
    fun `lost versions get a fixed suffix before their extension`() {
        val name = FolderNames.lostName("Receipt.pdf", 1_791_637_501_000L, utc)
        assertEquals("Receipt.lost-20261010-130501.pdf", name)
        assertTrue(FolderNames.isLost(name))
        assertTrue(FolderNames.isLost("Receipt.lost-20261010-130501-2.pdf"))
        assertFalse(FolderNames.isLost("my.lost-things.pdf"))
    }

    @Test
    fun `free names count up with a hyphen, never with a provider's brackets`() {
        val taken = setOf("a.pdf", "a-2.pdf")
        assertEquals("a-3.pdf", FolderNames.free("a.pdf") { it in taken })
        assertEquals("b.pdf", FolderNames.free("b.pdf") { it in taken })
        assertEquals("v1.0-2", FolderNames.free("v1.0", isFolder = true) { it == "v1.0" })
    }

    @Test
    fun `only single path segments are safe`() {
        listOf("a", "a b.pdf", ".x").forEach { assertTrue(it, FolderNames.isSafe(it)) }
        listOf("", ".", "..", "a/b", "a\\b").forEach { assertFalse(it, FolderNames.isSafe(it)) }
    }
}
