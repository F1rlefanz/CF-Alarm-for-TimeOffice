package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
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

    // CalendarStateHolder wird als Singleton bereitgestellt
    // Da CalendarStateHolder bereits @Inject constructor hat,
    // könnte man auch direkt @Singleton auf der Klasse verwenden
    // Aber explizites @Provides gibt uns mehr Kontrolle
    @Provides
    @Singleton
    fun provideCalendarStateHolder(): CalendarStateHolder = CalendarStateHolder()
}
