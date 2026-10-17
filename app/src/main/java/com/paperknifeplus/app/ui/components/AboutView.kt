package com.paperknifeplus.app.ui.components

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** About: the app, how it works and its credits; Open Source Libraries and the licence are sub-pages that Back returns from. */
@Composable
fun AboutView(onBack: () -> Unit, loadNotices: suspend () -> List<NoticeSection> = assetNoticeLoader()) {
    var currentSubPage by remember { mutableStateOf("main") }
    // The arrow and system Back agree: sub-pages return to the main page, which leaves About.
    val handleBack: () -> Unit = {
        if (currentSubPage == "main") onBack() else currentSubPage = "main"
    }
    BackHandler(enabled = currentSubPage != "main", onBack = handleBack)

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(
                    when (currentSubPage) {
                        "libraries" -> R.string.about_libraries_title
                        "license" -> R.string.about_license_title
                        else -> R.string.about_title
                    }
                ),
                subtitle = null,
                showChange = false,
                onBack = handleBack
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (currentSubPage) {
                "main" -> AboutMain(onNavigate = { currentSubPage = it })
                "libraries" -> LibrariesPage(loadNotices)
                "license" -> LicensePage()
            }
        }
    }
}

@Composable
fun AboutMain(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Logo(modifier = Modifier.size(64.dp), partColor = LocalScanPalette.current.tx)
                Spacer(Modifier.height(16.dp))
                Text(appName, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = colors.onBackground)
                Text(stringResource(R.string.about_tagline), fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
        }

        item {
            AboutCard {
                Column(Modifier.padding(20.dp)) {
                    AboutHeading(stringResource(R.string.about_how_title))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_how_body, appName), fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurface)
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = colors.outline)
                    Spacer(Modifier.height(20.dp))
                    BulletBlock(Icons.Filled.Security, stringResource(R.string.about_privacy_title), stringResource(R.string.about_privacy_body))
                    Spacer(Modifier.height(16.dp))
                    BulletBlock(Icons.Filled.Code, stringResource(R.string.about_open_title), stringResource(R.string.about_open_body))
                }
            }
        }

        item { SectionHeader(Icons.Filled.Favorite, stringResource(R.string.about_credits)) }
        item {
            LibraryRow(
                Icons.Filled.Description, "PaperKnife+", "GPL-3.0-or-later",
                "$appName is a fork of PaperKnife+ and keeps its PDF tools.\nBy potatameister; contributors are listed on the project's GitHub page.\n" +
                    "https://github.com/potatameister/PaperKnifePlus"
            )
        }
        item {
            LibraryRow(
                Icons.Filled.DocumentScanner, "OpenScan", "BSD-3-Clause",
                "The document scanner is ported from OpenScan to Kotlin.\nBy Vijay T S and Vikram H (Ethereal Developers); contributors are listed on " +
                    "the project's GitHub page.\nhttps://github.com/ethereal-developers/OpenScan"
            )
        }
        item {
            Text(
                "$appName is an independent fork and is not affiliated with or endorsed by the PaperKnife+ or OpenScan projects or their authors.",
                fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item { SectionHeader(Icons.Filled.Gavel, stringResource(R.string.about_legal)) }
        item {
            AboutCard {
                Column {
                    AboutMenuItem(Icons.Filled.Code, stringResource(R.string.about_libraries), stringResource(R.string.about_libraries_body)) { onNavigate("libraries") }
                    HorizontalDivider(color = colors.outline)
                    AboutMenuItem(Icons.Filled.Description, stringResource(R.string.about_license), stringResource(R.string.about_license_body)) { onNavigate("license") }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.settings_version, appName, appVersionName(context)),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun LibrariesPage(loadNotices: suspend () -> List<NoticeSection> = assetNoticeLoader()) {
    var notices by remember(loadNotices) { mutableStateOf(emptyList<NoticeSection>()) }
    LaunchedEffect(loadNotices) {
        notices = loadNotices()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionHeader(Icons.Filled.Settings, stringResource(R.string.about_core_engine)) }
        item { LibraryRow(Icons.Filled.Description, "PDFBox-Android", "Apache 2.0", "The native heart of our engine") }
        item { LibraryRow(Icons.Filled.Bolt, "Kotlin Coroutines", "Apache 2.0", "Powering our multi-threaded rendering engine") }
        item {
            LibraryRow(
                Icons.Filled.DocumentScanner, "OpenScan", "BSD-3-Clause",
                "Document scanner, ported from OpenScan. Copyright (c) 2021, Vijay T S and Vikram H"
            )
        }

        item { SectionHeader(Icons.Filled.Palette, stringResource(R.string.about_ui_design)) }
        item { LibraryRow(Icons.Filled.TouchApp, "Jetpack Compose", "Apache 2.0", "Modern UI toolkit for native Android") }
        item { LibraryRow(Icons.Filled.Style, "Material 3", "Apache 2.0", "Modern design system implementation") }

        item { SectionHeader(Icons.Filled.Image, stringResource(R.string.about_media)) }
        item { LibraryRow(Icons.Filled.PhotoLibrary, "Coil", "Apache 2.0", "High-performance image loading engine") }
        item { LibraryRow(Icons.Filled.PhotoCamera, "CameraX", "Apache 2.0", "Camera capture for the document scanner") }

        item { SectionHeader(Icons.Filled.Gavel, stringResource(R.string.about_notices)) }
        items(notices) { notice ->
            LibraryRow(Icons.Filled.Gavel, notice.title, "", notice.body)
        }
    }
}

/** One block of the bundled notices, as plain text: a NOTICE.md heading with the text under it, or one whole file from NOTICES/. */
data class NoticeSection(val title: String, val body: String)

internal const val NOTICE_ASSET = "NOTICE.md"
internal const val NOTICES_ASSET_DIR = "NOTICES"

/** The default notice source for the Libraries page: [readNotices] from this app's assets, run on the IO dispatcher. */
@Composable
fun assetNoticeLoader(): suspend () -> List<NoticeSection> {
    val assets = LocalContext.current.assets
    return remember(assets) { { withContext(Dispatchers.IO) { readNotices(assets) } } }
}

/** Reads NOTICE.md and NOTICES/ from the APK assets (copies of the repo files, kept in sync by NoticeAssetTest). Blocking; empty if unreadable. */
internal fun readNotices(assets: AssetManager): List<NoticeSection> = try {
    val notice = assets.open(NOTICE_ASSET).bufferedReader().use { it.readText() }
    val files = assets.list(NOTICES_ASSET_DIR).orEmpty().sorted().map { name ->
        NoticeSection(name, assets.open("$NOTICES_ASSET_DIR/$name").bufferedReader().use { it.readText() }.trim())
    }
    noticeSections(notice) + files
} catch (e: IOException) {
    emptyList()
}

/**
 * Splits Markdown at its headings, so each section becomes one row, and turns each line into plain text with [plainMarkdownLine].
 * Lines inside ``` code blocks (licence texts) stay verbatim, heading-like ones included.
 */
internal fun noticeSections(markdown: String): List<NoticeSection> {
    val sections = mutableListOf<NoticeSection>()
    var title = ""
    val body = StringBuilder()
    var inFence = false
    fun flush() {
        if (title.isNotEmpty() || body.isNotBlank()) sections += NoticeSection(title, body.toString().trim())
        body.clear()
    }
    for (line in markdown.lines()) {
        when {
            line.startsWith("```") -> inFence = !inFence
            inFence -> body.appendLine(line)
            line.startsWith("#") -> { flush(); title = plainMarkdownLine(line.trimStart('#').trim()) }
            else -> body.appendLine(plainMarkdownLine(line))
        }
    }
    flush()
    return sections
}

private val markdownLink = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
private val markdownStrong = Regex("""(\*\*|__)(.+?)\1""")
private val markdownEmphasis = Regex("""(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])""")

/**
 * One Markdown line as plain text: [text](url) becomes "text (url)", ** / __ / * emphasis markers go, and inline code loses its backticks
 * with its content kept as written. Single _ emphasis is left alone, since the notices use underscores in file and package names.
 */
internal fun plainMarkdownLine(line: String): String {
    fun plain(text: String) = text.replace(markdownLink, "$1 ($2)").replace(markdownStrong, "$2").replace(markdownEmphasis, "$1")
    val parts = line.split('`')
    if (parts.size % 2 == 0) return plain(line)
    return parts.mapIndexed { i, part -> if (i % 2 == 1) part else plain(part) }.joinToString("")
}

/** A card on the background, as the prototype's: surface, outline, 18 dp corners. */
@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun AboutHeading(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
}

/** An icon and a bold [title] over [body], inside the How it works card. */
@Composable
private fun BulletBlock(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        AboutHeading(title)
    }
    Spacer(Modifier.height(8.dp))
    Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A section heading (16 sp, as Home's "Recent in your folder") with its icon. */
@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
    }
}

/** One credited project or library: icon tile, [name] and its [license] tag, then [description]; text wraps rather than clips. */
@Composable
private fun LibraryRow(icon: ImageVector, name: String, license: String, description: String) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalScanPalette.current
    AboutCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(36.dp).background(colors.surfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colors.onSurface)
                if (license.isNotEmpty()) {
                    Text(
                        "[$license]",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.toolFg,
                        modifier = Modifier.background(palette.toolBg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(description, fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LicensePage() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
    ) {
        item {
            AboutCard {
                Text(
                    """
                    GNU GENERAL PUBLIC LICENSE
                    Version 3, 29 June 2007

                    Copyright (C) 2007 Free Software Foundation, Inc.
                    https://www.gnu.org/licenses/gpl-3.0.html

                    This program is free software: you can redistribute it
                    and/or modify it under the terms of the GNU General Public
                    License as published by the Free Software Foundation,
                    either version 3 of the License
                    any later version.

                    This program is distributed in the hope that it will be
                    useful, but WITHOUT ANY WARRANTY; without even the implied
                    warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
                    PURPOSE. See the GNU General Public License for more details.

                    You should have received a copy of the GNU General Public
                    License along with this program. If not, see
                    https://www.gnu.org/licenses/.
                    """.trimIndent(),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** A row of at least 60 dp that opens a sub-page: icon tile, [title] over [subtitle], and an arrow. */
@Composable
private fun AboutMenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp).background(colors.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = colors.onSurface)
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.onSurface)
            Text(subtitle, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}
