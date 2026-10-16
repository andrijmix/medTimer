package com.futsch1.medtimer.feature.mcp.server

import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp

/**
 * Mounts [server] at the secret path of [config] using the stateless Streamable HTTP transport.
 *
 * Stateless on purpose: there is no `Mcp-Session-Id` to go stale when the foreground service is restarted, and v1 never pushes
 * notifications from server to client. Each request opens and closes a session on the one shared [server].
 *
 * `allowedOrigins = null` together with explicit `allowedHosts` makes the SDK skip Origin validation (see the SDK's KtorServer.kt); the
 * Host check stays on, so only loopback and the user's tunnel host are served.
 */
fun Application.mcpModule(config: McpServerConfig, server: Server) {
    installSecretPathGate(config.mcpPath)
    mcpStatelessStreamableHttp(
        path = config.mcpPath,
        enableDnsRebindingProtection = true,
        allowedHosts = config.allowedHosts,
        allowedOrigins = null
    ) { server }
}
