package com.futsch1.medtimer.feature.mcp.di

import com.futsch1.medtimer.feature.mcp.tools.HistoryTools
import com.futsch1.medtimer.feature.mcp.tools.MedicineTools
import com.futsch1.medtimer.feature.mcp.tools.MedicineWriteTools
import com.futsch1.medtimer.feature.mcp.tools.ReminderWriteTools
import com.futsch1.medtimer.feature.mcp.tools.ScheduleTools
import com.futsch1.medtimer.feature.mcp.tools.ToolDefinition
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.Multibinds

/**
 * Declares the set of tools that [com.futsch1.medtimer.feature.mcp.service.McpServerService] injects. Declaring it keeps the injection valid
 * even if no group contributes anything; the groups are contributed by [McpToolGroupsModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class McpToolsModule {
    @Multibinds
    abstract fun tools(): Set<ToolDefinition>
}

/**
 * Adds every group of tools to that set. A new group is one more `@ElementsIntoSet` function here.
 */
@Module
@InstallIn(SingletonComponent::class)
object McpToolGroupsModule {
    @Provides
    @ElementsIntoSet
    fun medicineTools(tools: MedicineTools): Set<ToolDefinition> = tools.definitions().toSet()

    @Provides
    @ElementsIntoSet
    fun historyTools(tools: HistoryTools): Set<ToolDefinition> = tools.definitions().toSet()

    @Provides
    @ElementsIntoSet
    fun scheduleTools(tools: ScheduleTools): Set<ToolDefinition> = tools.definitions().toSet()

    @Provides
    @ElementsIntoSet
    fun medicineWriteTools(tools: MedicineWriteTools): Set<ToolDefinition> = tools.definitions().toSet()

    @Provides
    @ElementsIntoSet
    fun reminderWriteTools(tools: ReminderWriteTools): Set<ToolDefinition> = tools.definitions().toSet()
}
