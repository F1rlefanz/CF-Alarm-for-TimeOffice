package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.EventsPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.CalendarList
import com.google.api.services.calendar.model.Events
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarItem(val id: String, val displayName: String)

/**
 * Eine Antwortseite der Google-Calendar-API: ihre Eintraege und der Token auf die naechste Seite.
 */
internal data class ApiPage<T>(
    val items: List<T>,
    val nextPageToken: String?
)

/**
 * Holt ALLE Seiten einer Google-Calendar-Abfrage und gibt sie in Antwortreihenfolge zurueck.
 *
 * WARUM DAS KEIN EFFIZIENZTHEMA IST, SONDERN EINE ZUSICHERUNG:
 * Bis v1.27.0 fragte der Event-Abruf genau EINE Seite mit `maxResults = 50` ab und forderte
 * `nextPageToken` nicht einmal an. Eine abgeschnittene Liste war damit von einer vollstaendigen
 * nicht zu unterscheiden - und sie erreichte ueber `CalendarFetchOutcome.isComplete == true` die
 * loeschenden Konsumenten. Dort heisst "kein Event mit dieser id" gleichbedeutend "Termin
 * geloescht": `syncAlarms()` cancelt den Systemalarm, loescht ihn aus Repository und
 * Direct-Boot-Spiegel und meldet "Schicht entfernt". Die Regel der CLAUDE.md - eine unvollstaendige
 * Eventliste ist KEINE Loeschgrundlage - kannte bis dahin nur zwei Quellen der Unvollstaendigkeit
 * (Teilerfolg einzelner Kalender, Lazy-Praefix); die Kappung bei 50 Treffern war eine dritte, die
 * keine der Sperren sehen konnte.
 *
 * WARUM DER ABBRUCH WIRFT STATT ZU KUERZEN:
 * Ist nach [maxPages] Seiten noch ein Token offen, waere das Ergebnis wieder eine still gekuerzte
 * Liste. Der Wurf laeuft stattdessen als Fehler DIESES Kalenders in
 * `CalendarUseCase.getCalendarEventsWithStatus()`, landet dort in `failedCalendarIds` und macht
 * `isComplete` zu false - die vorhandenen Sperren greifen, es wird nichts geloescht, und die
 * Status-Karte nennt den betroffenen Kalender namentlich. Genau der Kanal, den die App fuer
 * "unvollstaendig" schon hat.
 */
internal suspend fun <T> collectAllPages(
    maxPages: Int,
    label: String,
    fetchPage: suspend (pageToken: String?) -> ApiPage<T>
): List<T> {
    require(maxPages > 0) { "maxPages muss mindestens 1 sein" }

    val collected = mutableListOf<T>()
    var pageToken: String? = null
    var pagesFetched = 0

    while (true) {
        val page = fetchPage(pageToken)
        collected.addAll(page.items)
        pagesFetched++

        // Ein leerer Token ist kein Token: Google liefert das Feld bei der letzten Seite gar
        // nicht - eine leere Zeichenkette waere eine Endlosschleife auf derselben Seite.
        pageToken = page.nextPageToken?.takeIf { it.isNotBlank() }

        if (pageToken == null) break

        if (pagesFetched >= maxPages) {
            throw AppError.CalendarAccessError(
                "$label: nach $pagesFetched Seiten sind weitere Eintraege offen - der Abruf waere " +
                    "abgeschnitten und darf deshalb nicht als vollstaendig gelten"
            )
        }
    }

    if (pagesFetched > 1) {
        Logger.i(
            LogTags.CALENDAR_API,
            "$label: ${collected.size} Eintraege ueber $pagesFetched Seiten geladen"
        )
    }

    return collected
}

/**
 * CalendarRepository implementiert ICalendarRepository Interface
 * für die Google-Calendar-Anbindung.
 *
 * Der kurzlebige [CalendarEventCache] fasst nur Abrufe DESSELBEN Bedienvorgangs zusammen; jeder
 * Abruf, der wirklich an die API geht, holt ALLE Seiten des 14-Tage-Fensters ([collectAllPages]).
 * Beides hängt zusammen: was dieses Repository zurückgibt, gilt weiter oben als vollständige Liste
 * und ist damit eine Löschgrundlage für `syncAlarms()`.
 */
@Singleton
class CalendarRepository @Inject constructor(
    @param:ApplicationContext private var context: Context
) : ICalendarRepository {
    
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val eventCache = CalendarEventCache()
    
    // Kein Object-Pooling / String-Interning: Event-Menge ist gering (Dutzende pro 6h-Zyklus)
    
    private var cachedService: Calendar? = null
    private var cachedToken: String? = null

    override fun setContext(context: Context) {
        this.context = context
    }

    override suspend fun getCalendarsWithToken(accessToken: String): Result<List<CalendarItem>> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarRepository.getCalendarsWithToken") {
            Logger.d(LogTags.CALENDAR_API, "Loading available calendars...")
            val service = getCalendarService(accessToken)

            try {
                // ALLE SEITEN, nicht nur die erste: `calendarList().list()` liefert ohne
                // Seitenschleife nur die Google-Standardseite (100 Eintraege), und `nextPageToken`
                // fehlte bis v1.27.0 sogar in der Feldmaske - die Diagnose-Ausgabe darunter loggte
                // also einen Wert, der garantiert null war und eine Kuerzung nie anzeigen konnte.
                // Ein so verschluckter Kalender fehlt in der Auswahl, ohne jeden Hinweis.
                val calendarEntries = collectAllPages(
                    maxPages = CalendarConstants.MAX_CALENDAR_LIST_PAGES,
                    label = "Kalenderliste"
                ) { pageToken ->
                    val request = service.calendarList().list()
                        .setFields("items(id,summary,primary,accessRole),nextPageToken")
                        .setMinAccessRole("reader")
                    if (pageToken != null) {
                        request.setPageToken(pageToken)
                    }
                    val page: CalendarList = request.execute()
                    ApiPage(page.items ?: emptyList(), page.nextPageToken)
                }

                Logger.d(LogTags.CALENDAR_API, "Calendar API response received: ${calendarEntries.size} items")

                if (calendarEntries.isEmpty()) {
                    Logger.w(LogTags.CALENDAR_API, "No calendars found in Google Calendar API response")
                    Logger.i(LogTags.CALENDAR_API, "DIAGNOSTIC: User account appears to have no calendars or calendar access is restricted")
                } else {
                    Logger.d(LogTags.CALENDAR_API, "Found calendars: ${calendarEntries.map { "${it.summary} (${it.id})" }}")
                    Logger.i(LogTags.CALENDAR_API, "DIAGNOSTIC: Successfully loaded ${calendarEntries.size} calendars")
                }

                val calendars = calendarEntries.mapNotNull { calendarEntry ->
                    try {
                        CalendarItem(
                            id = calendarEntry.id ?: return@mapNotNull null,
                            displayName = calendarEntry.summary ?: "Unnamed Calendar"
                        )
                    } catch (e: Exception) {
                        Logger.w(LogTags.CALENDAR_API, "Failed to parse calendar entry", e)
                        null
                    }
                }

                Logger.i(LogTags.CALENDAR_API, "${calendars.size} calendars loaded successfully")
                calendars
            } catch (e: Exception) {
                throw mapCalendarException(e)
            }
        }
    }

    override suspend fun getCalendarEventsWithToken(
        accessToken: String,
        calendarId: String
    ): Result<List<CalendarEvent>> {
        // PHASE 2 CLEANUP: daysAhead fixed at 14 days
        return getCalendarEventsWithCache(accessToken, calendarId, forceRefresh = false)
    }

    override suspend fun getCalendarEventsWithCache(
        accessToken: String,
        calendarId: String,
        forceRefresh: Boolean
    ): Result<List<CalendarEvent>> = withContext(Dispatchers.IO) {
        // PHASE 2 CLEANUP: daysAhead fixed at 14 days
        val daysAhead = CalendarConstants.DEFAULT_DAYS_AHEAD
        SafeExecutor.safeExecute("CalendarRepository.getEventsWithCache") {
            
            if (!forceRefresh && eventCache.isCached(calendarId)) {
                val cachedEvents = eventCache.get(calendarId)
                if (cachedEvents != null) {
                    Logger.i(LogTags.CALENDAR_CACHE, "Returning ${cachedEvents.size} cached events")
                    
                    return@safeExecute cachedEvents
                }
            }
            
            if (forceRefresh) {
                Logger.i(LogTags.CALENDAR_API, "Force refresh requested - bypassing cache")
            }
            
            Logger.d(LogTags.CALENDAR_API, "Loading events from API...")
            val service = getCalendarService(accessToken)

            try {
                val now = LocalDateTime.now()
                val timeMin = now.atZone(ZoneId.systemDefault()).toInstant().toString()
                val timeMax = now.plusDays(daysAhead.toLong())
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toString()

                // DIESE LISTE MUSS VOLLSTAENDIG SEIN - sie ist eine Loeschgrundlage.
                //
                // Bis v1.27.0 stand hier eine EINZELNE Abfrage mit `setMaxResults(50)`, und
                // `nextPageToken` fehlte in der Feldmaske: eine bei 50 Treffern abgeschnittene
                // Liste war von einer vollstaendigen nicht zu unterscheiden. Sie wanderte
                // unveraendert in `CalendarFetchOutcome`, dessen `isComplete` nur nach
                // fehlgeschlagenen Kalendern fragt - der gekappte Abruf galt also als
                // VOLLSTAENDIG. Genau das ist in dieser App die Erlaubnis zu loeschen:
                // `syncAlarms()` entfernt jeden Alarm, dessen eventId in der Liste fehlt, cancelt
                // den Systemalarm, raeumt den Direct-Boot-Spiegel und meldet dem Nutzer "Schicht
                // entfernt" - obwohl der Termin unveraendert im Kalender steht. Ein Kalender mit
                // mehr als 50 Terminen in 14 Tagen (Dienstplan plus private Eintraege) reicht.
                val rawEvents = collectAllPages(
                    maxPages = CalendarConstants.MAX_EVENT_PAGES_PER_CALENDAR,
                    label = "Events von Kalender ${calendarId.take(8)}..."
                ) { pageToken ->
                    val request = service.events().list(calendarId)
                        .setTimeMin(com.google.api.client.util.DateTime(timeMin))
                        .setTimeMax(com.google.api.client.util.DateTime(timeMax))
                        .setOrderBy("startTime")
                        .setSingleEvents(true)
                        .setMaxResults(CalendarConstants.EVENTS_PER_API_PAGE)
                        .setFields("items(id,summary,start,end),nextPageToken")

                    if (pageToken != null) {
                        request.setPageToken(pageToken)
                    }

                    val page: Events = request.execute()
                    ApiPage(page.items ?: emptyList(), page.nextPageToken)
                }

                Logger.i(LogTags.CALENDAR_API, "${rawEvents.size} events loaded for next $daysAhead days")

                // PERFORMANCE: Use optimized event processing
                val calendarEvents = processEventsWithOptimization(rawEvents, calendarId)

                eventCache.put(calendarId, calendarEvents)
                Logger.d(LogTags.CALENDAR_CACHE, "${calendarEvents.size} events cached for follow-up requests")

                calendarEvents
            } catch (e: Exception) {
                throw mapCalendarException(e)
            }
        }
    }
    
    override suspend fun getCalendarEventsWithPagination(
        accessToken: String,
        calendarId: String,
        maxResults: Int,
        pageToken: String?
    ): Result<EventsPage> = withContext(Dispatchers.IO) {
        // PHASE 2 CLEANUP: daysAhead fixed at 14 days
        val daysAhead = CalendarConstants.DEFAULT_DAYS_AHEAD
        SafeExecutor.safeExecute("CalendarRepository.getCalendarEventsWithPagination") {
            Logger.d(LogTags.CALENDAR_API, "Loading events with pagination: pageToken=${pageToken?.take(10)}..., maxResults=$maxResults")
            val service = getCalendarService(accessToken)

            try {
                val now = LocalDateTime.now()
                val timeMin = now.atZone(ZoneId.systemDefault()).toInstant().toString()
                val timeMax = now.plusDays(daysAhead.toLong())
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toString()

                val eventsRequest = service.events().list(calendarId)
                    .setTimeMin(com.google.api.client.util.DateTime(timeMin))
                    .setTimeMax(com.google.api.client.util.DateTime(timeMax))
                    .setOrderBy("startTime")
                    .setSingleEvents(true)
                    .setMaxResults(maxResults)
                    .setFields("items(id,summary,start,end),nextPageToken")

                if (pageToken != null) {
                    eventsRequest.pageToken = pageToken
                }

                val result = eventsRequest.execute()
                val events = result.items ?: emptyList()
                val nextPageToken = result.nextPageToken

                Logger.i(LogTags.CALENDAR_API, "${events.size} events loaded for page (maxResults=$maxResults), hasMore=${nextPageToken != null}")

                // PERFORMANCE: Use optimized event processing
                val calendarEvents = processEventsWithOptimization(events, calendarId)

                // BEWUSST KEIN eventCache.put HIER.
                //
                // Diese Funktion liefert eine SEITE, der Cache aber beantwortet die Frage "alle
                // Events der naechsten 14 Tage". Bis v1.27.0 legte die erste Seite ihr Ergebnis
                // unter demselben Schluessel ab, aus dem getCalendarEventsWithCache liest - eine
                // bewusst partielle Seite waere dort zur vollstaendigen Liste geworden und damit
                // zur Loeschgrundlage fuer syncAlarms(). Aktuell hat die Funktion keinen
                // Produktivaufrufer; wer sie verdrahtet, soll die Falle nicht miterben.

                EventsPage(
                    events = calendarEvents,
                    nextPageToken = nextPageToken,
                    hasMorePages = nextPageToken != null
                )
            } catch (e: Exception) {
                throw mapCalendarException(e)
            }
        }
    }
    
    override suspend fun invalidateCalendarCache(calendarId: String) {
        // PHASE 2 CLEANUP: Always invalidate for fixed 14 days
        eventCache.invalidateCalendar(calendarId)
    }
    
    override suspend fun clearEventCache() {
        eventCache.clear()
    }
    
    override suspend fun getCacheStats(): String {
        val cacheStats = eventCache.getCacheStats()
        return buildString {
            appendLine("📊 CALENDAR CACHE STATS:")
            appendLine("▸ Cache: $cacheStats")
        }
    }
    
    override fun cleanup() {
        Logger.d(LogTags.REPOSITORY, "Clearing CalendarRepository resources")
        cachedService = null
        cachedToken = null
    }
    
    private fun getCalendarService(accessToken: String): Calendar {
        if (cachedService == null || cachedToken != accessToken) {
            Logger.d(LogTags.CALENDAR_API, "🔗 API-SERVICE: Creating Calendar service")
            Logger.d(LogTags.CALENDAR_API, "📊 TOKEN-INFO: Token length=${accessToken.length}")
            
            // DIAGNOSTIC: Check if this looks like a real OAuth2 token
            when {
                accessToken == "valid_credential_token" -> {
                    Logger.e(LogTags.CALENDAR_API, "❌ CRITICAL: Still using placeholder token 'valid_credential_token'!")
                    Logger.e(LogTags.CALENDAR_API, "💡 FIX-HINT: OAuth2 token integration is broken - check AuthViewModel and ModernOAuth2TokenManager")
                }
                accessToken.startsWith("ya29.") -> {
                    Logger.business(LogTags.CALENDAR_API, "✅ TOKEN-OK: Real Google OAuth2 access token detected (ya29.)")
                }
                accessToken.length < 10 -> {
                    Logger.w(LogTags.CALENDAR_API, "⚠️ TOKEN-SUSPICIOUS: Token seems too short (${accessToken.length} chars)")
                }
                else -> {
                    Logger.d(LogTags.CALENDAR_API, "🔍 TOKEN-INFO: Using token of ${accessToken.length} chars")
                }
            }
            
            // Use standard OAuth2 Bearer token authentication
            Logger.d(LogTags.CALENDAR_API, "🔐 AUTH-METHOD: Using OAuth2 Bearer token authentication")
            val requestInitializer = HttpRequestInitializer { request: HttpRequest ->
                request.headers.authorization = "Bearer $accessToken"
            }
            
            cachedService = Calendar.Builder(transport, jsonFactory, requestInitializer)
                .setApplicationName("CF-Alarm for TimeOffice")
                .build()
            cachedToken = accessToken
            
            Logger.d(LogTags.CALENDAR_API, "✅ API-SERVICE: Calendar service ready for API calls")
        }
        return cachedService!!
    }
    
    private fun mapCalendarException(e: Exception): AppError {
        Logger.e(LogTags.CALENDAR_API, "Calendar API error", e)
        return when (e) {
            // Ein bereits klassifizierter Fehler bleibt, was er ist. Ohne diesen Zweig landete
            // z.B. der Abbruch von collectAllPages ("Liste waere abgeschnitten") im else-Fall und
            // wurde zu einem nichtssagenden UnknownError - die Meldung an den Nutzer haette dann
            // nicht mehr gesagt, WELCHER Kalender warum nicht vollstaendig gelesen werden konnte.
            is AppError -> e
            is GoogleJsonResponseException -> {
                when {
                    e.statusCode == 401 -> AppError.AuthenticationError("Google Calendar authentication failed")

                    // Hinter 403 stecken ZWEI grundverschiedene Faelle - frueher wurden beide
                    // pauschal zu PermissionError, und der Scope-Fall blieb dadurch ohne
                    // Rettung liegen (Log 14.07., 14:14:45: ACCESS_TOKEN_SCOPE_INSUFFICIENT).
                    e.statusCode == 403 && isInsufficientScope(e) ->
                        // Das Token ist gueltig, traegt aber den Kalender-Scope nicht. Kein
                        // Problem des Nutzers mit einem Kalender, sondern ein unbrauchbares
                        // Token - die Rettung ist exakt dieselbe wie bei 401: verwerfen und
                        // die Zustimmung neu einholen. Deshalb AuthenticationError, damit
                        // CalendarUseCase.invalidateTokenIfRejectedByGoogle() greift.
                        AppError.AuthenticationError("Dem Token fehlt die Kalender-Berechtigung")

                    e.statusCode == 403 ->
                        // Echtes Berechtigungsproblem (z.B. Kalender nicht freigegeben).
                        // Hier wuerde eine Neuanmeldung nichts bringen.
                        //
                        // message= NAMENTLICH setzen: der erste Parameter von PermissionError
                        // heisst `permission` (erwartet einen android.permission.*-Namen).
                        // Positional gebunden landete der Text dort, und ErrorHandler baute
                        // daraus "Berechtigung 'Insufficient permissions for Google Calendar'
                        // verweigert" - genau der Kauderwelsch aus dem Log vom 14.07.
                        AppError.PermissionError(
                            message = "Kein Zugriff auf diesen Google-Kalender"
                        )

                    // 404 ist KEIN Netzwerkfehler: die Calendar-API liefert das haeufig fuer
                    // Kalender, die geloescht oder nicht mehr freigegeben sind. Als
                    // NetworkError gemappt behauptete die App "Keine Internetverbindung",
                    // waehrend das Netz einwandfrei lief.
                    e.statusCode == 404 -> AppError.PermissionError(
                        message = "Kalender nicht gefunden oder nicht mehr freigegeben"
                    )
                    else -> AppError.NetworkError("Google Calendar API error: ${e.statusMessage}")
                }
            }
            is UnknownHostException -> AppError.NetworkError("No internet connection")
            is IOException -> AppError.NetworkError("Network error: ${e.message}")
            else -> AppError.UnknownError("Calendar error: ${e.message}")
        }
    }

    /**
     * Erkennt "das Token traegt den noetigen Scope nicht" hinter einem 403.
     *
     * Google liefert das an zwei Stellen der Antwort - beide werden geprueft, weil sich das
     * Format zwischen API-Versionen schon geaendert hat:
     *   "errors": [{ "reason": "insufficientPermissions", ... }]
     *   "details": [{ "@type": "...ErrorInfo", "reason": "ACCESS_TOKEN_SCOPE_INSUFFICIENT" }]
     * Der details-Block ist ueber die Java-Client-API nicht typisiert erreichbar, daher der
     * zusaetzliche Blick in den Rohtext.
     */
    private fun isInsufficientScope(e: GoogleJsonResponseException): Boolean {
        val reasons = e.details?.errors?.mapNotNull { it.reason }.orEmpty()
        return reasons.any { it.equals("insufficientPermissions", ignoreCase = true) } ||
            e.message?.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT", ignoreCase = true) == true
    }

    
    /**
     * Wandelt Google-Calendar-Events in interne CalendarEvent-Objekte um.
     */
    private fun processEventsWithOptimization(
        events: List<com.google.api.services.calendar.model.Event>,
        calendarId: String
    ): List<CalendarEvent> {
        Logger.d(LogTags.CALENDAR_API, "Processing ${events.size} events")
        val calendarEvents = ArrayList<CalendarEvent>(events.size)

        for (event in events) {
            try {
                CalendarEventConverter.toCalendarEvent(event, calendarId)?.let(calendarEvents::add)
            } catch (e: Exception) {
                Logger.w(LogTags.CALENDAR_API, "Failed to parse event", e)
            }
        }

        Logger.d(LogTags.CALENDAR_API, "Processed ${calendarEvents.size} events")
        return calendarEvents
    }
}

/**
 * Wandelt ein Google-Calendar-Event in ein internes [CalendarEvent] um.
 *
 * Bewusst als eigene, reine Funktion ausserhalb von [CalendarRepository]: die Umrechnung ist die
 * einzige echte Logik am Google-API-Rand und muss ohne Android/Netzwerk testbar sein
 * ([com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarEventConversionTest]).
 *
 * GANZTAEGIGE TERMINE SIND KEINE ZEITGEBUNDENEN. Google liefert entweder `start.dateTime`
 * (Zeitstempel mit Zone) ODER `start.date` (nur Datum, `DateTime.isDateOnly == true`). Beide Felder
 * in einen Topf zu werfen (frueher: `event.start?.dateTime ?: event.start?.date`, dann beide ueber
 * `Instant.ofEpochMilli(value)` in der Systemzone) ist falsch: der `value` eines `date`-Feldes wird
 * in der Google-Bibliothek in einem GMT-Kalender OHNE Uhrzeitanteil berechnet, ist also
 * UTC-Mitternacht. In Europe/Berlin wurde daraus 01:00 (Winter) bzw. 02:00 (Sommer) - in
 * UTC-negativen Zonen sogar der VORTAG. Folgen im Betrieb: "Deine Schicht beginnt um 02:00" in
 * Notification/Vollbild, ein DND-Dienstzeit-Fenster ab 02:00, und ein rund 24h zu langes Fenster,
 * weil `end.date` bei Google END-EXKLUSIV ist (der Folgetag) und das nicht kompensiert wurde.
 *
 * Darum hier: das Kalenderdatum zonenunabhaengig aus dem UTC-Wert lesen und daraus lokale
 * Tagesgrenzen bilden (Beginn 00:00 des ersten Tages, Ende 23:59 des LETZTEN Tages, also
 * `end.date` minus einen Tag). [CalendarEvent.isAllDay] wird dabei gesetzt, damit nachgelagerte
 * Logik den Unterschied ueberhaupt sehen kann.
 *
 * WOFUER [CalendarEvent.isAllDay] GEBRAUCHT WIRD (und warum die Vortags-Frage hier NICHT geloest
 * ist): `ShiftRecognitionEngine.calculateAlarmTime()` rechnet die Weckzeit einen Tag zurueck, sobald
 * sie nach dem Schichtbeginn liegt und die Vorlaufzeit danach <= 12h bleibt (Nachtschicht-Heuristik).
 * Bei einem ganztaegigen Eintrag ist der "Schichtbeginn" nur der 00:00-Anker des Kalendertags, gegen
 * den "danach" nichts sinnvoll pruefbar ist. Die Engine ueberspringt die Heuristik deshalb bei
 * `event.isAllDay` (`if (!event.isAllDay && alarmDateTime.isAfter(shiftStartTime))`), festgehalten in
 * `ShiftRecognitionEngineTest` ("ganztaegiger Termin weckt am Tag des Termins, nicht am Vortag" /
 * "zeitgebundene Nachtschicht weckt weiterhin am Vortag").
 *
 * HIER wird das bewusst NICHT nachgebaut: jeder Zeitpunkt, der die Heuristik durch eine andere
 * Umrechnung verstummen liesse, waere eine erfundene Uhrzeit und wuerde Anzeige und DND-Fenster
 * erneut verfaelschen. Die Umrechnung liefert nur die Wahrheit ueber den Termin; die Bewertung
 * "Nachtschicht oder nicht" gehoert in die Engine. Eine zweite Behandlung an dieser Stelle waere
 * genau die zweite Wahrheit, vor der der Absatz darueber warnt.
 */
internal object CalendarEventConverter {

    internal fun toCalendarEvent(
        event: com.google.api.services.calendar.model.Event,
        calendarId: String
    ): CalendarEvent? {
        val id = event.id ?: "unknown_${System.currentTimeMillis()}"
        val title = event.summary ?: "Unbenannter Termin"

        val startTimed = event.start?.dateTime
        if (startTimed != null) {
            // Zeitgebundener Termin: unveraendertes Verhalten (Zeitstempel in der Systemzone).
            val endTime = event.end?.dateTime?.let { toLocalDateTime(it.value) }
                ?: event.end?.date?.let { utcCalendarDateOf(it.value).atStartOfDay() }
                ?: return null

            return CalendarEvent(
                id = id,
                title = title,
                startTime = toLocalDateTime(startTimed.value),
                endTime = endTime,
                calendarId = calendarId,
                isAllDay = false
            )
        }

        val startDateOnly = event.start?.date ?: return null
        val firstDay = utcCalendarDateOf(startDateOnly.value)

        // end.date ist EXKLUSIV (Google-Semantik): ein einzelner ganzer Tag am 05.08. hat
        // end.date = 06.08. Fehlt das Feld oder ist es unplausibel, gilt "ein Tag".
        val endExclusive = event.end?.date?.let { utcCalendarDateOf(it.value) }
            ?.takeIf { it.isAfter(firstDay) }
            ?: firstDay.plusDays(1)

        return CalendarEvent(
            id = id,
            title = title,
            startTime = firstDay.atStartOfDay(),
            endTime = endExclusive.atStartOfDay().minusMinutes(1),
            calendarId = calendarId,
            isAllDay = true
        )
    }

    private fun toLocalDateTime(epochMillis: Long): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())

    /**
     * Kalendertag eines `date`-Feldes (dateOnly). Bewusst in UTC gelesen: genau so hat die
     * Google-Bibliothek den Wert gebildet (GMT-Kalender, Uhrzeit 0). Jede andere Zone wuerde den
     * Tag verschieben.
     */
    private fun utcCalendarDateOf(epochMillis: Long): java.time.LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
}
