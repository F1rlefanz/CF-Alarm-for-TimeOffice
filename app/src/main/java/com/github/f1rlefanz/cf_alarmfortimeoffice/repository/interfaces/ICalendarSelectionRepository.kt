package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface für Calendar Selection Repository: die ausgewählten Kalender, persistiert im
 * DataStore, als StateFlow mit synchronem .value-Zugriff.
 *
 * ENTFERNT (Aufraeumrunde 24): `saveSelectedCalendarIds`, `clearSelection` und
 * `hasSelectedCalendars` - im ganzen Baum ohne Aufrufstelle, nicht einmal ein Test-Double.
 * Auswahl UND Abwahl laufen einzeln ueber [addCalendarId]/[removeCalendarId]
 * (`CalendarViewModel`), gelesen wird ueber [selectedCalendarIds] und
 * [getCurrentSelectedCalendarIds]. Der atomare Komplettaustausch war eine zweite, unbenutzte
 * Schreibwahrheit. `hasSelectedCalendars` ist NICHT zu verwechseln mit dem gleichnamigen
 * UI-Feld `CalendarOperationState.hasSelectedCalendars` - das lebt und wird aus
 * `selectedCalendarIds.isNotEmpty()` gespeist.
 */
interface ICalendarSelectionRepository {
    
    /**
     * StateFlow der aktuell ausgewählten Kalender-IDs.
     * INITIAL: Startet mit emptySet() bis DataStore geladen
     */
    val selectedCalendarIds: StateFlow<Set<String>>
    
    /**
     * Lädt die aktuell gespeicherten Kalender-IDs
     */
    suspend fun getCurrentSelectedCalendarIds(): Result<Set<String>>
    
    /**
     * Fügt eine Kalender-ID zur Auswahl hinzu
     */
    suspend fun addCalendarId(calendarId: String): Result<Unit>
    
    /**
     * Entfernt eine Kalender-ID aus der Auswahl
     */
    suspend fun removeCalendarId(calendarId: String): Result<Unit>
}
