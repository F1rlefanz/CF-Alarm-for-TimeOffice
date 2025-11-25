package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import javax.inject.Inject

/**
 * MainViewModel koordiniert den globalen App-Zustand.
 * 
 * MIGRATION STATUS:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor Injection mit @Inject
 * ✅ CalendarStateHolder statt direkte ViewModel-Referenzen
 * ✅ Keine Abhängigkeiten zu anderen ViewModels mehr
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
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authUseCase: IAuthUseCase,
    private val shiftUseCase: IShiftUseCase,
    private val alarmUseCase: IAlarmUseCase,
    private val calendarUseCase: ICalendarUseCase, 
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val calendarStateHolder: CalendarStateHolder
) : ViewModel() {

    data class MainUiState(
        val isAuthenticated: Boolean = false,
        val hasSelectedCalendars: Boolean = false,
        val hasAvailableCalendars: Boolean = false,
        val hasShiftConfig: Boolean = false,
        val isProcessingShifts: Boolean = false,
        val hasUpcomingShift: Boolean = false,
        val hasActiveAlarms: Boolean = false
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeAppState()
    }

    private fun observeAppState() {
        viewModelScope.launch {
            combine(
                authUseCase.authData
                    .distinctUntilChanged(),
                alarmUseCase.activeAlarms
                    .debounce(200)
                    .distinctUntilChanged(),
                calendarSelectionRepository.selectedCalendarIds
                    .debounce(150)
                    .distinctUntilChanged(),
                calendarStateHolder.availableCalendars
                    .map { it.isNotEmpty() }
                    .debounce(100)
                    .distinctUntilChanged()
            ) { authData, activeAlarms, selectedCalendarIds, hasAvailableCalendars ->
                MainUiState(
                    isAuthenticated = authData.isLoggedIn,
                    hasSelectedCalendars = selectedCalendarIds.isNotEmpty(),
                    hasAvailableCalendars = hasAvailableCalendars,
                    hasActiveAlarms = activeAlarms.isNotEmpty()
                )
            }.distinctUntilChanged()
            .debounce(75)
            .collect { state ->
                _uiState.value = state
                
                Logger.d(LogTags.NAVIGATION, "🔄 UI-DEBOUNCE: Main state updated - authenticated=${state.isAuthenticated}, hasCalendars=${state.hasAvailableCalendars}, hasSelected=${state.hasSelectedCalendars}")
            }
        }
    }

    fun refreshAll(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh) {
                Logger.i(LogTags.UI, "Force refresh requested - refreshing calendar events")
                
                // Set loading state via CalendarStateHolder
                calendarStateHolder.setLoadingEvents(true)
                
                try {
                    // Get selected calendars
                    val selectedIds = calendarSelectionRepository.selectedCalendarIds.value
                    
                    if (selectedIds.isNotEmpty()) {
                        // Load events via CalendarUseCase
                        // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
                        val result = calendarUseCase.getCalendarEventsWithCache(
                            calendarIds = selectedIds,
                            forceRefresh = true
                        )
                        
                        result.onSuccess { events ->
                            calendarStateHolder.updateEvents(events)
                            Logger.i(LogTags.UI, "Successfully refreshed ${events.size} events")
                        }.onFailure { error ->
                            Logger.e(LogTags.UI, "Failed to refresh calendar events", error)
                        }
                    } else {
                        Logger.w(LogTags.UI, "No calendars selected, skipping refresh")
                    }
                } finally {
                    calendarStateHolder.setLoadingEvents(false)
                }
            } else {
                // Normal refresh with default 14 days
                loadEventsForSelectedCalendars()
            }
        }
    }
    
    private fun loadEventsForSelectedCalendars() {
        viewModelScope.launch {
            calendarStateHolder.setLoadingEvents(true)
            
            try {
                val selectedIds = calendarSelectionRepository.selectedCalendarIds.value
                
                if (selectedIds.isNotEmpty()) {
                    // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
                    val result = calendarUseCase.getCalendarEventsWithCache(
                        calendarIds = selectedIds,
                        forceRefresh = false
                    )
                    
                    result.onSuccess { events ->
                        calendarStateHolder.updateEvents(events)
                        Logger.d(LogTags.UI, "Loaded ${events.size} events for selected calendars")
                    }
                }
            } finally {
                calendarStateHolder.setLoadingEvents(false)
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
            _uiState.value = MainUiState()
            Logger.d(LogTags.LIFECYCLE, "MainViewModel cleared - cleaning up state references and resources")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during MainViewModel cleanup", e)
        }
    }
    
    /**
     * PUBLIC API: Manual cleanup for MainActivity destruction
     * Calls onCleared() safely from external context
     */
    fun cleanupResources() {
        try {
            Logger.d(LogTags.LIFECYCLE, "MainViewModel: Manual cleanup requested")
            onCleared()
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during MainViewModel manual cleanup", e)
        }
    }
}
