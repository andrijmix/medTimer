package com.futsch1.medtimer

import android.app.Application
import com.futsch1.medtimer.feature.mcp.McpServerController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MedTimerApplication : Application() {

    @Inject
    lateinit var mcpServerController: McpServerController

    override fun onCreate() {
        super.onCreate()
        mcpServerController.initialize()
    }
}
