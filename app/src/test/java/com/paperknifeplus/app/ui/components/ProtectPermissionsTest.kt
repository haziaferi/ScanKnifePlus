package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProtectPermissionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun protect(options: ProtectOptions, password: String = "open"): File {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "perm-in.pdf")
        val output = File(context.cacheDir, "perm-out.pdf").apply { delete() }
        runBlocking { protectPdf(context, input, Uri.fromFile(output), null, password, options) }
        return output
    }

    /** The permissions a reader opening [file] with [password] gets. */
    private fun permissions(file: File, password: String): AccessPermission = PDDocument.load(file, password).use { it.currentAccessPermission }

    @Test
    fun `without options the open password is also the owner password and nothing is restricted`() {
        val output = protect(ProtectOptions())
        PDDocument.load(output, "open").use { doc ->
            assertTrue(doc.currentAccessPermission.isOwnerPermission)
            assertEquals(6, doc.encryption.revision)
        }
    }

    @Test
    fun `each block option takes away its permissions and keeps the others`() {
        val printing = permissions(protect(ProtectOptions(blockPrinting = true)), "open")
        assertFalse(printing.isOwnerPermission)
        assertFalse(printing.canPrint())
        assertFalse(printing.canPrintFaithful())
        assertTrue(printing.canExtractContent())
        assertTrue(printing.canModify())
        assertTrue(printing.canModifyAnnotations())

        val copying = permissions(protect(ProtectOptions(blockCopying = true)), "open")
        assertFalse(copying.canExtractContent())
        assertTrue(copying.canExtractForAccessibility())
        assertTrue(copying.canPrint())

        val modification = permissions(protect(ProtectOptions(blockModification = true)), "open")
        assertFalse(modification.canModify())
        assertFalse(modification.canAssembleDocument())
        assertTrue(modification.canFillInForm())

        val annotations = permissions(protect(ProtectOptions(blockAnnotations = true)), "open")
        assertFalse(annotations.canModifyAnnotations())
        assertFalse(annotations.canFillInForm())
        assertTrue(annotations.canModify())
    }

    @Test
    fun `a separate owner password lifts the restrictions`() {
        val output = protect(ProtectOptions(ownerPassword = "owner", blockPrinting = true, blockCopying = true))
        assertFalse(permissions(output, "open").canPrint())
        assertTrue(permissions(output, "owner").isOwnerPermission)
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output, "wrong").close() }
    }

    @Test
    fun `restrictions without an owner password get a random one, so the open password never lifts them`() {
        val policy = protectionPolicy("open", ProtectOptions(blockModification = true))
        assertTrue(policy.ownerPassword.isNotEmpty())
        assertTrue(policy.ownerPassword != "open")
        assertEquals(256, policy.encryptionKeyLength)
        assertFalse(permissions(protect(ProtectOptions(blockModification = true)), "open").isOwnerPermission)
    }

    @Test
    fun `an owner password equal to the open password is refused when anything is restricted`() {
        assertThrows(IllegalArgumentException::class.java) { protectionPolicy("same", ProtectOptions(ownerPassword = "same", blockPrinting = true)) }
        assertEquals("same", protectionPolicy("same", ProtectOptions(ownerPassword = "same")).ownerPassword)
    }
}
