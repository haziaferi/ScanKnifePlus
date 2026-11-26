package com.haziaferi.scanknifeplus.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The index of saved files and the files kept in the app (ledger L8): unique names, restarts, a damaged index, the grant cap and the checks. */
@RunWith(RobolectricTestRunner::class)
class SavedFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private val base: File = Files.createTempDirectory("saved-files").toFile()
    private val dir = File(base, "saved")
    private val index = File(base, "saved-index.json")
    private var now = 1_000L
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION

    /** A fresh instance over the same folder and index, as after a restart of the app. */
    private fun files() = SavedFiles(dir, index, AUTHORITY, resolver, clock = { now++ })

    /** A file kept in the app named [name], holding [content], listed as [tool]'s output. */
    private fun SavedFiles.keep(name: String, content: String = "pdf $name", tool: String = "merge"): SavedEntry {
        val file = create(name)!!.apply { writeText(content) }
        return record(uriOf(file), file.name, tool, 2)
    }

    /** A file saved elsewhere: a file:// URI outside the app, as a picker's document. */
    private fun elsewhere(name: String): Uri = Uri.fromFile(File(base, "elsewhere/$name").apply { parentFile!!.mkdirs(); writeText(name) })

    private fun held(): Set<Uri> = resolver.persistedUriPermissions.map { it.uri }.toSet()

    @Test
    fun `a second file of the same name gets -2 and never writes over the first`() {
        val files = files()
        val first = files.create("merged.pdf")!!.apply { writeText("first") }
        val second = files.create("merged.pdf")!!
        val third = files.create("MERGED.pdf")!!
        assertEquals("merged.pdf", first.name)
        assertEquals("merged-2.pdf", second.name)
        assertEquals("a name taken in another case is taken", "MERGED-3.pdf", third.name)
        assertEquals("first", first.readText())
        assertEquals(dir, first.parentFile)
    }

    @Test
    fun `a name that could leave the folder stays in it`() {
        val file = files().create("../../evil.pdf")!!
        assertEquals(dir, file.parentFile)
        assertFalse(file.name.startsWith("."))
    }

    @Test
    fun `a file kept in the app is named by a content URI of the FileProvider's saved path, and only those URIs name files`() {
        val files = files()
        val file = files.create("a b.pdf")!!
        val uri = files.uriOf(file)
        assertEquals("content", uri.scheme)
        assertEquals(AUTHORITY, uri.authority)
        assertEquals(listOf("saved", "a b.pdf"), uri.pathSegments)
        assertEquals(file, files.fileOf(uri))
        assertNull(files.fileOf(Uri.parse("content://$AUTHORITY/shared/a.pdf")))
        assertNull(files.fileOf(Uri.parse("content://other/saved/a.pdf")))
        assertNull(files.fileOf(Uri.parse("content://$AUTHORITY/saved/..")))
    }

    @Test
    fun `the list survives a restart, newest first, with where each file lives`() {
        val files = files()
        val app = files.keep("kept.pdf")
        val out = elsewhere("picked.pdf")
        files.record(out, "picked.pdf", "compress", 3)
        val folderDoc = DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri("tree.authority", "root"), "root/x.pdf")
        files.record(folderDoc, "x.pdf", "split", 1)

        val again = files().entries()
        assertEquals(listOf("x.pdf", "picked.pdf", "kept.pdf"), again.map { it.name })
        assertEquals(listOf(SavedPlace.FOLDER, SavedPlace.ELSEWHERE, SavedPlace.APP), again.map { it.place })
        val kept = again.last()
        assertEquals(app.copy(), kept)
        assertEquals("kept.pdf", kept.file)
        assertNull(kept.uri)
        assertEquals("pdf kept.pdf".length.toLong(), kept.size)
        assertEquals(out, again[1].uri)
        assertTrue("the grant of a file saved elsewhere is kept", out in held())
    }

    @Test
    fun `the index is written whole and renamed into place, and one cut before its rename is read`() {
        val files = files()
        files.keep("a.pdf")
        assertTrue(index.isFile)
        assertFalse(File(index.path + ".tmp").exists())
        // A host that cannot rename over a file deletes the old one first; a cut there leaves only the .tmp.
        index.renameTo(File(index.path + ".tmp"))
        assertEquals(listOf("a.pdf"), files().entries().map { it.name })
    }

    @Test
    fun `a damaged index does not crash, the list starts empty, the files stay and are listed again, and it says so`() {
        val files = files()
        files.keep("kept.pdf", "keep me")
        index.writeText("{ not json")

        val after = files()
        assertEquals(emptyList<SavedEntry>(), after.entries())
        assertTrue(after.damaged)
        assertTrue("the damaged index is kept aside", File(index.path + ".bad").readText().startsWith("{ not json"))
        assertEquals("keep me", File(dir, "kept.pdf").readText())

        assertEquals(1, after.recover())
        val listed = after.entries().single()
        assertEquals("kept.pdf", listed.file)
        assertEquals(SavedPlace.APP, listed.place)
        assertEquals(SavedFiles.UNKNOWN_TOOL, listed.tool)
    }

    @Test
    fun `the launch check lists a kept file without its row, skips an empty one, and releases grants no row holds but the folder's`() {
        val files = files()
        File(dir.apply { mkdirs() }, "orphan.pdf").writeText("x")
        File(dir, "empty.pdf").createNewFile()
        val stray = elsewhere("stray.pdf")
        resolver.takePersistableUriPermission(stray, read)
        val tree = DocumentsContract.buildTreeDocumentUri("tree.authority", "root")
        resolver.takePersistableUriPermission(tree, read)
        val listed = elsewhere("listed.pdf")
        files.record(listed, "listed.pdf", "merge")

        assertEquals(1, files.recover())
        assertEquals(setOf("orphan.pdf", "listed.pdf"), files.entries().map { it.name }.toSet())
        assertEquals(setOf(tree, listed), held())
    }

    @Test
    fun `only the newest 100 files saved elsewhere are kept, and the older ones' grants are released`() {
        val files = files()
        val uris = (1..105).map { elsewhere("out$it.pdf").also { uri -> files.record(uri, "out.pdf", "merge") } }
        val kept = files.entries().filter { it.place == SavedPlace.ELSEWHERE }
        assertEquals(100, kept.size)
        assertEquals(uris.drop(5).toSet(), kept.map { it.uri }.toSet())
        assertEquals(uris.drop(5).toSet(), held())
        assertEquals(100, files().entries().size)
    }

    @Test
    fun `files kept in the app are never capped`() {
        val files = files()
        repeat(SavedFiles.ELSEWHERE_CAP + 3) { files.keep("k$it.pdf") }
        assertEquals(SavedFiles.ELSEWHERE_CAP + 3, files.entries().size)
    }

    @Test
    fun `a file saved elsewhere that can no longer be opened leaves the list, with its grant`() {
        val files = files()
        val gone = elsewhere("gone.pdf")
        val there = elsewhere("there.pdf")
        files.record(gone, "gone.pdf", "merge")
        files.record(there, "there.pdf", "merge")
        files.keep("kept.pdf")
        File(gone.path!!).delete()

        assertEquals(1, files.prune())
        assertEquals(setOf("there.pdf", "kept.pdf"), files.entries().map { it.name }.toSet())
        assertEquals(setOf(there), held())
    }

    @Test
    fun `clearing the list drops the files saved elsewhere and their grants, and keeps the files kept in the app`() {
        val files = files()
        val out = elsewhere("picked.pdf")
        files.record(out, "picked.pdf", "merge")
        val kept = files.keep("kept.pdf")
        assertEquals(1, files.clearList())
        assertEquals(listOf(kept), files.entries())
        assertTrue(File(dir, "kept.pdf").isFile)
        assertTrue("the file saved elsewhere stays", File(out.path!!).isFile)
        assertEquals(emptySet<Uri>(), held())
    }

    @Test
    fun `Delete removes a kept file, then its row`() {
        val files = files()
        val kept = files.keep("kept.pdf")
        assertTrue(files.delete(kept.id))
        assertFalse(File(dir, "kept.pdf").exists())
        assertEquals(emptyList<SavedEntry>(), files().entries())
    }

    @Test
    fun `a save that failed discards its file unless a row names it`() {
        val files = files()
        val unfinished = files.create("half.pdf")!!.apply { writeText("half") }
        assertTrue(files.discard(files.uriOf(unfinished)))
        assertFalse(unfinished.exists())

        val kept = files.keep("kept.pdf")
        assertFalse(files.discard(files.uriOf(File(dir, kept.file!!))))
        assertTrue(File(dir, "kept.pdf").isFile)
    }

    @Test
    fun `a file listed again is listed once, as new`() {
        val files = files()
        val file = files.create("a.pdf")!!.apply { writeText("a") }
        val first = files.record(files.uriOf(file), "a.pdf", "merge")
        val second = files.record(files.uriOf(file), "a.pdf", "merge")
        assertEquals(listOf(second), files.entries())
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `a moved file leaves the app before its row says it is in the folder, and a scan's PDF goes with its row`() {
        val files = files()
        val tool = files.keep("tool.pdf")
        val folderUri = Uri.parse("content://tree.authority/tree/root/document/root%2Ftool.pdf")
        files.moved(tool.id, folderUri)
        assertFalse(File(dir, "tool.pdf").exists())
        val row = files.entries().single()
        assertEquals(SavedPlace.FOLDER, row.place)
        assertEquals(folderUri, row.uri)
        assertNull(row.file)

        val scanFile = files.create("scan.pdf")!!.apply { writeText("scan") }
        val scan = files.record(files.uriOf(scanFile), "scan.pdf", "scan", 1, scan = "doc-1")
        assertEquals("doc-1", scan.scan)
        files.moved(scan.id, null)
        assertFalse(scanFile.exists())
        assertEquals(listOf(SavedPlace.FOLDER), files.entries().map { it.place })
    }

    @Test
    fun `listeners hear each change with the whole list`() {
        val files = files()
        val heard = mutableListOf<Int>()
        val stop = files.listen { heard += it.size }
        files.keep("a.pdf")
        files.keep("b.pdf")
        stop()
        files.keep("c.pdf")
        assertEquals(listOf(1, 2), heard)
        assertNotNull(files.entries().firstOrNull())
    }

    private companion object {
        const val AUTHORITY = "com.haziaferi.scanknifeplus.fileprovider"
    }
}
