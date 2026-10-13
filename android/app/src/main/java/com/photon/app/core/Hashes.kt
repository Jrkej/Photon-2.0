package com.photon.app.core

import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Hashes travel as 32 raw bytes in protobuf and as lowercase hex in URLs and in Room. */
object Hashes {
    const val SHA256_BYTES = 32

    fun hex(bytes: ByteString): String = bytes.hex()

    fun bytes(hex: String): ByteString = hex.lowercase().decodeHex()

    fun isValidHex(hex: String): Boolean =
        hex.length == SHA256_BYTES * 2 && hex.all { it in '0'..'9' || it in 'a'..'f' }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toByteString().hex()
    }
}
