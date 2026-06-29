package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.CalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.ShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt Module für UseCase Bindings
 * 
 * WICHTIG: UseCases sind STATELESS - daher kein @Singleton!
 * Sie werden von Services und ViewModels verwendet
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UseCaseModule {
    
    // ==============================
    // Core UseCases
    // ==============================
    
    @Binds
    abstract fun bindAlarmUseCase(
        impl: AlarmUseCase
    ): IAlarmUseCase
    
    @Binds
    abstract fun bindAlarmSkipUseCase(
        impl: AlarmSkipUseCase
    ): IAlarmSkipUseCase
    
    @Binds
    abstract fun bindCalendarUseCase(
        impl: CalendarUseCase
    ): ICalendarUseCase
    
    @Binds
    abstract fun bindShiftUseCase(
        impl: ShiftUseCase
    ): IShiftUseCase
    
    @Binds
    abstract fun bindAuthUseCase(
        impl: AuthUseCase
    ): IAuthUseCase
    
    // ==============================
    // Hue UseCases
    // ==============================
    
    @Binds
    abstract fun bindHueBridgeUseCase(
        impl: HueBridgeUseCase
    ): IHueBridgeUseCase
    
    @Binds
    abstract fun bindHueLightUseCase(
        impl: HueLightUseCase
    ): IHueLightUseCase

    @Binds
    abstract fun bindHueLightUseCaseAdvanced(
        impl: HueLightUseCase
    ): IHueLightUseCaseAdvanced

    @Binds
    abstract fun bindHueRuleUseCase(
        impl: HueRuleUseCase
    ): IHueRuleUseCase
}
