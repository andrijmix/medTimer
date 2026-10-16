package com.futsch1.medtimer.core.datastore

import java.security.SecureRandom
import java.util.Base64

private const val SECRET_BYTES = 32

/**
 * Generates the secret path segment of the MCP connector URL: 256 random bits, URL-safe base64 without padding (43 characters).
 */
object McpSecretGenerator {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
