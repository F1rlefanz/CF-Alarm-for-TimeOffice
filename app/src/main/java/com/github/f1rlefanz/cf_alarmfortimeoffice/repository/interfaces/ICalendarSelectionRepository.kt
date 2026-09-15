package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface für Calendar Selection Repository
 * 
 * SINGLE SOURCE OF TRUTH: Zentrale Verwaltung der ausgewählten Kalender
 * - Persistente Speicherung mit DataStore
 * - Reactive StateFlow-basierte API mit synchronem .value Zugriff
 * - Atomare State Updates
 * 
 * ARCHITECTURE NOTE (HILT MIGRATION):
 * StateFlow statt Flow verwendet für:
 * - Synchronen Zugriff via .value in ViewModels
 * - Konsistenz mit CalendarStateHolder-Pattern
 * - Bessere Compose-Integration via collectAsStateWithLifecycle()
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
     * StateFlow der aktuell ausgewählten Kalender-IDs
     * 
     * REACTIVE: Emittiert Updates bei Änderungen
     * SYNCHRON: .value für sofortigen Zugriff verfügbar
     * INITIAL: Startet mit emptySet() bis DataStore geladen
     */
    val selectedCalendarIds: StateFlow<Set<String>>
    
    /**
     * Lädt die aktuell gespeicherten Kalender-IDs
     * 
     * @return Result mit Set der ausgewählten Kalender-IDs
     */
    suspend fun getCurrentSelectedCalendarIds(): Result<Set<String>>
    
    /**
     * Fügt eine Kalender-ID zur Auswahl hinzu
     * 
     * @param calendarId ID des hinzuzufügenden Kalenders
     * @return Result für Erfolg/Fehler
     */
    suspend fun addCalendarId(calendarId: String): Result<Unit>
    
    /**
     * Entfernt eine Kalender-ID aus der Auswahl
     * 
     * @param calendarId ID des zu entfernenden Kalenders
     * @return Result für Erfolg/Fehler
     */
    suspend fun removeCalendarId(calendarId: String): Result<Unit>
}
