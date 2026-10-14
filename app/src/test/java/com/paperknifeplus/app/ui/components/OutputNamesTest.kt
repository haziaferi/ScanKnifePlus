package com.paperknifeplus.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNamesTest {
    private fun utf8Size(name: String) = name.toByteArray(Charsets.UTF_8).size

    @Test
    fun `letters and digits of any script stay`() {
        assertEquals("日本語のメモ-grayscale.pdf", outputFileName("日本語のメモ-grayscale", "pdf"))
        assertEquals("Café notes (draft) #2.zip", outputFileName("Café notes (draft) #2", "zip"))
    }

    @Test
    fun `characters Windows forbids and control characters become underscores`() {
        assertEquals("a_b_c_d_e_f_g_h_i_j.pdf", outputFileName("a<b>c:d\"e/f\\g|h?i*j", "pdf"))
        assertEquals("tab_line_.txt", outputFileName("tab\tline\u0007", "txt"))
    }

    @Test
    fun `trailing dots and spaces are trimmed and an empty name gets a fallback`() {
        assertEquals("report.pdf", outputFileName("  report. . ", "pdf"))
        assertEquals("document.pdf", outputFileName("...", "pdf"))
        assertEquals("document.pdf", outputFileName("", "pdf"))
    }

    @Test
    fun `reserved device names are changed in any case and before an inner dot`() {
        assertEquals("CON_.pdf", outputFileName("CON", "pdf"))
        assertEquals("nul_.pdf", outputFileName("nul", "pdf"))
        assertEquals("Com1_.zip", outputFileName("Com1", "zip"))
        assertEquals("lpt9_.backup.pdf", outputFileName("lpt9.backup", "pdf"))
        assertEquals("aux_.pdf", outputFileName("aux .", "pdf"))
        assertEquals("aux _.txt.pdf", outputFileName("aux .txt", "pdf"))
        assertEquals("COM10.pdf", outputFileName("COM10", "pdf"))
        assertEquals("CONSOLE.pdf", outputFileName("CONSOLE", "pdf"))
        assertEquals("PRN-signed.pdf", outputFileName("PRN-signed", "pdf"))
    }

    @Test
    fun `the name is cut to 255 UTF-8 bytes without splitting a character, keeping the extension`() {
        val cjk = outputFileName("日".repeat(300), "pdf")
        assertTrue(utf8Size(cjk) <= 255)
        assertEquals("日".repeat(83) + ".pdf", cjk)

        val emoji = outputFileName("😀".repeat(100), "pdf")
        assertEquals("😀".repeat(62) + ".pdf", emoji)
        assertTrue(utf8Size(emoji) <= 255)

        val ascii = outputFileName("a".repeat(400), "zip")
        assertEquals(255, utf8Size(ascii))
        assertTrue(ascii.endsWith("a.zip"))
    }
}
