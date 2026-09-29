package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarItem
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent

/**
 * Interface für Calendar Repository Operations
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
     */
    suspend fun getCalendarsWithToken(accessToken: String): Result<List<CalendarItem>>
    
    /**
     * Lädt Events mit Cache-Unterstützung; [forceRefresh] umgeht den Cache.
     */
    suspend fun getCalendarEventsWithCache(
        accessToken: String,
        calendarId: String,
        forceRefresh: Boolean = false
    ): Result<List<CalendarEvent>>
    
    /**
     * Invalidiert Cache für spezifischen Kalender
     */
    suspend fun invalidateCalendarCache(calendarId: String)
}
