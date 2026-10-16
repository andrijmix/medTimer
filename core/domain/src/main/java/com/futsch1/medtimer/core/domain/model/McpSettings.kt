package com.futsch1.medtimer.core.domain.model

import java.net.URI
import java.net.URISyntaxException

/**
 * Settings of the optional in-app MCP server.
 *
 * Deliberately separate from [UserPreferences]: these values must never end up in a settings backup, because [secret] is the only credential
 * protecting the user's medication data.
 *
 * @property publicBaseUrl the user's tunnel URL (`https://host`), only used to allow its host name in the server's Host header check and to
 * build the connector URL.
 * @property lastError a human-readable reason why the server could not start, empty when there is none.
 */
data class McpSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val publicBaseUrl: String = "",
    val secret: String = "",
    val lastError: String = ""
) {
    /** The host name of the tunnel, or null when no usable [publicBaseUrl] is set. */
    val publicHost: String?
        get() = normalizePublicUrl(publicBaseUrl)?.removePrefix(HTTPS_PREFIX)

    /** The URL to paste into Claude's "Add custom connector" dialog, or null while the tunnel URL or the secret is missing. */
    val connectorUrl: String?
        get() = publicHost?.takeIf { secret.isNotEmpty() }?.let { "$HTTPS_PREFIX$it$PATH_PREFIX$secret" }

    /** The URL on the phone itself, for testing through `adb forward`. */
    val localUrl: String?
        get() = secret.takeIf { it.isNotEmpty() }?.let { "http://127.0.0.1:$port$PATH_PREFIX$it" }

    override fun toString(): String =
        "McpSettings(enabled=$enabled, port=$port, publicBaseUrl=$publicBaseUrl, secret=<redacted>, lastError=$lastError)"

    companion object {
        const val DEFAULT_PORT = 8765
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** Every request must start with this path, followed by the secret. */
        const val PATH_PREFIX = "/mcp/"

        private const val HTTPS_PREFIX = "https://"

        /** Parses a port typed by the user; null when it is not a number or outside [MIN_PORT]..[MAX_PORT]. */
        fun parsePort(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }

        /**
         * Reduces what the user typed to `https://host`, dropping port, path, query and fragment. A missing scheme is taken to be https.
         * Returns null for anything else that cannot be the address of a tunnel: other schemes, credentials in the URL, no valid host.
         */
        fun normalizePublicUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            val uri = try {
                URI(if (trimmed.contains("://")) trimmed else "$HTTPS_PREFIX$trimmed")
            } catch (_: URISyntaxException) {
                return null
            }
            val host = uri.host?.takeIf { it.isNotEmpty() }
            if (host == null || !uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) {
                return null
            }
            return "$HTTPS_PREFIX${host.lowercase()}"
        }
    }
}
