package com.futsch1.medtimer.feature.mcp.server

import com.futsch1.medtimer.core.domain.model.McpSettings

private val LOOPBACK_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

// The secret is used as a URL path segment, so only URL-safe base64 characters are allowed.
private val SECRET_PATTERN = Regex("[A-Za-z0-9_-]{32,128}")

/**
 * Everything the embedded HTTP server needs to start. Pure and JVM-testable.
 *
 * The server only ever listens on loopback ([HOST]); [publicHost] is the host name of the user's tunnel, which arrives in the `Host` header
 * of proxied requests and therefore has to be allowed explicitly.
 */
data class McpServerConfig(
    val port: Int,
    val secret: String,
    val publicHost: String? = null
) {
    val mcpPath: String
        get() = "${McpSettings.PATH_PREFIX}$secret"

    val allowedHosts: List<String>
        get() = LOOPBACK_HOSTS + listOfNotNull(publicHost?.lowercase())

    val isValid: Boolean
        get() = port in McpSettings.MIN_PORT..McpSettings.MAX_PORT && SECRET_PATTERN.matches(secret)

    // The secret is the only credential protecting the user's medication data: never let a log statement or crash report print it.
    override fun toString(): String = "McpServerConfig(port=$port, secret=<redacted>, publicHost=$publicHost)"

    companion object {
        const val HOST = "127.0.0.1"

        fun from(settings: McpSettings): McpServerConfig =
            McpServerConfig(port = settings.port, secret = settings.secret, publicHost = settings.publicHost)
    }
}
