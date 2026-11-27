package com.haziaferi.scanknifeplus.storage

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.SavedFilesProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.w3c.dom.Element

/**
 * The files kept in the app as a location of the system picker (ledger 2026-11-27): a root only while a finished file is kept, local only,
 * every file read only, and nothing else reachable through it.
 */
@RunWith(RobolectricTestRunner::class)
class KeptFilesProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private val authority = KeptFilesProvider.authority(context)
    private val files get() = SavedFiles.of(context)
    private val roots = DocumentsContract.buildRootsUri(authority)
    private val children = DocumentsContract.buildChildDocumentsUri(authority, KeptFilesProvider.ROOT_DOC)

    @Before
    fun setUp() {
        // Made from the manifest's own declaration, which DocumentsProvider checks (exported, grants, MANAGE_DOCUMENTS) as it attaches.
        Robolectric.buildContentProvider(KeptFilesProvider::class.java).create(declared())
    }

    @After
    fun cleanUp() = SavedFilesProvider.forget(context)

    private fun declared() = context.packageManager.resolveContentProvider(authority, 0).also { assertNotNull("declared in the manifest", it) }!!

    private fun keep(name: String, content: String = "pdf $name"): SavedEntry {
        val file = files.create(name)!!.apply { writeText(content) }
        return files.record(files.uriOf(file), file.name, "merge", 1)
    }

    private fun doc(id: String): Uri = DocumentsContract.buildDocumentUri(authority, id)

    private fun rootRows(): List<Map<String, Any?>> = rows(roots)

    private fun rows(uri: Uri): List<Map<String, Any?>> = resolver.query(uri, null, null, null)!!.use { c ->
        buildList {
            while (c.moveToNext()) {
                add(c.columnNames.associateWith { col ->
                    val i = c.getColumnIndexOrThrow(col)
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        else -> c.getString(i)
                    }
                })
            }
        }
    }

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("expected the provider to refuse")
        } catch (e: FileNotFoundException) {
            // refused
        }
    }

    @Test
    fun `with nothing kept there is no root, nor for a save still being written or a file saved elsewhere`() {
        assertEquals(emptyList<Any>(), rootRows())
        files.create("half.pdf")!!.writeText("half")
        val other = File(context.cacheDir, "elsewhere.pdf").apply { writeText("x") }
        files.record(Uri.fromFile(other), other.name, "merge")
        assertEquals(emptyList<Any>(), rootRows())
    }

    @Test
    fun `a kept file brings one root, local only and nothing more`() {
        keep("merged.pdf")
        val root = rootRows().single()
        assertEquals(Root.FLAG_LOCAL_ONLY.toLong(), root[Root.COLUMN_FLAGS])
        assertEquals(KeptFilesProvider.ROOT_ID, root[Root.COLUMN_ROOT_ID])
        assertEquals(KeptFilesProvider.ROOT_DOC, root[Root.COLUMN_DOCUMENT_ID])
        assertEquals("ScanKnife+", root[Root.COLUMN_TITLE])
        assertEquals("Files kept in the app", root[Root.COLUMN_SUMMARY])
        assertEquals("only the kept files' types", "application/pdf", root[Root.COLUMN_MIME_TYPES])
    }

    @Test
    fun `the root lists finished kept files only, held ones included, each with no flags`() {
        val merged = keep("merged.pdf", "12345")
        val held = keep("held.pdf")
        files.hold(held.id)
        val moved = keep("moved.pdf")
        files.moved(moved.id, Uri.parse("content://folder/tree/x/document/moved.pdf"))
        files.create("half.pdf")!!.writeText("half")
        val other = File(context.cacheDir, "elsewhere.pdf").apply { writeText("x") }
        files.record(Uri.fromFile(other), other.name, "merge")

        val listed = rows(children)
        assertEquals(setOf("merged.pdf", "held.pdf"), listed.map { it[Document.COLUMN_DISPLAY_NAME] }.toSet())
        assertTrue(listed.all { it[Document.COLUMN_FLAGS] == 0L })
        val row = listed.single { it[Document.COLUMN_DOCUMENT_ID] == merged.id }
        assertEquals("application/pdf", row[Document.COLUMN_MIME_TYPE])
        assertEquals(5L, row[Document.COLUMN_SIZE])

        val one = rows(doc(merged.id)).single()
        assertEquals(0L, one[Document.COLUMN_FLAGS])
        assertEquals("merged.pdf", one[Document.COLUMN_DISPLAY_NAME])
        val root = rows(doc(KeptFilesProvider.ROOT_DOC)).single()
        assertEquals(Document.MIME_TYPE_DIR, root[Document.COLUMN_MIME_TYPE])
        assertEquals("the root takes no new files", 0L, root[Document.COLUMN_FLAGS])
    }

    @Test
    fun `a kept file opens for reading and for nothing else`() {
        val merged = keep("merged.pdf", "the merged bytes")
        val uri = doc(merged.id)
        assertEquals("the merged bytes", resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
        for (mode in listOf("w", "rw", "wt", "rwt")) refused { resolver.openFileDescriptor(uri, mode)!!.close() }
        assertEquals("the merged bytes", File(files.dir, merged.file!!).readText())
    }

    @Test
    fun `a path out of the folder, a name not listed and a save still being written are refused`() {
        keep("merged.pdf")
        File(files.dir, "loose.pdf").writeText("not listed")
        File(context.filesDir, "x").writeText("outside")
        files.create("half.pdf")!!.writeText("half")
        // A kept file's own name is not its document id either: ids are its row's UUID, which can't be guessed.
        for (id in listOf("../x", "..", "merged.pdf", "loose.pdf", "half.pdf", "missing.pdf")) {
            refused { resolver.openInputStream(doc(id))!!.close() }
            // A documents provider answers a query it refuses with no cursor.
            assertNull(id, resolver.query(doc(id), null, null, null))
        }
        assertNull(resolver.query(DocumentsContract.buildChildDocumentsUri(authority, "merged.pdf"), null, null, null))
    }

    @Test
    fun `the picker hears each change from the start, so the root comes and goes with the files`() {
        // No query first: the system picker caches the roots, so the provider must tell it of the first kept file unasked.
        val shadow = shadowOf(resolver)
        fun heard(from: Int) = shadow.notifiedUris.drop(from).map { it.uri }.toSet()
        var before = shadow.notifiedUris.size
        val merged = keep("merged.pdf")
        assertTrue(heard(before).containsAll(setOf(roots, children)))
        assertEquals(1, rootRows().size)
        before = shadow.notifiedUris.size
        files.create("next.pdf")!!.writeText("half")
        assertTrue("a save still being written changes nothing the picker shows", heard(before).intersect(setOf(roots, children)).isEmpty())
        before = shadow.notifiedUris.size
        assertTrue(files.delete(merged.id))
        assertTrue(heard(before).containsAll(setOf(roots, children)))
        assertEquals(emptyList<Any>(), rootRows())
    }

    @Test
    fun `a later file of the same name is a new document, so a grant for the first never opens it`() {
        val first = keep("report-compressed.pdf", "first")
        val firstUri = doc(first.id)
        assertEquals("first", resolver.openInputStream(firstUri)!!.use { it.readBytes().decodeToString() })
        assertTrue(files.delete(first.id))
        val second = keep("report-compressed.pdf", "second")
        assertEquals("the name is free again", first.file, second.file)
        assertTrue(first.id != second.id)
        refused { resolver.openInputStream(firstUri)!!.close() }
        assertEquals("second", resolver.openInputStream(doc(second.id))!!.use { it.readBytes().decodeToString() })
    }

    @Test
    fun `a file moved out since it was picked is refused without its id in the message a tool would show`() {
        val merged = keep("merged.pdf")
        val uri = doc(merged.id)
        assertTrue(files.delete(merged.id))
        val e = try {
            resolver.openInputStream(uri)!!.close()
            null
        } catch (e: FileNotFoundException) {
            e
        }
        assertNotNull(e)
        assertTrue(e!!.message.orEmpty(), merged.id !in e.message.orEmpty())
    }

    @Test
    fun `the root offers only the kept files' types, so an image picker leaves it out`() {
        keep("merged.pdf")
        keep("pages.zip")
        assertEquals("application/pdf\napplication/zip", rootRows().single()[Root.COLUMN_MIME_TYPES])
    }

    @Test
    fun `the manifest declares the provider for the system picker, guarded by MANAGE_DOCUMENTS`() {
        val info = declared()
        assertEquals(KeptFilesProvider::class.java.name, info.name)
        assertTrue(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, info.readPermission)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, info.writePermission)

        // The merged manifest Robolectric runs on: the provider answers the system picker's DOCUMENTS_PROVIDER intent.
        val config = Properties().apply {
            KeptFilesProviderTest::class.java.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load)
        }
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(config.getProperty("android_merged_manifest")))
        val ns = "http://schemas.android.com/apk/res/android"
        val providers = manifest.getElementsByTagName("provider")
        val provider = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == KeptFilesProvider::class.java.name }
        assertEquals(authority, provider.getAttributeNS(ns, "authorities"))
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, provider.getAttributeNS(ns, "permission"))
        val actions = provider.getElementsByTagName("action")
        val names = (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ns, "name") }
        assertEquals(listOf(DocumentsContract.PROVIDER_INTERFACE), names)
    }
}
