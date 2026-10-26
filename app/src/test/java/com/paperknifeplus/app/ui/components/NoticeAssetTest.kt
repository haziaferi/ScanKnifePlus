package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NoticeAssetTest {
    /** The repository root: the nearest folder up from the test's working directory that holds NOTICE.md and settings.gradle.kts. */
    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
        .first { File(it, NOTICE_ASSET).isFile && File(it, "settings.gradle.kts").isFile }

    private val assetsDir = File(repoRoot, "app/src/main/assets")

    @Test
    fun `the bundled NOTICE md and NOTICES files equal the repository copies`() {
        assertArrayEquals(File(repoRoot, NOTICE_ASSET).readBytes(), File(assetsDir, NOTICE_ASSET).readBytes())
        val repoNotices = File(repoRoot, NOTICES_ASSET_DIR).listFiles().orEmpty().map { it.name }.sorted()
        val bundled = File(assetsDir, NOTICES_ASSET_DIR).listFiles().orEmpty().map { it.name }.sorted()
        assertTrue(repoNotices.isNotEmpty())
        assertEquals(repoNotices, bundled)
        for (name in repoNotices) {
            assertArrayEquals(name, File(repoRoot, "$NOTICES_ASSET_DIR/$name").readBytes(), File(assetsDir, "$NOTICES_ASSET_DIR/$name").readBytes())
        }
    }

    @Test
    fun `the APK assets load as NOTICE md sections followed by one section per NOTICES file`() {
        val sections = readNotices(ApplicationProvider.getApplicationContext<Context>().assets)
        val titles = sections.map { it.title }
        assertEquals("Notices and credits", titles.first())
        assertTrue("OpenScan license" in titles)
        val noticeFiles = File(repoRoot, NOTICES_ASSET_DIR).list().orEmpty().sorted()
        assertEquals(noticeFiles, titles.takeLast(noticeFiles.size))
        assertTrue(sections.first { it.title == "OpenScan license" }.body.contains("Vijay T S"))
        val paperKnife = sections.first { it.title == "PaperKnife+" }.body
        assertTrue(paperKnife, paperKnife.contains("PaperKnife+ (https://github.com/potatameister/PaperKnifePlus) by potatameister"))
        assertTrue(paperKnife, paperKnife.contains("(see LICENSE)"))
    }

    @Test
    fun `Markdown lines become plain text with links spelled out`() {
        assertEquals("a fork of PaperKnife+ (https://x.test/p) by me", plainMarkdownLine("a fork of [PaperKnife+](https://x.test/p) by me"))
        assertEquals("- Liberation Sans 2.1.5 and Bold too", plainMarkdownLine("- **Liberation Sans** 2.1.5 and __Bold__ *too*"))
        assertEquals("Dart's List.sort in sdk/lib/internal/sort.dart", plainMarkdownLine("Dart's `List.sort` in `sdk/lib/internal/sort.dart`"))
    }

    @Test
    fun `inline code, lone markers and underscores in names stay as written`() {
        assertEquals("keep **this** and [x](y) inside code", plainMarkdownLine("keep `**this** and [x](y)` inside code"))
        assertEquals("bcprov_jdk15to18 and a_b_c", plainMarkdownLine("bcprov_jdk15to18 and a_b_c"))
        assertEquals("2 * 3 * 4", plainMarkdownLine("2 * 3 * 4"))
        assertEquals("an odd ` backtick with bold", plainMarkdownLine("an odd ` backtick with **bold**"))
    }

    @Test
    fun `headings and body lines are plain text but code fences stay verbatim`() {
        val sections = noticeSections("## The `image` package\nsee [site](https://s.test)\n```\n**kept** [as](is)\n```")
        assertEquals(listOf(NoticeSection("The image package", "see site (https://s.test)\n**kept** [as](is)")), sections)
    }

    @Test
    fun `sections split at headings of any level and keep the text under each`() {
        val sections = noticeSections("intro line\n# One\nfirst body\n\n## Two\nsecond body\n#### Three\n")
        assertEquals(
            listOf(NoticeSection("", "intro line"), NoticeSection("One", "first body"), NoticeSection("Two", "second body"), NoticeSection("Three", "")),
            sections
        )
    }

    @Test
    fun `heading-like lines inside code fences stay in the body`() {
        val sections = noticeSections("# Licence\n```\n# not a heading\ntext\n```\n# Next\nbody")
        assertEquals(listOf(NoticeSection("Licence", "# not a heading\ntext"), NoticeSection("Next", "body")), sections)
    }
}
