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
 * beantwortet "alle Events des Abruf-Fensters"; bis v1.27.0 legte die erste Seite ihr
 * Ergebnis dort ab, eine bewusst partielle Seite wurde so zur vollstaendigen Liste - und damit
 * zur Loeschgrundlage fuer `syncAlarms()`. Siehe CLAUDE.md: "Eine unvollstaendige Eventliste ist
 * KEINE Loeschgrundlage."
 */
/**
 * Ergebnis EINES Kalenderabrufs (oder Cache-Treffers): die Events und das Fenster, für das sie
 * vollständig sind.
 *
 * [horizontEnde] ist der `timeMax` der Abfrage (Epoch-Millis, exklusiv auf den Terminbeginn) - bei
 * einem Cache-Treffer der des URSPRÜNGLICHEN Abrufs, nicht "jetzt + Tage". Genau darüber weiß ein
 * löschender Konsument, wo "nicht in der Liste" aufhört, "Termin gelöscht" zu bedeuten (#51).
 */
data class KalenderEventAbruf(
    val events: List<CalendarEvent>,
    val fensterTage: Int,
    val horizontEnde: Long
)

interface ICalendarRepository {

    /**
     * Lädt verfügbare Kalender mit dem übergebenen Access Token
     */
    suspend fun getCalendarsWithToken(accessToken: String): Result<List<CalendarItem>>
    
    /**
     * Lädt ALLE Events der nächsten [fensterTage] Tage ab jetzt, mit Cache-Unterstützung;
     * [forceRefresh] umgeht den Cache. Ein Cache-Eintrag eines ANDEREN Fensters zählt als
     * Fehltreffer.
     *
     * [fensterTage] ist Pflicht (kein Default): der Aufrufer liest die Einstellung EINMAL pro Abruf
     * (`KalenderVorausschauPrefs`) und gibt sie hier hinein - eine stille 14 an dieser Stelle wäre
     * die zweite Wahrheit neben der Einstellung.
     */
    suspend fun getCalendarEventsWithCache(
        accessToken: String,
        calendarId: String,
        forceRefresh: Boolean = false,
        fensterTage: Int
    ): Result<KalenderEventAbruf>
    
    /**
     * Invalidiert Cache für spezifischen Kalender
     */
    suspend fun invalidateCalendarCache(calendarId: String)
}
