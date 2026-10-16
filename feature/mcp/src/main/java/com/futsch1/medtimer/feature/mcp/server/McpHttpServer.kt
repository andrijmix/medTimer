package com.futsch1.medtimer.feature.mcp.server

import android.util.Log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val STOP_GRACE_PERIOD_MILLIS = 100L
private const val STOP_TIMEOUT_MILLIS = 500L
private const val LOG_TAG = "MedTimerMcp"

/**
 * Owns the embedded Ktor (CIO) server. Always listens on loopback only; the user's tunnel forwards public HTTPS traffic to it.
 */
@Singleton
class McpHttpServer @Inject constructor() {
    private var engine: EmbeddedServer<*, *>? = null

    // Ktor runs the engine's startup and its connections in coroutines that nothing joins. Without a handler, an exception in one of them
    // (a failed bind because the user picked a port that another app uses, a broken connection) reaches the thread's uncaught exception
    // handler, and on Android that ends the whole app. Only the type is logged: messages could carry request details.
    private val engineScope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            Log.w(LOG_TAG, "MCP server coroutine failed: ${throwable.javaClass.simpleName}")
        }
    )

    /**
     * Binds the port and starts serving [server].
     *
     * @throws java.io.IOException if the port cannot be bound, for example because it is already in use.
     */
    @Synchronized
    fun start(config: McpServerConfig, server: Server) {
        check(engine == null) { "The MCP server is already running" }
        val started = engineScope.embeddedServer(CIO, port = config.port, host = McpServerConfig.HOST) { mcpModule(config, server) }
        try {
            started.start(wait = false)
        } catch (e: CancellationException) {
            // CIO reports a failed bind (typically: port in use) as a cancelled startup job without the underlying BindException. This
            // function is not suspending, so it is not our own coroutine being cancelled; translate it so callers handle it as an IOException.
            stopBestEffort(started)
            throw IOException("Could not start the MCP server on port ${config.port}", e)
        }
        engine = started
    }

    @Synchronized
    fun stop() {
        engine?.stop(STOP_GRACE_PERIOD_MILLIS, STOP_TIMEOUT_MILLIS)
        engine = null
    }

    // Releases what a failed start may have allocated. The start failure is the error worth reporting, so a failure here is not.
    private fun stopBestEffort(failed: EmbeddedServer<*, *>) {
        try {
            failed.stop(STOP_GRACE_PERIOD_MILLIS, STOP_TIMEOUT_MILLIS)
        } catch (_: Exception) {
            // Intentionally ignored: see above.
        }
    }
}
