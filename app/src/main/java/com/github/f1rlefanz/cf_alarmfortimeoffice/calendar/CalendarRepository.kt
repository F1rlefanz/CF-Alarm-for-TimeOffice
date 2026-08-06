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
 * CalendarRepository implementiert ICalendarRepository Interface
 * mit ETag-basiertem Event-Caching für die Google-Calendar-Anbindung.
 * 
 * Event-Caching reduziert unnötige Google-Calendar-API-Aufrufe.
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
                val calendarList: CalendarList = service.calendarList().list()
                    .setFields("items(id,summary,primary,accessRole)")
                    .setMinAccessRole("reader")
                    .execute()

                Logger.d(LogTags.CALENDAR_API, "Calendar API response received: ${calendarList.items?.size ?: 0} items")
                
                if (calendarList.items.isNullOrEmpty()) {
                    Logger.w(LogTags.CALENDAR_API, "No calendars found in Google Calendar API response")
                    Logger.d(LogTags.CALENDAR_API, "Full API response: $calendarList")
                    Logger.i(LogTags.CALENDAR_API, "DIAGNOSTIC: User account appears to have no calendars or calendar access is restricted")
                    
                    // Enhanced diagnostic logging
                    Logger.d(LogTags.CALENDAR_API, "DIAGNOSTIC: API Response Details:")
                    Logger.d(LogTags.CALENDAR_API, "  - ETag: ${calendarList.etag}")
                    Logger.d(LogTags.CALENDAR_API, "  - Kind: ${calendarList.kind}")
                    Logger.d(LogTags.CALENDAR_API, "  - NextPageToken: ${calendarList.nextPageToken}")
                    Logger.d(LogTags.CALENDAR_API, "  - NextSyncToken: ${calendarList.nextSyncToken}")
                } else {
                    Logger.d(LogTags.CALENDAR_API, "Found calendars: ${calendarList.items.map { "${it.summary} (${it.id})" }}")
                    Logger.i(LogTags.CALENDAR_API, "DIAGNOSTIC: Successfully loaded ${calendarList.items.size} calendars")
                }

                val calendars = calendarList.items?.mapNotNull { calendarEntry ->
                    try {
                        CalendarItem(
                            id = calendarEntry.id ?: return@mapNotNull null,
                            displayName = calendarEntry.summary ?: "Unnamed Calendar"
                        )
                    } catch (e: Exception) {
                        Logger.w(LogTags.CALENDAR_API, "Failed to parse calendar entry", e)
                        null
                    }
                } ?: emptyList()

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

                // ETag-Support: Get cached ETag for conditional request
                val cachedEtag = eventCache.getETag(calendarId)

                val request = service.events().list(calendarId)
                    .setTimeMin(com.google.api.client.util.DateTime(timeMin))
                    .setTimeMax(com.google.api.client.util.DateTime(timeMax))
                    .setOrderBy("startTime")
                    .setSingleEvents(true)
                    .setMaxResults(CalendarConstants.MAX_EVENTS_PER_QUERY)
                    .setFields("items(id,summary,start,end),etag")

                // Add If-None-Match header if we have a cached ETag
                if (cachedEtag != null) {
                    request.requestHeaders.set("If-None-Match", cachedEtag)
                    Logger.d(LogTags.CALENDAR_API, "Using ETag for conditional request: ${cachedEtag.take(20)}...")
                }

                val result: Events = try {
                    request.execute()
                } catch (e: GoogleJsonResponseException) {
                    // Handle 304 Not Modified response
                    if (e.statusCode == 304) {
                        Logger.i(LogTags.CALENDAR_API, "304 Not Modified - using cached data")
                        val cachedEvents = eventCache.get(calendarId)
                        if (cachedEvents != null) {
                            return@safeExecute cachedEvents
                        }
                        // Fallback if cache was cleared - re-fetch without ETag
                        Logger.w(LogTags.CALENDAR_API, "Cache miss despite 304 - refetching without ETag")
                        request.requestHeaders.remove("If-None-Match")
                        request.execute()
                    } else {
                        throw e
                    }
                }

                val events = result.items ?: emptyList()
                Logger.i(LogTags.CALENDAR_API, "${events.size} events loaded for next $daysAhead days")

                // PERFORMANCE: Use optimized event processing
                val calendarEvents = processEventsWithOptimization(events, calendarId)
                
                // Store events with ETag for future conditional requests
                val newEtag = result.etag
                if (newEtag != null) {
                    Logger.d(LogTags.CALENDAR_API, "Received new ETag: ${newEtag.take(20)}...")
                }
                eventCache.put(calendarId, calendarEvents, newEtag)
                Logger.d(LogTags.CALENDAR_CACHE, "${calendarEvents.size} events cached with ETag for future requests")
                
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
                    .setFields("items(id,summary,start,end),nextPageToken,etag")

                if (pageToken != null) {
                    eventsRequest.pageToken = pageToken
                }
                
                // ETag-Support for pagination requests
                val cachedEtag = eventCache.getETag(calendarId)
                if (cachedEtag != null && pageToken == null) { // Only use ETag on first page
                    eventsRequest.requestHeaders.set("If-None-Match", cachedEtag)
                    Logger.d(LogTags.CALENDAR_API, "Using ETag for conditional pagination request: ${cachedEtag.take(20)}...")
                }

                val result = try {
                    eventsRequest.execute()
                } catch (e: GoogleJsonResponseException) {
                    // Handle 304 Not Modified response
                    if (e.statusCode == 304) {
                        Logger.i(LogTags.CALENDAR_API, "304 Not Modified - using cached data for pagination")
                        val cachedEvents = eventCache.get(calendarId)
                        if (cachedEvents != null) {
                            return@safeExecute EventsPage(
                                events = cachedEvents,
                                nextPageToken = null,
                                hasMorePages = false
                            )
                        }
                        // Fallback if cache was cleared - re-fetch without ETag
                        Logger.w(LogTags.CALENDAR_API, "Cache miss despite 304 - refetching without ETag")
                        eventsRequest.requestHeaders.remove("If-None-Match")
                        eventsRequest.execute()
                    } else {
                        throw e
                    }
                }
                val events = result.items ?: emptyList()
                val nextPageToken = result.nextPageToken

                Logger.i(LogTags.CALENDAR_API, "${events.size} events loaded for page (maxResults=$maxResults), hasMore=${nextPageToken != null}")

                // PERFORMANCE: Use optimized event processing
                val calendarEvents = processEventsWithOptimization(events, calendarId)
                
                // Store first page with ETag for future conditional requests
                if (pageToken == null && result.etag != null) {
                    Logger.d(LogTags.CALENDAR_API, "Caching first page with ETag: ${result.etag.take(20)}...")
                    eventCache.put(calendarId, calendarEvents, result.etag)
                }
                
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
 * NOCH OFFEN (bewusst NICHT hier geloest): `ShiftRecognitionEngine.calculateAlarmTime()` rechnet
 * die Weckzeit einen Tag zurueck, sobald sie nach dem Schichtbeginn liegt und die Vorlaufzeit danach
 * <= 12h bleibt (Nachtschicht-Heuristik). Bei einem ganztaegigen Eintrag ist der Schichtbeginn jetzt
 * 00:00 - eine Nachtschicht-Weckzeit von z. B. 21:00 loest die Heuristik damit weiterhin aus (3h
 * Vorlauf) und der Wecker landet auf dem VORTAG. Die Umrechnung allein kann das nicht beheben: jeder
 * Zeitpunkt, der die Heuristik verstummen liesse, waere eine erfundene Uhrzeit und wuerde Anzeige und
 * DND-Fenster erneut verfaelschen. Richtig waere, die Heuristik bei `event.isAllDay` zu ueberspringen
 * (ein ganztaegiger Eintrag hat gar keinen Schichtbeginn, gegen den "danach" pruefbar waere) - das
 * gehoert in `ShiftRecognitionEngine`, nicht hierher. Genau dafuer wird `isAllDay` hier gesetzt.
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
