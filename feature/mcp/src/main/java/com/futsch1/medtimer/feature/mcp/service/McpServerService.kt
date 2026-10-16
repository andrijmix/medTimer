package com.futsch1.medtimer.feature.mcp.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.futsch1.medtimer.core.common.di.Dispatcher
import com.futsch1.medtimer.core.common.di.MedTimerDispatchers
import com.futsch1.medtimer.core.datastore.McpSettingsDataSource
import com.futsch1.medtimer.core.ui.R
import com.futsch1.medtimer.feature.mcp.server.MCP_SERVER_VERSION
import com.futsch1.medtimer.feature.mcp.server.McpHttpServer
import com.futsch1.medtimer.feature.mcp.server.McpServerConfig
import com.futsch1.medtimer.feature.mcp.server.createMcpServer
import com.futsch1.medtimer.feature.mcp.tools.ToolDefinition
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * Foreground service that keeps the MCP server alive while the user has it enabled.
 *
 * It follows [McpSettingsDataSource]: every start with [applyIntent] (re)applies the stored settings and is idempotent, so the settings
 * screen, the boot receiver and the system restarting a sticky service can all simply send it.
 */
@AndroidEntryPoint
class McpServerService : Service() {

    @Inject
    lateinit var settingsDataSource: McpSettingsDataSource

    @Inject
    lateinit var httpServer: McpHttpServer

    @Inject
    lateinit var tools: Set<@JvmSuppressWildcards ToolDefinition>

    @Inject
    @Dispatcher(MedTimerDispatchers.IO)
    lateinit var ioDispatcher: CoroutineDispatcher

    private lateinit var scope: CoroutineScope
    private var runningConfig: McpServerConfig? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settingsDataSource.setEnabled(false)
            shutDown()
            return START_NOT_STICKY
        }
        // A service started with startForegroundService must call startForeground within seconds, before any real work.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Binding a socket is network I/O, which must not run on the main thread.
        scope.launch { applySettings() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Waits for an apply that is already running, so a server it starts is stopped here rather than leaked.
        synchronized(this) {
            httpServer.stop()
            runningConfig = null
        }
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val notification = McpNotifications.build(this, settingsDataSource.settings.value.port)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return try {
            ServiceCompat.startForeground(this, McpNotifications.NOTIFICATION_ID, notification, type)
            true
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) when the system refuses a foreground start from the background.
            false
        }
    }

    @Synchronized
    private fun applySettings() {
        val settings = settingsDataSource.settings.value
        if (!settings.enabled) {
            shutDown()
            return
        }
        val config = McpServerConfig.from(settings.copy(secret = settingsDataSource.ensureSecret()))
        if (!config.isValid) {
            fail(getString(R.string.mcp_error_invalid_settings))
            return
        }
        if (config == runningConfig) {
            return
        }
        httpServer.stop()
        runningConfig = null
        try {
            httpServer.start(config, createMcpServer(tools.toList(), MCP_SERVER_VERSION))
        } catch (_: IOException) {
            fail(getString(R.string.mcp_error_port_in_use, config.port))
            return
        }
        runningConfig = config
        settingsDataSource.setLastError("")
        getSystemService(NotificationManager::class.java).notify(McpNotifications.NOTIFICATION_ID, McpNotifications.build(this, config.port))
    }

    private fun fail(message: String) {
        settingsDataSource.setLastError(message)
        shutDown()
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_APPLY = "com.futsch1.medtimer.feature.mcp.action.APPLY"
        private const val ACTION_STOP = "com.futsch1.medtimer.feature.mcp.action.STOP"

        /** Starts the service (or re-applies the settings if it already runs). Send with `startForegroundService`. */
        internal fun applyIntent(context: Context): Intent = Intent(context, McpServerService::class.java).setAction(ACTION_APPLY)

        /** Disables the server and stops the service. Used by the notification's Stop action. */
        internal fun stopIntent(context: Context): Intent = Intent(context, McpServerService::class.java).setAction(ACTION_STOP)
    }
}
