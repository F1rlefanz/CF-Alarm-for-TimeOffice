package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * IMMUTABLE UI State für optimale Compose Performance
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable verhindert unnötige Recompositions
 * ✅ Strukturelle Gleichheit für distinctUntilChanged()
 * ✅ Memory-efficient durch effiziente Copy-Operations
 */
@Immutable
data class CalendarUiState(
    val isLoading: Boolean = false,
    val availableCalendars: List<AndroidCalendar> = emptyList(),
    val selectedCalendarIds: Set<String> = emptySet(),
    val events: List<CalendarEvent> = emptyList(),
    val error: String? = null,
    val hasValidToken: Boolean = false,
    // PAGINATION SUPPORT: Calendar pagination fields
    val currentPage: Int = 0,
    val hasMoreCalendars: Boolean = false,
    val totalCalendars: Int = 0,
    val isLoadingMore: Boolean = false,
    // LAZY LOADING: Event pagination fields
    val hasMoreEvents: Boolean = false,
    val isLoadingMoreEvents: Boolean = false,
    val eventOffset: Int = 0,
    val totalEvents: Int = 0
)

/**
 * CalendarViewModel - REFACTORED with Single Source of Truth
 * 
 * STATE SYNCHRONISATION FIXES:
 * ✅ Verwendet ICalendarSelectionRepository als Single Source of Truth
 * ✅ Keine temporären States mehr - nur persistente Speicherung
 * ✅ Debounced + distinctUntilChanged für Performance
 * ✅ Reactive State Management mit Flow Kombinationen
 * ✅ Interface-basierte Abhängigkeiten für bessere Testbarkeit
 * ✅ Eliminiert Race Conditions durch atomare Updates
 */
@OptIn(FlowPreview::class)
class CalendarViewModel(
    private val calendarUseCase: ICalendarUseCase,
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val errorHandler: ErrorHandler,
    private val shiftUseCase: com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase,
    private val alarmUseCase: com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
) : ViewModel() {

    private val _localUiState = MutableStateFlow(CalendarUiState())
    
    /**
     * PERFORMANCE OPTIMIZATION: Advanced State Update Batching
     * THREAD-SAFE: Volatile fields für Thread-Safety bei State Updates
     * ADAPTIVE: Dynamische Batch-Delays basierend auf Update-Frequenz
     */
    @Volatile
    private var pendingStateUpdate: CalendarUiState? = null
    @Volatile
    private var batchUpdateJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var lastBatchTime = 0L
    
    /**
     * SINGLE SOURCE OF TRUTH: Kombiniert lokalen State mit persistiertem Selection State
     * PERFORMANCE: debounce(30) und distinctUntilChanged() verhindern excessive Updates
     * EFFICIENCY: Optimierte Debounce-Zeit für bessere Responsiveness und reduzierte GC-Last
     */
    val uiState: StateFlow<CalendarUiState> = combine(
        _localUiState.asStateFlow(),
        calendarSelectionRepository.selectedCalendarIds
            .debounce(30) // PERFORMANCE: Reduziert von 50ms auf 30ms für noch bessere Responsiveness
            .distinctUntilChanged() // EFFICIENCY: Nur bei echten Änderungen
    ) { localState, persistedCalendarIds ->
        // PERFORMANCE: Nur neuen State erstellen wenn sich tatsächlich etwas geändert hat
        if (localState.selectedCalendarIds != persistedCalendarIds) {
            localState.copy(selectedCalendarIds = persistedCalendarIds)
        } else {
            localState
        }
    }.distinctUntilChanged() // ZUSÄTZLICHE OPTIMIERUNG: Verhindert identische UI State Updates
    .debounce(50) // PERFORMANCE: Batch UI State Updates
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = CalendarUiState()
    )

    init {
        checkTokenValidity()
        observeCalendarSelection()
        // PERFORMANCE FIX: observeShiftConfigChanges() entfernt - war ein Performance-Killer
        // ShiftConfig-Änderungen werden jetzt über explizite refreshEventsWithNewDaysAhead() gehandhabt
    }

    /**
     * PERFORMANCE OPTIMIZATION: Batched State Updates
     * Sammelt State-Updates und emmittiert sie als Batch für bessere Performance
     */
    /**
     * PERFORMANCE: Advanced Batched State Updates
     * ADAPTIVE TIMING: 16ms für normale Updates, 33ms bei hoher Frequenz
     * FRAME-SYNC: Optimiert für 60fps UI Performance
     */
    private fun updateLocalState(updateFunc: (CalendarUiState) -> CalendarUiState) {
        batchUpdateJob?.cancel()
        
        val currentState = _localUiState.value
        val newState = updateFunc(currentState)
        
        // PERFORMANCE: Nur Update wenn sich der State tatsächlich geändert hat
        if (currentState != newState) {
            pendingStateUpdate = newState
            
            // ADAPTIVE BATCHING: Dynamische Delays basierend auf Update-Frequenz
            val currentTime = System.currentTimeMillis()
            val timeSinceLastBatch = currentTime - lastBatchTime
            val batchDelay = if (timeSinceLastBatch < 100) {
                33L // 30fps bei häufigen Updates für Stabilität
            } else {
                16L // 60fps bei normaler Frequenz
            }
            
            batchUpdateJob = viewModelScope.launch {
                kotlinx.coroutines.delay(batchDelay)
                pendingStateUpdate?.let { update ->
                    _localUiState.value = update
                    pendingStateUpdate = null
                    lastBatchTime = System.currentTimeMillis()
                }
            }
        }
    }
    
    /**
     * IMMEDIATE UPDATE: Für kritische State-Änderungen die sofort emmittiert werden müssen
     */
    private fun updateLocalStateImmediate(updateFunc: (CalendarUiState) -> CalendarUiState) {
        batchUpdateJob?.cancel()
        pendingStateUpdate = null
        
        val currentState = _localUiState.value
        val newState = updateFunc(currentState)
        
        if (currentState != newState) {
            _localUiState.value = newState
        }
    }

    /**
     * LAZY LOADING OPTIMIZATION: Verhindert doppelte Calendar-Loadings
     * THREAD-SAFE: Atomic operations für Race Condition Prevention
     * PERFORMANCE: Time-based throttling für excessive API calls
     */
    @Volatile
    private var isCalendarLoadingInProgress = false
    @Volatile 
    private var lastCalendarLoadTime = 0L
    
    private fun checkTokenValidity() {
        viewModelScope.launch {
            val hasValidToken = calendarUseCase.hasValidAccessToken()
            updateLocalState { it.copy(hasValidToken = hasValidToken) }
            
            if (hasValidToken && shouldLoadCalendars()) {
                loadAvailableCalendars(resetPagination = true)
            }
        }
    }
    
    /**
     * DEDUPLICATION: Intelligent Calendar Loading Decision
     * THREAD-SAFE: Atomic reads und time-based guards
     * PERFORMANCE: Verhindert redundante API-Calls durch Event-Deduplication
     */
    private fun shouldLoadCalendars(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastLoad = currentTime - lastCalendarLoadTime
        val currentState = _localUiState.value
        
        // PERFORMANCE GUARDS: Multiple conditions für intelligente Loading-Entscheidung
        val hasEmptyCalendars = currentState.availableCalendars.isEmpty()
        val notCurrentlyLoading = !isCalendarLoadingInProgress
        val sufficientTimeGap = timeSinceLastLoad > 3000 // Erhöht von 2s auf 3s
        val notRecentlyLoaded = currentState.availableCalendars.isEmpty() || timeSinceLastLoad > 10000 // 10s für reload
        
        return hasEmptyCalendars && notCurrentlyLoading && sufficientTimeGap && notRecentlyLoaded
    }

    /**
     * REACTIVE PATTERN: Beobachtet Änderungen der Calendar Selection
     * AUTOMATIC LOADING: Lädt Events automatisch bei Selection-Änderungen
     * BUG FIX: Lädt Events mit aktueller daysAhead-Konfiguration neu
     * LAZY LOADING: Initial nur begrenzte Anzahl Events für bessere Performance
     */
    private fun observeCalendarSelection() {
        viewModelScope.launch {
            calendarSelectionRepository.selectedCalendarIds
                .distinctUntilChanged()
                .collect { selectedIds ->
                    val calendarCount = selectedIds.size
                    Logger.d(LogTags.CALENDAR, "🔄 REACTIVE-CALENDAR: Calendar selection changed - $calendarCount calendars")
                    
                    // LAZY LOADING: Auto-load events with lazy loading when selection changes
                    if (selectedIds.isNotEmpty()) {
                        loadEventsForSelectedCalendars(
                            loadAll = false, // LAZY LOADING: Start with lazy loading
                            initialPageSize = 10 // LAZY LOADING: Load only 10 events initially
                        )
                    } else {
                        // Clear events und reset pagination wenn keine Kalender ausgewählt
                        updateLocalState { 
                            it.copy(
                                events = emptyList(),
                                eventOffset = 0,
                                totalEvents = 0,
                                hasMoreEvents = false
                            )
                        }
                    }
                }
        }
    }

    /**
     * PROGRESSIVE CALENDAR LOADING: Verhindert Main-Thread-Blockierung
     * YIELD-BASED: Gibt Control an UI-Thread zwischen Verarbeitungsschritten ab
     * BATCHED: Verarbeitet Kalender in kleinen Chunks für bessere Responsiveness
     */
    fun loadAvailableCalendars(pageSize: Int = 20, resetPagination: Boolean = true) {
        viewModelScope.launch {
            // LAZY LOADING: Prevent duplicate loading operations
            if (isCalendarLoadingInProgress && resetPagination) {
                Logger.d(LogTags.CALENDAR, "Calendar loading already in progress, skipping duplicate request")
                return@launch
            }
            
            // TIME-BASED GUARD: Prevent rapid successive calls
            val currentTime = System.currentTimeMillis()
            if (resetPagination && (currentTime - lastCalendarLoadTime) < 1000) {
                Logger.d(LogTags.CALENDAR, "Calendar loading too frequent, throttling request")
                return@launch
            }
            
            val currentState = _localUiState.value
            val isLoadingMore = !resetPagination && currentState.availableCalendars.isNotEmpty()
            val targetPage = if (resetPagination) 0 else currentState.currentPage
            
            if (resetPagination) {
                isCalendarLoadingInProgress = true
                lastCalendarLoadTime = currentTime
            }
            
            // IMMEDIATE UI FEEDBACK: Show loading state instantly
            updateLocalStateImmediate { 
                it.copy(
                    isLoading = resetPagination,
                    isLoadingMore = isLoadingMore,
                    error = null
                )
            }
            
            try {
                // BACKGROUND LOADING: Load calendars in background
                val calendarPageResult = calendarUseCase.getAvailableCalendarsPaginated(
                    page = targetPage,
                    pageSize = pageSize
                )
                
                calendarPageResult.onSuccess { calendarPage ->
                    // PROGRESSIVE UPDATE: Update UI progressively as data becomes available
                    val newCalendars = if (resetPagination) {
                        calendarPage.calendars
                    } else {
                        currentState.availableCalendars + calendarPage.calendars
                    }
                    
                    // YIELD TO UI: Allow UI thread to process updates
                    kotlinx.coroutines.delay(16) // One frame at 60fps
                    
                    val currentPage = calendarPage.page
                    val newCalendarCount = calendarPage.calendars.size
                    val totalCalendars = calendarPage.totalCalendars
                    
                    updateLocalStateImmediate { 
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            availableCalendars = newCalendars,
                            hasValidToken = true,
                            currentPage = currentPage,
                            hasMoreCalendars = calendarPage.hasNextPage,
                            totalCalendars = totalCalendars
                        )
                    }
                    
                    if (resetPagination) {
                        isCalendarLoadingInProgress = false
                    }
                    
                    Logger.i(LogTags.CALENDAR, "Progressive calendar loading completed - page $currentPage: $newCalendarCount calendars, total: $totalCalendars")
                    
                    // DIAGNOSTIC: Log special case when no calendars are found
                    if (resetPagination && totalCalendars == 0) {
                        Logger.w(LogTags.CALENDAR, "🔍 CALENDAR-DIAGNOSIS: Google account has no calendars accessible via Calendar API")
                        Logger.i(LogTags.CALENDAR, "🔍 CALENDAR-DIAGNOSIS: This could mean:")
                        Logger.i(LogTags.CALENDAR, "   - User's Google account has no calendars created")
                        Logger.i(LogTags.CALENDAR, "   - Calendar access is restricted by organization policy")  
                        Logger.i(LogTags.CALENDAR, "   - API permissions are insufficient")
                        Logger.i(LogTags.CALENDAR, "💡 CALENDAR-DIAGNOSIS: User should create a calendar in Google Calendar first")
                    } else if (resetPagination && totalCalendars > 0) {
                        Logger.i(LogTags.CALENDAR, "✅ CALENDAR-DIAGNOSIS: Successfully found $totalCalendars calendars - user can proceed with calendar selection")
                    }
                    
                }.onFailure { error ->
                    updateLocalStateImmediate { 
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = errorHandler.getErrorMessage(error),
                            hasValidToken = false
                        )
                    }
                    
                    if (resetPagination) {
                        isCalendarLoadingInProgress = false
                    }
                    
                    Logger.e(LogTags.CALENDAR, "Progressive calendar loading failed", error)
                }
                
            } catch (e: Exception) {
                updateLocalStateImmediate { 
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = errorHandler.getErrorMessage(e),
                        hasValidToken = false
                    )
                }
                
                if (resetPagination) {
                    isCalendarLoadingInProgress = false
                }
                
                Logger.e(LogTags.CALENDAR, "Exception during progressive calendar loading", e)
            }
        }
    }
    
    /**
     * PAGINATION: Load next page of calendars
     */
    fun loadMoreCalendars(pageSize: Int = 20) {
        val currentState = _localUiState.value
        if (currentState.hasMoreCalendars && !currentState.isLoadingMore) {
            viewModelScope.launch {
                updateLocalState { it.copy(isLoadingMore = true, error = null) }
                
                calendarUseCase.getAvailableCalendarsPaginated(
                    page = currentState.currentPage + 1,
                    pageSize = pageSize
                ).onSuccess { calendarPage ->
                    val allCalendars = currentState.availableCalendars + calendarPage.calendars
                    val currentPageNumber = calendarPage.page
                    val newCalendarCount = calendarPage.calendars.size
                    val totalCalendarCount = allCalendars.size
                    
                    updateLocalState { 
                        it.copy(
                            isLoadingMore = false,
                            availableCalendars = allCalendars,
                            currentPage = currentPageNumber,
                            hasMoreCalendars = calendarPage.hasNextPage
                        )
                    }
                    
                    Logger.i(LogTags.CALENDAR, "Loaded more calendars page $currentPageNumber: $newCalendarCount new, total: $totalCalendarCount")
                }.onFailure { error ->
                    updateLocalState { 
                        it.copy(
                            isLoadingMore = false,
                            error = errorHandler.getErrorMessage(error)
                        )
                    }
                }
            }
        }
    }

    /**
     * PERFORMANCE CRITICAL: Background Event Loading mit progressiven UI Updates
     * MAIN-THREAD OPTIMIZATION: Komplett asynchrone Event-Loading ohne UI-Blockierung
     * LAZY LOADING: Progressive Event-Darstellung für bessere User Experience
     * 
     * @param daysAhead Anzahl der Tage für Event-Loading
     * @param forceRefresh Ob Cache umgangen werden soll
     * @param initialPageSize Initiale Anzahl Events (LAZY LOADING)
     * @param loadAll Ob alle Events geladen werden sollen (Default: false für Lazy Loading)
     */
    fun loadEventsForSelectedCalendars(
        daysAhead: Int? = null,
        forceRefresh: Boolean = false,
        initialPageSize: Int = 10, // LAZY LOADING: Nur 10 Events initial
        loadAll: Boolean = false // LAZY LOADING: Default ist Lazy Loading
    ) {
        viewModelScope.launch {
            val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse { 
                    Logger.w(LogTags.CALENDAR, "Could not get selected calendar IDs")
                    emptySet() 
                }
            
            if (selectedIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendars selected for event loading")
                return@launch
            }

            // IMMEDIATE UI FEEDBACK: Show loading state instantly
            updateLocalStateImmediate { it.copy(isLoading = true, error = null) }
            
            // BUG FIX: Verwende aktuelle ShiftConfig.daysAhead statt hardcodierte DEFAULT_DAYS_AHEAD
            val effectiveDaysAhead = daysAhead ?: getCurrentDaysAheadFromShiftConfig()
            
            try {
                // LAZY LOADING IMPLEMENTATION: Load limited events first
                if (!forceRefresh && !loadAll) {
                    updateLocalStateImmediate { 
                        it.copy(
                            events = emptyList(),
                            eventOffset = 0,
                            totalEvents = 0,
                            hasMoreEvents = true // Assume more events initially
                        )
                    }
                }
                
                val allEvents = mutableListOf<CalendarEvent>()
                var processedCalendars = 0
                var totalEventCount = 0
                
                // PERFORMANCE OPTIMIZATION: Process calendars sequentially but with proper async handling
                selectedIds.forEach { calendarId ->
                    try {
                        val singleCalendarResult = if (loadAll) {
                            calendarUseCase.getCalendarEventsWithCache(
                                calendarIds = setOf(calendarId),
                                daysAhead = effectiveDaysAhead,
                                forceRefresh = forceRefresh
                            )
                        } else {
                            // LAZY LOADING: Load only initial page size
                            calendarUseCase.getCalendarEventsLazy(
                                calendarIds = setOf(calendarId),
                                daysAhead = effectiveDaysAhead,
                                maxEvents = initialPageSize,
                                offset = 0
                            ).map { eventPage ->
                                totalEventCount += eventPage.totalEvents
                                eventPage.events
                            }
                        }
                        
                        singleCalendarResult.onSuccess { events ->
                            allEvents.addAll(events)
                            processedCalendars++
                            
                            // PROGRESSIVE UI UPDATE: Update UI with partial results
                            val sortedEvents = allEvents.sortedBy { it.startTime }
                            
                            // LAZY LOADING: Calculate if more events are available
                            val hasMore = if (loadAll) {
                                false // No more events when loading all
                            } else {
                                events.size >= initialPageSize || totalEventCount > sortedEvents.size
                            }
                            
                            updateLocalState { 
                                it.copy(
                                    events = sortedEvents,
                                    totalEvents = if (loadAll) sortedEvents.size else totalEventCount,
                                    hasMoreEvents = hasMore && processedCalendars < selectedIds.size,
                                    eventOffset = sortedEvents.size
                                )
                            }
                            
                    Logger.d(LogTags.CALENDAR, "Progressive loading: ${events.size} events loaded, total: $totalEventCount")
                        }.onFailure { error ->
                            Logger.e(LogTags.CALENDAR, "Failed to load events for calendar ${calendarId.take(8)}...", error)
                            processedCalendars++
                        }
                        
                    } catch (e: Exception) {
                        Logger.e(LogTags.CALENDAR, "Exception loading calendar ${calendarId.take(8)}...", e)
                        processedCalendars++
                    }
                }
                
                // FINAL UPDATE: Complete loading state and CREATE ALARMS
                val finalSortedEvents = allEvents.sortedBy { it.startTime }
                val finalHasMore = if (loadAll) {
                    false
                } else {
                    // LAZY LOADING: More events available if we hit our page size limit
                    finalSortedEvents.size >= (initialPageSize * selectedIds.size) || totalEventCount > finalSortedEvents.size
                }
                
                updateLocalStateImmediate { 
                    it.copy(
                        isLoading = false,
                        events = finalSortedEvents,
                        eventOffset = finalSortedEvents.size,
                        totalEvents = if (loadAll) finalSortedEvents.size else totalEventCount,
                        hasMoreEvents = finalHasMore
                    )
                }
                
                // 🚨 CRITICAL FIX: Automatically create alarms from recognized shifts!
                if (finalSortedEvents.isNotEmpty()) {
                    // DEBUGGING: Log current state before alarm creation
                    logCurrentStateForDebugging(finalSortedEvents)
                    createAlarmsFromLoadedEvents(finalSortedEvents)
                }
                
                if (forceRefresh) {
                    Logger.i(LogTags.CALENDAR, "Progressive calendar events force refreshed - ${finalSortedEvents.size} events loaded for $effectiveDaysAhead days${if (!loadAll) " (lazy loaded)" else ""}")
                } else {
                    Logger.d(LogTags.CALENDAR, "Progressive calendar events loaded - ${finalSortedEvents.size} events for $effectiveDaysAhead days${if (!loadAll) " (lazy loaded)" else ""}")
                }
                
            } catch (e: Exception) {
                updateLocalStateImmediate { 
                    it.copy(
                        isLoading = false,
                        error = errorHandler.getErrorMessage(e)
                    )
                }
                Logger.e(LogTags.CALENDAR, "Failed to load calendar events progressively", e)
            }
        }
    }

    /**
     * GRANULAR SELECTION: Einzelne Kalender hinzufügen/entfernen
     */
    fun toggleCalendarSelection(calendarId: String) {
        viewModelScope.launch {
            val currentIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse { emptySet() }
            
            if (currentIds.contains(calendarId)) {
                calendarSelectionRepository.removeCalendarId(calendarId)
            } else {
                calendarSelectionRepository.addCalendarId(calendarId)
            }
        }
    }

    fun clearError() {
        updateLocalState { it.copy(error = null) }
    }

    fun refreshData(forceRefresh: Boolean = false, useLazyLoading: Boolean = true) {
        if (forceRefresh) {
            Logger.i(LogTags.CALENDAR, "Force refresh requested")
            // Cache für aktuelle Auswahl invalidieren
            viewModelScope.launch {
                val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                    .getOrElse { emptySet() }
                if (selectedIds.isNotEmpty()) {
                    calendarUseCase.invalidateCalendarCache(selectedIds)
                    // LAZY LOADING: Reset event pagination on refresh
                    updateLocalState { 
                        it.copy(
                            eventOffset = 0,
                            hasMoreEvents = false,
                            totalEvents = 0
                        )
                    }
                    loadEventsForSelectedCalendars(
                        forceRefresh = true,
                        loadAll = !useLazyLoading, // LAZY LOADING: Respect lazy loading preference
                        initialPageSize = if (useLazyLoading) 10 else 50
                    )
                    
                    // BACKGROUND SYNC: Start background refresh for other calendars
                    startBackgroundSync()
                } else {
                    loadAvailableCalendars(resetPagination = true) // PAGINATION: Reset to first page on refresh
                }
            }
        } else {
            checkTokenValidity()
        }
    }
    
    /**
     * BUG FIX: Helper-Methode um aktuelle daysAhead aus ShiftConfig zu lesen
     */
    private suspend fun getCurrentDaysAheadFromShiftConfig(): Int {
        return shiftUseCase.getCurrentShiftConfig()
            .getOrNull()
            ?.daysAhead
            ?: CalendarConstants.DEFAULT_DAYS_AHEAD // Fallback auf Default wenn ShiftConfig nicht verfügbar
    }

    /**
     * LAZY LOADING: Load more events with pagination
     * BUG FIX: Verwendet ShiftConfig.daysAhead statt hardcodierte Konstante
     * PERFORMANCE FIX: Verbesserte Race Condition Prevention
     */
    fun loadMoreEvents(offset: Int = 0, limit: Int = 50) {
        viewModelScope.launch {
            val currentState = _localUiState.value
            
            // RACE CONDITION PROTECTION: Atomic check and set
            if (currentState.isLoadingMoreEvents) {
                Logger.w(LogTags.CALENDAR, "loadMoreEvents already in progress, ignoring duplicate call")
                return@launch
            }
            
            // IMMEDIATE STATE UPDATE: Prevent further calls
            updateLocalStateImmediate { it.copy(isLoadingMoreEvents = true, error = null) }
            
            val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse { emptySet() }
            
            if (selectedIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendars selected for loading more events")
                updateLocalStateImmediate { it.copy(isLoadingMoreEvents = false) }
                return@launch
            }

            // BUG FIX: Verwende aktuelle ShiftConfig.daysAhead statt hardcodierte DEFAULT_DAYS_AHEAD
            val effectiveDaysAhead = getCurrentDaysAheadFromShiftConfig()
            
            calendarUseCase.getCalendarEventsLazy(
                calendarIds = selectedIds,
                daysAhead = effectiveDaysAhead,
                maxEvents = limit,
                offset = offset
            ).onSuccess { eventPage ->
                val currentEvents = _localUiState.value.events
                val allEvents = currentEvents + eventPage.events
                
                updateLocalState { 
                    it.copy(
                        isLoadingMoreEvents = false,
                        events = allEvents,
                        hasMoreEvents = eventPage.hasMore,
                        eventOffset = offset + eventPage.events.size,
                        totalEvents = eventPage.totalEvents
                    )
                }
                
                    Logger.i(LogTags.CALENDAR, "Loaded ${eventPage.events.size} more events for $effectiveDaysAhead days, total: ${allEvents.size}/${eventPage.totalEvents}")
            }.onFailure { error ->
                updateLocalState { 
                    it.copy(
                        isLoadingMoreEvents = false,
                        error = errorHandler.getErrorMessage(error)
                    )
                }
            }
        }
    }
    
    /**
     * BUG FIX: Optimierte Methode um Events nach ShiftConfig-Änderung neu zu laden
     * PERFORMANCE: Verhindert redundante Calls durch Debouncing
     * REACTIVITY LOOP PREVENTION: State-basierte Duplikaterkennung
     * LAZY LOADING: Verwendet Lazy Loading für bessere Performance
     */
    @Volatile
    private var lastDaysAheadRefresh = 0L
    @Volatile 
    private var currentDaysAheadRefreshJob: kotlinx.coroutines.Job? = null
    
    fun refreshEventsWithNewDaysAhead(useLazyLoading: Boolean = true) {
        // PERFORMANCE: Cancel previous refresh job to prevent overlapping calls
        currentDaysAheadRefreshJob?.cancel()
        
        currentDaysAheadRefreshJob = viewModelScope.launch {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRefresh = currentTime - lastDaysAheadRefresh
            
            // DEBOUNCING: Prevent rapid successive refreshes (minimum 1 second gap)
            if (timeSinceLastRefresh < 1000) {
                Logger.d(LogTags.CALENDAR, "DaysAhead refresh debounced - too frequent ($timeSinceLastRefresh ms)")
                return@launch
            }
            
            val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds().getOrElse { emptySet() }
            if (selectedIds.isNotEmpty()) {
                lastDaysAheadRefresh = currentTime
                
                val selectedCalendarCount = selectedIds.size
                Logger.i(LogTags.CALENDAR, "Refreshing events due to daysAhead settings change${if (useLazyLoading) " (lazy loading)" else ""}")
                
                // BATCH OPERATION: Single invalidation + load instead of separate calls
                calendarUseCase.invalidateCalendarCache(selectedIds)
                loadEventsForSelectedCalendars(
                    forceRefresh = true,
                    loadAll = !useLazyLoading, // LAZY LOADING: Respect lazy loading preference  
                    initialPageSize = if (useLazyLoading) 10 else 50
                )
                
                Logger.d(LogTags.CALENDAR, "DaysAhead refresh completed for $selectedCalendarCount calendars")
            } else {
                Logger.w(LogTags.CALENDAR, "No calendars selected for daysAhead refresh")
            }
        }
    }

    fun getCacheStats() {
        viewModelScope.launch {
            val stats = calendarUseCase.getCacheStats()
            Logger.i(LogTags.CALENDAR_CACHE, stats)
        }
    }
    
    fun clearEventCache() {
        viewModelScope.launch {
            calendarUseCase.clearEventCache()
            Logger.i(LogTags.CALENDAR_CACHE, "Event cache cleared by user")
        }
    }

    /**
     * 🚨 CRITICAL FIX: Automatically create alarms from loaded events
     * TIMING FIX: Waits for ShiftConfig to be available before creating alarms
     * RETRY LOGIC: Attempts multiple times to handle race conditions
     */
    private fun createAlarmsFromLoadedEvents(events: List<CalendarEvent>) {
        viewModelScope.launch {
            try {
                val eventCount = events.size
                Logger.business(LogTags.ALARM, "🚨 TIMING-FIX: Starting alarm creation for $eventCount loaded events")
                
                // TIMING FIX: Wait for ShiftConfig with retry logic
                var shiftConfig: com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig? = null
                var attempts = 0
                val maxAttempts = 10 // Try for up to 5 seconds (10 * 500ms)
                
                while (shiftConfig == null && attempts < maxAttempts) {
                    shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
                    
                    if (shiftConfig == null) {
                        attempts++
                        Logger.d(LogTags.ALARM, "⏳ TIMING-FIX: ShiftConfig not ready yet, attempt $attempts/$maxAttempts")
                        kotlinx.coroutines.delay(500) // Wait 500ms before next attempt
                    }
                }
                
                if (shiftConfig == null) {
                    Logger.w(LogTags.ALARM, "⚠️ TIMING-FIX: ShiftConfig still not available after $maxAttempts attempts - checking for default config")
                    
                    // FALLBACK: Try to load or create a default ShiftConfig
                    shiftConfig = createDefaultShiftConfigIfNeeded()
                }
                
                if (shiftConfig?.autoAlarmEnabled == true) {
                    Logger.business(LogTags.ALARM, "✅ TIMING-FIX: ShiftConfig available with autoAlarm enabled, creating alarms...")
                    
                    // CRITICAL: Clear existing alarms first to prevent duplicates
                    alarmUseCase.deleteAllAlarms()
                        .onSuccess {
                            Logger.business(LogTags.ALARM, "🧹 AUTO-ALARM: Cleared existing alarms")
                        }
                        .onFailure { error ->
                            Logger.w(LogTags.ALARM, "⚠️ AUTO-ALARM: Failed to clear existing alarms", error)
                        }
                    
                    // Small delay to ensure alarm cleanup is complete
                    kotlinx.coroutines.delay(100)
                    
                    // Create alarms from events using AlarmUseCase
                    alarmUseCase.createAlarmsFromEvents(events, shiftConfig)
                        .onSuccess { createdAlarms ->
                            val alarmCount = createdAlarms.size
                            Logger.business(LogTags.ALARM, "✅ AUTO-ALARM: Successfully created $alarmCount alarms")
                            
                            // Schedule system alarms for each created alarm
                            createdAlarms.forEach { alarmInfo ->
                                alarmUseCase.scheduleSystemAlarm(alarmInfo)
                                    .onSuccess {
                                        Logger.business(LogTags.ALARM, "✅ SYSTEM-ALARM: Scheduled alarm for ${alarmInfo.shiftName} at ${alarmInfo.formattedTime}")
                                    }
                                    .onFailure { error ->
                                        Logger.e(LogTags.ALARM, "❌ SYSTEM-ALARM: Failed to schedule alarm for ${alarmInfo.shiftName}", error)
                                    }
                            }
                        }
                        .onFailure { error ->
                            Logger.e(LogTags.ALARM, "❌ AUTO-ALARM: Failed to create alarms from events", error)
                        }
                } else {
                    val configStatus = shiftConfig?.let { "autoAlarmEnabled=${it.autoAlarmEnabled}" } ?: "ShiftConfig is null"
                    Logger.w(LogTags.ALARM, "⚠️ TIMING-FIX: Cannot create alarms - $configStatus")
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "❌ AUTO-ALARM: Exception during alarm creation", e)
            }
        }
    }

    /**
     * FALLBACK: Creates a default ShiftConfig if none exists
     * This ensures alarm creation can proceed even if ShiftConfig loading fails
     */
    private suspend fun createDefaultShiftConfigIfNeeded(): com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig? {
        return try {
            Logger.i(LogTags.ALARM, "🔧 FALLBACK: Attempting to create default ShiftConfig")
            
            // Use the existing default configuration from ShiftConfig companion object
            val defaultConfig = com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig.getDefaultConfig()
            
            // Save the default config
            shiftUseCase.saveShiftConfig(defaultConfig)
                .onSuccess {
                    Logger.business(LogTags.ALARM, "✅ FALLBACK: Default ShiftConfig created and saved successfully")
                }
                .onFailure { error ->
                    Logger.e(LogTags.ALARM, "❌ FALLBACK: Failed to save default ShiftConfig", error)
                }
            
            defaultConfig
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ FALLBACK: Exception creating default ShiftConfig", e)
            null
        }
    }

    /**
     * Creates default shift definitions for common work patterns
     * NOTE: Not used anymore, using ShiftConfig.getDefaultConfig() instead
     */
    @Deprecated("Use ShiftConfig.getDefaultConfig() instead")
    private fun createDefaultShiftDefinitions(): List<com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition> {
        return listOf(
            com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition(
                id = "early",
                name = "Frühdienst",
                keywords = listOf("früh", "early", "morning", "06:00", "07:00", "08:00"),
                alarmTime = java.time.LocalTime.of(5, 30), // 30 minutes before typical early shift
                isEnabled = true
            ),
            com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition(
                id = "late", 
                name = "Spätdienst",
                keywords = listOf("spät", "late", "evening", "14:00", "15:00", "16:00"),
                alarmTime = java.time.LocalTime.of(13, 30), // 30 minutes before typical late shift
                isEnabled = true
            ),
            com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition(
                id = "night",
                name = "Nachtdienst", 
                keywords = listOf("nacht", "night", "22:00", "23:00", "00:00"),
                alarmTime = java.time.LocalTime.of(21, 30), // 30 minutes before typical night shift
                isEnabled = true
            )
        )
    }

    /**
     * 🚨 PUBLIC API: Triggers alarm creation from current events
     * Used by ShiftViewModel when shift config changes
     */
    fun createAlarmsFromCurrentEvents() {
        val currentEvents = _localUiState.value.events
        if (currentEvents.isNotEmpty()) {
            val eventCount = currentEvents.size
            Logger.business(LogTags.ALARM, "🔄 PUBLIC-API: Creating alarms from $eventCount current events")
            
            viewModelScope.launch {
                logCurrentStateForDebugging(currentEvents)
                createAlarmsFromLoadedEvents(currentEvents)
            }
        } else {
            Logger.w(LogTags.ALARM, "⚠️ PUBLIC-API: No current events available for alarm creation")
        }
    }

    /**
     * DEBUGGING: Logs current state to help diagnose timing issues
     */
    private suspend fun logCurrentStateForDebugging(events: List<CalendarEvent>) {
        try {
            val eventCount = events.size
            Logger.business(LogTags.ALARM, "🔍 DEBUG-STATE: Starting alarm creation process")
            Logger.business(LogTags.ALARM, "🔍 DEBUG-STATE: Events loaded: $eventCount")
            
            events.take(3).forEach { event ->
                Logger.d(LogTags.ALARM, "🔍 DEBUG-STATE: Event: '${event.title}' at ${event.startTime}")
            }
            
            val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
            if (shiftConfig != null) {
                val definitionCount = shiftConfig.definitions.size
                Logger.business(LogTags.ALARM, "🔍 DEBUG-STATE: ShiftConfig available - autoAlarm=${shiftConfig.autoAlarmEnabled}, definitions=$definitionCount")
                shiftConfig.definitions.forEach { def ->
                    Logger.d(LogTags.ALARM, "🔍 DEBUG-STATE: ShiftDef: '${def.name}' enabled=${def.isEnabled}, keywords=${def.keywords}")
                }
            } else {
                Logger.w(LogTags.ALARM, "🔍 DEBUG-STATE: ⚠️ ShiftConfig is NULL - this is the problem!")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "🔍 DEBUG-STATE: Exception during debugging", e)
        }
    }
    
    /**
     * BACKGROUND SYNC: Intelligente Hintergrund-Synchronisation
     * Aktualisiert stale Cache-Einträge ohne die UI zu blockieren
     */
    private fun startBackgroundSync() {
        viewModelScope.launch {
            try {
                // Get all calendar IDs that might need background sync
                val allCalendarIds = _localUiState.value.availableCalendars.map { it.id }.toSet()
                
                if (allCalendarIds.isNotEmpty()) {
                    val calendarCount = allCalendarIds.size
                    Logger.d(LogTags.CALENDAR, "Starting background sync for $calendarCount calendars")
                    
                    // Use batch processing to avoid overwhelming the API
                    allCalendarIds.chunked(3).forEach { batch ->
                        batch.forEach { calendarId ->
                            // Load events with cache (allows stale) for each calendar
                            calendarUseCase.getCalendarEventsWithCache(
                                calendarIds = setOf(calendarId),
                                daysAhead = CalendarConstants.DEFAULT_DAYS_AHEAD,
                                forceRefresh = false
                            ).onSuccess {
                                Logger.d(LogTags.CALENDAR, "Background sync completed for calendar ${calendarId.take(8)}...")
                            }.onFailure {
                                Logger.w(LogTags.CALENDAR, "Background sync failed for calendar ${calendarId.take(8)}...", it)
                            }
                        }
                        
                        // Small delay between batches to prevent API rate limiting
                        kotlinx.coroutines.delay(200)
                    }
                    
                    Logger.i(LogTags.CALENDAR, "Background sync completed for all calendars")
                }
            } catch (e: Exception) {
                Logger.w(LogTags.CALENDAR, "Background sync failed", e)
            }
        }
    }
    
    /**
     * PERFORMANCE: Cleanup Resources on ViewModel destruction
     * MEMORY LEAK PREVENTION: Proper resource cleanup
     */
    override fun onCleared() {
        super.onCleared()
        
        // PERFORMANCE: Cancel pending batch updates to prevent memory leaks
        batchUpdateJob?.cancel()
        batchUpdateJob = null
        pendingStateUpdate = null
        
        // REACTIVITY FIX: Cancel daysAhead refresh job
        currentDaysAheadRefreshJob?.cancel()
        currentDaysAheadRefreshJob = null
        
        // MEMORY OPTIMIZATION: Reset loading flags to prevent stale references
        isCalendarLoadingInProgress = false
        lastCalendarLoadTime = 0L
        lastDaysAheadRefresh = 0L
        
        Logger.d(LogTags.LIFECYCLE, "CalendarViewModel cleared - cleaning up resources")
        // Note: ViewModelScope automatically cancels all coroutines
        // CalendarRepository cleanup wird durch DI Container gehandhabt
    }
}
