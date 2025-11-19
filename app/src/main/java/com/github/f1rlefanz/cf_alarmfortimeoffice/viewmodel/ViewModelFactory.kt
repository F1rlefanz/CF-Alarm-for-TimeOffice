package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.AppContainer
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler

/**
 * ViewModelFactory für Clean Architecture Dependency Injection
 * 
 * REFACTORED: Interface-basierte Dependency Injection mit Singleton-Pattern
 * ✅ Alle ViewModels verwenden Interface-Abhängigkeiten für bessere Testbarkeit
 * ✅ UseCase-Layer mit Interface-Abstraktion (Dependency Inversion)
 * ✅ Mock-fähige Dependencies für Unit Tests
 * ✅ Singleton ViewModels für State-Sharing zwischen Komponenten
 * ✅ Reactive Architecture für lose Kopplung
 */
@Deprecated("Will be removed after Hilt migration - Phase 5")
class ViewModelFactory(
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    // SINGLETON PATTERN: Shared ViewModel Instances für State-Konsistenz
    private var _calendarViewModel: CalendarViewModel? = null
    private var _shiftViewModel: ShiftViewModel? = null
    private var _hueViewModel: HueViewModel? = null
    
    private fun getOrCreateCalendarViewModel(): CalendarViewModel {
        return _calendarViewModel ?: CalendarViewModel(
            calendarUseCase = appContainer.calendarUseCase,
            calendarSelectionRepository = appContainer.calendarSelectionRepository,
            errorHandler = ErrorHandler,
            shiftUseCase = appContainer.shiftUseCase,
            alarmUseCase = appContainer.alarmUseCase
        ).also { _calendarViewModel = it }
    }
    
    private fun getOrCreateHueViewModel(): HueViewModel {
        return _hueViewModel ?: HueViewModel(
            hueBridgeUseCase = appContainer.hueBridgeUseCase,
            hueLightUseCase = appContainer.hueLightUseCase,
            hueRuleUseCase = appContainer.hueRuleUseCase
        ).also { _hueViewModel = it }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AuthViewModel::class.java) -> {
                AuthViewModel(
                    authDataStoreRepository = appContainer.authDataStoreRepository,
                    credentialAuthManager = appContainer.credentialAuthManager,
                    errorHandler = ErrorHandler,
                    authUseCase = appContainer.authUseCase, // Add AuthUseCase for Calendar authorization
                    calendarSelectionRepository = appContainer.calendarSelectionRepository, // REACTIVE: Add CalendarSelectionRepository for hasSelectedCalendars sync
                    backgroundServiceManager = appContainer.backgroundServiceManager // Add BackgroundServiceManager for maintenance service initialization
                ) as T
            }
            modelClass.isAssignableFrom(CalendarViewModel::class.java) -> {
                getOrCreateCalendarViewModel() as T
            }
            modelClass.isAssignableFrom(ShiftViewModel::class.java) -> {
                // REACTIVE ARCHITECTURE: ShiftViewModel observiert CalendarViewModel
                (_shiftViewModel ?: ShiftViewModel(
                    shiftUseCase = appContainer.shiftUseCase,
                    errorHandler = ErrorHandler,
                    calendarViewModel = getOrCreateCalendarViewModel() // REACTIVE: Für automatische Schichterkennung
                ).also { _shiftViewModel = it }) as T
            }
            modelClass.isAssignableFrom(AlarmViewModel::class.java) -> {
                AlarmViewModel(
                    alarmUseCase = appContainer.alarmUseCase, // Interface-Abhängigkeit
                    alarmSkipUseCase = appContainer.alarmSkipUseCase, // Skip UseCase
                    shiftUseCase = appContainer.shiftUseCase, // Für Manual Alarm Feature
                    errorHandler = ErrorHandler
                ) as T
            }
            modelClass.isAssignableFrom(MainViewModel::class.java) -> {
                MainViewModel(
                    authUseCase = appContainer.authUseCase, // Interface-Abhängigkeit  
                    shiftUseCase = appContainer.shiftUseCase, // Interface-Abhängigkeit
                    alarmUseCase = appContainer.alarmUseCase, // Interface-Abhängigkeit
                    calendarSelectionRepository = appContainer.calendarSelectionRepository,
                    calendarViewModel = getOrCreateCalendarViewModel() // COORDINATION: Für refreshAll()
                ) as T
            }
            modelClass.isAssignableFrom(NavigationViewModel::class.java) -> {
                // NavigationViewModel wird jetzt auch über Factory erstellt
                // statt als Singleton im AppContainer
                NavigationViewModel() as T
            }
            modelClass.isAssignableFrom(HueViewModel::class.java) -> {
                getOrCreateHueViewModel() as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
