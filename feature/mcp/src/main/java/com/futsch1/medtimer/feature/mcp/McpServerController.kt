package com.futsch1.medtimer.feature.mcp

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.futsch1.medtimer.core.common.di.ApplicationScope
import com.futsch1.medtimer.core.datastore.McpSettingsDataSource
import com.futsch1.medtimer.core.domain.model.McpSettings
import com.futsch1.medtimer.feature.mcp.service.McpServerService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and stops [McpServerService] to match [McpSettingsDataSource]. Call [initialize] once from `Application.onCreate`.
 *
 * Neither the settings screen nor any other feature knows about the service; changing the stored settings is all they do.
 */
@Singleton
class McpServerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDataSource: McpSettingsDataSource,
    @param:ApplicationScope private val scope: CoroutineScope
) {
    fun initialize() {
        settingsDataSource.settings
            // The service itself reports errors through lastError; reacting to that would make it retry in a loop.
            .map { it.copy(lastError = "") }
            .distinctUntilChanged()
            .onEach { reconcile(it) }
            .launchIn(scope)
        // Android refuses to start a foreground service from the background, so a start that was refused at launch is retried here.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    reconcile(settingsDataSource.settings.value)
                }
            }
        )
    }

    private fun reconcile(settings: McpSettings) {
        if (!settings.enabled) {
            context.stopService(McpServerService.applyIntent(context))
            return
        }
        try {
            ContextCompat.startForegroundService(context, McpServerService.applyIntent(context))
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+): the app is in the background. Retried on the next ON_START.
        }
    }
}
