package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/**
 * Copies [src] from another document onto the end of this one, as [appendPagesFrom] does for a single page: links on it to itself keep
 * working, links to any other page of the source are dropped.
 */
fun PDDocument.appendPageFrom(src: PDPage): PDPage = appendPagesFrom(listOf(src)).single()

/**
 * Copies [pages] of another document, in order, onto the end of this one and returns the copies. [PDDocument.importPage] carries over
 * MediaBox, CropBox and Rotate, and inherited /Resources are set explicitly as PDFBox's Splitter does, so fonts and images survive.
 * References back into the source would make the saved file carry pages that were left out, so they are cut: annotation /P and article
 * beads go, link targets on a copied page are moved to its copy, and links to any other page or to a named destination are dropped.
 */
fun PDDocument.appendPagesFrom(pages: List<PDPage>): List<PDPage> {
    val copies = IdentityHashMap<COSDictionary, PDPage>()
    val imported = pages.map { src ->
        importPage(src).also { page ->
            if (!page.cosObject.containsKey(COSName.RESOURCES)) page.resources = src.resources
            page.cosObject.removeItem(COSName.B)
            copies[src.cosObject] = page
        }
    }
    for (page in imported) {
        for (annotation in page.annotations) {
            if (annotation is PDAnnotationLink) relink(annotation, copies)
            annotation.page = null
        }
    }
    return imported
}

/** Points [link] at the copy of its target page, or removes its target when the page was not copied or is named. */
private fun relink(link: PDAnnotationLink, copies: Map<COSDictionary, PDPage>) {
    val action = link.action as? PDActionGoTo
    when (val destination = link.destination ?: action?.destination) {
        is PDPageDestination -> {
            val target = destination.page ?: return // A page number, not an object: nothing in the source is reachable through it.
            destination.setPage(copies[target.cosObject])
        }
        is PDNamedDestination -> if (link.destination != null) link.destination = null else link.action = null
        else -> {}
    }
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
