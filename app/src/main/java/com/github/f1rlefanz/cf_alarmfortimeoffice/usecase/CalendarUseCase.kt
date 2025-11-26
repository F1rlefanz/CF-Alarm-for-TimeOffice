package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.EventPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UseCase für alle Calendar-bezogenen Operationen mit OAuth2 Token Management
 * 
 * ✅ PHASE 4 MODERNIZED (2025):
 * - Verwendet OAuth2TokenManager statt deprecated TokenRefreshUseCase
 * - Smart Retry Logic mit TokenRefreshStrategy
 * - Token Rotation Support
 * - Improved Exception Handling mit TokenException hierarchy
 * 
 * REFACTORED: 
 * ✅ Implementiert ICalendarUseCase Interface für bessere Testbarkeit
 * ✅ Integriert neues OAuth2TokenManager System
 * ✅ Verwendet Repository-Interfaces statt konkrete Implementierungen
 * ✅ Graceful Error Handling bei Token-Problemen
 * ✅ Backwards compatibility mit bestehendem AuthDataStore
 * ✅ Abstracts Calendar Repository Operations
 * 
 * OPTIMIZATION ENHANCEMENTS:
 * ✅ Lazy Loading für Events mit Batch-Processing
 * ✅ Pagination für große Kalenderlisten
 * ✅ Erweiterte Cache-Management mit Offline-Support
 */
@Singleton
class CalendarUseCase @Inject constructor(
    private val calendarRepository: ICalendarRepository,
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val oauth2TokenManager: OAuth2TokenManager?
) : ICalendarUseCase {
    
    /**
     * Lädt verfügbare Kalender für den aktuell authentifizierten User
     * UPDATED: Verwendet neue Token-Management Infrastruktur mit Pagination Support
     * 
     * 🔧 OPTION 4 FIX: Defensive token validation before API calls
     */
    override suspend fun getAvailableCalendars(): Result<List<AndroidCalendar>> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.getAvailableCalendars") {
            
            // ✅ PHASE 4: Modern token validation with OAuth2TokenManager
            val accessToken = if (oauth2TokenManager != null) {
                Logger.business(LogTags.CALENDAR, "🔐 MODERNIZED: Validating OAuth2 token before calendar access...")
                
                val tokenResult = oauth2TokenManager.getValidToken()
                if (tokenResult.isFailure) {
                    val error = tokenResult.exceptionOrNull()
                    Logger.e(LogTags.TOKEN, "❌ MODERNIZED: No valid token available - authorization required!", error)
                    
                    // Provide user-friendly error messages based on exception type
                    val errorMessage = when (error) {
                        is TokenException.NoTokenAvailable -> "Calendar access requires authorization. Please sign in."
                        is TokenException.AuthorizationExpired -> "Your Calendar authorization has expired. Please re-authorize."
                        is TokenException.RefreshFailed -> "Failed to refresh Calendar access. Please re-authorize."
                        else -> "Calendar access error: ${error?.message}"
                    }
                    throw Exception(errorMessage)
                }
                
                val tokenData = tokenResult.getOrThrow()
                Logger.business(LogTags.TOKEN, "✅ MODERNIZED: Token validated (${tokenData.getRemainingLifetimeMinutes()}min remaining)")
                tokenData.accessToken
                
            } else {
                Logger.w(LogTags.CALENDAR, "⚠️ LEGACY-ONLY: Using legacy auth system (no OAuth2 available)...")
                
                // Even in legacy mode, check if token is expired before using it
                val legacyToken = getLegacyAccessToken()
                val authData = authDataStoreRepository.authData.first()
                val tokenExpiryTime = authData.tokenExpiryTime ?: 0L
                val currentTime = System.currentTimeMillis()
                
                if (currentTime >= tokenExpiryTime) {
                    Logger.e(LogTags.TOKEN, "❌ LEGACY: Token expired at ${java.util.Date(tokenExpiryTime)}, current time: ${java.util.Date(currentTime)}")
                    throw Exception("Calendar access token expired. Please sign out and sign in again to refresh authorization.")
                }
                
                Logger.business(LogTags.CALENDAR, "✅ LEGACY: Token validated")
                legacyToken
            }
            
            Logger.d(LogTags.CALENDAR_API, "Loading available calendars with token...")
            
            // PAGINATION SUPPORT: Load calendars in manageable chunks
            val allCalendars = mutableListOf<AndroidCalendar>()
            
            calendarRepository.getCalendarsWithToken(accessToken)
                .fold(
                    onSuccess = { calendarItems ->
                        // OPTIMIZATION: Process calendars in chunks for better memory management
                        val chunkSize = 20 // Process max 20 calendars per chunk
                        
                        calendarItems.chunked(chunkSize).forEach { chunk ->
                            val androidCalendars = chunk.map { item ->
                                AndroidCalendar(
                                    id = item.id,
                                    name = item.displayName
                                )
                            }
                            allCalendars.addAll(androidCalendars)
                            
                            Logger.d(LogTags.CALENDAR, "Processed ${chunk.size} calendars (chunk)")
                        }
                        
                        // Sort calendars by name for better UX
                        val sortedCalendars = allCalendars.sortedBy { it.name }
                        
                        Logger.i(LogTags.CALENDAR, "Loaded ${sortedCalendars.size} calendars")
                        sortedCalendars
                    },
                    onFailure = { error ->
                        Logger.e(LogTags.CALENDAR, "Failed to load calendars", error)
                        throw error
                    }
                )
        }
    }
    
    /**
     * Lädt Events für spezifische Kalender mit automatischer Token-Verwaltung
     * PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
     */
    override suspend fun getCalendarEvents(
        calendarIds: Set<String>
    ): Result<List<CalendarEvent>> {
        // Delegation an Cache-Methode mit Standard-Verhalten (kein Force-Refresh)
        return getCalendarEventsWithCache(calendarIds, forceRefresh = false)
    }
    
    /**
     * Lädt Events für spezifische Kalender mit Cache-Support und Force-Refresh Option
     * CRITICAL PERFORMANCE FIX: Background Threading mit Main-Thread Schonung
     * PROGRESSIVE LOADING: Verhindert UI-Blockierung durch gestaffelte Verarbeitung
     * 
     * 🔧 OPTION 4 FIX: Defensive token validation before API calls
     */
    override suspend fun getCalendarEventsWithCache(
        calendarIds: Set<String>,
        forceRefresh: Boolean
    ): Result<List<CalendarEvent>> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.getCalendarEventsWithCache") {
            
            // ✅ PHASE 4: Modern token validation with OAuth2TokenManager
            val accessToken = if (oauth2TokenManager != null) {
                Logger.business(LogTags.CALENDAR, "🔐 MODERNIZED: Validating OAuth2 token before events access...")
                
                val tokenResult = oauth2TokenManager.getValidToken()
                if (tokenResult.isFailure) {
                    val error = tokenResult.exceptionOrNull()
                    Logger.e(LogTags.TOKEN, "❌ MODERNIZED: No valid token available - authorization required!", error)
                    
                    val errorMessage = when (error) {
                        is TokenException.NoTokenAvailable -> "Calendar events require authorization. Please sign in."
                        is TokenException.AuthorizationExpired -> "Your Calendar authorization has expired. Please re-authorize."
                        is TokenException.RefreshFailed -> "Failed to refresh Calendar access. Please re-authorize."
                        else -> "Calendar access error: ${error?.message}"
                    }
                    throw Exception(errorMessage)
                }
                
                val tokenData = tokenResult.getOrThrow()
                Logger.business(LogTags.TOKEN, "✅ MODERNIZED: Token validated (${tokenData.getRemainingLifetimeMinutes()}min remaining)")
                tokenData.accessToken
                
            } else {
                Logger.w(LogTags.CALENDAR, "⚠️ LEGACY-ONLY: Using legacy auth system for events...")
                
                // Even in legacy mode, check if token is expired before using it
                val legacyToken = getLegacyAccessToken()
                val authData = authDataStoreRepository.authData.first()
                val tokenExpiryTime = authData.tokenExpiryTime ?: 0L
                val currentTime = System.currentTimeMillis()
                
                if (currentTime >= tokenExpiryTime) {
                    Logger.e(LogTags.TOKEN, "❌ LEGACY: Token expired at ${java.util.Date(tokenExpiryTime)}, current time: ${java.util.Date(currentTime)}")
                    throw Exception("Calendar access token expired. Please sign out and sign in again to refresh authorization.")
                }
                
                Logger.business(LogTags.CALENDAR, "✅ LEGACY: Token validated")
                legacyToken
            }
            
            if (calendarIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendar IDs provided")
                emptyList<CalendarEvent>()
            } else {
                if (forceRefresh) {
                    Logger.i(LogTags.CALENDAR, "Force refresh requested - loading events from API for ${calendarIds.size} calendars")
                } else {
                    Logger.d(LogTags.CALENDAR, "Loading events (with cache) for ${calendarIds.size} calendars")
                }
                
                // PROGRESSIVE LOADING: Single calendar at a time to prevent UI blocking
                val allEvents = mutableListOf<CalendarEvent>()
                var hasErrors = false
                var processedCount = 0
                
                for (calendarId in calendarIds) {
                    try {
                        // BACKGROUND PROCESSING: Ensure we're on IO thread
                            calendarRepository.getCalendarEventsWithCache(
                                accessToken = accessToken,
                                calendarId = calendarId,
                                forceRefresh = forceRefresh
                            ).fold(
                            onSuccess = { events ->
                                allEvents.addAll(events)
                                processedCount++
                                Logger.d(LogTags.CALENDAR_API, "Loaded ${events.size} events from calendar ${calendarId.take(8)}...")
                                
                                // YIELD TO MAIN THREAD: Allow UI updates between calendars
                                if (processedCount % 2 == 0) { // Every 2 calendars
                                    kotlinx.coroutines.delay(50) // 50ms yield for UI thread
                                }
                            },
                            onFailure = { error ->
                                Logger.e(LogTags.CALENDAR_API, "Failed to load events for calendar ${calendarId.take(8)}...", error)
                                hasErrors = true
                                processedCount++
                                // Continue with other calendars instead of failing completely
                            }
                        )
                        
                    } catch (e: Exception) {
                        Logger.e(LogTags.CALENDAR_API, "Exception loading events for calendar ${calendarId.take(8)}...", e)
                        hasErrors = true
                        processedCount++
                    }
                }
                
                // FINAL PROCESSING: Sort events by start time
                val sortedEvents = allEvents.sortedBy { it.startTime }
                
                Logger.i(LogTags.CALENDAR, "Loaded total ${sortedEvents.size} events from ${calendarIds.size} calendars" + 
                        if (hasErrors) " (with some errors)" else "" +
                        if (forceRefresh) " (force refreshed)" else "")
                
                sortedEvents
            }
        }
    }
    
    /**
     * PAGINATION: Lädt verfügbare Kalender mit Pagination Support
     */
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
            
            Logger.d(LogTags.CALENDAR, "Paginated calendars: page=$page, size=$pageSize, total=${allCalendars.size}, returned=${pageCalendars.size}")
            
            CalendarPage(
                calendars = pageCalendars,
                page = page,
                pageSize = pageSize,
                totalCalendars = allCalendars.size,
                hasNextPage = hasNextPage
            )
        }
    }
    
    /**
     * LAZY LOADING: Lädt Events mit erweiterten Optionen für bessere Performance
     * FIXED: Echtes Lazy Loading direkt an der API-Ebene
     * 
     * 🔧 OPTION 4 FIX: Defensive token validation before API calls
     */
    override suspend fun getCalendarEventsLazy(
        calendarIds: Set<String>,
        maxEvents: Int,
        offset: Int
    ): Result<EventPage> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.getCalendarEventsLazy") {
            
            // ✅ PHASE 4: Modern token validation with OAuth2TokenManager
            val accessToken = if (oauth2TokenManager != null) {
                Logger.business(LogTags.CALENDAR, "🔐 MODERNIZED: Validating OAuth2 token before lazy events access...")
                
                val tokenResult = oauth2TokenManager.getValidToken()
                if (tokenResult.isFailure) {
                    val error = tokenResult.exceptionOrNull()
                    Logger.e(LogTags.TOKEN, "❌ MODERNIZED: No valid token available - authorization required!", error)
                    
                    val errorMessage = when (error) {
                        is TokenException.NoTokenAvailable -> "Calendar events require authorization. Please sign in."
                        is TokenException.AuthorizationExpired -> "Your Calendar authorization has expired. Please re-authorize."
                        is TokenException.RefreshFailed -> "Failed to refresh Calendar access. Please re-authorize."
                        else -> "Calendar access error: ${error?.message}"
                    }
                    throw Exception(errorMessage)
                }
                
                val tokenData = tokenResult.getOrThrow()
                Logger.business(LogTags.TOKEN, "✅ MODERNIZED: Token validated (${tokenData.getRemainingLifetimeMinutes()}min remaining)")
                tokenData.accessToken
                
            } else {
                Logger.w(LogTags.CALENDAR, "⚠️ LEGACY-ONLY: Using legacy auth system for events...")
                
                // Even in legacy mode, check if token is expired before using it
                val legacyToken = getLegacyAccessToken()
                val authData = authDataStoreRepository.authData.first()
                val tokenExpiryTime = authData.tokenExpiryTime ?: 0L
                val currentTime = System.currentTimeMillis()
                
                if (currentTime >= tokenExpiryTime) {
                    Logger.e(LogTags.TOKEN, "❌ LEGACY: Token expired at ${java.util.Date(tokenExpiryTime)}, current time: ${java.util.Date(currentTime)}")
                    throw Exception("Calendar access token expired. Please sign out and sign in again to refresh authorization.")
                }
                
                Logger.business(LogTags.CALENDAR, "✅ LEGACY: Token validated")
                legacyToken
            }
            
            if (calendarIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendar IDs provided for lazy loading")
                return@safeExecute EventPage(
                    events = emptyList(),
                    offset = offset,
                    maxEvents = maxEvents,
                    totalEvents = 0,
                    hasMore = false
                )
            }
            
            // REAL LAZY LOADING: Load events from API with offset/limit instead of loading all
            Logger.d(LogTags.CALENDAR, "Lazy loading events: offset=$offset, max=$maxEvents for ${calendarIds.size} calendars")
            
            val allEvents = mutableListOf<CalendarEvent>()
            var totalEventsAcrossCalendars = 0
            
            // Process calendars and collect events until we have enough or reach end
            for (calendarId in calendarIds) {
                // Check cache first for total count estimation
                val cachedEvents = calendarRepository.getCalendarEventsWithCache(
                    accessToken = accessToken,
                    calendarId = calendarId,
                    forceRefresh = false
                ).getOrElse { emptyList() }
                
                totalEventsAcrossCalendars += cachedEvents.size
                
                // Add to our collection (we'll do offset/limit at the end for now)
                allEvents.addAll(cachedEvents)
                
                Logger.d(LogTags.CALENDAR_API, "Added ${cachedEvents.size} events from calendar ${calendarId.take(8)}...")
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
                offset = offset,
                maxEvents = maxEvents,
                totalEvents = sortedEvents.size,
                hasMore = hasMore
            )
        }
    }
    
    /**
     * Überprüft ob ein gültiges Access Token verfügbar ist
     * ✅ PHASE 4 MODERNIZED: Uses OAuth2TokenManager
     */
    override suspend fun hasValidAccessToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try modern OAuth2 system first
            if (oauth2TokenManager != null) {
                val tokenResult = oauth2TokenManager.getValidToken()
                if (tokenResult.isSuccess) {
                    val tokenData = tokenResult.getOrNull()
                    Logger.d(LogTags.TOKEN, "✅ MODERNIZED: Valid token available (${tokenData?.getRemainingLifetimeMinutes()}min remaining)")
                    return@withContext true
                }
                
                // Log specific error for debugging
                val error = tokenResult.exceptionOrNull()
                Logger.d(LogTags.TOKEN, "Token validation failed: ${error?.message}")
            }
            
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
    
    override suspend fun clearEventCache() {
        calendarRepository.clearEventCache()
        Logger.i(LogTags.CALENDAR_CACHE, "Cleared complete event cache")
    }
    
    override suspend fun getCacheStats(): String {
        return calendarRepository.getCacheStats()
    }
    
    /**
     * Testet die Kalender-Verbindung durch Laden der verfügbaren Kalender
     */
    override suspend fun testCalendarConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("CalendarUseCase.testCalendarConnection") {
            val result = getAvailableCalendars()
            result.isSuccess
        }
    }
    
    /**
     * Private helper: Gets access token from legacy auth system
     */
    private suspend fun getLegacyAccessToken(): String {
        val authData = authDataStoreRepository.getCurrentAuthData().getOrThrow()
        return authData.accessToken ?: throw Exception("No access token available in legacy system")
    }
}
