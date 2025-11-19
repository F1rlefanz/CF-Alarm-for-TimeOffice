package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.DataStoreTokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.AuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftConfigRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt Module für Repository Bindings
 * 
 * Bindet alle Repository-Implementierungen an ihre Interfaces
 * Ermöglicht einfaches Mocking für Tests
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    // ==============================
    // Core Repositories
    // ==============================
    
    @Binds
    abstract fun bindAlarmRepository(
        impl: AlarmRepository
    ): IAlarmRepository
    
    @Binds
    abstract fun bindAlarmSkipRepository(
        impl: AlarmSkipRepository
    ): IAlarmSkipRepository
    
    @Binds
    abstract fun bindCalendarRepository(
        impl: CalendarRepository
    ): ICalendarRepository
    
    @Binds
    abstract fun bindCalendarSelectionRepository(
        impl: CalendarSelectionRepository
    ): ICalendarSelectionRepository
    
    @Binds
    abstract fun bindShiftConfigRepository(
        impl: ShiftConfigRepository
    ): IShiftConfigRepository
    
    @Binds
    abstract fun bindAuthDataStoreRepository(
        impl: AuthDataStoreRepository
    ): IAuthDataStoreRepository
    
    @Binds
    abstract fun bindTokenRepository(
        impl: DataStoreTokenRepository
    ): TokenRepository
    
    // ==============================
    // Hue Repositories
    // ==============================
    
    @Binds
    abstract fun bindHueBridgeRepository(
        impl: HueBridgeRepository
    ): IHueBridgeRepository
    
    @Binds
    abstract fun bindHueLightRepository(
        impl: HueLightRepository
    ): IHueLightRepository
    
    @Binds
    abstract fun bindHueConfigRepository(
        impl: HueConfigRepository
    ): IHueConfigRepository
}
