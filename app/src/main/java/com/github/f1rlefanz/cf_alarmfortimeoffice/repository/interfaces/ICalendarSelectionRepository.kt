package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * Interface für Calendar Selection Repository
 * 
 * SINGLE SOURCE OF TRUTH: Zentrale Verwaltung der ausgewählten Kalender
 * - Persistente Speicherung mit DataStore
 * - Reactive Flow-basierte API
 * - Atomare State Updates
 */
interface ICalendarSelectionRepository {
    
    /**
     * Flow der aktuell ausgewählten Kalender-IDs
     * REACTIVE: Emittiert Updates bei Änderungen
     */
    val selectedCalendarIds: Flow<Set<String>>
    
    /**
     * Speichert die ausgewählten Kalender-IDs persistent
     * ATOMIC: Kompletter Austausch der ausgewählten IDs
     * 
     * @param calendarIds Set der ausgewählten Kalender-IDs
     * @return Result für Erfolg/Fehler
     */
    suspend fun saveSelectedCalendarIds(calendarIds: Set<String>): Result<Unit>
    
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
    
    /**
     * Leert die Kalender-Auswahl
     * 
     * @return Result für Erfolg/Fehler
     */
    suspend fun clearSelection(): Result<Unit>
    
    /**
     * Überprüft ob mindestens ein Kalender ausgewählt ist
     * 
     * @return Result mit Boolean (true wenn Kalender ausgewählt)
     */
    suspend fun hasSelectedCalendars(): Result<Boolean>
}
