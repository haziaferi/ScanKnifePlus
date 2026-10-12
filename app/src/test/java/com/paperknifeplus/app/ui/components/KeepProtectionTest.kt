package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/** Rotate, Split, Rearrange and Delete keep the source's password on what they write. */
@RunWith(RobolectricTestRunner::class)
class KeepProtectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private val tools: Map<String, suspend (Uri, Uri, String) -> Unit> = mapOf(
        "rotate" to { input, output, password -> rotatePages(context, input, output, password, mapOf(0 to 90)) },
        "split" to { input, output, password -> extractPages(context, input, output, password, listOf(0, 1)) },
        "rearrange" to { input, output, password -> rearrangePages(context, input, output, password, listOf(2, 1, 0)) },
        "delete" to { input, output, password -> deletePages(context, input, output, password, setOf(2)) }
    )

    /** Runs every tool on [source] opened with [password] and hands each output file to [check]. */
    private fun forEachTool(source: ByteArray, password: String, check: (String, File) -> Unit) {
        val input = TestPdfs.write(context.cacheDir, source, "protected-in.pdf")
        for ((name, tool) in tools) {
            val output = File(context.cacheDir, "protected-$name.pdf").apply { delete() }
            runBlocking { tool(input, Uri.fromFile(output), password) }
            check(name, output)
        }
    }

    /** A [pages]-page PDF encrypted with [keyLength] bits, opened by [user] or by [TestPdfs.OWNER_PASSWORD]. */
    private fun encrypted(keyLength: Int, user: String = TestPdfs.USER_PASSWORD, pages: Int = 3): ByteArray =
        PDDocument.load(TestPdfs.plain(pages)).use { doc ->
            doc.protect(StandardProtectionPolicy(TestPdfs.OWNER_PASSWORD, user, AccessPermission()).apply { encryptionKeyLength = keyLength })
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    @Test
    fun `an encrypted source gives AES-256 output that opens with the same password only`() {
        for (keyLength in listOf(40, 128, 256)) {
            forEachTool(encrypted(keyLength), TestPdfs.USER_PASSWORD) { name, output ->
                assertThrows(name, InvalidPasswordException::class.java) { PDDocument.load(output).close() }
                PDDocument.load(output, TestPdfs.USER_PASSWORD).use { doc ->
                    assertTrue(name, doc.isEncrypted)
                    assertEquals("$name $keyLength", 6, doc.encryption.revision)
                    assertEquals("$name $keyLength", 256, doc.encryption.length)
                }
            }
        }
    }

    @Test
    fun `a password AES-256 refuses (SASLprep prohibits control characters) keeps the older file's own cipher`() {
        val password = "bell" + Char(7)
        val source = encrypted(128, user = password)
        val (revision, cipher) = PDDocument.load(source, password).use {
            it.encryption.revision to it.encryption.stdCryptFilterDictionary?.cryptFilterMethod
        }
        forEachTool(source, password) { name, output ->
            PDDocument.load(output, password).use { doc ->
                assertEquals(name, revision, doc.encryption.revision)
                assertEquals(name, 128, doc.encryption.length)
                assertEquals(name, cipher, doc.encryption.stdCryptFilterDictionary?.cryptFilterMethod)
            }
        }
    }

    @Test
    fun `opened with the owner password, a revision 2-4 file keeps its own open password and the owner password`() {
        for (keyLength in listOf(40, 128)) {
            forEachTool(encrypted(keyLength), TestPdfs.OWNER_PASSWORD) { name, output ->
                PDDocument.load(output, TestPdfs.USER_PASSWORD).use { assertTrue("$name $keyLength", it.isEncrypted) }
                PDDocument.load(output, TestPdfs.OWNER_PASSWORD).use { assertTrue("$name $keyLength", it.currentAccessPermission.isOwnerPermission) }
            }
        }
    }

    @Test
    fun `opened with the owner password, an AES-256 file cannot give up its open password, so the owner password opens the output`() {
        forEachTool(encrypted(256), TestPdfs.OWNER_PASSWORD) { name, output ->
            assertThrows(name, InvalidPasswordException::class.java) { PDDocument.load(output, TestPdfs.USER_PASSWORD).close() }
            PDDocument.load(output, TestPdfs.OWNER_PASSWORD).use { assertTrue(name, it.currentAccessPermission.isOwnerPermission) }
        }
    }

    @Test
    fun `an unencrypted source stays unencrypted`() {
        forEachTool(TestPdfs.plain(3), "") { name, output -> PDDocument.load(output).use { assertFalse(name, it.isEncrypted) } }
    }

    @Test
    fun `permissions are kept and a password-less open stays password-less`() {
        val restricted = PDDocument.load(TestPdfs.plain(3)).use { doc ->
            val permissions = AccessPermission().apply {
                setCanPrint(false)
                setCanModify(false)
            }
            doc.protect(StandardProtectionPolicy(TestPdfs.OWNER_PASSWORD, "", permissions).apply { encryptionKeyLength = 128 })
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        forEachTool(restricted, "") { name, output ->
            PDDocument.load(output).use { doc ->
                assertTrue(name, doc.isEncrypted)
                assertFalse(name, doc.currentAccessPermission.isOwnerPermission)
                assertFalse(name, doc.currentAccessPermission.canPrint())
                assertFalse(name, doc.currentAccessPermission.canModify())
                assertTrue(name, doc.currentAccessPermission.canExtractContent())
            }
        }
    }
}
