package com.futsch1.medtimer.feature.mcp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.futsch1.medtimer.core.datastore.McpSettingsDataSource

/**
 * Brings the MCP server back after a reboot or an app update, if the user left it enabled.
 *
 * Deliberately not a Hilt entry point: it reads the one flag it needs straight from the settings file. Hilt test applications are not
 * initialized yet when a package-replaced broadcast arrives (which is why the debug manifest removes the app's own boot receivers), and an
 * injected receiver would fail there. The service that this starts is injected as usual.
 */
class McpBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val enabled = context.getSharedPreferences(McpSettingsDataSource.FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(McpSettingsDataSource.ENABLED, false)
        if (!enabled) {
            return
        }
        try {
            ContextCompat.startForegroundService(context, McpServerService.applyIntent(context))
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+). The controller retries when the app next comes to the foreground.
        }
    }
}
