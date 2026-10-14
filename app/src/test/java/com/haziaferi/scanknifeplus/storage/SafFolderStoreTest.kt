package com.haziaferi.scanknifeplus.storage

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SafFolderStore and FolderAccess over a DocumentsProvider, through the real DocumentsContract calls; and the folder layer on top of them. */
@RunWith(RobolectricTestRunner::class)
class SafFolderStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val resolver = context.contentResolver
    private val treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT)
    private val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private lateinit var f: StorageFixture
    private lateinit var store: SafFolderStore

    @Before
    fun setUp() {
        f = StorageFixture { root ->
            TestDocumentsProvider.install(root)
            SafFolderStore(resolver, treeUri)
        }
        store = f.store as SafFolderStore
        resolver.takePersistableUriPermission(treeUri, readWrite)
    }

    @After
    fun cleanUp() = f.cleanUp()

    @Test
    fun `files are created, written, listed, read, renamed and deleted through the provider`() {
        val folder = store.createFolder(store.rootId, "Scan sources")
        assertTrue(folder.isFolder)
        val tmp = store.createFile(folder.id, "a.pdf.tmp")
        assertEquals("a.pdf.tmp", tmp.name)
        store.openWrite(tmp.id).use { it.write("hello".toByteArray()) }
        val renamed = store.rename(tmp.id, "a.pdf")

        assertEquals("a.pdf", renamed.name)
        assertEquals(5L, renamed.size)
        assertEquals(5L, store.sizeOf(renamed.id))
        assertEquals(listOf("a.pdf"), store.list(folder.id).map { it.name })
        assertEquals("hello", store.openRead(renamed.id).use { String(it.readBytes()) })
        assertEquals(listOf("Scan sources"), store.list(store.rootId).map { it.name })

        store.delete(renamed.id)
        assertTrue(store.list(folder.id).isEmpty())
    }

    @Test
    fun `a taken name is never replaced, the provider's other name comes back`() {
        f.file("a.pdf").writeText("mine")
        assertEquals("a (1).pdf", store.createFile(store.rootId, "a.pdf").name)
        val b = store.createFile(store.rootId, "b.pdf")
        assertEquals("a (2).pdf", store.rename(b.id, "a.pdf").name)
        assertEquals("mine", f.file("a.pdf").readText())
    }

    @Test
    fun `a folder that is gone throws when listed, never lists as empty`() {
        f.folderRoot.deleteRecursively()
        assertThrows(IOException::class.java) { store.list(store.rootId) }
        assertFalse(store.isAvailable())
    }

    @Test
    fun `a folder the provider answers with no children is unreadable if it is gone, empty if it is there`() {
        TestDocumentsProvider.emptyForMissing = true
        val sources = store.createFolder(store.rootId, "Scan sources")
        assertTrue(store.list(sources.id).isEmpty())
        File(f.folderRoot, "Scan sources").delete()
        assertThrows(IOException::class.java) { store.list(sources.id) }
        assertEquals(FolderListing.Unreadable, listingWithSourcesGone())
    }

    private fun listingWithSourcesGone(): FolderListing {
        // The root still lists "Scan sources", but the folder itself is gone when it is listed.
        val stale = object : FolderStore by store {
            override fun list(folderId: String) =
                if (folderId == store.rootId) listOf(FolderEntry("root/Scan sources", "Scan sources", true, null, 0)) else store.list(folderId)
        }
        return ScanFolder(stale, f.library, f.libraryRoot, f.copies).listing()
    }

    @Test
    fun `a provider's own runtime exception comes out as IOException`() {
        TestDocumentsProvider.crash = true
        assertThrows(IOException::class.java) { store.list(store.rootId) }
        assertEquals(FolderListing.Unreadable, f.folder.listing())
    }

    @Test
    fun `the folder is available only while the grant is held`() {
        assertTrue(store.isAvailable())
        resolver.releasePersistableUriPermission(treeUri, readWrite)
        assertFalse(store.isAvailable())
    }

    @Test
    fun `a scan is written back and listed through SAF`() {
        val doc = f.scan("Receipt", pages = 2)
        assertEquals(WriteResult.Written("Receipt", emptyList()), f.folder.writeBack(doc.id, f.pdfs))
        assertEquals("pdf of Receipt with 2 pages", f.file("Receipt.pdf").readText())
        assertEquals(doc.pages.flatMap { it.files }.toSet() + "document.json", f.names("Scan sources/Receipt"))

        val listing = f.folder.listing() as FolderListing.Listed
        assertEquals("Receipt.pdf", listing.scans.single().pdf?.name)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/Receipt.pdf"), store.uri(listing.files.single().id))
    }

    @Test
    fun `adopting a folder persists it, and losing the grant asks for it again`() {
        resolver.releasePersistableUriPermission(treeUri, readWrite)
        val access = FolderAccess(context)
        assertEquals(FolderState.NotChosen, access.state())

        assertTrue(access.adopt(treeUri))
        assertEquals(treeUri, access.treeUri())
        assertTrue(access.state() is FolderState.Ready)

        resolver.releasePersistableUriPermission(treeUri, readWrite)
        assertEquals(FolderState.ChooseAgain, access.state())
    }

    @Test
    fun `adopting a folder releases the previous folder's grant`() {
        val access = FolderAccess(context)
        val other = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "${TestDocumentsProvider.ROOT}/other")
        File(f.folderRoot, "other").mkdirs()
        assertTrue(access.adopt(other))
        assertTrue(access.adopt(treeUri))
        assertEquals(listOf(treeUri), resolver.persistedUriPermissions.map { it.uri })
    }

    @Test
    fun `a folder that cannot be written is not adopted`() {
        resolver.releasePersistableUriPermission(treeUri, readWrite)
        f.folderRoot.deleteRecursively()
        val access = FolderAccess(context)
        assertFalse(access.adopt(treeUri))
        assertEquals(FolderState.NotChosen, access.state())
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `a folder that does not take new files is not adopted`() {
        resolver.releasePersistableUriPermission(treeUri, readWrite)
        TestDocumentsProvider.readOnly = true
        val access = FolderAccess(context)
        assertFalse(access.adopt(treeUri))
        assertEquals(FolderState.NotChosen, access.state())
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `a grant that is only read access counts as no folder to write to`() {
        val access = FolderAccess(context)
        assertTrue(access.adopt(treeUri))
        resolver.releasePersistableUriPermission(treeUri, readWrite)
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        assertFalse(store.isAvailable())
        assertEquals(FolderState.ChooseAgain, access.state())
    }
}
