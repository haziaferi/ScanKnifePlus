package com.haziaferi.scanknifeplus.storage

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The launch sweep removes only private working copies the folder already holds, and private leftovers; never anything in the folder. */
@RunWith(RobolectricTestRunner::class)
class LaunchSweepTest {
    private val f = StorageFixture()

    @After
    fun cleanUp() = f.cleanUp()

    private fun sweep() = f.copies.sweep(f.library, f.libraryRoot)

    @Test
    fun `a working copy confirmed written goes, its scan in the folder stays`() {
        val doc = f.scan("Receipt", pages = 2)
        assertTrue(f.folder.writeBack(doc.id, f.pdfs) is WriteResult.Written)
        val folder = f.folderSnapshot()

        assertEquals(1, sweep())

        assertNull(f.library.document(doc.id))
        assertNull(f.copies.get(doc.id))
        assertEquals(folder, f.folderSnapshot())
    }

    @Test
    fun `a working copy changed since it was written, or never written, stays`() {
        val edited = f.scan("Edited", pages = 1)
        assertTrue(f.folder.writeBack(edited.id) is WriteResult.Written)
        f.now += 1000
        f.library.rename(edited.id, "Edited again")
        val unwritten = f.scan("New", pages = 1)
        val failed = f.scan("Failed", pages = 1)
        f.copies.put(failed.id, Binding(f.store.location, "Failed", base = null, confirmed = null))

        assertEquals(0, sweep())

        assertNotNull(f.library.document(edited.id))
        assertNotNull(f.library.document(unwritten.id))
        assertNotNull(f.library.document(failed.id))
    }

    @Test
    fun `nothing in the user's folder is removed, even files that look like leftovers`() {
        val doc = f.scan("Receipt", pages = 1)
        assertTrue(f.folder.writeBack(doc.id, f.pdfs) is WriteResult.Written)
        listOf("x.pdf.tmp", "Scan sources/Receipt/document.json.tmp", "Scan sources/Receipt/123.jpg", ".import-1/a")
            .forEach { f.file(it).apply { parentFile?.mkdirs() }.writeText("keep") }
        val folder = f.folderSnapshot()

        sweep()

        assertEquals(folder, f.folderSnapshot())
    }

    @Test
    fun `private leftovers of interrupted copy-ins and binding writes go`() {
        File(f.libraryRoot, "${WorkingCopies.IMPORT_PREFIX}x").mkdirs()
        File(f.libraryRoot, "${WorkingCopies.IMPORT_PREFIX}x/1.jpg").writeText("half")
        f.bindingsDir.mkdirs()
        File(f.bindingsDir, "a.json.tmp").writeText("{")

        assertEquals(2, sweep())

        assertTrue(f.libraryRoot.list()!!.isEmpty())
        assertFalse(File(f.bindingsDir, "a.json.tmp").exists())
    }

    @Test
    fun `a binding whose working copy is gone is dropped`() {
        f.copies.put("gone", Binding(f.store.location, "Gone", "a", "b"))
        assertEquals(1, sweep())
        assertNull(f.copies.get("gone"))
    }
}
