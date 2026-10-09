package com.haziaferi.scanknifeplus.scanner

import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

/** One case of a parity fixture produced by tools/parity from OpenScan's original Dart code. */
class ParityCase(val name: String, private val values: Map<String, String>) {
    fun has(key: String): Boolean = key in values

    fun raw(key: String): String = values[key] ?: error("$name: missing key '$key'")

    fun int(key: String): Int = raw(key).toInt()

    fun double(key: String): Double = raw(key).toDouble()

    /** An input buffer (`b64:` or `gz:`). */
    fun bytes(key: String): ByteArray {
        val v = raw(key)
        return when {
            v.startsWith("b64:") -> Base64.getDecoder().decode(v.removePrefix("b64:"))
            v.startsWith("gz:") -> GZIPInputStream(Base64.getDecoder().decode(v.removePrefix("gz:")).inputStream()).readBytes()
            else -> error("$name: '$key' is not an input buffer")
        }
    }

    /** Asserts [actual] matches the expected buffer, stored either whole (`b64:`) or as a digest (`sha256:`). */
    fun assertBytes(key: String, actual: ByteArray) {
        val v = raw(key)
        when {
            v.startsWith("b64:") -> assertArrayEquals("$name: $key", Base64.getDecoder().decode(v.removePrefix("b64:")), actual)
            v.startsWith("sha256:") -> assertEquals("$name: $key", v.removePrefix("sha256:"), sha256(actual))
            else -> error("$name: '$key' is not an output buffer")
        }
    }

    override fun toString(): String = name

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

object ParityFixture {
    /** Loads `parity/<fileName>` from test resources: `case<TAB>name` starts a case, then `key<TAB>value` lines. */
    fun load(fileName: String): List<ParityCase> {
        val text = ParityFixture::class.java.getResourceAsStream("/parity/$fileName")?.bufferedReader()?.readText()
            ?: error("missing fixture parity/$fileName; run tools/parity/gen.sh")
        val cases = mutableListOf<ParityCase>()
        var name: String? = null
        var values = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            val key = line.substring(0, tab)
            val value = line.substring(tab + 1)
            if (key == "case") {
                name?.let { cases += ParityCase(it, values) }
                name = value
                values = LinkedHashMap()
            } else {
                values[key] = value
            }
        }
        name?.let { cases += ParityCase(it, values) }
        return cases
    }
}
