package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Module für State Management
 * 
 * Stellt zentrale State-Holder bereit
 * für ViewModel-Entkopplung
 */
@Module
@InstallIn(SingletonComponent::class)
object StateModule {
    
    // CalendarStateHolder als explizites @Provides für bessere Kontrolle
    // Alternative wäre @Singleton direkt auf der Klasse
    @Provides
    @Singleton
    fun provideCalendarStateHolder(): CalendarStateHolder = CalendarStateHolder()
}
