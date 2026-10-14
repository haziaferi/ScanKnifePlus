package com.paperknifeplus.app.ui.components

import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.util.UUID

/**
 * What Protect restricts for a reader who opens the file with its open password, and the owner password that lifts the restrictions. The
 * defaults restrict nothing and use the open password as owner password, as Protect always has.
 */
data class ProtectOptions(
    /** A separate owner password; blank uses the open password, or a random one when anything is restricted. */
    val ownerPassword: String = "",
    /** No printing, in low or high quality. */
    val blockPrinting: Boolean = false,
    /** No copying or extracting text and images; extraction for accessibility tools stays allowed. */
    val blockCopying: Boolean = false,
    /** No changes to the content, and no inserting, deleting or rotating pages. */
    val blockModification: Boolean = false,
    /** No adding or changing annotations, and no filling in form fields. */
    val blockAnnotations: Boolean = false
) {
    val restricts: Boolean get() = blockPrinting || blockCopying || blockModification || blockAnnotations
}

/**
 * The AES-256 encryption Protect applies: [userPassword] opens the file, with the permissions [options] leave. Whoever knows the owner
 * password gets every right, so restrictions need an owner password other than [userPassword]: a blank one becomes a random password (the
 * restrictions can then never be lifted), and one equal to [userPassword] is refused with IllegalArgumentException.
 */
internal fun protectionPolicy(userPassword: String, options: ProtectOptions): StandardProtectionPolicy {
    val owner = when {
        options.ownerPassword.isNotEmpty() -> options.ownerPassword
        options.restricts -> UUID.randomUUID().toString()
        else -> userPassword
    }
    require(!options.restricts || owner != userPassword) { "The owner password must differ from the open password" }
    val permissions = AccessPermission().apply {
        if (options.blockPrinting) {
            setCanPrint(false)
            setCanPrintFaithful(false)
        }
        if (options.blockCopying) setCanExtractContent(false)
        if (options.blockModification) {
            setCanModify(false)
            setCanAssembleDocument(false)
        }
        if (options.blockAnnotations) {
            setCanModifyAnnotations(false)
            setCanFillInForm(false)
        }
    }
    return StandardProtectionPolicy(owner, userPassword, permissions).apply { encryptionKeyLength = 256 }
}
