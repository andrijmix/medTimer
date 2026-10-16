package com.futsch1.medtimer.feature.mcp.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.security.MessageDigest

internal const val MAX_REQUEST_BODY_BYTES = 256L * 1024

/**
 * Lets only requests for [expectedPath] reach the MCP routes and answers every other request with an empty 404.
 *
 * The connector is authless from Claude's point of view, so the secret in the path is the only credential. The paths are compared as SHA-256
 * digests via [MessageDigest.isEqual], so neither the content nor the length of the secret leaks through response timing. The gate runs
 * before routing, which makes a wrong path indistinguishable from a path that does not exist at all (including the OAuth discovery
 * paths under `.well-known`, so Claude treats the server as authless).
 */
internal fun Application.installSecretPathGate(expectedPath: String, maxBodyBytes: Long = MAX_REQUEST_BODY_BYTES) {
    val expectedDigest = sha256(expectedPath)
    intercept(ApplicationCallPipeline.Plugins) {
        if (!MessageDigest.isEqual(expectedDigest, sha256(call.request.path()))) {
            call.respond(HttpStatusCode.NotFound)
            finish()
            return@intercept
        }
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > maxBodyBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            finish()
        }
    }
}

private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
