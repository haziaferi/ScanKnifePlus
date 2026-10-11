package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies [src] from another document onto the end of this one, keeping what it inherits from its page tree: [PDDocument.importPage] carries
 * over MediaBox, CropBox and Rotate, and inherited /Resources are set explicitly as PDFBox's Splitter does, so fonts and images survive.
 * Like the Splitter, it cuts every reference back into the source (annotation /P, link targets, article beads), which would otherwise make
 * the saved file carry pages that were meant to be left out. Link annotations therefore lose their in-document target.
 */
fun PDDocument.appendPageFrom(src: PDPage): PDPage {
    val page = importPage(src)
    if (!page.cosObject.containsKey(COSName.RESOURCES)) page.resources = src.resources
    page.cosObject.removeItem(COSName.B)
    for (annotation in page.annotations) {
        if (annotation is PDAnnotationLink) {
            val destination = annotation.destination ?: (annotation.action as? PDActionGoTo)?.destination
            if (destination is PDPageDestination) destination.setPage(null)
        }
        annotation.page = null
    }
    return page
}

/**
 * Writes [input] (opened with [unlockPassword] if it has one) to [output] encrypted with AES-256, [newPassword] serving as both user and
 * owner password. Throws on failure; the password may also be rejected with IllegalArgumentException by SASLprep.
 */
suspend fun protectPdf(context: Context, input: Uri, output: Uri, unlockPassword: String?, newPassword: String) = withContext(Dispatchers.IO) {
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, unlockPassword.orEmpty()).use { document ->
            document.protect(StandardProtectionPolicy(newPassword, newPassword, AccessPermission()).apply { encryptionKeyLength = 256 })
            saveAndFlush(context, document, output)
        }
    }
}
