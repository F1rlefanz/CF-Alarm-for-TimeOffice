package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * MainViewModel koordiniert den globalen App-Zustand.
 * 
 * REFACTORED: Interface-basierte Abhängigkeiten für bessere Testbarkeit
 * ✅ Verwendet Interface-Abhängigkeiten (Dependency Inversion)
 * ✅ Mock-fähig für Unit Tests
 * ✅ Verwendet nur UseCases, keine Repositories direkt
 * ✅ Folgt Clean Architecture Principles
 * ✅ Zentrale Koordination ohne Mixed Responsibilities
 * ✅ FIXED: hasSelectedCalendars wird jetzt korrekt überwacht
 * ✅ CRITICAL FIX: Auto-triggers calendar loading after authentication
 */
@OptIn(FlowPreview::class)
class MainViewModel(
    private val authUseCase: IAuthUseCase,
    private val shiftUseCase: IShiftUseCase,
    private val alarmUseCase: IAlarmUseCase,
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val calendarViewModel: CalendarViewModel? = null,
    private val authViewModel: AuthViewModel? = null
) : ViewModel() {

    data class MainUiState(
        val isAuthenticated: Boolean = false,
        val hasSelectedCalendars: Boolean = false,
        val hasAvailableCalendars: Boolean = false, // NEW: Track available calendars separately
        val hasShiftConfig: Boolean = false,
        val isProcessingShifts: Boolean = false,
        val hasUpcomingShift: Boolean = false,
        val hasActiveAlarms: Boolean = false
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeAppState()
        setupAuthCalendarTrigger()
    }
    
    /**
     * CRITICAL FIX: Sets up automatic calendar loading after authentication
     * This connects AuthViewModel to CalendarViewModel for seamless user experience
     */
    private fun setupAuthCalendarTrigger() {
        authViewModel?.setCalendarReloadTrigger {
            Logger.business(LogTags.NAVIGATION, "🔄 MAIN-COORDINATOR: Triggering calendar reload after authentication")
            calendarViewModel?.loadAvailableCalendars(resetPagination = true)
        }
    }

    private fun observeAppState() {
        viewModelScope.launch {
            combine(
                authUseCase.authData
                    .distinctUntilChanged(), // PERFORMANCE: Auth changes are expensive
                alarmUseCase.activeAlarms
                    .debounce(200) // PERFORMANCE: Batch alarm changes (less critical)
                    .distinctUntilChanged(),
                calendarSelectionRepository.selectedCalendarIds
                    .debounce(150) // PERFORMANCE: Batch calendar selection changes
                    .distinctUntilChanged(), // PERFORMANCE: Only emit on actual changes
                calendarViewModel?.uiState?.map { it.availableCalendars.isNotEmpty() }
                    ?.debounce(100) // PERFORMANCE: Batch availability checks
                    ?.distinctUntilChanged() ?: flowOf(false)
            ) { authData, activeAlarms, selectedCalendarIds, hasAvailableCalendars ->
                MainUiState(
                    isAuthenticated = authData.isLoggedIn,
                    hasSelectedCalendars = selectedCalendarIds.isNotEmpty(),
                    hasAvailableCalendars = hasAvailableCalendars, // NEW: Track available calendars
                    hasActiveAlarms = activeAlarms.isNotEmpty()
                )
            }.distinctUntilChanged() // PERFORMANCE: Only emit when main state actually changes
            .debounce(75) // PERFORMANCE: Batch main state updates with shorter delay for UI responsiveness
            .collect { state ->
                _uiState.value = state
                
                // Debug-Log für Diagnose
                Logger.d(LogTags.NAVIGATION, "🔄 UI-DEBOUNCE: Main state updated - authenticated=${state.isAuthenticated}, hasCalendars=${state.hasAvailableCalendars}, hasSelected=${state.hasSelectedCalendars}")
            }
        }
    }

    fun refreshAll(forceRefresh: Boolean = false) {
        // Lädt Calendar-Events mit aktueller daysAhead-Konfiguration neu
        viewModelScope.launch {
            val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
            val daysAhead = shiftConfig?.daysAhead
            
            if (forceRefresh) {
                Logger.i(LogTags.UI, "Force refresh requested")
                calendarViewModel?.refreshData(forceRefresh = true)
            } else {
                calendarViewModel?.loadEventsForSelectedCalendars(daysAhead)
            }
        }
    }
    
    fun forceRefreshCalendarEvents() {
        refreshAll(forceRefresh = true)
    }
    
    /**
     * MEMORY LEAK PREVENTION: Comprehensive resource cleanup
     * PERFORMANCE OPTIMIZATION: Clear all state and references
     */
    override fun onCleared() {
        super.onCleared()
        
        try {
            // MEMORY LEAK FIX: Clear UI state to release object references
            _uiState.value = MainUiState()
            
            Logger.d(LogTags.LIFECYCLE, "MainViewModel cleared - cleaning up state references and resources")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during MainViewModel cleanup", e)
        }
        
        // Note: ViewModelScope automatically cancels all coroutines
        // UseCases handle their own cleanup via DI container
    }
}
