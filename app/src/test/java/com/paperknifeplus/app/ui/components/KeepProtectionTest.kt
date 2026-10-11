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

    @Test
    fun `an encrypted source gives AES-256 output that opens with the same password only`() {
        forEachTool(TestPdfs.userPassword(pages = 3), TestPdfs.USER_PASSWORD) { name, output ->
            assertThrows(name, InvalidPasswordException::class.java) { PDDocument.load(output).close() }
            PDDocument.load(output, TestPdfs.USER_PASSWORD).use { doc ->
                assertTrue(name, doc.isEncrypted)
                assertEquals(name, 5, doc.encryption.version)
                assertEquals(name, 6, doc.encryption.revision)
            }
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

    @Test
    fun `opened with the owner password, the output keeps it as its owner password`() {
        forEachTool(TestPdfs.userPassword(pages = 3), TestPdfs.OWNER_PASSWORD) { name, output ->
            PDDocument.load(output, TestPdfs.OWNER_PASSWORD).use { assertTrue(name, it.currentAccessPermission.isOwnerPermission) }
        }
    }
}
