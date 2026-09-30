package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.WartungTokenFehler
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarFetchOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.EventPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UseCase für alle Calendar-bezogenen Operationen mit OAuth2 Token Management.
 */
@Singleton
class CalendarUseCase @Inject constructor(
    private val calendarRepository: ICalendarRepository,
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val oauth2TokenManager: OAuth2TokenManager
) : ICalendarUseCase {
    
    /**
     * Lädt verfügbare Kalender für den aktuell authentifizierten User.
     */
    override suspend fun getAvailableCalendars(): Result<List<AndroidCalendar>> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.getAvailableCalendars") {
            
            val accessToken = resolveAccessToken(
                ohneTokenText = "Calendar access requires authorization. Please sign in.",
                oauthLogZweck = "calendar access"
            )
            
            Logger.d(LogTags.CALENDAR_API, "Loading available calendars with token...")
            
            calendarRepository.getCalendarsWithToken(accessToken)
                .fold(
                    onSuccess = { calendarItems ->
                        val sortedCalendars = calendarItems
                            .map { AndroidCalendar(id = it.id, name = it.displayName) }
                            .sortedBy { it.name }

                        Logger.i(LogTags.CALENDAR, "Loaded ${sortedCalendars.size} calendars")
                        sortedCalendars
                    },
                    onFailure = { error ->
                        Logger.e(LogTags.CALENDAR, "Failed to load calendars", error)
                        invalidateTokenIfRejectedByGoogle(error)
                        throw error
                    }
                )
        }
    }

    /**
     * Verwirft das Token, wenn Google es mit 401 abgelehnt hat.
     *
     * Ein 401 heisst: das Token ist serverseitig tot - egal, was unsere eigene Ablaufzeit sagt.
     * Und genau da lag die Falle: getValidToken() prueft nur die LOKAL gespeicherte
     * Ablaufzeit ("noch 59 Min gueltig") und laesst das Token deshalb durch, ohne je den
     * Refresh-Pfad (und damit GoogleAuthUtil.clearToken) anzustossen. Das tote Token blieb
     * liegen, jeder Versuch lief erneut in denselben 401, und die UI lud endlos nach.
     *
     * Typischer Ausloeser: Zugriff im Google-Konto entzogen. Der GoogleAuthUtil-Cache in den
     * Play Services ueberlebt sogar eine App-Neuinstallation und liefert das tote Token
     * weiter aus - ohne Zustimmungsdialog, weil es fuer GMS gueltig aussieht.
     *
     * Nach dem Verwerfen meldet getValidToken() sauber NoTokenAvailable; die App faellt in den
     * regulaeren Sign-in-Pfad, GoogleAuthUtil hat keinen Cache mehr und fordert die Zustimmung
     * neu an.
     */
    private suspend fun invalidateTokenIfRejectedByGoogle(error: Throwable) {
        if (error !is AppError.AuthenticationError) return
        Logger.w(
            LogTags.TOKEN,
            "🔐 Google lehnt das Token ab (401) - verwerfe es samt Play-Services-Cache"
        )
        oauth2TokenManager.invalidate()
    }
    
    /**
     * Unveraenderter Vertrag (Liste oder Fehler) - fuer alle Konsumenten, die nur ANZEIGEN.
     * Wer aus dem Fehlen eines Events auf "Termin geloescht" schliesst, MUSS
     * [getCalendarEventsWithStatus] nehmen: siehe die Begruendung dort.
     */
    override suspend fun getCalendarEventsWithCache(
        calendarIds: Set<String>,
        forceRefresh: Boolean
    ): Result<List<CalendarEvent>> =
        getCalendarEventsWithStatus(calendarIds, forceRefresh).map { it.events }

    /**
     * Lädt Events der Kalender (Cache oder Force-Refresh) und meldet, welche Kalender scheiterten.
     */
    override suspend fun getCalendarEventsWithStatus(
        calendarIds: Set<String>,
        forceRefresh: Boolean
    ): Result<CalendarFetchOutcome> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.getCalendarEventsWithStatus") {

            val accessToken = resolveAccessToken(
                ohneTokenText = "Calendar events require authorization. Please sign in.",
                oauthLogZweck = "events access"
            )
            
            if (calendarIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendar IDs provided")
                CalendarFetchOutcome(emptyList(), requestedCalendars = 0)
            } else {
                if (forceRefresh) {
                    Logger.i(LogTags.CALENDAR, "Force refresh requested - loading events from API for ${calendarIds.size} calendars")
                } else {
                    Logger.d(LogTags.CALENDAR, "Loading events (with cache) for ${calendarIds.size} calendars")
                }
                
                val allEvents = mutableListOf<CalendarEvent>()
                val failedCalendarIds = mutableSetOf<String>()
                var firstError: Throwable? = null
                
                for (calendarId in calendarIds) {
                    try {
                            calendarRepository.getCalendarEventsWithCache(
                                accessToken = accessToken,
                                calendarId = calendarId,
                                forceRefresh = forceRefresh
                            ).fold(
                            onSuccess = { events ->
                                allEvents.addAll(events)
                            },
                            onFailure = { error ->
                                Logger.e(LogTags.CALENDAR_API, "Failed to load events for calendar ${calendarId.take(8)}...", error)
                                // Auch hier: ein 401 bedeutet totes Token. Ohne Verwerfen liefen
                                // die restlichen Kalender in denselben Fehler und der naechste
                                // Sync gleich mit.
                                invalidateTokenIfRejectedByGoogle(error)
                                // Auch eine abgeschnittene Seitenkette landet hier als Fehler, nie als Erfolg
                                // (CalendarRepository.collectAllPages).
                                failedCalendarIds += calendarId
                                if (firstError == null) firstError = error
                                // Continue with other calendars instead of failing completely
                            }
                        )

                    } catch (e: Exception) {
                        Logger.e(LogTags.CALENDAR_API, "Exception loading events for calendar ${calendarId.take(8)}...", e)
                        failedCalendarIds += calendarId
                        if (firstError == null) firstError = e
                    }
                }

                // TOTALAUSFALL IST EIN FEHLER, KEINE LEERE ERFOLGSLISTE.
                //
                // Scheitert MINDESTENS EIN Kalender, aber nicht alle, bleibt das bewusst ein
                // Teilerfolg (dieselbe Abgrenzung wie CalendarViewModel.
                // resolveCalendarAuthorizationOutcome()). Scheitern aber ALLE - beim Nutzer ist
                // typischerweise genau EINER ausgewaehlt ("Timeoffice Dienstplanfeed") - dann waere
                // Result.success(emptyList()) genau die Luege, vor der CLAUDE.md warnt: fuer eine
                // Wecker-App ist "leer" von "du hast frei" nicht zu unterscheiden. Und der
                // Unterschied ist nicht theoretisch: AlarmUseCase.syncAlarms() deutet eine leere
                // Eventliste als "keine Schichten" und LOESCHT daraufhin alle Alarme.
                //
                // Der Wurf landet im umgebenden SafeExecutor.safeExecute und wird dort zu
                // Result.failure - Aufrufer, die bereits isFailure pruefen (BootReceiver,
                // AlarmMaintenanceService, CalendarPreAlarmRefreshWorker), profitieren sofort.
                if (failedCalendarIds.isNotEmpty() && failedCalendarIds.size == calendarIds.size) {
                    val error = firstError
                        ?: AppError.UnknownError("Alle Kalender-Abrufe sind fehlgeschlagen")
                    Logger.e(
                        LogTags.CALENDAR,
                        "❌ ALLE ${failedCalendarIds.size} Kalender-Abrufe fehlgeschlagen - kein Teilergebnis, meldet Fehler statt leerer Liste",
                        error
                    )
                    throw error
                }

                val sortedEvents = allEvents.sortedBy { it.startTime }

                // Klammern statt verschachtelter if-Ausdruecke in einer String-Konkatenation:
                // `"a" + if (x) "b" else "" + if (y) "c" else ""` parst Kotlin als
                // `if (x) "b" else ("" + if (y) "c" else "")` - der force-refresh-Hinweis fiel
                // damit ausgerechnet im Fehlerfall weg, dem einzigen, in dem er bei der Diagnose
                // gebraucht wird.
                val logSuffix = buildString {
                    if (failedCalendarIds.isNotEmpty()) append(" (with some errors: ${failedCalendarIds.size}/${calendarIds.size} calendars failed)")
                    if (forceRefresh) append(" (force refreshed)")
                }
                Logger.i(
                    LogTags.CALENDAR,
                    "Loaded total ${sortedEvents.size} events from ${calendarIds.size} calendars$logSuffix"
                )

                CalendarFetchOutcome(
                    events = sortedEvents,
                    requestedCalendars = calendarIds.size,
                    failedCalendarIds = failedCalendarIds
                )
            }
        }
    }
    
    /** Lädt verfügbare Kalender seitenweise. */
    override suspend fun getAvailableCalendarsPaginated(
        page: Int,
        pageSize: Int
    ): Result<CalendarPage> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.getAvailableCalendarsPaginated") {
            
            // Get all calendars first
            val allCalendarsResult = getAvailableCalendars()
            val allCalendars = allCalendarsResult.getOrThrow()
            
            // Apply pagination
            val startIndex = page * pageSize
            val endIndex = minOf(startIndex + pageSize, allCalendars.size)
            
            val pageCalendars = if (startIndex < allCalendars.size) {
                allCalendars.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            val hasNextPage = endIndex < allCalendars.size
            
            
            CalendarPage(
                calendars = pageCalendars,
                page = page,
                totalCalendars = allCalendars.size,
                hasNextPage = hasNextPage
            )
        }
    }
    
    /**
     * Laedt Events seitenweise (offset/maxEvents).
     */
    override suspend fun getCalendarEventsLazy(
        calendarIds: Set<String>,
        maxEvents: Int,
        offset: Int
    ): Result<EventPage> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.getCalendarEventsLazy") {
            
            val accessToken = resolveAccessToken(
                ohneTokenText = "Calendar events require authorization. Please sign in.",
                oauthLogZweck = "lazy events access"
            )
            
            if (calendarIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendar IDs provided for lazy loading")
                return@safeExecute EventPage(
                    events = emptyList(),
                    totalEvents = 0,
                    hasMore = false
                )
            }
            
            
            val allEvents = mutableListOf<CalendarEvent>()
            var totalEventsAcrossCalendars = 0
            
            // Process calendars and collect events until we have enough or reach end
            for (calendarId in calendarIds) {
                // Check cache first for total count estimation
                val eventsResult = calendarRepository.getCalendarEventsWithCache(
                    accessToken = accessToken,
                    calendarId = calendarId,
                    forceRefresh = false
                )

                // KEIN getOrElse { emptyList() } mehr!
                //
                // Das verschluckte JEDEN Fehler - auch 401 (totes Token) und 403
                // (ACCESS_TOKEN_SCOPE_INSUFFICIENT) - und machte daraus stillschweigend
                // "0 Events, kein Fehler". Ausgerechnet DIESER Pfad ist der Normalfall:
                // CalendarViewModel.observeCalendarSelection ruft
                // loadEventsForSelectedCalendars(loadAll = false) bei jedem App-Start mit
                // ausgewaehlten Kalendern, und das landet hier. Die Aufraeumlogik in
                // getAvailableCalendars/getCalendarEventsWithCache lief damit am
                // meistbenutzten Pfad vorbei: das tote Token blieb liegen, der Nutzer sah
                // eine leere Schichtliste ohne jeden Hinweis - und bekam keine Wecker.
                //
                // Fuer eine Wecker-App ist "leer" die gefaehrlichste Luege: sie ist von
                // "du hast frei" nicht zu unterscheiden.
                val cachedEvents = eventsResult.getOrElse { error ->
                    Logger.e(LogTags.CALENDAR_API, "Lazy load failed for calendar ${calendarId.take(8)}...", error)
                    invalidateTokenIfRejectedByGoogle(error)
                    throw error
                }

                totalEventsAcrossCalendars += cachedEvents.size

                // Add to our collection (we'll do offset/limit at the end for now)
                allEvents.addAll(cachedEvents)
            }
            
            // Sort all events by start time first
            val sortedEvents = allEvents.sortedBy { it.startTime }
            
            // Apply lazy loading with offset and maxEvents
            val startIndex = offset
            val endIndex = minOf(startIndex + maxEvents, sortedEvents.size)
            
            val pageEvents = if (startIndex < sortedEvents.size) {
                sortedEvents.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            val hasMore = endIndex < sortedEvents.size
            
            Logger.d(LogTags.CALENDAR, "Lazy loaded events: offset=$offset, max=$maxEvents, total=${sortedEvents.size}, returned=${pageEvents.size}, hasMore=$hasMore")
            
            EventPage(
                events = pageEvents,
                totalEvents = sortedEvents.size,
                hasMore = hasMore
            )
        }
    }
    
    /**
     * Überprüft ob ein gültiges Access Token verfügbar ist
     */
    override suspend fun hasValidAccessToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try modern OAuth2 system first
            val tokenResult = oauth2TokenManager.getValidToken()
            if (tokenResult.isSuccess) {
                val tokenData = tokenResult.getOrNull()
                Logger.d(LogTags.TOKEN, "✅ MODERNIZED: Valid token available (${tokenData?.getRemainingLifetimeMinutes()}min remaining)")
                return@withContext true
            }

            // Log specific error for debugging
            val error = tokenResult.exceptionOrNull()
            Logger.d(LogTags.TOKEN, "Token validation failed: ${error?.message}")

            // Fallback to legacy system
            val authData = authDataStoreRepository.authData.first()
            val hasToken = authData.accessToken?.isNotEmpty() == true
            val isNotExpired = (authData.tokenExpiryTime ?: 0L) > System.currentTimeMillis()
            
            val isValid = hasToken && isNotExpired
            Logger.d(LogTags.TOKEN, "Legacy token validity: hasToken=$hasToken, notExpired=$isNotExpired")
            return@withContext isValid
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error checking access token validity", e)
            return@withContext false
        }
    }
    
    override suspend fun invalidateCalendarCache(calendarIds: Set<String>) {
        calendarIds.forEach { calendarId ->
            calendarRepository.invalidateCalendarCache(calendarId)
        }
        Logger.i(LogTags.CALENDAR_CACHE, "Invalidated cache for ${calendarIds.size} calendars")
    }
    
    /**
     * Access-Token fuer einen Kalenderabruf. Wirft bei Token-Fehlern bewusst ein generisches
     * Exception(text), KEIN AppError.AuthenticationError - Skill cfalarm-persistenz-und-auth.
     *
     * AUSNAHME Funkloch: scheiterte der Refresh an der Verbindung (IOException in der Kette,
     * dieselbe Einstufung wie [WartungTokenFehler]), wird daraus [AppError.NetworkError] MIT
     * Ursache. Vorher wurde daraus "Please re-authorize" ohne Ursache: offline mit abgelaufenem
     * Token (es lebt eine Stunde) meldete die Oberflaeche den Kalender-Zugriff als verloren.
     */
    private suspend fun resolveAccessToken(
        ohneTokenText: String,
        oauthLogZweck: String
    ): String {
        Logger.business(LogTags.CALENDAR, "🔐 MODERNIZED: Validating OAuth2 token before $oauthLogZweck...")

        val tokenResult = oauth2TokenManager.getValidToken()
        if (tokenResult.isFailure) {
            val error = tokenResult.exceptionOrNull()
            if (WartungTokenFehler.istNetzursache(error)) {
                Logger.w(LogTags.TOKEN, "🌐 Token-Refresh ohne Verbindung gescheitert - voruebergehend, keine Neuanmeldung", error)
                throw AppError.NetworkError("No internet connection", error)
            }
            Logger.e(LogTags.TOKEN, "❌ MODERNIZED: No valid token available - authorization required!", error)

            val errorMessage = when (error) {
                is TokenException.NoTokenAvailable -> ohneTokenText
                is TokenException.AuthorizationExpired -> "Your Calendar authorization has expired. Please re-authorize."
                is TokenException.RefreshFailed -> "Failed to refresh Calendar access. Please re-authorize."
                is TokenException.ConsentRequired -> "Der Zugriff auf deinen Kalender wurde entzogen. Bitte melde dich erneut an."
                else -> "Calendar access error: ${error?.message}"
            }
            throw Exception(errorMessage)
        }

        val tokenData = tokenResult.getOrThrow()
        Logger.business(LogTags.TOKEN, "✅ MODERNIZED: Token validated (${tokenData.getRemainingLifetimeMinutes()}min remaining)")
        return tokenData.accessToken
    }
}
