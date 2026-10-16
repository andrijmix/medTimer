package com.futsch1.medtimer.core.datastore.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MedTimerPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class McpPreferences
