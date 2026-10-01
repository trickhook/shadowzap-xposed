package io.github.trickhook.shadowzap.core

import java.io.File
import java.security.MessageDigest

/** SHA-256 helpers. */
internal object Sha256 {
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    fun hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }
}

private fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
