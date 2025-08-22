package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarItem
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants

/**
 * Events Page für echte Google Calendar API Pagination
 */
data class EventsPage(
    val events: List<CalendarEvent>,
    val nextPageToken: String?,
    val hasMorePages: Boolean
)

/**
 * Interface für Calendar Repository Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: Abstraktion statt konkrete Implementierung
 * - Testbarkeit: UseCase/ViewModel kann mit Mock-Repository getestet werden
 * - Flexibilität: Implementierung austauschbar (Local/Remote/Hybrid)
 * - OFFLINE SUPPORT: Context für Netzwerk-Konnektivitätsprüfungen
 * - LAZY LOADING: Echte API-level Pagination mit pageToken Support
 */
interface ICalendarRepository {
    
    /**
     * OFFLINE SUPPORT: Setzt Android Context für Netzwerk-Konnektivitätsprüfungen
     * @param context Application Context
     */
    fun setContext(context: Context)
    
    /**
     * Lädt verfügbare Kalender mit dem übergebenen Access Token
     * 
     * @param accessToken OAuth2 Access Token für Google Calendar API
     * @return Result mit Liste der verfügbaren Kalender oder Fehler
     */
    suspend fun getCalendarsWithToken(accessToken: String): Result<List<CalendarItem>>
    
    /**
     * Lädt Events für einen spezifischen Kalender
     * 
     * @param accessToken OAuth2 Access Token für Google Calendar API
     * @param calendarId ID des Kalenders, für den Events geladen werden sollen
     * @param daysAhead Anzahl Tage in die Zukunft (Standard: DEFAULT_DAYS_AHEAD)
     * @return Result mit Liste der Calendar Events oder Fehler
     */
    suspend fun getCalendarEventsWithToken(
        accessToken: String,
        calendarId: String,
        daysAhead: Int = CalendarConstants.DEFAULT_DAYS_AHEAD
    ): Result<List<CalendarEvent>>
    
    /**
     * Lädt Events mit Cache-Unterstützung und Force-Refresh Option
     * 
     * @param accessToken OAuth2 Access Token für Google Calendar API
     * @param calendarId ID des Kalenders, für den Events geladen werden sollen
     * @param daysAhead Anzahl Tage in die Zukunft
     * @param forceRefresh Bypass Cache und lade Events direkt von API
     * @return Result mit Liste der Calendar Events oder Fehler
     */
    suspend fun getCalendarEventsWithCache(
        accessToken: String,
        calendarId: String,
        daysAhead: Int = CalendarConstants.DEFAULT_DAYS_AHEAD,
        forceRefresh: Boolean = false
    ): Result<List<CalendarEvent>>
    
    /**
     * LAZY LOADING: Lädt Events mit Google Calendar API Pagination
     * @param accessToken OAuth2 Access Token
     * @param calendarId Kalender-ID
     * @param daysAhead Anzahl Tage in die Zukunft
     * @param maxResults Maximale Anzahl Events pro Seite
     * @param pageToken Optional: Token für nächste Seite
     * @return Result mit Events und nextPageToken
     */
    suspend fun getCalendarEventsWithPagination(
        accessToken: String,
        calendarId: String,
        daysAhead: Int = CalendarConstants.DEFAULT_DAYS_AHEAD,
        maxResults: Int = 50,
        pageToken: String? = null
    ): Result<EventsPage>
    
    /**
     * Invalidiert Cache für spezifischen Kalender
     * 
     * @param calendarId ID des Kalenders, dessen Cache invalidiert werden soll
     * @param daysAhead Optional: Spezifischer Zeitbereich, null für alle
     */
    suspend fun invalidateCalendarCache(calendarId: String, daysAhead: Int? = null)
    
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
     * Cleanup-Methode für Repository-Ressourcen
     * Sollte aufgerufen werden wenn Repository nicht mehr benötigt wird
     */
    fun cleanup()
}
