package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants

/**
 * PAGINATION SUPPORT: Data classes für paginierte Ergebnisse
 */
data class CalendarPage(
    val calendars: List<AndroidCalendar>,
    val page: Int,
    val pageSize: Int,
    val totalCalendars: Int,
    val hasNextPage: Boolean
) {
    val totalPages: Int = (totalCalendars + pageSize - 1) / pageSize
}

data class EventPage(
    val events: List<CalendarEvent>,
    val offset: Int,
    val maxEvents: Int,
    val totalEvents: Int,
    val hasMore: Boolean
)

/**
 * Interface für Calendar UseCase Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: ViewModel abhängig von Abstraktion
 * - Testbarkeit: ViewModel kann mit Mock-UseCase getestet werden
 * - Business Logic Separation: Kapselt Calendar-spezifische Geschäftslogik
 * 
 * OPTIMIZATION ENHANCEMENTS:
 * ✅ Lazy Loading für Events mit Pagination
 * ✅ Pagination für große Kalenderlisten
 * ✅ Erweiterte Cache-Management Funktionen
 */
interface ICalendarUseCase {
    
    /**
     * Lädt verfügbare Kalender für den aktuell authentifizierten User
     * 
     * @return Result mit Liste der verfügbaren Kalender oder Fehler
     */
    suspend fun getAvailableCalendars(): Result<List<AndroidCalendar>>
    
    /**
     * PAGINATION: Lädt verfügbare Kalender mit Pagination Support
     * 
     * @param page Seiten-Nummer (beginnend bei 0)
     * @param pageSize Anzahl Kalender pro Seite (Standard: 20)
     * @return Result mit paginiertem CalendarPage oder Fehler
     */
    suspend fun getAvailableCalendarsPaginated(
        page: Int = 0,
        pageSize: Int = 20
    ): Result<CalendarPage>
    
    /**
     * LAZY LOADING: Lädt Events mit erweiterten Optionen
     * 
     * PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
     *
     * @param calendarIds Set der Kalender-IDs
     * @param maxEvents Maximale Anzahl Events (für Lazy Loading)
     * @param offset Offset für Pagination von Events
     * @return Result mit paginiertem EventPage oder Fehler
     */
    suspend fun getCalendarEventsLazy(
        calendarIds: Set<String>,
        maxEvents: Int = CalendarConstants.MAX_EVENTS_PER_QUERY,
        offset: Int = 0
    ): Result<EventPage>
    
    /**
     * Lädt Events für spezifische Kalender
     * 
     * PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
     * 
     * @param calendarIds Set der Kalender-IDs für die Events geladen werden sollen
     * @return Result mit Liste der Calendar Events oder Fehler
     */
    suspend fun getCalendarEvents(
        calendarIds: Set<String>
    ): Result<List<CalendarEvent>>
    
    /**
     * Überprüft ob ein gültiges Access Token verfügbar ist
     * 
     * @return Boolean - true wenn gültiges Token verfügbar
     */
    suspend fun hasValidAccessToken(): Boolean
    
    /**
     * Lädt Events für spezifische Kalender mit Cache-Support
     * 
     * PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
     * 
     * @param calendarIds Set der Kalender-IDs für die Events geladen werden sollen
     * @param forceRefresh Bypass Cache und lade Events direkt von API
     * @return Result mit Liste der Calendar Events oder Fehler
     */
    suspend fun getCalendarEventsWithCache(
        calendarIds: Set<String>,
        forceRefresh: Boolean = false
    ): Result<List<CalendarEvent>>
    
    /**
     * Invalidiert Cache für spezifische Kalender
     * 
     * @param calendarIds Set der Kalender-IDs deren Cache invalidiert werden soll
     */
    suspend fun invalidateCalendarCache(calendarIds: Set<String>)
    
    /**
     * Leert den kompletten Event-Cache
     */
    suspend fun clearEventCache()
    
    /**
     * Cache-Statistiken für Debugging
     * @return String mit Cache-Informationen
     */
    suspend fun getCacheStats(): String
    
    /**
     * Testet die Kalender-Verbindung durch Laden der verfügbaren Kalender
     * 
     * @return Result mit Boolean (true wenn Verbindung erfolgreich) oder Fehler
     */
    suspend fun testCalendarConnection(): Result<Boolean>
}
