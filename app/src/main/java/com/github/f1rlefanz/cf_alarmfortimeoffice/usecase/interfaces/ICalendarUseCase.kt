package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants

/**
 * Data classes für paginierte Ergebnisse
 */
data class CalendarPage(
    val calendars: List<AndroidCalendar>,
    val page: Int,
    val totalCalendars: Int,
    val hasNextPage: Boolean
)

/**
 * Eine Seite aus dem Lazy-Laden: [events] ist ein PRAEFIX der sortierten Gesamtliste, nie die
 * vollstaendige Eventliste - und damit keine Loeschgrundlage (CLAUDE.md, "Kalender").
 *
 * Bewusst OHNE eigenes "es gibt mehr"-Feld: bis v1.44 stand hier `hasMore`, das niemand las
 * (#112). Ob nachgeladen werden kann, entscheidet allein der Verbraucher
 * (`CalendarViewModel.loadEventsForSelectedCalendars`) aus [totalEvents] und der Seitengroesse -
 * eine zweite Rechnung daneben waere eine zweite Wahrheit. Wer die Vollstaendigkeit braucht,
 * fragt `getCalendarEventsWithStatus()` nach `isComplete`, nicht diese Seite.
 */
data class EventPage(
    val events: List<CalendarEvent>,
    val totalEvents: Int,
    /** Ende des Abruf-Fensters der zugrunde liegenden Gesamtliste - siehe [CalendarFetchOutcome.horizontEnde]. */
    val horizontEnde: Long? = null
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
    val failedCalendarIds: Set<String> = emptySet(),
    /**
     * Bis wohin (Epoch-Millis, exklusiv) diese Liste den Kalender gelesen hat - das KLEINSTE
     * Abruf-Ende der beteiligten Kalender (ein Cache-Eintrag kann einige Minuten aelter sein als
     * ein frischer Abruf). `null` = unbekannt (kein Kalender angefragt, oder ein Test-Doppel).
     *
     * WARUM DER HORIZONT MIT DER LISTE REIST (#51): Die Vorausschau ist einstellbar. "Vollstaendig"
     * ([isComplete]) heisst nur "vollstaendig fuer DIESES Fenster" - nach einer verkleinerten
     * Vorausschau (28 -> 7 Tage) sagt die Liste ueber Tag 8..28 nichts. Ein loeschender Konsument
     * muss deshalb wissen, bis wohin sie reicht (`syncAlarms(..., abrufHorizontEnde)`,
     * `BootAlarmValidation.beurteile`). Ihn getrennt aus der Einstellung zu lesen, waere eine
     * zweite Wahrheit: zwischen Abruf und Sync kann der Nutzer umgestellt haben.
     */
    val horizontEnde: Long? = null
) {
    val failedCalendars: Int get() = failedCalendarIds.size

    val isComplete: Boolean get() = failedCalendarIds.isEmpty()
}

/**
 * Interface für Calendar UseCase Operations
 *
 * ENTFERNT (Aufraeumrunde 24): `getCalendarEvents` und `testCalendarConnection` - im ganzen Baum
 * ohne Aufrufstelle. `getCalendarEvents` war ein reiner Delegat an
 * [getCalendarEventsWithCache]; wer Events braucht, nimmt direkt eine der beiden verbliebenen
 * Fassungen und beantwortet damit die Frage nach der Vollstaendigkeit
 * ([getCalendarEventsWithStatus] fuer jeden loeschenden Konsumenten).
 */
interface ICalendarUseCase {
    
    /**
     * Lädt verfügbare Kalender für den aktuell authentifizierten User
     */
    suspend fun getAvailableCalendars(): Result<List<AndroidCalendar>>
    
    /**
     * Lädt verfügbare Kalender seitenweise.
     *
     * @param page Seiten-Nummer (beginnend bei 0)
     */
    suspend fun getAvailableCalendarsPaginated(
        page: Int = 0,
        pageSize: Int = 20
    ): Result<CalendarPage>
    
    /**
     * Lädt Events gestaffelt ([offset], höchstens [maxEvents]).
     */
    suspend fun getCalendarEventsLazy(
        calendarIds: Set<String>,
        maxEvents: Int = CalendarConstants.MAX_EVENTS_PER_QUERY,
        offset: Int = 0
    ): Result<EventPage>
    
    /**
     * Überprüft ob ein gültiges Access Token verfügbar ist
     */
    suspend fun hasValidAccessToken(): Boolean
    
    /**
     * Lädt Events für spezifische Kalender mit Cache-Support; [forceRefresh] umgeht den Cache.
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
     */
    suspend fun invalidateCalendarCache(calendarIds: Set<String>)
}
