package com.futsch1.medtimer.core.datastore.di

import android.content.Context
import android.content.SharedPreferences
import com.futsch1.medtimer.core.datastore.McpSettingsDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Kept apart from [DatastoreModule] on purpose: Hilt tests replace [DatastoreModule] with their own preferences, and the MCP preferences
 * have nothing to do with what those tests exercise.
 */
@Module
@InstallIn(SingletonComponent::class)
object McpPreferencesModule {

    @Provides
    @Singleton
    @McpPreferences
    fun providesMcpSharedPreferences(@ApplicationContext ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences(McpSettingsDataSource.FILE_NAME, Context.MODE_PRIVATE)
    }
}
