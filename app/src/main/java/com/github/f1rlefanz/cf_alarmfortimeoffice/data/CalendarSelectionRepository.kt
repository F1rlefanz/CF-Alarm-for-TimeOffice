package com.github.f1rlefanz.cf_alarmfortimeoffice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

// Extension property for Context to create DataStore
private val Context.calendarSelectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "calendar_selection_prefs")

/**
 * CalendarSelectionRepository - Persistente Speicherung der ausgewählten Kalender
 * 
 * SINGLE SOURCE OF TRUTH IMPLEMENTATION:
 * ✅ Zentrale Verwaltung der ausgewählten Kalender-IDs
 * ✅ Persistente Speicherung mit DataStore Preferences
 * ✅ Reactive Flow-basierte API mit distinctUntilChanged()
 * ✅ Atomare State Updates - keine Race Conditions
 * ✅ Result-basierte Fehlerbehandlung
 * ✅ Comprehensive CRUD Operations
 */
class CalendarSelectionRepository(
    private val context: Context
) : ICalendarSelectionRepository {

    private val dataStore = context.calendarSelectionDataStore
    private val selectedCalendarIdsKey = stringSetPreferencesKey("selected_calendar_ids")

    /**
     * REACTIVE STATE: Flow der ausgewählten Kalender-IDs mit distinctUntilChanged()
     * Verhindert unnötige Recompositions bei identischen Sets
     */
    override val selectedCalendarIds: Flow<Set<String>> = dataStore.data
        .map { preferences ->
            preferences[selectedCalendarIdsKey] ?: emptySet()
        }
        .distinctUntilChanged() // PERFORMANCE: Nur bei echten Änderungen emittieren

    /**
     * ATOMIC UPDATE: Kompletter Austausch der ausgewählten Kalender-IDs
     */
    override suspend fun saveSelectedCalendarIds(calendarIds: Set<String>): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.saveSelectedCalendarIds") {
            dataStore.edit { preferences ->
                preferences[selectedCalendarIdsKey] = calendarIds
            }
            Logger.i(LogTags.CALENDAR, "Calendar selection saved: ${calendarIds.size} calendars selected")
        }

    override suspend fun getCurrentSelectedCalendarIds(): Result<Set<String>> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.getCurrentSelectedCalendarIds") {
            val preferences = dataStore.data.first()
            preferences[selectedCalendarIdsKey] ?: emptySet()
        }

    /**
     * GRANULAR UPDATE: Hinzufügen einer einzelnen Kalender-ID
     */
    override suspend fun addCalendarId(calendarId: String): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.addCalendarId") {
            if (calendarId.isBlank()) {
                throw AppError.ValidationError("Calendar ID cannot be blank")
            }
            
            dataStore.edit { preferences ->
                val currentIds = preferences[selectedCalendarIdsKey] ?: emptySet()
                preferences[selectedCalendarIdsKey] = currentIds + calendarId
            }
            Logger.d(LogTags.CALENDAR, "Calendar added to selection: ${calendarId.take(8)}...")
        }

    /**
     * GRANULAR UPDATE: Entfernen einer einzelnen Kalender-ID
     */
    override suspend fun removeCalendarId(calendarId: String): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.removeCalendarId") {
            dataStore.edit { preferences ->
                val currentIds = preferences[selectedCalendarIdsKey] ?: emptySet()
                preferences[selectedCalendarIdsKey] = currentIds - calendarId
            }
            Logger.d(LogTags.CALENDAR, "Calendar removed from selection: ${calendarId.take(8)}...")
        }

    override suspend fun clearSelection(): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.clearSelection") {
            dataStore.edit { preferences ->
                preferences.remove(selectedCalendarIdsKey)
            }
            Logger.i(LogTags.CALENDAR, "Calendar selection cleared")
        }

    override suspend fun hasSelectedCalendars(): Result<Boolean> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.hasSelectedCalendars") {
            val currentIds = getCurrentSelectedCalendarIds().getOrElse { emptySet() }
            currentIds.isNotEmpty()
        }
}
