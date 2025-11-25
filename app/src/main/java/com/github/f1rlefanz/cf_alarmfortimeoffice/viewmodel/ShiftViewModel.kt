package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import javax.inject.Inject

data class ShiftUiState(
    val isLoading: Boolean = false,
    val currentShiftConfig: ShiftConfig? = null,
    val recognizedShifts: List<ShiftInfo> = emptyList(),
    val upcomingShift: ShiftInfo? = null,
    val error: String? = null
)

/**
 * ShiftViewModel - REFACTORED mit Hilt und CalendarStateHolder
 *
 * MIGRATION:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor Injection mit @Inject
 * ✅ CalendarStateHolder statt CalendarViewModel
 * ✅ Keine direkte ViewModel-zu-ViewModel Dependency mehr!
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftUseCase: IShiftUseCase,
    private val alarmUseCase: IAlarmUseCase,  // NEW: For alarm creation
    private val calendarStateHolder: CalendarStateHolder,
    private val errorHandler: ErrorHandler
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShiftUiState())
    val uiState: StateFlow<ShiftUiState> = _uiState.asStateFlow()

    init {
        loadShiftConfig()
        observeCalendarEvents() // Reactive Schichterkennung via StateHolder
    }

    /**
     * REACTIVE PATTERN: Observiert Calendar Events vom StateHolder
     * PERFORMANCE: Enhanced debouncing strategy für different scenarios
     * DECOUPLED: Nutzt CalendarStateHolder statt direkte ViewModel-Referenz
     * MEMORY SAFE: Proper cleanup über viewModelScope
     * 
     * NOTE: distinctUntilChanged() removed - StateFlow already provides this behavior
     * (Operator Fusion - see StateFlow documentation)
     */
    private fun observeCalendarEvents() {
        viewModelScope.launch {
            calendarStateHolder.events
                .debounce(400) // ENHANCED: Längeres Debouncing für teure Shift-Recognition (400ms)
                .collect { events: List<CalendarEvent> ->
                    if (events.isNotEmpty()) {
                        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 UI-DEBOUNCE: Calendar events changed via StateHolder, triggering shift recognition for ${events.size} events")
                        processCalendarEvents(events)
                    } else {
                        // Clear recognized shifts wenn keine Events vorhanden
                        _uiState.value = _uiState.value.copy(
                            recognizedShifts = emptyList(),
                            upcomingShift = null
                        )
                        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 UI-DEBOUNCE: No calendar events in StateHolder, clearing recognized shifts")
                    }
                }
        }
    }

    private fun loadShiftConfig() {
        viewModelScope.launch {
            // SINGLETON OPTIMIZATION: Enhanced startup with cache awareness
            Logger.d(LogTags.SHIFT_CONFIG, "🔄 SINGLETON-STARTUP: Loading ShiftConfig with singleton pattern...")
            
            shiftUseCase.getCurrentShiftConfig()
                .onSuccess { config ->
                    _uiState.value = _uiState.value.copy(currentShiftConfig = config)
                    Logger.business(LogTags.SHIFT_CONFIG, "✅ SINGLETON-STARTUP: ShiftConfig loaded successfully - autoAlarm=${config.autoAlarmEnabled}, definitions=${config.definitions.size}")
                }
                .onFailure { error ->
                    Logger.w(LogTags.SHIFT_CONFIG, "⚠️ SINGLETON-STARTUP: Failed to load ShiftConfig, creating default", error)
                    
                    // FALLBACK: Create default configuration if loading fails
                    val defaultConfig = ShiftConfig.getDefaultConfig()
                    
                    shiftUseCase.saveShiftConfig(defaultConfig)
                        .onSuccess {
                            _uiState.value = _uiState.value.copy(currentShiftConfig = defaultConfig)
                            Logger.business(LogTags.SHIFT_CONFIG, "✅ SINGLETON-STARTUP: Default ShiftConfig created and loaded - autoAlarm=${defaultConfig.autoAlarmEnabled}")
                        }
                        .onFailure { saveError ->
                            _uiState.value = _uiState.value.copy(
                                error = errorHandler.getErrorMessage(saveError)
                            )
                            Logger.e(LogTags.SHIFT_CONFIG, "❌ SINGLETON-STARTUP: Failed to save default ShiftConfig", saveError)
                        }
                }
        }
    }

    fun updateShiftConfig(config: ShiftConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            // CRITICAL FIX: Clear recognition cache BEFORE saving config
            try {
                // Access the ShiftRecognitionEngine through the UseCase and clear its cache
                Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 CACHE-CLEAR: Clearing recognition cache before config update")
                
                // Force clear the recognition cache by calling recognizeShiftsInEvents with empty list
                // This will reset the internal cache state
                shiftUseCase.recognizeShiftsInEvents(emptyList())
                
                Logger.d(LogTags.SHIFT_RECOGNITION, "✅ CACHE-CLEAR: Recognition cache cleared successfully")
            } catch (e: Exception) {
                Logger.w(LogTags.SHIFT_RECOGNITION, "⚠️ CACHE-CLEAR: Failed to clear cache", e)
            }
            
            shiftUseCase.saveShiftConfig(config)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentShiftConfig = config
                    )
                    
                    // REACTIVE FIX: Re-run shift recognition with updated config
                    // HILT MIGRATION: Now uses CalendarStateHolder instead of direct ViewModel reference
                    val currentEvents = calendarStateHolder.events.value
                    if (currentEvents.isNotEmpty()) {
                        val eventCount = currentEvents.size
                        Logger.d(LogTags.SHIFT_RECOGNITION, "Shift config updated, re-processing $eventCount calendar events with new definitions")

                        // Small delay to ensure config is fully persisted
                        kotlinx.coroutines.delay(200)

                        processCalendarEvents(currentEvents)

                        // 🚨 CRITICAL FIX: Trigger automatic alarm creation after shift config update!
                        Logger.business(LogTags.ALARM, "🔄 CONFIG-UPDATE: Triggering alarm creation after shift config change")
                        triggerAlarmCreationFromConfigUpdate()
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errorHandler.getErrorMessage(error)
                    )
                }
        }
    }

    // REMOVED: updateDaysAhead() - daysAhead is now fixed at 14 days as per Briefing 4.0
    // REMOVED: updateSyncInterval() - syncIntervalHours is now fixed at 6 hours as per Briefing 4.0

    fun processCalendarEvents(events: List<CalendarEvent>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            // Interface-Version verwendet recognizeShiftsInEvents
            shiftUseCase.recognizeShiftsInEvents(events)
                .onSuccess { shiftMatches ->
                    // Konvertiere ShiftMatch zu ShiftInfo für UI-Kompatibilität
                    val shifts = shiftMatches.map { match ->
                        ShiftInfo(
                            id = match.calendarEvent.id,
                            shiftType = com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftType(
                                name = match.shiftDefinition.id,
                                displayName = match.shiftDefinition.name
                            ),
                            startTime = match.calendarEvent.startTime,
                            endTime = match.calendarEvent.endTime,
                            eventTitle = match.calendarEvent.title,
                            alarmTime = match.calculatedAlarmTime
                        )
                    }
                    
                    // Upcoming shift calculation - legacy method
                    val upcomingShift = shifts
                        .filter { it.startTime.isAfter(java.time.LocalDateTime.now()) }
                        .minByOrNull { it.startTime }
                    
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        recognizedShifts = shifts,
                        upcomingShift = upcomingShift
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errorHandler.getErrorMessage(error)
                    )
                }
        }
    }

    /**
     * 🚨 CRITICAL FIX: Triggers alarm creation after shift config updates
     * This ensures that when new shift definitions are added, alarms are automatically created
     * 
     * NOW USES: CalendarStateHolder events instead of direct CalendarViewModel reference
     */
    private fun triggerAlarmCreationFromConfigUpdate() {
        viewModelScope.launch {
            Logger.business(LogTags.ALARM, "🔄 CONFIG-UPDATE: Triggering alarm creation for newly recognized shifts")
            
            // Small delay to ensure shift recognition is complete
            kotlinx.coroutines.delay(100)
            
            // Get current events from CalendarStateHolder
            val currentEvents = calendarStateHolder.events.value
            
            if (currentEvents.isNotEmpty()) {
                // Trigger alarm creation via AlarmUseCase directly
                _uiState.value.currentShiftConfig?.let { config ->
                    // First recognize shifts in events
                    shiftUseCase.recognizeShiftsInEvents(currentEvents)
                        .onSuccess { shiftMatches ->
                            // Then create alarms from recognized shifts
                            val eventsWithShifts = shiftMatches.map { it.calendarEvent }
                            alarmUseCase.createAlarmsFromEvents(eventsWithShifts, config)
                                .onSuccess { alarms ->
                                    Logger.business(LogTags.ALARM, "✅ CONFIG-UPDATE: Created ${alarms.size} alarms from config update")
                                }
                                .onFailure { error ->
                                    Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: Failed to create alarms", error)
                                }
                        }
                        .onFailure { error ->
                            Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: Failed to recognize shifts", error)
                        }
                }
            } else {
                Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: No events available for alarm creation")
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * CRITICAL FIX: Enhanced Memory Leak Prevention - Comprehensive resource cleanup
     * MUTEX ERROR PREVENTION: Clear all state references that could cause threading issues
     */
    override fun onCleared() {
        super.onCleared()
        
        try {
            Logger.d(LogTags.LIFECYCLE, "ShiftViewModel: Starting cleanup...")
            
            // CRITICAL FIX: Clear UI state to release object references
            _uiState.value = ShiftUiState()
            
            Logger.d(LogTags.LIFECYCLE, "ShiftViewModel: Cleanup completed successfully")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during ShiftViewModel cleanup", e)
        }
        
        // Note: ViewModelScope automatically cancels all coroutines
        // UseCase cleanup wird durch DI Container gehandhabt
    }
    
    /**
     * PUBLIC API: Manual cleanup for MainActivity destruction
     * Calls onCleared() safely from external context
     */
    fun cleanupResources() {
        try {
            Logger.d(LogTags.LIFECYCLE, "ShiftViewModel: Manual cleanup requested")
            onCleared()
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during ShiftViewModel manual cleanup", e)
        }
    }
}
