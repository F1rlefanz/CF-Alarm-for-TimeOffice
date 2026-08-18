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
 * Ergebnis eines Kalender-Abrufs MIT der Angabe, ob es vollstaendig ist.
 *
 * [isComplete] ist die einzige Frage, die loeschende Konsumenten stellen duerfen: nur wenn JEDER
 * angefragte Kalender geantwortet hat, bedeutet "kein Event mit dieser id" auch wirklich "Termin
 * geloescht". Siehe [ICalendarUseCase.getCalendarEventsWithStatus].
 */
data class CalendarFetchOutcome(
    val events: List<CalendarEvent>,
    val requestedCalendars: Int,
    /**
     * Die IDs der Kalender, die NICHT geantwortet haben - nicht bloss ihre Anzahl.
     *
     * Bis v1.25.3 stand hier ein blosses `failedCalendars: Int`, und genau das war die Luecke:
     * die Sperren unten wussten, DASS etwas fehlt, konnten dem Nutzer aber nicht sagen, WAS. Ein
     * dauerhaft nicht abrufbarer Kalender (geloescht, Freigabe entzogen, Feed-Quelle
     * abgeschaltet) haelt den Alarm-Sync unbefristet an - und ohne den Namen ist die Meldung
     * darueber nicht handlungsfaehig ("irgendein Kalender" laesst sich nicht abwaehlen).
     *
     * [failedCalendars] bleibt als ABGELEITETE Property bestehen: eine Wahrheit, kein zweites
     * Feld, das auseinanderlaufen kann.
     */
    val failedCalendarIds: Set<String> = emptySet()
) {
    val failedCalendars: Int get() = failedCalendarIds.size

    val isComplete: Boolean get() = failedCalendarIds.isEmpty()
}

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
     * Wie [getCalendarEventsWithCache], liefert aber zusaetzlich, WIE VOLLSTAENDIG das Ergebnis ist.
     *
     * WARUM ES DIESE ZWEITE FASSUNG BRAUCHT (gleiche Ueberlegung wie
     * `DimScheduleUseCase.previewTimelineWithStatus()`): Ein Teilerfolg - mindestens ein Kalender
     * geladen, mindestens einer gescheitert - bleibt bewusst `Result.success` (siehe
     * `resolveCalendarAuthorizationOutcome`: ein einzelner kaputter Kalender darf nicht die ganze
     * Anmeldung in Frage stellen). Fuer die ANZEIGE ist das richtig.
     *
     * Fuer jeden Konsumenten, der aus dem Fehlen eines Events auf "Termin geloescht" schliesst, ist
     * es toedlich: `AlarmUseCase.syncAlarms()` loescht im Delta-Sync jeden Alarm, dessen eventId
     * nicht in der uebergebenen Liste steht, und `BootReceiver` loescht jeden Alarm ohne Treffer in
     * der Event-Map. Faellt von zwei ausgewaehlten Kalendern der Dienstplan-Feed aus, waehrend der
     * private Kalender antwortet, sind "dieses Event gibt es nicht mehr" und "dieser Kalender hat
     * gerade nicht geantwortet" auf der reinen Liste NICHT mehr unterscheidbar - alle Schicht-Wecker
     * werden geloescht, im Repository, im AlarmManager und im Direct-Boot-Spiegel.
     *
     * Loeschende Konsumenten muessen deshalb ueber diese Fassung gehen und bei
     * [CalendarFetchOutcome.isComplete] == false auf das Loeschen verzichten (lieber ein veralteter
     * Wecker als gar keiner).
     */
    suspend fun getCalendarEventsWithStatus(
        calendarIds: Set<String>,
        forceRefresh: Boolean = false
    ): Result<CalendarFetchOutcome>
    
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
