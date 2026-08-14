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
import kotlinx.coroutines.flow.drop
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

    /**
     * Die Konfiguration, die DIESES ViewModel gerade selbst schreibt - damit
     * [observeExternalConfigChanges] sie nicht fuer eine fremde Aenderung haelt.
     *
     * Gesetzt VOR dem Write, geloescht beim ersten Treffer im Beobachter. Ein Vergleich gegen
     * `uiState.currentShiftConfig` kann das nicht leisten: DataStore veroeffentlicht seinen neuen
     * Wert typischerweise, bevor `edit{}` zurueckkehrt - der Beobachter laeuft also potenziell
     * VOR dem `onSuccess`, das den UI-State setzt. Folge waere ein zweiter, nebenlaeufiger Lauf von
     * Erkennung und Alarm-Sync auf derselben ShiftRecognitionEngine.
     *
     * `@Volatile`, weil der Setter (viewModelScope, Main) und der Collector (ebenfalls Main, aber
     * ueber einen anderen Suspend-Punkt eingeplant) nicht in derselben Ausfuehrung liegen.
     *
     * Steht VOR dem `init{}`-Block - Kotlin initialisiert in Textreihenfolge, und der Collector im
     * `init{}` kann synchron bis zum ersten echten Suspend-Punkt laufen (siehe die
     * Initialisierungsfalle in CLAUDE.md, die im August einen Crash-on-Launch verursacht hat).
     */
    @Volatile
    private var selfWrittenConfig: ShiftConfig? = null

    init {
        loadShiftConfig()
        observeCalendarEvents() // Reactive Schichterkennung via StateHolder
        observeExternalConfigChanges()
    }

    /**
     * Faengt Konfigurationsaenderungen auf, die NICHT ueber [updateShiftConfig] dieses ViewModels
     * kamen - und zieht Anzeige, Schichterkennung UND Alarme nach.
     *
     * WARUM DAS NOETIG WURDE (am Geraet gefunden, 11.08.2026): Der Konfigurations-Import
     * ([com.github.f1rlefanz.cf_alarmfortimeoffice.backup.ConfigBackupUseCase]) schreibt direkt
     * ueber das Repository. Der Store war danach korrekt - nach einem App-Neustart stand das
     * importierte Muster da - aber die LAUFENDE App zeigte weiter den alten Stand, weil
     * `currentShiftConfig` nur beim Start und in `updateShiftConfig()` gesetzt wurde. Der Nutzer
     * importiert, sieht nichts und haelt es fuer gescheitert.
     * Schlimmer noch: die ALARME wurden nicht neu gesetzt. Eine importierte Konfiguration mit
     * anderen Weckzeiten haette bis zur naechsten 6h-Wartung die alten Zeiten weitergeweckt - bei
     * einer Wecker-App der ernstere Teil des Fehlers.
     *
     * Bewusst ein Beobachter am gemeinsamen Datenfluss statt eines Aufrufs im Import: das ist
     * dieselbe Lehre wie beim Master-Pause-Backstop in `syncAlarms()` - ein zentraler Punkt am
     * geteilten Einstieg deckt JEDEN heutigen und kuenftigen Schreiber ab, waehrend ein Gate pro
     * Aufrufer beim naechsten uebersehen wird. `IShiftUseCase.shiftConfig` existierte bereits und
     * wurde von diesem ViewModel schlicht nicht beobachtet.
     *
     * KEINE DOPPELARBEIT: Eigene Aenderungen ueber [updateShiftConfig] setzen `currentShiftConfig`
     * selbst; die daraufhin folgende Flow-Emission ist dann gleich und wird uebersprungen. Nur
     * fremde Aenderungen loesen hier Arbeit aus. Ohne diesen Vergleich liefen Erkennung und
     * Alarm-Sync bei jeder Nutzeraenderung zweimal - und zwar nebenlaeufig auf derselben
     * Engine-Instanz, was CLAUDE.md ausdruecklich als Race-Ursache festhaelt.
     */
    private fun observeExternalConfigChanges() {
        viewModelScope.launch {
            shiftUseCase.shiftConfig
                .drop(1) // Erste Emission ist der Ist-Zustand, den loadShiftConfig() ohnehin holt.
                .collect { flowConfig ->
                    // EIGENE Schreibvorgaenge erkennen - ueber den VOR dem Write gesetzten Merker,
                    // nicht ueber `currentShiftConfig`. Letzteres verliert das Rennen: DataStore
                    // veroeffentlicht seinen neuen Wert typischerweise, BEVOR `edit{}` zurueckkehrt,
                    // also bevor `onSuccess` den UI-State aktualisiert hat. Der Vergleich gegen den
                    // UI-State sah die eigene Aenderung deshalb als fremde an und liess Erkennung
                    // UND Alarm-Sync ein zweites Mal laufen - nebenlaeufig auf derselben
                    // ShiftRecognitionEngine, also genau die Race-Ursache, die CLAUDE.md als
                    // "0 Alarme trotz korrekt erkannter Schichten" festhaelt.
                    if (flowConfig == selfWrittenConfig) {
                        selfWrittenConfig = null
                        return@collect
                    }
                    if (flowConfig == _uiState.value.currentShiftConfig) return@collect

                    // DIE VIERTE TUER, die dieser Beobachter selbst geoeffnet hatte: der Flow
                    // `shiftConfig` DEGRADIERT bei einer vorhandenen, aber unlesbaren Konfiguration
                    // bewusst auf die Standardwerte (damit die Dimmer-/DND-Screens nicht abstuerzen).
                    // Ungefiltert haette dieser Collector das als "externe Aenderung" gelesen, die
                    // Standardwerte in den UI-State geschrieben UND einen Alarm-Sync mit ihnen
                    // ausgeloest - also genau das getan, was in CalendarViewModel, ShiftViewModel
                    // und CFAlarmApplication gerade abgeschafft wurde.
                    // `getCurrentShiftConfig()` ist der maßgebliche Pfad: er SCHEITERT im
                    // Defektfall. Nur ein Erfolg gilt als echte Aenderung.
                    val authoritative = shiftUseCase.getCurrentShiftConfig().getOrElse { error ->
                        Logger.e(
                            LogTags.SHIFT_CONFIG,
                            "❌ EXTERNE AENDERUNG ignoriert: Schicht-Konfiguration nicht lesbar - es " +
                                "werden KEINE Standardwerte uebernommen und kein Alarm-Sync ausgeloest.",
                            error
                        )
                        return@collect
                    }
                    if (authoritative == _uiState.value.currentShiftConfig) return@collect
                    val config = authoritative

                    Logger.business(
                        LogTags.SHIFT_CONFIG,
                        "🔄 EXTERNE KONFIGURATIONSAENDERUNG erkannt (${config.definitions.size} " +
                            "Definitionen) - Anzeige, Erkennung und Alarme werden nachgezogen"
                    )
                    _uiState.value = _uiState.value.copy(currentShiftConfig = config)

                    // Erkennung neu laufen lassen (aktualisiert auch die Kuerzel-Vorschlaege) und
                    // die Alarme an die neuen Weckzeiten anpassen.
                    val events = calendarStateHolder.events.value
                    if (events.isNotEmpty()) {
                        processCalendarEvents(events)
                    }
                    triggerAlarmCreationFromConfigUpdate(config)
                }
        }
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
        // Die eigentliche Entscheidung liegt als reine Funktion im Modell (aktiviert das Ziel,
        // entfernt das Kuerzel bei allen anderen - siehe dort, warum jedes davon noetig ist).
        val updated = config.withCodeAssignedTo(code, definitionId)
        if (updated == null) {
            Logger.d(
                LogTags.SHIFT_CONFIG,
                "KUERZEL-ZUORDNUNG: nichts zu tun fuer '$code' -> $definitionId (steht schon so, " +
                    "leeres Kuerzel oder unbekannte Definition)"
            )
            return
        }

        val target = updated.definitions.first { it.id == definitionId }
        Logger.business(
            LogTags.SHIFT_CONFIG,
            "✅ KUERZEL-ZUORDNUNG: '${code.trim()}' gehoert jetzt zu '${target.name}' (aktiviert, " +
                "bei allen anderen Schichten entfernt)"
        )
        updateShiftConfig(updated)
    }

    fun updateShiftConfig(config: ShiftConfig) {
        viewModelScope.launch {
            // VOR dem Write vormerken, nicht danach - siehe [selfWrittenConfig]. Die
            // DataStore-Emission kann den Beobachter erreichen, BEVOR `saveShiftConfig()`
            // zurueckkehrt; ein Merker, der erst im `onSuccess` gesetzt wird, kommt zu spaet.
            selfWrittenConfig = config
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
                    // Merker zuruecksetzen: es kommt keine passende Emission mehr, und ein
                    // haengender Merker wuerde die NAECHSTE echte externe Aenderung mit demselben
                    // Inhalt (Import derselben Datei, zweiter Zuordnungsversuch) verschlucken.
                    selfWrittenConfig = null
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

            // Events aus dem CalendarStateHolder - aber NUR, wenn sie nachweislich der
            // vollstaendige Bestand sind.
            //
            // Der fruehere Kommentar hier nannte sie pauschal den "vollstaendigen Soll-Zustand
            // fuer den Delta-Sync". Das stimmte nicht: CalendarViewModel legt dort im Normalfall
            // das LAZY-PRAEFIX ab (pro Kalender die ersten 10 Events), und ein ausgefallener
            // Kalender fehlt darin ebenfalls. syncAlarms() loescht jeden Alarm, dessen eventId in
            // der uebergebenen Liste fehlt - jede Aenderung an der Schicht-Konfiguration hat damit
            // bei mehr als zehn Terminen in 14 Tagen die spaetesten Wecker entfernt.
            val currentEvents = calendarStateHolder.events.value

            if (currentEvents.isEmpty()) {
                Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: No events available for alarm sync")
                return@launch
            }

            if (!calendarStateHolder.eventsComplete.value) {
                // Fail-safe: lieber ein spaeter nachgezogener Wecker als ein geloeschter. Die
                // Konfiguration ist bereits gespeichert; der naechste vollstaendige Ladevorgang
                // (Vordergrund-Sync, 6h-Wartung, Pre-Alarm-Refresh) synchronisiert sie nach.
                Logger.w(
                    LogTags.ALARM,
                    "⚠️ CONFIG-UPDATE: Eventliste ist nur ein Ausschnitt (${currentEvents.size} Events) - " +
                        "kein Alarm-Sync, bestehende Alarme bleiben unveraendert"
                )
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
