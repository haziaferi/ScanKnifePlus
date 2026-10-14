package com.haziaferi.scanknifeplus.storage

import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Cases where a slip would lose the user's edits or touch the wrong scan: edits during a write, a change of folder, stale copies, odd providers. */
@RunWith(RobolectricTestRunner::class)
class StorageSafetyTest {
    private val f = StorageFixture()
    private val folder = f.folder

    @After
    fun cleanUp() = f.cleanUp()

    private fun record(path: String): ScanDocument = ScanDocument.fromJson(JSONObject(f.file(path).readText()))

    @Test
    fun `an edit made while a write-back runs is never confirmed, so the sweep keeps it`() {
        val doc = f.scan("Receipt", pages = 1)
        val editing = PdfMaker { id, deliver ->
            f.now += 1000
            f.library.rename(id, "Edited meanwhile")
            f.pdfs.make(id, deliver)
        }

        assertTrue(folder.writeBack(doc.id, editing) is WriteResult.Written)

        assertEquals("Receipt", record("Scan sources/Receipt/document.json").name)
        assertEquals(WorkingCopies.sha256(f.file("Scan sources/Receipt/document.json").readBytes()), f.copies.get(doc.id)!!.confirmed)
        assertTrue(folder.hasChanges(doc.id))
        f.copies.sweep(f.library, f.libraryRoot)
        assertEquals("Edited meanwhile", f.library.document(doc.id)!!.name)
    }

    @Test
    fun `after a change of folder a scan is written to the new folder as new, never merged into a scan there`() {
        val doc = f.scan("Receipt", pages = 1)
        assertTrue(folder.writeBack(doc.id, f.pdfs) is WriteResult.Written)
        val oldFolder = f.folderSnapshot()
        val newRoot = File(f.base, "new folder").apply { mkdirs() }
        File(newRoot, "Scan sources/Receipt").mkdirs()
        File(newRoot, "Scan sources/Receipt/document.json").writeText("""{"theirs":true}""")
        val other = ScanFolder(FileFolderStore(newRoot), f.library, f.libraryRoot, f.copies, clock = { f.now }, timeZone = f.utc)

        assertTrue(other.hasChanges(doc.id))
        val migration = other.migrate(f.pdfs)

        assertEquals(listOf(doc.id), migration.written)
        assertEquals("""{"theirs":true}""", File(newRoot, "Scan sources/Receipt/document.json").readText())
        assertEquals(doc.pages, ScanDocument.fromJson(JSONObject(File(newRoot, "Scan sources/Receipt-2/document.json").readText())).pages)
        assertEquals(oldFolder, f.folderSnapshot())
        assertEquals(CopyInResult.NotFound, other.copyIn("Receipt"))
    }

    @Test
    fun `copy-in refreshes a working copy the folder has moved past`() {
        val doc = f.scan("Receipt", pages = 1)
        assertTrue(folder.writeBack(doc.id) is WriteResult.Written)
        assertEquals(CopyInResult.Ready(doc.id, emptyList()), folder.copyIn("Receipt"))
        val remotePage = f.remoteAddsPage("Receipt", doc)

        assertEquals(CopyInResult.Ready(doc.id, emptyList()), folder.copyIn("Receipt"))

        assertEquals(doc.pages + remotePage, f.library.document(doc.id)!!.pages)
        assertFalse(folder.hasChanges(doc.id))
    }

    @Test
    fun `copy-in merges a working copy with edits of its own the folder has moved past`() {
        val doc = f.scan("Receipt", pages = 1)
        assertTrue(folder.writeBack(doc.id) is WriteResult.Written)
        val remotePage = f.remoteAddsPage("Receipt", doc)
        f.now += 1000
        f.library.rename(doc.id, "Mine")

        val ready = folder.copyIn("Receipt") as CopyInResult.Ready

        assertEquals(doc.id, ready.id)
        assertEquals("Mine", f.library.document(doc.id)!!.name)
        assertEquals(doc.pages + remotePage, f.library.document(doc.id)!!.pages)
        assertEquals("Mine", record("Scan sources/Receipt/document.json").name)
    }

    @Test
    fun `a provider that reports no sizes still gets verified writes`() {
        val sizeless = object : FolderStore by f.store {
            override fun list(folderId: String) = f.store.list(folderId).map { it.copy(size = null) }

            override fun createFile(parentId: String, name: String) = f.store.createFile(parentId, name).copy(size = null)

            override fun rename(id: String, name: String) = f.store.rename(id, name).copy(size = null)
        }
        val scans = ScanFolder(sizeless, f.library, f.libraryRoot, f.copies, clock = { f.now }, timeZone = f.utc)
        val doc = f.scan("Receipt", pages = 2)

        assertTrue(scans.writeBack(doc.id, f.pdfs) is WriteResult.Written)
        f.library.delete(doc.id)
        f.copies.remove(doc.id)
        val ready = scans.copyIn("Receipt") as CopyInResult.Ready
        assertEquals(doc.pages, f.library.document(ready.id)!!.pages)
    }

    @Test
    fun `a short write is caught before it gets its name`() {
        val short = object : FolderStore by f.store {
            override fun sizeOf(id: String) = f.store.sizeOf(id) - 1
        }
        val scans = ScanFolder(short, f.library, f.libraryRoot, f.copies, clock = { f.now }, timeZone = f.utc)
        assertEquals(WriteResult.Failed, scans.copyOutput("a.pdf") { "data".byteInputStream() })
        assertTrue(f.names().isEmpty())
    }

    @Test
    fun `the launch sweep keeps a working copy whose record cannot be read, and its binding`() {
        val doc = f.scan("Receipt", pages = 1)
        assertTrue(folder.writeBack(doc.id) is WriteResult.Written)
        f.library.file(doc.id, "document.json").writeText("{ damaged")

        f.copies.sweep(f.library, f.libraryRoot)

        assertTrue(File(f.libraryRoot, doc.id).isDirectory)
        assertNotNull(f.copies.get(doc.id))
    }

    @Test
    fun `the library imports a staged document into a folder it claims, never one that exists`() {
        File(f.libraryRoot, "Receipt").mkdirs() // left by someone else, empty
        val staged = File(f.base, "staged").apply { mkdirs() }
        File(staged, "1.jpg").writeText("page")
        val doc = ScanDocument("x", "Receipt", 1, 2, listOf(com.haziaferi.scanknifeplus.scan.library.ScanPage("p1", "1.jpg")))

        val stored = f.library.import(staged, "Receipt", doc)

        assertEquals("Receipt-2", stored.document.id)
        assertTrue(File(f.libraryRoot, "Receipt").list()!!.isEmpty())
        assertEquals("page", f.library.file("Receipt-2", "1.jpg").readText())
        assertTrue(stored.bytes.contentEquals(f.library.file("Receipt-2", "document.json").readBytes()))
    }

    @Test
    fun `the library replaces a document only if it is still the version expected`() {
        val doc = f.scan("Receipt", pages = 1)
        val before = f.library.stored(doc.id)!!
        f.now += 1000
        f.library.rename(doc.id, "Edited")
        val staged = File(f.base, "staged").apply { mkdirs() }

        assertNull(f.library.replace(doc.id, before.bytes, staged, doc.copy(name = "Stale")))
        assertEquals("Edited", f.library.document(doc.id)!!.name)
        assertNotEquals(null, f.library.replace(doc.id, f.library.stored(doc.id)!!.bytes, staged, doc.copy(name = "Fresh")))
        assertEquals("Fresh", f.library.document(doc.id)!!.name)
    }
}
