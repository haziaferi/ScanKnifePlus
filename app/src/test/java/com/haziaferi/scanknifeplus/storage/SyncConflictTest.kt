package com.haziaferi.scanknifeplus.storage

import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Syncthing conflict copies of a scan's record and of PDFs: merged or settled, the losing version always kept, each reported once. */
@RunWith(RobolectricTestRunner::class)
class SyncConflictTest {
    private val f = StorageFixture()
    private lateinit var doc: ScanDocument
    private val dir = "Scan sources/Receipt"
    private val copyName = "document.sync-conflict-20261010-101010-ABCDEFG.json"

    @Before
    fun setUp() {
        doc = f.scan("Receipt", pages = 2)
        assertTrue(f.folder.writeBack(doc.id) is WriteResult.Written)
    }

    @After
    fun cleanUp() = f.cleanUp()

    private fun record(name: String = "document.json") = ScanDocument.fromJson(JSONObject(f.file("$dir/$name").readText()))

    private fun lostFiles(path: String) = f.names(path).filter { FolderNames.isLost(it) }

    /** A version from another device with a page of its own, written as the conflict copy. */
    private fun remoteCopy(modified: Long, name: String? = doc.name): ScanDocument {
        val other = f.scan("Other", pages = 1)
        other.pages[0].files.forEach { f.library.file(other.id, it).copyTo(f.file("$dir/$it")) }
        val remote = doc.copy(name = name, modified = modified, pages = doc.pages + other.pages[0])
        f.file("$dir/$copyName").writeText(remote.toJson().toString())
        return remote
    }

    @Test
    fun `a newer conflict copy that only adds pages is merged in and nothing needs keeping`() {
        val remote = remoteCopy(modified = doc.modified + 1000)

        val reports = f.folder.resolveConflicts()

        assertEquals(remote.pages, record().pages)
        assertFalse(f.file("$dir/$copyName").exists())
        assertEquals(listOf(ConflictReport("$dir/document.json", null)), reports)
        assertTrue(lostFiles(dir).isEmpty())
        // Reported once: the copy is gone, so the next pass finds nothing.
        assertTrue(f.folder.resolveConflicts().isEmpty())
    }

    @Test
    fun `an older conflict copy with another name is merged and kept as lost`() {
        val remote = remoteCopy(modified = doc.modified - 1000, name = "Their name")

        val reports = f.folder.resolveConflicts()

        assertEquals("Receipt", record().name)
        assertEquals(remote.pages, record().pages)
        val lost = lostFiles(dir).single()
        assertEquals("document.lost-20261010-000000.json", lost)
        assertEquals("Their name", record(lost).name)
        assertFalse(f.file("$dir/$copyName").exists())
        assertEquals(listOf(ConflictReport("$dir/document.json", lost)), reports)
    }

    @Test
    fun `when the conflict copy wins, the replaced record is kept as lost`() {
        f.now += 5000
        f.library.rename(doc.id, "Mine")
        assertTrue(f.folder.writeBack(doc.id) is WriteResult.Written)
        val mine = record()
        val remote = remoteCopy(modified = mine.modified + 1000, name = "Theirs")

        f.folder.resolveConflicts()

        assertEquals("Theirs", record().name)
        assertEquals(remote.pages, record().pages)
        assertEquals("Mine", record(lostFiles(dir).single()).name)
    }

    @Test
    fun `an unreadable conflict copy is kept as lost and the record left alone`() {
        val before = f.file("$dir/document.json").readText()
        f.file("$dir/$copyName").writeText("garbage")

        val reports = f.folder.resolveConflicts()

        assertEquals(before, f.file("$dir/document.json").readText())
        assertEquals("garbage", f.file("$dir/${lostFiles(dir).single()}").readText())
        assertEquals(1, reports.size)
    }

    @Test
    fun `conflicts are resolved before a write-back reads the record`() {
        val remote = remoteCopy(modified = doc.modified + 1000)
        f.now += 5000
        f.library.rename(doc.id, "Mine")

        val result = f.folder.writeBack(doc.id) as WriteResult.Written

        assertEquals("Mine", record().name)
        assertEquals(remote.pages, record().pages)
        assertEquals(ConflictReport("$dir/document.json", null), result.conflicts.first())
    }

    @Test
    fun `a newer PDF conflict copy takes the name and the older PDF is kept`() {
        f.file("Receipt.pdf").writeText("mine")
        f.file("Receipt.pdf").setLastModified(1_000_000_000_000L)
        val copy = f.file("Receipt.sync-conflict-20261010-101010-ABCDEFG.pdf").apply { writeText("theirs") }
        copy.setLastModified(1_100_000_000_000L)

        val reports = f.folder.resolveConflicts()

        assertEquals("theirs", f.file("Receipt.pdf").readText())
        val lost = lostFiles("").single()
        assertEquals("mine", f.file(lost).readText())
        assertEquals(listOf(ConflictReport("Receipt.pdf", lost)), reports)
        assertFalse(copy.exists())
    }

    @Test
    fun `an older PDF conflict copy is kept as lost`() {
        f.file("Receipt.pdf").writeText("mine")
        f.file("Receipt.pdf").setLastModified(1_100_000_000_000L)
        f.file("Receipt.sync-conflict-20261010-101010-ABCDEFG.pdf").apply { writeText("theirs") }.setLastModified(1_000_000_000_000L)

        f.folder.resolveConflicts()

        assertEquals("mine", f.file("Receipt.pdf").readText())
        assertEquals("theirs", f.file(lostFiles("").single()).readText())
    }

    @Test
    fun `conflict copies of other files are left for the user`() {
        f.file("notes.sync-conflict-20261010-101010-ABCDEFG.txt").writeText("x")
        assertTrue(f.folder.resolveConflicts().isEmpty())
        assertTrue(f.file("notes.sync-conflict-20261010-101010-ABCDEFG.txt").exists())
        assertNull(lostFiles("").firstOrNull())
    }
}
