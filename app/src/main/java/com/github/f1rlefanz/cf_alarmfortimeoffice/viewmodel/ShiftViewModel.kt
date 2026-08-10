package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShiftUiState(
    val isLoading: Boolean = false,
    val currentShiftConfig: ShiftConfig? = null,
    val recognizedShifts: List<ShiftInfo> = emptyList(),
    val upcomingShift: ShiftInfo? = null,
    val error: String? = null,
    /**
     * Kuerzel, die im Kalender des Nutzers vorkommen, aber von keinem Erkennungsmuster getroffen
     * werden - siehe [com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester].
     * Bewusst nur Vorschlaege: zugeordnet wird von Hand.
     */
    val codeSuggestions: ShiftCodeSuggester.SuggestionResult =
        ShiftCodeSuggester.SuggestionResult(emptyList(), 0)
)

/**
 * ShiftViewModel - REFACTORED mit Hilt und CalendarStateHolder
 *
 * MIGRATION:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor Injection mit @Inject
 * ✅ CalendarStateHolder statt CalendarViewModel
 * ✅ Keine direkte ViewModel-zu-ViewModel Dependency mehr!
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftUseCase: IShiftUseCase,
    private val alarmUseCase: IAlarmUseCase,  // NEW: For alarm creation
    private val calendarStateHolder: CalendarStateHolder,
    private val errorHandler: ErrorHandler
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShiftUiState())
    val uiState: StateFlow<ShiftUiState> = _uiState.asStateFlow()

    init {
        loadShiftConfig()
        observeCalendarEvents() // Reactive Schichterkennung via StateHolder
    }

    /**
     * REACTIVE PATTERN: Observiert Calendar Events vom StateHolder
     * PERFORMANCE: Enhanced debouncing strategy für different scenarios
     * DECOUPLED: Nutzt CalendarStateHolder statt direkte ViewModel-Referenz
     * MEMORY SAFE: Proper cleanup über viewModelScope
     * 
     * NOTE: distinctUntilChanged() removed - StateFlow already provides this behavior
     * (Operator Fusion - see StateFlow documentation)
     */
    private fun observeCalendarEvents() {
        viewModelScope.launch {
            calendarStateHolder.events
                .debounce(400) // ENHANCED: Längeres Debouncing für teure Shift-Recognition (400ms)
                .collect { events: List<CalendarEvent> ->
                    if (events.isNotEmpty()) {
                        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 UI-DEBOUNCE: Calendar events changed via StateHolder, triggering shift recognition for ${events.size} events")
                        processCalendarEvents(events)
                    } else {
                        // Clear recognized shifts wenn keine Events vorhanden
                        _uiState.value = _uiState.value.copy(
                            recognizedShifts = emptyList(),
                            upcomingShift = null
                        )
                        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 UI-DEBOUNCE: No calendar events in StateHolder, clearing recognized shifts")
                    }
                }
        }
    }

    private fun loadShiftConfig() {
        viewModelScope.launch {
            // SINGLETON OPTIMIZATION: Enhanced startup with cache awareness
            Logger.d(LogTags.SHIFT_CONFIG, "🔄 SINGLETON-STARTUP: Loading ShiftConfig with singleton pattern...")
            
            shiftUseCase.getCurrentShiftConfig()
                .onSuccess { config ->
                    _uiState.value = _uiState.value.copy(currentShiftConfig = config)
                    Logger.business(LogTags.SHIFT_CONFIG, "✅ SINGLETON-STARTUP: ShiftConfig loaded successfully - autoAlarm=${config.autoAlarmEnabled}, definitions=${config.definitions.size}")
                }
                .onFailure { error ->
                    // KEIN Default-Fallback, der SCHREIBT - siehe die identische Stelle in
                    // CalendarViewModel.createAlarmsFromLoadedEvents() und in
                    // CFAlarmApplication.initializeApp(): dieselbe Fehlerklasse hatte DREI
                    // Schreibstellen.
                    //
                    // Seit ShiftConfigRepository zwischen "noch nie konfiguriert" und "vorhanden,
                    // aber unlesbar" unterscheidet, kann getCurrentShiftConfig() nur noch aus
                    // EINEM Grund fehlschlagen: die Konfiguration ist defekt. Der
                    // Nicht-konfiguriert-Fall liefert die Standardkonfiguration bereits als
                    // Erfolg. Genau im Defektfall ist Ueberschreiben Datenverlust - und diese
                    // Funktion laeuft im init{}-Block, also bei JEDER ViewModel-Erzeugung.
                    // Der bewusste Weg zum Default heisst resetToDefaults() und gehoert dem
                    // Nutzer; die Rohdaten liegen als shift_config_broken gesichert.
                    _uiState.value = _uiState.value.copy(
                        error = errorHandler.getErrorMessage(error)
                    )
                    Logger.e(
                        LogTags.SHIFT_CONFIG,
                        "❌ SINGLETON-STARTUP: Schicht-Konfiguration nicht lesbar - sie wird NICHT mit " +
                            "Standardwerten ueberschrieben. Rohdaten liegen als shift_config_broken.",
                        error
                    )
                }
        }
    }

    /**
     * Ordnet ein im Kalender gefundenes Kuerzel einer bestehenden Schichtdefinition zu - der
     * Handgriff, den [ShiftCodeSuggester] vorbereitet.
     *
     * Bewusst KEINE Automatik: die App schlaegt vor, der Mensch entscheidet. Eine stille Zuordnung,
     * die danebengreift, stellt einen Wecker auf die falsche Uhrzeit, und darauf verlaesst sich
     * jemand.
     *
     * Das Kuerzel wird als exaktes Keyword ergaenzt. Damit greift Stufe 2 der Staffelung in
     * [ShiftConfig.findDefinitionFor] (exaktes Keyword) und - entscheidend fuer die Erkennung -
     * [ShiftDefinition.matchesKeywords] mit Wortgrenzen. Auch einbuchstabige Kuerzel sind erlaubt:
     * sie treffen dort nur als eigenstaendiges Wort, und genau solche Codes stehen real im
     * Dienstplan. Die unscharfe Teiltreffer-Stufe bleibt von ihnen unberuehrt
     * ([ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH]).
     *
     * Geht ueber [updateShiftConfig], damit alles daran Haengende mitlaeuft: Speichern,
     * Cache-Invalidierung, erneute Erkennung und Alarm-Sync.
     */
    fun assignCodeToDefinition(code: String, definitionId: String) {
        val config = _uiState.value.currentShiftConfig
        if (config == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ KUERZEL-ZUORDNUNG: keine Konfiguration geladen - abgebrochen")
            return
        }
        val normalized = code.trim()
        if (normalized.isEmpty()) return

        val target = config.definitions.firstOrNull { it.id == definitionId }
        if (target == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ KUERZEL-ZUORDNUNG: Definition $definitionId nicht gefunden")
            return
        }
        if (target.keywords.any { it.equals(normalized, ignoreCase = true) }) {
            Logger.d(LogTags.SHIFT_CONFIG, "KUERZEL-ZUORDNUNG: '$normalized' steht schon bei '${target.name}'")
            return
        }

        Logger.business(
            LogTags.SHIFT_CONFIG,
            "✅ KUERZEL-ZUORDNUNG: '$normalized' wird Muster von '${target.name}'"
        )
        updateShiftConfig(
            config.copy(
                definitions = config.definitions.map { definition ->
                    if (definition.id == definitionId) {
                        definition.copy(keywords = definition.keywords + normalized)
                    } else {
                        definition
                    }
                }
            )
        )
    }

    fun updateShiftConfig(config: ShiftConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Hier stand bis v1.22.1 ein `recognizeShiftsInEvents(emptyList())` mit dem Kommentar
            // "Force clear the recognition cache" und einem Erfolgs-Log "Recognition cache cleared
            // successfully". Der Aufruf loeschte NICHTS: er fuehrte eine vollstaendige Erkennung
            // mit leerer Eventliste durch und BEFUELLTE den Cache dabei sogar neu
            // (lastRecognitionHash = emptyList().hashCode(), cachedMatches = emptyList()). Der
            // echte Reset passiert eine Zeile weiter unten in `saveShiftConfig()` ->
            // `invalidateAllCaches()` -> `ShiftRecognitionEngine.clearRecognitionCache()`, und
            // zwar nur bei erfolgreichem Speichern - genau richtig. Das falsche Erfolgs-Log war
            // aktiv schaedlich: es liess im Datei-Log einen Cache-Reset als bewiesen erscheinen,
            // der nie stattgefunden hat. Kein Erfolgs-Log fuer eine Operation, die nicht passiert.

            shiftUseCase.saveShiftConfig(config)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentShiftConfig = config
                    )
                    
                    // REACTIVE FIX: Re-run shift recognition with updated config
                    // HILT MIGRATION: Now uses CalendarStateHolder instead of direct ViewModel reference
                    val currentEvents = calendarStateHolder.events.value
                    if (currentEvents.isNotEmpty()) {
                        val eventCount = currentEvents.size
                        Logger.d(LogTags.SHIFT_RECOGNITION, "Shift config updated, re-processing $eventCount calendar events with new definitions")

                        // Small delay to ensure config is fully persisted
                        kotlinx.coroutines.delay(200)

                        processCalendarEvents(currentEvents)
                    }

                    // 🚨 CRITICAL FIX: Trigger automatic alarm creation after shift config update!
                    // Unconditional (auch ohne Events): ein Ausschalten von "Automatische Alarme"
                    // muss die Alarme sofort raeumen, nicht nur wenn gerade Events geladen sind.
                    Logger.business(LogTags.ALARM, "🔄 CONFIG-UPDATE: Triggering alarm creation after shift config change")
                    triggerAlarmCreationFromConfigUpdate(config)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errorHandler.getErrorMessage(error)
                    )
                }
        }
    }

    // REMOVED: updateDaysAhead() - daysAhead is now fixed at 14 days as per Briefing 4.0
    // REMOVED: updateSyncInterval() - syncIntervalHours is now fixed at 6 hours as per Briefing 4.0

    /**
     * WICHTIG: `suspend`, nicht mehr selbst `viewModelScope.launch` - beide Aufrufer
     * (`observeCalendarEvents()`s `collect`, `updateShiftConfig()`s `.onSuccess`) laufen
     * bereits in einer eigenen Coroutine. Ein zusätzlicher, fire-and-forget verschachtelter
     * `launch` hier ließ diesen Aufruf nebenläufig zu `triggerAlarmCreationFromConfigUpdate()`s
     * eigenem `ShiftRecognitionEngine`-Zugriff laufen - dieselbe Engine-Instanz cached ihren
     * Zustand aber in einzelnen `@Volatile`-Feldern ohne gemeinsame Atomizität
     * (`lastRecognitionHash`/`cachedMatches`/`recognitionInProgress`), sodass die beiden
     * nebenläufigen Aufrufe sich gegenseitig einen falschen (leeren) Zwischenzustand
     * unterschieben konnten - am Fairphone reproduziert: "Automatische Alarme" wieder
     * einschalten erzeugte trotz korrekt erkannter Schichten 0 Alarme. Als `suspend` läuft
     * dieser Aufruf im Aufrufer-Coroutine vollständig ab, BEVOR `triggerAlarmCreationFromConfigUpdate()`
     * überhaupt startet - keine Überlappung mehr auf der gemeinsamen Engine.
     */
    suspend fun processCalendarEvents(events: List<CalendarEvent>) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // Interface-Version verwendet recognizeShiftsInEvents
        shiftUseCase.recognizeShiftsInEvents(events)
            .onSuccess { shiftMatches ->
                // Konvertiere ShiftMatch zu ShiftInfo für UI-Kompatibilität
                val shifts = shiftMatches.map { match ->
                    ShiftInfo(
                        id = match.calendarEvent.id,
                        shiftType = com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftType(
                            name = match.shiftDefinition.id,
                            displayName = match.shiftDefinition.name
                        ),
                        startTime = match.calendarEvent.startTime,
                        endTime = match.calendarEvent.endTime,
                        eventTitle = match.calendarEvent.title,
                        alarmTime = match.calculatedAlarmTime
                    )
                }

                // Upcoming shift calculation - legacy method
                val upcomingShift = shifts
                    .filter { it.startTime.isAfter(java.time.LocalDateTime.now()) }
                    .minByOrNull { it.startTime }

                // Kuerzel-Vorschlaege im SELBEN Durchgang berechnen: hier liegen Events und
                // Konfiguration beide vor, und die Erkennung ist gerade gelaufen - was jetzt keinen
                // Treffer hatte, ist genau der Kandidat, den der Nutzer zuordnen soll. Rein
                // rechnerisch, kein Netz, kein DataStore.
                val suggestions = _uiState.value.currentShiftConfig?.let { config ->
                    ShiftCodeSuggester.suggest(events, config)
                } ?: ShiftCodeSuggester.SuggestionResult(emptyList(), 0)

                if (suggestions.suggestions.isNotEmpty()) {
                    Logger.business(
                        LogTags.SHIFT_RECOGNITION,
                        "💡 KUERZEL-VORSCHLAEGE: ${suggestions.suggestions.size} unbekannte Kuerzel im " +
                            "Kalender (${suggestions.suggestions.joinToString { "${it.code}×${it.occurrences}" }})" +
                            if (suggestions.droppedCount > 0) ", ${suggestions.droppedCount} weitere nicht gezeigt" else ""
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    recognizedShifts = shifts,
                    upcomingShift = upcomingShift,
                    codeSuggestions = suggestions
                )
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorHandler.getErrorMessage(error)
                )
            }
    }

    /**
     * 🚨 CRITICAL FIX: Triggers alarm creation after shift config updates
     * This ensures that when new shift definitions are added, alarms are automatically created
     * 
     * NOW USES: CalendarStateHolder events instead of direct CalendarViewModel reference
     */
    private fun triggerAlarmCreationFromConfigUpdate(config: ShiftConfig) {
        viewModelScope.launch {
            Logger.business(LogTags.ALARM, "🔄 CONFIG-UPDATE: Triggering alarm sync for updated shift config")

            // "Automatische Alarme" ausgeschaltet: soll ein sofortiger, echter Pause sein - nicht
            // nur ein Gate fuer kuenftige Alarme. Bestehende Alarme muessen JETZT verschwinden,
            // unabhaengig davon ob gerade Kalender-Events vorliegen.
            if (!config.autoAlarmEnabled) {
                alarmUseCase.deleteAllAlarms()
                    .onSuccess {
                        Logger.business(LogTags.ALARM, "✅ CONFIG-UPDATE: Alarme pausiert (autoAlarmEnabled=false) - alle Alarme geloescht")
                    }
                    .onFailure { error ->
                        Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: Pausieren der Alarme fehlgeschlagen", error)
                    }
                return@launch
            }

            // Get current events from CalendarStateHolder (vollstaendiger Soll-Zustand fuer den Delta-Sync)
            val currentEvents = calendarStateHolder.events.value

            if (currentEvents.isEmpty()) {
                Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: No events available for alarm sync")
                return@launch
            }

            // Orchestrator: syncAlarms erkennt die Schichten selbst (frische Engine dank
            // Cache-Invalidierung in saveShiftConfig) und setzt die System-Alarme intern.
            // Kein Vor-Recognize und kein delay()-Hack mehr noetig.
            alarmUseCase.syncAlarms(currentEvents, config)
                .onSuccess { alarms ->
                    Logger.business(LogTags.ALARM, "✅ CONFIG-UPDATE: Alarm-Sync erfolgreich - ${alarms.size} Alarme aktiv")
                }
                .onFailure { error ->
                    Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: Alarm-Sync fehlgeschlagen", error)
                }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * CRITICAL FIX: Enhanced Memory Leak Prevention - Comprehensive resource cleanup
     * MUTEX ERROR PREVENTION: Clear all state references that could cause threading issues
     */
    override fun onCleared() {
        super.onCleared()
        
        try {
            Logger.d(LogTags.LIFECYCLE, "ShiftViewModel: Starting cleanup...")
            
            // CRITICAL FIX: Clear UI state to release object references
            _uiState.value = ShiftUiState()
            
            Logger.d(LogTags.LIFECYCLE, "ShiftViewModel: Cleanup completed successfully")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during ShiftViewModel cleanup", e)
        }
        
        // Note: ViewModelScope automatically cancels all coroutines
        // UseCase cleanup wird durch DI Container gehandhabt
    }
    
}
