package com.paperknifeplus.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextFields
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.components.ToolCategory.CONVERT
import com.paperknifeplus.app.ui.components.ToolCategory.EDIT
import com.paperknifeplus.app.ui.components.ToolCategory.OPTIMIZE
import com.paperknifeplus.app.ui.components.ToolCategory.SECURE

/**
 * Every PDF tool, once: its route id, name, icon, category and screen. The tool list (All tools sheet, the reader's tool sheet) and the
 * shell's tool routing read only from here, so a tool added here appears everywhere and a tool missing here cannot be opened.
 */
object ToolCatalog {
    val tools: List<Tool> = listOf(
        Tool("merge", R.string.tool_merge, R.string.tool_merge_desc, Icons.Outlined.Layers, EDIT) {
            MergeView(initialUri = it.initialUri, initialPassword = it.initialPassword, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("split", R.string.tool_split, R.string.tool_split_desc, Icons.Outlined.ContentCut, EDIT) {
            SplitView(initialUri = it.initialUri, initialPassword = it.initialPassword, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("delete", R.string.tool_delete, R.string.tool_delete_desc, Icons.Outlined.Delete, EDIT) {
            DeleteView(initialUri = it.initialUri, initialPassword = it.initialPassword, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("rearrange", R.string.tool_rearrange, R.string.tool_rearrange_desc, Icons.Outlined.SwapVert, EDIT) {
            RearrangeView(initialUri = it.initialUri, initialPassword = it.initialPassword, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("rotate", R.string.tool_rotate, R.string.tool_rotate_desc, Icons.AutoMirrored.Outlined.RotateRight, EDIT) {
            RotateView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("sign", R.string.tool_sign, R.string.tool_sign_desc, Icons.Outlined.Draw, EDIT) {
            SignView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("watermark", R.string.tool_watermark, R.string.tool_watermark_desc, Icons.Outlined.TextFields, EDIT) {
            WatermarkView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("page-numbers", R.string.tool_page_numbers, R.string.tool_page_numbers_desc, Icons.Outlined.FormatListNumbered, EDIT) {
            PageNumbersView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("bookmarks", R.string.tool_bookmarks, R.string.tool_bookmarks_desc, Icons.Outlined.BookmarkBorder, EDIT) {
            BookmarksView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("compress", R.string.tool_compress, R.string.tool_compress_desc, Icons.Outlined.Bolt, OPTIMIZE) {
            CompressView(initialUri = it.initialUri, initialPassword = it.initialPassword, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("grayscale", R.string.tool_grayscale, R.string.tool_grayscale_desc, Icons.Outlined.Palette, OPTIMIZE) {
            GrayscaleView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("repair", R.string.tool_repair, R.string.tool_repair_desc, Icons.Outlined.Build, OPTIMIZE) {
            RepairView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("compare", R.string.tool_compare, R.string.tool_compare_desc, Icons.Outlined.Compare, OPTIMIZE) {
            CompareView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("protect", R.string.tool_protect, R.string.tool_protect_desc, Icons.Outlined.Lock, SECURE) {
            ProtectView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("unlock", R.string.tool_unlock, R.string.tool_unlock_desc, Icons.Outlined.LockOpen, SECURE) {
            UnlockView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("metadata", R.string.tool_metadata, R.string.tool_metadata_desc, Icons.Outlined.Fingerprint, SECURE) {
            MetadataView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("pdf2img", R.string.tool_pdf2img, R.string.tool_pdf2img_desc, Icons.Outlined.BurstMode, CONVERT) {
            PdfToImageView(initialUri = it.initialUri, onBack = it.onBack)
        },
        // Builds a new PDF from photos, so a pre-loaded PDF does not apply.
        Tool("img2pdf", R.string.tool_img2pdf, R.string.tool_img2pdf_desc, Icons.Outlined.PictureAsPdf, CONVERT) {
            ImageToPdfView(onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("pdf2zip", R.string.tool_pdf2zip, R.string.tool_pdf2zip_desc, Icons.Outlined.FolderZip, CONVERT) {
            PdfToZipView(initialUri = it.initialUri, onBack = it.onBack)
        },
        Tool("extract-images", R.string.tool_extract_images, R.string.tool_extract_images_desc, Icons.Outlined.Collections, CONVERT) {
            ExtractImagesView(initialUri = it.initialUri, onBack = it.onBack, onOpenPreview = it.onOpenPreview)
        },
        Tool("pdf2text", R.string.tool_pdf2text, R.string.tool_pdf2text_desc, Icons.AutoMirrored.Outlined.Article, CONVERT) {
            PdfToTextView(initialUri = it.initialUri, onBack = it.onBack)
        }
    )

    /** The tool routed as [id]; null for any other route (the shell's own screens). */
    fun byId(id: String?): Tool? = tools.firstOrNull { it.id == id }
}
