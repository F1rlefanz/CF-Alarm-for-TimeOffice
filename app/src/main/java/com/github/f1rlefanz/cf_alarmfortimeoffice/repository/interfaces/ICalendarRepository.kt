package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarItem
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent

/**
 * Interface für Calendar Repository Operations
 *
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: Abstraktion statt konkrete Implementierung
 * - Testbarkeit: UseCase/ViewModel kann mit Mock-Repository getestet werden
 * - Flexibilität: Implementierung austauschbar (Local/Remote/Hybrid)
 *
 * ENTFERNT (Aufraeumrunde 24): `setContext`, `getCalendarEventsWithToken`,
 * `getCalendarEventsWithPagination` samt Rueckgabetyp `EventsPage` und `cleanup` - im ganzen
 * Baum ohne Aufrufstelle, nur Deklaration, Implementierung und Test-Doubles. Mit
 * `getCalendarEventsWithPagination` fiel die letzte API-level-Pagination weg: der gestaffelte
 * Weg laeuft ueber `ICalendarUseCase.getCalendarEventsLazy` und das Lazy-Praefix, nicht ueber
 * `pageToken`.
 *
 * WER API-LEVEL-PAGINATION WIEDER VERDRAHTET, erbt diese Falle - sie stand im Rumpf der
 * entfernten Funktion und gilt unabhaengig von ihr: Eine SEITE darf NICHT unter dem Schluessel
 * im `CalendarEventCache` landen, aus dem [getCalendarEventsWithCache] liest. Der Cache
 * beantwortet "alle Events der naechsten 14 Tage"; bis v1.27.0 legte die erste Seite ihr
 * Ergebnis dort ab, eine bewusst partielle Seite wurde so zur vollstaendigen Liste - und damit
 * zur Loeschgrundlage fuer `syncAlarms()`. Siehe CLAUDE.md: "Eine unvollstaendige Eventliste ist
 * KEINE Loeschgrundlage."
 */
interface ICalendarRepository {

    /**
     * Lädt verfügbare Kalender mit dem übergebenen Access Token
     * 
     * @param accessToken OAuth2 Access Token für Google Calendar API
     * @return Result mit Liste der verfügbaren Kalender oder Fehler
     */
    suspend fun getCalendarsWithToken(accessToken: String): Result<List<CalendarItem>>
    
    /**
     * Lädt Events mit Cache-Unterstützung und Force-Refresh Option
     * 
     * PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
     *
     * @param accessToken OAuth2 Access Token für Google Calendar API
     * @param calendarId ID des Kalenders, für den Events geladen werden sollen
     * @param forceRefresh Bypass Cache und lade Events direkt von API
     * @return Result mit Liste der Calendar Events oder Fehler
     */
    suspend fun getCalendarEventsWithCache(
        accessToken: String,
        calendarId: String,
        forceRefresh: Boolean = false
    ): Result<List<CalendarEvent>>
    
    /**
     * Invalidiert Cache für spezifischen Kalender
     * 
     * PHASE 2 CLEANUP: daysAhead removed - cache invalidation now for fixed 14 days
     * 
     * @param calendarId ID des Kalenders, dessen Cache invalidiert werden soll
     */
    suspend fun invalidateCalendarCache(calendarId: String)
    
    /**
     * Leert den kompletten Event-Cache
     */
    suspend fun clearEventCache()
    
    /**
     * Cache-Statistiken für Debugging
     * @return String mit Cache-Informationen
     */
    suspend fun getCacheStats(): String
}
