package com.github.f1rlefanz.cf_alarmfortimeoffice.di.modules

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.GoogleCalendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zentraler State-Holder für Calendar-bezogene Daten
 * 
 * ARCHITEKTUR:
 * - Single Source of Truth für Calendar Events
 * - Entkoppelt ViewModels voneinander
 * - Thread-safe durch StateFlow
 * - Testbar durch Interface-Abstraktion
 * 
 * CRITICAL: Löst das Problem der ViewModel-zu-ViewModel Dependencies!
 * ShiftViewModel und MainViewModel können jetzt unabhängig auf Calendar-Daten zugreifen
 */
@Singleton
class CalendarStateHolder @Inject constructor() {
    
    // ==============================
    // Calendar Events
    // ==============================
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()
    
    // ==============================
    // Selected Calendars
    // ==============================
    private val _selectedCalendars = MutableStateFlow<Set<String>>(emptySet())
    val selectedCalendars: StateFlow<Set<String>> = _selectedCalendars.asStateFlow()
    
    // ==============================
    // Available Calendars
    // ==============================
    private val _availableCalendars = MutableStateFlow<List<GoogleCalendar>>(emptyList())
    val availableCalendars: StateFlow<List<GoogleCalendar>> = _availableCalendars.asStateFlow()
    
    // ==============================
    // Loading States
    // ==============================
    private val _isLoadingEvents = MutableStateFlow(false)
    val isLoadingEvents: StateFlow<Boolean> = _isLoadingEvents.asStateFlow()
    
    private val _isLoadingCalendars = MutableStateFlow(false)
    val isLoadingCalendars: StateFlow<Boolean> = _isLoadingCalendars.asStateFlow()
    
    // ==============================
    // Error States
    // ==============================
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    // ==============================
    // Update Functions
    // ==============================
    
    fun updateEvents(events: List<CalendarEvent>) {
        _events.value = events
    }
    
    fun updateSelectedCalendars(calendars: Set<String>) {
        _selectedCalendars.value = calendars
    }
    
    fun updateAvailableCalendars(calendars: List<GoogleCalendar>) {
        _availableCalendars.value = calendars
    }
    
    fun setLoadingEvents(loading: Boolean) {
        _isLoadingEvents.value = loading
    }
    
    fun setLoadingCalendars(loading: Boolean) {
        _isLoadingCalendars.value = loading
    }
    
    fun setError(error: String?) {
        _lastError.value = error
    }
    
    fun clearError() {
        _lastError.value = null
    }
    
    // ==============================
    // Convenience Functions
    // ==============================
    
    fun hasEvents(): Boolean = _events.value.isNotEmpty()
    
    fun hasSelectedCalendars(): Boolean = _selectedCalendars.value.isNotEmpty()
    
    fun clearAll() {
        _events.value = emptyList()
        _selectedCalendars.value = emptySet()
        _availableCalendars.value = emptyList()
        _isLoadingEvents.value = false
        _isLoadingCalendars.value = false
        _lastError.value = null
    }
}
