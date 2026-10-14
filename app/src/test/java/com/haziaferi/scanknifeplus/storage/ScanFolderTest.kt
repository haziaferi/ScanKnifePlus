package com.haziaferi.scanknifeplus.storage

import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import java.io.IOException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The folder layout, write-back, copy-in, migration, tool output copies and History listing, over a plain folder. */
@RunWith(RobolectricTestRunner::class)
class ScanFolderTest {
    private val f = StorageFixture()
    private val folder = f.folder

    @After
    fun cleanUp() = f.cleanUp()

    private fun record(path: String): ScanDocument = ScanDocument.fromJson(JSONObject(f.file(path).readText()))

    private fun written(result: WriteResult): String {
        assertTrue("$result", result is WriteResult.Written)
        return (result as WriteResult.Written).name
    }

    @Test
    fun `a new scan goes to the top level as a PDF and to Scan sources as its files`() {
        val doc = f.scan("Receipt", pages = 2)
        assertEquals("Receipt", written(folder.writeBack(doc.id, f.pdfs)))

        assertEquals(setOf("Receipt.pdf", "Scan sources"), f.names())
        assertEquals("pdf of Receipt with 2 pages", f.file("Receipt.pdf").readText())
        val files = doc.pages.flatMap { it.files }.toSet()
        assertEquals(files + "document.json", f.names("Scan sources/Receipt"))
        files.forEach { assertEquals(f.library.file(doc.id, it).readText(), f.file("Scan sources/Receipt/$it").readText()) }
        assertEquals(doc.pages, record("Scan sources/Receipt/document.json").pages)
        assertFalse(folder.hasChanges(doc.id))
    }

    @Test
    fun `a taken name is never written over`() {
        f.file("Receipt.pdf").writeText("the user's own file")
        f.file("Scan sources/receipt").mkdirs()
        val before = f.folderSnapshot()

        val doc = f.scan("Receipt", pages = 1)
        assertEquals("Receipt-2", written(folder.writeBack(doc.id, f.pdfs)))

        before.forEach { (path, content) -> assertEquals(content, f.file(path).readText()) }
        assertTrue(f.file("Receipt-2.pdf").isFile)
        assertTrue(f.file("Scan sources/Receipt-2/document.json").isFile)
    }

    @Test
    fun `an edit replaces the record and PDF, keeps their previous versions, and removes the pages it made obsolete`() {
        val doc = f.scan("Receipt", pages = 2)
        written(folder.writeBack(doc.id, f.pdfs))
        val dropped = doc.pages[0]
        f.now += 1000
        f.library.deletePage(doc.id, dropped.id)
        assertTrue(folder.hasChanges(doc.id))

        written(folder.writeBack(doc.id, f.pdfs))

        assertEquals(listOf(doc.pages[1]), record("Scan sources/Receipt/document.json").pages)
        assertEquals(doc.pages, record("Scan sources/Receipt/document.json.bak").pages)
        assertEquals("pdf of Receipt with 1 pages", f.file("Receipt.pdf").readText())
        assertEquals("pdf of Receipt with 2 pages", f.file("Receipt.pdf.bak").readText())
        dropped.files.forEach { assertFalse(it, f.file("Scan sources/Receipt/$it").exists()) }
        doc.pages[1].files.forEach { assertTrue(it, f.file("Scan sources/Receipt/$it").exists()) }
        assertTrue(f.names("Scan sources/Receipt").none { it.endsWith(".tmp") })
    }

    @Test
    fun `a record changed in the folder meanwhile is merged, never written over`() {
        val doc = f.scan("Receipt", pages = 1)
        written(folder.writeBack(doc.id, f.pdfs))
        // Another device adds a page to the folder copy.
        val remote = f.scan("Other", pages = 1)
        val remotePage = remote.pages[0]
        remotePage.files.forEach { f.library.file(remote.id, it).copyTo(f.file("Scan sources/Receipt/$it")) }
        f.now += 1000
        val theirs = doc.copy(name = "Receipt", modified = f.now, pages = doc.pages + remotePage)
        f.file("Scan sources/Receipt/document.json").writeText(theirs.toJson().toString())
        // Meanwhile this device renames its working copy, later.
        f.now += 1000
        f.library.rename(doc.id, "Receipt renamed")

        val result = folder.writeBack(doc.id, f.pdfs) as WriteResult.Written

        val merged = record("Scan sources/Receipt/document.json")
        assertEquals("Receipt renamed", merged.name)
        assertEquals(doc.pages + remotePage, merged.pages)
        remotePage.files.forEach { assertTrue(f.file("Scan sources/Receipt/$it").exists()) }
        // The folder's version had a name the merge dropped: it is kept and reported.
        val lost = f.names("Scan sources/Receipt").single { FolderNames.isLost(it) }
        assertEquals("Receipt", record("Scan sources/Receipt/$lost").name)
        assertEquals(listOf(ConflictReport("Scan sources/Receipt/document.json", lost)), result.conflicts)
    }

    @Test
    fun `after a merge the working copy is reloaded with the merged pages, and stops merging`() {
        val doc = f.scan("Receipt", pages = 1)
        written(folder.writeBack(doc.id))
        val remotePage = f.remoteAddsPage("Receipt", doc)
        f.now += 1000
        f.library.rename(doc.id, "Renamed")
        written(folder.writeBack(doc.id))

        assertEquals(doc.pages + remotePage, f.library.document(doc.id)!!.pages)
        remotePage.files.forEach { assertTrue(f.library.file(doc.id, it).exists()) }
        assertFalse(folder.hasChanges(doc.id))
        val lostAfterMerge = f.names("Scan sources/Receipt").filter { FolderNames.isLost(it) }

        f.now += 1000
        f.library.rename(doc.id, "Renamed again")
        written(folder.writeBack(doc.id))

        assertEquals(doc.pages + remotePage, record("Scan sources/Receipt/document.json").pages)
        assertEquals("Renamed again", record("Scan sources/Receipt/document.json").name)
        assertEquals(lostAfterMerge, f.names("Scan sources/Receipt").filter { FolderNames.isLost(it) })
    }

    @Test
    fun `a page deleted after a merge stays deleted`() {
        val doc = f.scan("Receipt", pages = 1)
        written(folder.writeBack(doc.id))
        val remotePage = f.remoteAddsPage("Receipt", doc)
        f.now += 1000
        f.library.rename(doc.id, "Renamed")
        written(folder.writeBack(doc.id))
        f.now += 1000
        f.library.deletePage(doc.id, remotePage.id)

        written(folder.writeBack(doc.id))

        assertEquals(doc.pages, record("Scan sources/Receipt/document.json").pages)
        assertEquals(doc.pages, f.library.document(doc.id)!!.pages)
    }

    @Test
    fun `a folder record this app cannot read is kept, not written over`() {
        val doc = f.scan("Receipt", pages = 1)
        written(folder.writeBack(doc.id))
        f.file("Scan sources/Receipt/document.json").writeText("{ written by a newer version")
        f.now += 1000
        f.library.rename(doc.id, "New")

        val result = folder.writeBack(doc.id) as WriteResult.Written

        val lost = f.names("Scan sources/Receipt").single { FolderNames.isLost(it) }
        assertEquals("{ written by a newer version", f.file("Scan sources/Receipt/$lost").readText())
        assertEquals("New", record("Scan sources/Receipt/document.json").name)
        assertEquals(lost, result.conflicts.single().keptAs)
    }

    @Test
    fun `a page file of the same name but other content stops the write-back`() {
        val doc = f.scan("Receipt", pages = 1)
        val page = doc.pages[0].image
        f.file("Scan sources/Receipt").mkdirs()
        f.file("Scan sources/Receipt/$page").writeText("someone else's longer content")
        f.copies.put(doc.id, Binding(f.store.location, "Receipt", null, null))

        assertEquals(WriteResult.Failed, folder.writeBack(doc.id))
        assertEquals("someone else's longer content", f.file("Scan sources/Receipt/$page").readText())
        assertTrue(folder.hasChanges(doc.id))
    }

    @Test
    fun `an unreachable folder blocks the write and leaves the working copy as it was`() {
        val doc = f.scan("Receipt", pages = 1)
        f.folderRoot.deleteRecursively()

        assertEquals(WriteResult.FolderUnavailable, folder.writeBack(doc.id, f.pdfs))
        assertEquals(WriteResult.FolderUnavailable, folder.copyOutput("out.pdf") { "x".byteInputStream() })
        assertEquals(doc, f.library.document(doc.id))
        assertTrue(folder.hasChanges(doc.id))
    }

    @Test
    fun `copy-in makes a working copy identical to the folder's scan`() {
        val doc = f.scan("Receipt", pages = 2)
        written(folder.writeBack(doc.id))
        f.library.delete(doc.id)
        f.copies.remove(doc.id)

        val ready = folder.copyIn("Receipt") as CopyInResult.Ready

        assertEquals(doc.pages, f.library.document(ready.id)!!.pages)
        doc.pages.flatMap { it.files }.forEach {
            assertEquals(f.file("Scan sources/Receipt/$it").readText(), f.library.file(ready.id, it).readText())
        }
        assertFalse(folder.hasChanges(ready.id))
        assertEquals(ready, folder.copyIn("Receipt"))
        assertTrue(f.libraryRoot.list()!!.none { it.startsWith(WorkingCopies.IMPORT_PREFIX) })
    }

    @Test
    fun `copy-in waits for pages that have not synced yet`() {
        val doc = f.scan("Receipt", pages = 1)
        written(folder.writeBack(doc.id))
        f.library.delete(doc.id)
        f.copies.remove(doc.id)
        val image = doc.pages[0].image
        f.file("Scan sources/Receipt/$image").delete()

        assertEquals(CopyInResult.Incomplete(listOf(image)), folder.copyIn("Receipt"))
        assertEquals(CopyInResult.NotFound, folder.copyIn("Nothing"))
    }

    @Test
    fun `a record interrupted between its renames is read from its complete temp file`() {
        val doc = f.scan("Receipt", pages = 1)
        written(folder.writeBack(doc.id))
        f.library.delete(doc.id)
        f.copies.remove(doc.id)
        f.file("Scan sources/Receipt/document.json").renameTo(f.file("Scan sources/Receipt/document.json.tmp"))

        val ready = folder.copyIn("Receipt") as CopyInResult.Ready
        assertEquals(doc.pages, f.library.document(ready.id)!!.pages)
    }

    @Test
    fun `migration writes every private scan, creating files only, and keeps the private copies`() {
        val a = f.scan("A", pages = 1)
        val b = f.scan("B", pages = 2)
        f.file("A.pdf").writeText("the user's A")
        val before = f.folderSnapshot()

        val migration = folder.migrate(f.pdfs)

        assertEquals(setOf(a.id, b.id), migration.written.toSet())
        assertTrue(migration.failed.isEmpty())
        before.forEach { (path, content) -> assertEquals(content, f.file(path).readText()) }
        assertEquals(setOf("A.pdf", "A-2.pdf", "B.pdf", "Scan sources"), f.names())
        assertEquals(setOf("A-2", "B"), f.names("Scan sources"))
        assertNotNull(f.library.document(a.id))
        assertNotNull(f.library.document(b.id))
        // Run again, nothing is left to move.
        assertEquals(Migration(emptyList(), emptyList(), false), folder.migrate(f.pdfs))
    }

    @Test
    fun `tool outputs are copied to the top level under a free name`() {
        f.file("merged.pdf").writeText("old")
        assertEquals("merged-2.pdf", written(folder.copyOutput("merged.pdf") { "new".byteInputStream() }))
        assertEquals("old", f.file("merged.pdf").readText())
        assertEquals("new", f.file("merged-2.pdf").readText())
        assertEquals(WriteResult.Failed, folder.copyOutput("../escape.pdf") { "x".byteInputStream() })
    }

    @Test
    fun `History lists the folder's files and scans and hides sync and write leftovers`() {
        val doc = f.scan("Receipt", pages = 1)
        written(folder.writeBack(doc.id, f.pdfs))
        written(folder.copyOutput("merged.pdf") { "m".byteInputStream() })
        listOf(".stfolder", ".syncthing.x.pdf.tmp", "~syncthing~y.pdf.tmp", "a.pdf.tmp", "a.pdf.bak").forEach { f.file(it).writeText("") }
        f.file(".stversions").mkdirs()
        f.file("merged.sync-conflict-20261010-101010-ABCDEFG.pdf").writeText("c")

        val listing = folder.listing() as FolderListing.Listed

        assertEquals(setOf("Receipt.pdf", "merged.pdf"), listing.files.map { it.name }.toSet())
        assertEquals(listOf("Receipt"), listing.scans.map { it.name })
        assertEquals("Receipt.pdf", listing.scans.single().pdf?.name)
        assertEquals(listOf("merged.sync-conflict-20261010-101010-ABCDEFG.pdf"), listing.conflicts.map { it.name })
    }

    @Test
    fun `an empty folder lists as empty, and one that cannot be listed as unreadable`() {
        assertEquals(FolderListing.Listed(emptyList(), emptyList(), emptyList()), folder.listing())
        f.folderRoot.deleteRecursively()
        assertEquals(FolderListing.Unreadable, folder.listing())
    }

    @Test
    fun `a Scan sources folder that cannot be listed makes the whole listing unreadable`() {
        f.file("Scan sources").mkdirs()
        val failing = ScanFolder(object : FolderStore by f.store {
            override fun list(folderId: String): List<FolderEntry> =
                if (folderId.isEmpty()) f.store.list(folderId) else throw IOException("provider died")
        }, f.library, f.libraryRoot, f.copies)
        assertEquals(FolderListing.Unreadable, failing.listing())
    }

    @Test
    fun `nothing is copied in for an unsafe or hidden name`() {
        f.file("Scan sources/.hidden").mkdirs()
        listOf("..", "", ".hidden", "a/b").forEach { assertEquals(it, CopyInResult.NotFound, folder.copyIn(it)) }
        assertTrue(f.libraryRoot.list()!!.isEmpty())
    }
}
