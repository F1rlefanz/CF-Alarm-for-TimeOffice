package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ShiftUiState(
    val isLoading: Boolean = false,
    val currentShiftConfig: ShiftConfig? = null,
    val recognizedShifts: List<ShiftInfo> = emptyList(),
    val upcomingShift: ShiftInfo? = null,
    val error: String? = null,
    /**
     * Hinweis zum Regel-Nachzug beim UMBENENNEN einer Schicht - bewusst ein EIGENER Kanal neben
     * [error].
     *
     * WARUM NICHT [error] (Regression aus Pruefrunde 8): Diese Meldung sagt "gespeichert, aber
     * eine von dir eingerichtete Funktion wirkt nicht mehr" - etwas anderes als ein gescheiterter
     * Lade- oder Speichervorgang. Und vor allem: [error] wird von [processCalendarEvents] bei
     * JEDEM Durchgang auf `null` gesetzt, und genau so ein Durchgang folgt unmittelbar auf das
     * Speichern (`delay(200)`, sobald Events vorliegen - im Normalbetrieb also immer). Die Meldung
     * war damit gesetzt und Sekundenbruchteile spaeter wieder weg, bevor sie irgendwo ankam.
     *
     * Dieses Feld ueberlebt den folgenden Ladevorgang und wird ausschliesslich vom Nutzer
     * ([clearRegelNachzugHinweis]) bzw. nach dem Anzeigen geloescht. Gerendert wird es im
     * `ShiftConfigScreen` (dort steht der Nutzer beim Umbenennen) als bleibende Karte und im
     * `MainContentScreen` als Snackbar (dort steht er beim Konfigurations-Import). Beide Screens
     * schliessen sich gegenseitig aus, es zeigt also immer genau einer.
     */
    val regelNachzugHinweis: String? = null,
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
    private val errorHandler: ErrorHandler,
    /**
     * Nur fuer den Muster-Nachzug beim Umbenennen einer Schicht ([zieheRegelmusterNach]).
     *
     * BEWUSST `dagger.Lazy`, wie in `CFAlarmApplication`: dieses ViewModel entsteht beim
     * App-Start. Direkt injiziert wuerde damit der komplette Hue-Graph (ApiClient/OkHttp mit
     * eigenem TrustManager) mit aufgebaut, obwohl der Nutzer vielleicht nie eine Schicht
     * umbenennt - und Hue womoeglich gar nicht nutzt.
     */
    private val dimRuleUseCase: dagger.Lazy<DimRuleUseCase>,
    private val hueRuleUseCase: dagger.Lazy<HueRuleUseCase>,
    /**
     * Nach einem geaenderten Dimm-Regelmuster muessen die Fenster neu berechnet und die
     * Tick-Kette neu armiert werden - genauso, wie `DimmerRulesViewModel.saveRule()` direkt nach
     * jedem Regel-Schreibvorgang `enable()` ruft. Der Vorlaeufer ist `ConfigBackupUseCase`: auch
     * dort armiert ein generischer Schreiber beide Ketten selbst, sonst stuende die neue Regel im
     * Store, waehrend bis zur naechsten 6h-Wartung nach dem ALTEN Plan gedimmt wird.
     */
    private val dimScheduleUseCase: dagger.Lazy<DimScheduleUseCase>,
    private val dndScheduleUseCase: dagger.Lazy<DndScheduleUseCase>,
    /**
     * Die DRITTE Stelle, die ueber den Schichtnamen bindet: die beiden Schicht-Auswahlen von
     * "Nicht stoeren" ([DndPrefs.renameShiftName]). Sie wurden beim Nachzug in v1.30.0
     * uebersehen - warum das teuer ist, steht an der Funktion.
     *
     * Die Nacht-Ausnahmen des Dimmers waren einmal eine VIERTE solche Stelle; mit dem
     * eingebauten Nacht-Standard ist auch seine Namensliste entfallen. Der Dimmer bindet nur
     * noch ueber `DimRule.shiftPattern`, und das zieht `dimRuleUseCase` oben nach.
     *
     * Ebenfalls `dagger.Lazy`, aus demselben Grund wie oben: dieses ViewModel entsteht beim
     * App-Start, und die Klasse soll erst angefasst werden, wenn wirklich umbenannt wird.
     */
    private val dndPrefs: dagger.Lazy<DndPrefs>
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

                    // Der Stand VOR dem Uebernehmen - die einzige Gelegenheit, eine Umbenennung
                    // zu erkennen. Muss vor dem Schreiben an `_uiState` gelesen werden.
                    val vorherigeConfig = _uiState.value.currentShiftConfig

                    Logger.business(
                        LogTags.SHIFT_CONFIG,
                        "🔄 EXTERNE KONFIGURATIONSAENDERUNG erkannt (${config.definitions.size} " +
                            "Definitionen) - Anzeige, Erkennung und Alarme werden nachgezogen"
                    )
                    _uiState.value = _uiState.value.copy(currentShiftConfig = config)

                    // Dimmer-/Hue-Regelmuster mitziehen - HIER, am geteilten Einstieg, statt nur
                    // in `updateShiftConfig()`. Eine Ruecksicherung oder ein Geraetewechsel bringt
                    // dieselbe Definition (gleiche `id`) unter anderem Namen zurueck; genau dieser
                    // Fall kommt nur ueber diesen Weg herein und haette die Migration sonst
                    // umgangen. Die Notlage-Standardkonfiguration ist oben bereits ausgefiltert
                    // ("DIE VIERTE TUER") - sie erreicht diese Zeile nicht und kann deshalb keine
                    // Regel auf einen Standardnamen umziehen.
                    val nacharmieren = zieheRegelmusterNach(vorherigeConfig, config)

                    // Erkennung neu laufen lassen (aktualisiert auch die Kuerzel-Vorschlaege) und
                    // die Alarme an die neuen Weckzeiten anpassen. Das Nacharmieren der Zeitketten
                    // haengt am Ausgang des Syncs (siehe [armiereZeitkettenNeu]) - auch auf DIESEM
                    // Weg, nicht nur in [updateShiftConfig]: eine Ruecksicherung bringt genau die
                    // Kombination "neue Namen, alte Spannen" herein, gegen die der Fix gerichtet ist.
                    val events = calendarStateHolder.events.value
                    if (events.isNotEmpty()) {
                        processCalendarEvents(events)
                    }
                    triggerAlarmCreationFromConfigUpdate(config, nacharmieren)
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
            // Der Stand VOR dem Schreiben - die einzige Gelegenheit, eine Umbenennung ueberhaupt
            // zu erkennen (die Definition behaelt ihre id, nur der Name aendert sich). Muss vor
            // jedem Schreiben an `_uiState` gelesen werden. Siehe [zieheRegelmusterNach].
            val vorherigeConfig = _uiState.value.currentShiftConfig

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

                    // VOR der Erkennung und vor dem Alarm-Sync: beides fuehrt (ueber ShiftSpanStore
                    // -> DimScheduleUseCase bzw. den Hue-Pfad) auf die Regelmuster, und die sollen
                    // dabei schon den neuen Namen tragen. Das NACHARMIEREN der Zeitketten gehoert
                    // dagegen hinter den Sync - es braucht die Spannen mit dem neuen Namen, siehe
                    // [armiereZeitkettenNeu]. Deshalb wandert der Bedarf weiter, statt hier sofort
                    // ausgefuehrt zu werden.
                    val nacharmieren = zieheRegelmusterNach(vorherigeConfig, config)

                    // DAS try/finally UMSCHLIESST AUCH DAS delay(200) (Befund 21.08.2026):
                    // `zieheRegelmusterNach` hat die Namenslisten unter NonCancellable bereits auf
                    // den neuen Namen gezogen. Das `delay` darunter ist ein garantierter
                    // Abbruchpunkt - verlaesst der Nutzer den Bildschirm genau dort, wurde das
                    // Nacharmieren nie erreicht, und die Dimm-/DND-Kette stuende bis zum naechsten
                    // Keep-alive oder zur 6h-Wartung auf dem Plan von VORHER: neue Liste, alter
                    // Tick. Genau die Nacht dazwischen ist die, um die es geht.
                    try {
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
                        triggerAlarmCreationFromConfigUpdate(config, nacharmieren)
                    } finally {
                        // Im Normalfall laeuft das Nacharmieren dadurch ZWEIMAL: einmal im finally
                        // von `triggerAlarmCreationFromConfigUpdate`, einmal hier. Das ist bewusst
                        // in Kauf genommen und folgenlos - `enable()` rechnet den Zustand aus den
                        // aktuellen Daten neu und setzt den naechsten Tick; zweimal dasselbe zu
                        // rechnen kostet Arbeit, aendert aber nichts. Der Bedarf ist ein
                        // unveraenderlicher Wert, es gibt also kein "schon erledigt"-Merken.
                        // Der zweite Aufruf ist die Versicherung fuer den Fall, dass der Abbruch
                        // im `delay` darueber zuschlug und das erste finally nie erreicht wurde.
                        armiereZeitkettenNeu(nacharmieren)
                    }
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

    /**
     * Zieht ALLE ueber den Schichtnamen gebundenen Einstellungen nach, wenn eine Schichtdefinition
     * UMBENANNT wurde: Dimm-Regeln, Hue-Regeln, die beiden DND-Schichtauswahlen und die
     * Ausnahmenliste des Nacht-Standards.
     *
     * WARUM (Pruefrunde 8, Befund 2): Alle diese Stellen binden ueber den NAMEN der Definition
     * (`DimRule.shiftPattern`, `HueSchedule.shiftPattern`, `dnd_oncall_shifts`,
     * `dnd_shift_excluded_shifts`, `dim_night_default_excluded_shifts`), waehrend der Editor den
     * Namen bei gleichbleibender `id` frei aendern laesst. Ohne diesen Nachzug legt eine reine
     * Beschriftungsaenderung sie lautlos still - die Regellisten zeigen sie weiter als
     * aktiv, das Licht bleibt am Wecktag aus, das Dimm-Fenster verschwindet, und im DND-Modus
     * "folgt dem Dimmer" faellt das Nachtfenster gleich mit weg.
     *
     * NACHTRAG 21.08.2026: Die drei Namenslisten kamen erst hier dazu - v1.30.0 hat nur die beiden
     * Regelarten mitgezogen. Am teuersten war `dnd_oncall_shifts`: Sie steuert den
     * Rufbereitschaft-Cutoff, und ihr Ins-Leere-Zeigen liess "Nicht stoeren" in der Nacht VOR der
     * Rufbereitschaft ueber 05:00 hinaus an - der Nutzer war nicht erreichbar, ohne dass irgendwo
     * etwas darauf hinwies (die Chips werden aus den AKTUELLEN Namen gebaut, der Alt-Name ist
     * unsichtbar). Genau dieser Zustand lag am Geraet vor. Wer eine WEITERE Stelle ergaenzt, die
     * einen Schichtnamen persistent speichert, gehoert hierher.
     *
     * `NonCancellable`: Das hier stellt einen konsistenten Zustand HER - dieselbe Begruendung wie
     * bei `MasterPauseUseCase.pause()/resume()`. Bricht der `viewModelScope` mittendrin ab (der
     * Nutzer verlaesst den Screen), bliebe sonst ein halb migrierter Bestand liegen: Dimmer
     * nachgezogen, Hue nicht - und niemand erfuehre davon.
     *
     * FEHLER WERDEN GEMELDET, nicht geschluckt: eine nicht nachgezogene Regel ist eine Funktion,
     * die der Nutzer bewusst eingerichtet hat und die ab jetzt nichts mehr tut. Die Meldung geht
     * ueber [ShiftUiState.regelNachzugHinweis] - NICHT ueber `error`; warum, steht dort.
     *
     * Aufgerufen aus [updateShiftConfig] UND aus [observeExternalConfigChanges]. Letzteres ist der
     * zentrale Einstieg fuer FREMDE Schreiber (Konfigurations-Import, also auch die
     * Backup-Ruecksicherung und der Geraetewechsel) - genau dort kommt eine Definition mit
     * gleicher `id` und anderem Namen an, und ohne diesen zweiten Aufruf umginge ausgerechnet
     * dieser Fall die Migration. Doppelt laeuft dadurch nichts: eigene Schreibvorgaenge erkennt
     * der Beobachter am Merker `selfWrittenConfig` bzw. am Vergleich mit `currentShiftConfig` und
     * steigt vorher aus.
     *
     * @return was danach neu armiert werden muss. Diese Funktion armiert bewusst NICHT selbst -
     *   das Nacharmieren braucht die Schichtspannen mit dem NEUEN Namen, und die schreibt erst
     *   `syncAlarms()`. Begruendung im Detail bei [armiereZeitkettenNeu].
     */
    private suspend fun zieheRegelmusterNach(
        vorher: ShiftConfig?,
        nachher: ShiftConfig
    ): NacharmierBedarf {
        val plan = planeSchichtUmbenennungen(vorher, nachher)
        if (plan.umbenennungen.isEmpty() && plan.blockiert.isEmpty()) return NacharmierBedarf.KEINE

        val fehlgeschlagen = mutableListOf<String>()
        // GETRENNT von `fehlgeschlagen`: ein gescheitertes RAEUMEN ist die gefaehrlichere Lage.
        // Der Umstell-Text ("wirkt wieder, wenn du dort den neuen Namen auswaehlst") lenkt auf eine
        // harmlose Nacharbeit - beim Raeumen bleibt dagegen ein Eintrag stehen, der scharf fuer die
        // FALSCHE Schicht wirkt. Das braucht einen eigenen Satz.
        val raeumenFehlgeschlagen = mutableListOf<String>()
        var nachgezogen = 0
        var dimmGeaendert = 0
        var dndGeaendert = 0
        /** Blockaden, bei denen wirklich ein scharfer Falscheintrag geraeumt wurde - fuer den Text. */
        val geraeumt = mutableMapOf<SchichtUmbenennung, MutableSet<String>>()

        withContext(NonCancellable) {
            for (umbenennung in plan.umbenennungen) {
                dimRuleUseCase.get().renameShiftPattern(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { dimmGeaendert += it }
                    .onFailure { fehlgeschlagen += "Dimmer" }

                hueRuleUseCase.get().renameShiftPattern(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { nachgezogen += it }
                    .onFailure { fehlgeschlagen += "Hue" }

                // Die uebersehenen Namenslisten (Befund 21.08.2026): zwei in "Nicht stoeren"
                // (Rufbereitschaft + Dienstzeit-Ausnahmen). Die dritte lag im Dimmer
                // (Nacht-Ausnahmen) und ist mit dem eingebauten Nacht-Standard entfallen.
                // Der Fehlschlag wird EINZELN gemeldet - eine unlesbare DND-Auswahl darf den
                // Nachzug der Dimm-Regeln nicht verhindern und umgekehrt. Der Nutzer sieht die
                // Namen der BILDSCHIRME ("Nicht stören", "Dimmer"), nicht die Speicherschluessel.
                dndPrefs.get().renameShiftName(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { dndGeaendert += it }
                    .onFailure { fehlgeschlagen += "Nicht stören" }
            }
            // VOR der Blockade-Schleife: `nachgezogen` zaehlt nur MIGRIERTES. Ein geraeumter
            // Falscheintrag ist kein Nachzug - er darf weder im Erfolgs-Log noch im Satz "alle
            // uebrigen wurden korrekt mitgezogen" auftauchen.
            nachgezogen += dimmGeaendert + dndGeaendert

            // DIE BLOCKIERTEN UMBENENNUNGEN - und warum Nichtstun hier NICHT reicht.
            //
            // Fuer eine Dimm-/Hue-REGEL ist Nichtstun ehrlich: sie wird wirkungslos und steht dabei
            // sichtbar in ihrer Regelliste, der Nutzer kann sie dort neu zuordnen. Die reinen
            // NAMENSLISTEN haben diese Sichtbarkeit nicht - ihre Chips werden aus den AKTUELLEN
            // Definitionsnamen gebaut. Gehoert der gespeicherte Altname nach einem Namenstausch
            // inzwischen einer ANDEREN Definition, ist der Eintrag deshalb nicht tot, sondern
            // scharf fuer die falsche Schicht: "Nicht stoeren" endet an deren Tagen frueher
            // (On-Call-Cutoff), waehrend die echte Rufbereitschaftsnacht durchgehend stumm bleibt.
            //
            // NUR DANN GERAEUMT: Bei den uebrigen Blockaden (mehrdeutiger Zielname, reserviertes
            // Muster, Zielname war frueher ein anderer Name) zeigt der Altname auf GAR KEINE
            // Definition mehr. Ein solcher Eintrag wirkt nirgends, und er wird von selbst wieder
            // richtig, sobald der Nutzer die Umbenennung zuruecknimmt - ihn zu loeschen waere
            // Datenverlust ohne Gegenwert.
            for (blockade in plan.blockiert) {
                if (!blockade.alterNameGehoertJetztAnderer) continue
                val alterName = blockade.umbenennung.alterName
                val betroffen = geraeumt.getOrPut(blockade.umbenennung) { mutableSetOf() }

                // Der PARTNERNAME (der neue Name derselben Schicht) entscheidet ueber den
                // Tauschfall: steht er ebenfalls in der Liste, ist deren Inhalt weiterhin exakt
                // richtig und darf nicht geraeumt werden - siehe entferneSchichtnamen().
                val partnerName = blockade.umbenennung.neuerName

                dndPrefs.get().removeShiftName(alterName, partnerName)
                    .onSuccess {
                        dndGeaendert += it
                        if (it > 0) betroffen += "„Nicht stören“"
                    }
                    .onFailure { raeumenFehlgeschlagen += "„Nicht stören“" }
            }
        }

        plan.blockiert.forEach { blockade ->
            val zusatz = if (blockade.alterNameGehoertJetztAnderer) {
                " - Namenslisten geraeumt, damit der Eintrag nicht fuer die falsche Schicht wirkt"
            } else {
                " - Altname gehoert keiner Definition mehr, Eintraege bleiben unberuehrt"
            }
            Logger.w(
                LogTags.SHIFT_CONFIG,
                "⚠️ UMBENENNUNG '${blockade.umbenennung.alterName}' -> " +
                    "'${blockade.umbenennung.neuerName}': Regelmuster NICHT nachgezogen " +
                    "(${blockade.grund})$zusatz"
            )
        }

        if (nachgezogen > 0) {
            Logger.business(
                LogTags.SHIFT_CONFIG,
                "🔁 SCHICHT UMBENANNT: $nachgezogen Regel(n)/Auswahl(en) in Dimmer, Hue und " +
                    "\"Nicht stoeren\" auf den neuen Namen nachgezogen"
            )
        }

        // Der Nutzer erfaehrt es - sonst haelt er eine Regel fuer aktiv, die es nicht mehr ist.
        // Beschreibt die WIRKUNG, nicht die Innerei, und sagt, was zu tun ist.
        //
        // ZUSAMMENGESETZT statt `when`-Auswahl (Befund 21.08.2026): Beide Listen des Plans koennen
        // gleichzeitig gefuellt sein - eine Schicht wandert sauber mit, eine zweite ist blockiert.
        // Ein `when` nannte in diesem Fall nur den ersten Zweig und behauptete pauschal, es sei
        // NICHTS mitgezogen worden. Der Text sagt jetzt, was wirklich passiert ist, und er nennt
        // die betroffene Schicht - ohne ihren Namen weiss der Nutzer nicht, wo er nachbessern soll.
        // Die Bezeichner sind die Namen der BILDSCHIRME ("Dimmer", "Hue", "Nicht stören"), nicht
        // die der Speicherschlüssel dahinter.
        val teile = mutableListOf<String>()

        if (fehlgeschlagen.isNotEmpty()) {
            teile += "Die Schicht wurde umbenannt, aber die Einstellungen für " +
                "${fehlgeschlagen.distinct().joinToString(" und ")} konnten nicht auf den neuen " +
                "Namen umgestellt werden. Sie wirken für diese Schicht erst wieder, wenn du dort " +
                "den neuen Namen auswählst."
        }

        if (raeumenFehlgeschlagen.isNotEmpty()) {
            teile += "Achtung: in ${raeumenFehlgeschlagen.distinct().joinToString(" und ")} steht " +
                "noch ein alter Schichtname, der jetzt zu einer ANDEREN Schicht gehört – die " +
                "Einstellung wirkt dort, wo du sie nie wolltest. Bitte dort einmal nachsehen und " +
                "die Auswahl neu setzen."
        }

        plan.blockiert.forEach { blockade ->
            val alt = blockade.umbenennung.alterName
            val neu = blockade.umbenennung.neuerName
            val satz = StringBuilder(
                "„$alt“ heißt jetzt „$neu“, aber die Regeln und Einstellungen dazu wurden NICHT " +
                    "mitgezogen: ${blockade.grund}."
            )
            val betroffen = geraeumt[blockade.umbenennung].orEmpty()
            if (betroffen.isNotEmpty()) {
                // Der Nutzer muss WISSEN, dass hier etwas entfernt wurde - sonst sucht er den
                // verschwundenen Haken als Fehler.
                satz.append(
                    " Die gespeicherte Auswahl „$alt“ in ${betroffen.sorted().joinToString(" und ")} " +
                        "wurde entfernt, weil dieser Name inzwischen zu einer anderen Schicht " +
                        "gehört und dort still gewirkt hätte."
                )
            }
            satz.append(" Stell für „$neu“ ein, was du brauchst – sonst wirkt dort nichts.")
            teile += satz.toString()
        }

        if (nachgezogen > 0 && plan.blockiert.isNotEmpty()) {
            // Ohne diesen Satz liest sich die Blockade-Meldung, als sei der ganze Speichervorgang
            // schiefgegangen - dabei sind die uebrigen Schichten korrekt migriert.
            teile += "Alle übrigen umbenannten Schichten wurden korrekt mitgezogen."
        }

        if (teile.isNotEmpty()) {
            // EIGENES Feld, nicht `error`: die unmittelbar folgende `processCalendarEvents()`
            // setzt `error` auf null (siehe [ShiftUiState.regelNachzugHinweis]) - die Meldung war
            // weg, bevor sie jemand lesen konnte.
            _uiState.value = _uiState.value.copy(regelNachzugHinweis = teile.joinToString(" "))
        }

        // WAS NEU ARMIERT WERDEN MUSS - aber NICHT hier, siehe [armiereZeitkettenNeu].
        //
        // WARUM DIE AUSNAHMENLISTE DES NACHT-STANDARDS IN `dimmGeaendert` ZAEHLT: sie ist ein
        // Eingang von `DimScheduleUseCase.computeWindows()` (`isExcluded`) - eine Aenderung daran
        // verschiebt die Dimm-Fenster genauso wie eine geaenderte Regel.
        //
        // WARUM DIE DND-AUSWAHLEN NUR DIE DND-KETTE NACHARMIEREN: `dnd_oncall_shifts` und
        // `dnd_shift_excluded_shifts` liest ausschliesslich `DndScheduleUseCase` (Dienstzeit-Fenster
        // und Rufbereitschaft-Cutoff). Der Dimmer kennt sie nicht - ein `dimScheduleUseCase.enable()`
        // dafuer waere Arbeit ohne jede Wirkung. Umgekehrt zieht ein geaendertes Dimm-Fenster die
        // DND-Kette sehr wohl mit: im Modus "folgt dem Dimmer" ist die Dimm-Zeitleiste die
        // Fensterquelle von "Nicht stoeren".
        return NacharmierBedarf(
            dimmer = dimmGeaendert > 0,
            dnd = dimmGeaendert > 0 || dndGeaendert > 0
        )
    }

    /**
     * Armiert die Dimm- und DND-Zeitketten neu, nachdem eine Umbenennung nachgezogen wurde.
     *
     * WARUM NICHT DIREKT IN [zieheRegelmusterNach] (Befund 21.08.2026): Dort lief es unmittelbar
     * nach dem Umschreiben der Namenslisten - also BEVOR `syncAlarms()` die Schichtspannen mit dem
     * neuen Namen neu geschrieben hat. Der [com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore]
     * wird ausschliesslich dort beschrieben, und `syncAlarms()` armiert die Ketten nicht selbst.
     * `enable()` mischte damit frisch nachgezogene Listen (NEUER Name) mit Spannen, die noch den
     * ALTEN trugen: das Fenster entstand ohne Rufbereitschaft-Cutoff und ohne Dienstzeit-Ausnahme,
     * und weil der naechste Tick genau auf dieses (zu spaete) Ende faellt, blieb der falsche Plan
     * fuer die ganze Nacht stehen. Erst die Spannen, dann das Nacharmieren.
     *
     * DESHALB AUCH DANN, WENN DER SYNC NICHT LAEUFT: Ein Aufruf im Erfolgszweig von `syncAlarms()`
     * waere der halbe Fix. Der Sync faellt regelmaessig aus - "Automatische Alarme" ist aus, es
     * liegen keine Termine vor, die Eventliste ist nur ein Ausschnitt, oder er scheitert. Dann sind
     * die Spannen zwar alt, aber die LISTEN sind bereits neu, und der armierte Tick steht noch auf
     * dem Stand von davor. Nicht nachzuarmieren waere in diesem Fall nicht neutral, sondern der
     * schlechtere von zwei alten Staenden. Deshalb haengt der Aufruf im `finally` von
     * [triggerAlarmCreationFromConfigUpdate] - er laeuft auf JEDEM Ausgang.
     *
     * `NonCancellable` und einzeln gefangen (Vorbild ConfigBackupUseCase): das hier stellt einen
     * Zustand HER, und beide `enable()` haben ihren eigenen Master-Pause-Backstop. Ein Fehlschlag
     * darf die bereits erfolgreich umgeschriebene Regel nicht als gescheitert melden.
     */
    private suspend fun armiereZeitkettenNeu(bedarf: NacharmierBedarf) {
        if (!bedarf.beanspruche()) return
        withContext(NonCancellable) {
            if (bedarf.dimmer) {
                runCatching { dimScheduleUseCase.get().enable() }
                    .onFailure { Logger.w(LogTags.DIMMER, "⚠️ UMBENENNUNG: Dimmer-Kette nicht neu armiert", it) }
            }
            if (bedarf.dnd) {
                runCatching { dndScheduleUseCase.get().enable() }
                    .onFailure { Logger.w(LogTags.DND, "⚠️ UMBENENNUNG: DND-Kette nicht neu armiert", it) }
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
                //
                // BLEIBT BEWUSST AUF DEM AUFRUFER-THREAD (Pruefrunde 7): die teure Haelfte dieses
                // Durchgangs - die Schichterkennung - wechselt inzwischen selbst auf
                // `Dispatchers.Default` (siehe ShiftRecognitionEngine.getAllMatchingShifts). Was
                // hier uebrig bleibt, ist eine Schleife ueber Termine x aktivierte Definitionen mit
                // VORKOMPILIERTEN Mustern (WordBoundaryPatterns) - genau dieselben Muster, die die
                // Erkennung eben benutzt hat, also garantiert bereits im Vorrat. Ein eigener
                // Dispatcher-Wechsel wuerde hier nur einen zweiten Thread-Wechsel pro
                // Kalender-Aktualisierung kosten.
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
     *
     * `suspend` STATT `viewModelScope.launch` (Befund 21.08.2026): Beide Aufrufer laufen bereits
     * in einer eigenen Coroutine, und [nacharmieren] MUSS nach dem Sync ablaufen - ein
     * fire-and-forget-`launch` haette dafuer keinen definierten Zeitpunkt. Nebenbei faellt damit
     * dieselbe Ueberlappung weg, die schon bei `processCalendarEvents()` teuer war: der Aufruf
     * liegt jetzt sichtbar in der Reihenfolge seines Aufrufers.
     *
     * @param nacharmieren was nach dem Sync neu armiert werden muss - im `finally`, also auf JEDEM
     *   Ausgang dieser Funktion. Warum gerade dort und nicht im Erfolgszweig, steht bei
     *   [armiereZeitkettenNeu].
     */
    private suspend fun triggerAlarmCreationFromConfigUpdate(
        config: ShiftConfig,
        nacharmieren: NacharmierBedarf = NacharmierBedarf.KEINE
    ) {
        try {
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
                return
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
                return
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
                return
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
        } finally {
            // AUF JEDEM AUSGANG - auch auf den drei fruehen `return` oben (Master-Pause, keine
            // Termine, unvollstaendige Liste) und auch bei einem Abbruch. Die Namenslisten sind zu
            // diesem Zeitpunkt bereits umgeschrieben; ein ausgefallenes Nacharmieren liesse den
            // armierten Tick auf dem Stand von davor stehen. `armiereZeitkettenNeu` haelt seine
            // Arbeit selbst in `NonCancellable` - deshalb ueberlebt sie auch einen abgebrochenen
            // Scope.
            armiereZeitkettenNeu(nacharmieren)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Bestaetigt den Hinweis zum Regel-Nachzug ([ShiftUiState.regelNachzugHinweis]).
     *
     * Nur der Nutzer raeumt ihn weg - kein Ladevorgang, kein Folgezustand. Er beschreibt eine
     * Funktion, die ab jetzt nicht mehr tut, was sie soll; sie wegzuwischen, weil gerade Events
     * nachgeladen wurden, war genau der Fehler, den dieses Feld behebt.
     */
    fun clearRegelNachzugHinweis() {
        _uiState.value = _uiState.value.copy(regelNachzugHinweis = null)
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

/** Eine erkannte Umbenennung: dieselbe Definition (gleiche `id`), neuer Name. */
internal data class SchichtUmbenennung(val alterName: String, val neuerName: String)

/**
 * Welche Zeitketten nach einem Nachzug neu armiert werden muessen - siehe
 * [ShiftViewModel.armiereZeitkettenNeu] fuer das Warum und vor allem fuer das WANN.
 *
 * Ein eigener Wert statt zweier Booleans im Aufruf: Er reist vom Nachzug bis hinter den Alarm-Sync,
 * und an dieser Strecke soll man lesen koennen, was transportiert wird.
 */
internal data class NacharmierBedarf(val dimmer: Boolean, val dnd: Boolean) {
    val irgendetwas: Boolean get() = dimmer || dnd

    /**
     * EINWEG-SPERRE. Das Nacharmieren wird an ZWEI Stellen angestossen: im `finally` von
     * `triggerAlarmCreationFromConfigUpdate` (der Normalfall, nach dem Alarm-Sync) und im `finally`
     * eine Ebene darueber, das auch das `delay(200)` umschliesst - dieses `delay` ist ein
     * Abbruchpunkt, und ohne die zweite Stelle fiele das Nacharmieren dort ersatzlos aus.
     * Beide Wege sollen greifen, aber die Kette soll genau EINMAL neu armiert werden: doppelt
     * dasselbe zu rechnen ist Arbeit ohne Wirkung, und ein Testlauf soll die Zusicherung
     * "genau einmal" auch pruefen koennen.
     */
    private val erledigt = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true nur beim ERSTEN Aufrufer - siehe [erledigt]. */
    fun beanspruche(): Boolean = irgendetwas && erledigt.compareAndSet(false, true)

    companion object {
        val KEINE = NacharmierBedarf(dimmer = false, dnd = false)
    }
}

/**
 * Eine Umbenennung, deren Regelmuster bewusst NICHT nachgezogen wird - mit dem Warum.
 *
 * [alterNameGehoertJetztAnderer] trennt die beiden sehr verschiedenen Blockade-Ausgaenge: Zeigt
 * der gespeicherte Altname nach der Umbenennung auf KEINE Definition mehr, ist er ein toter
 * Eintrag - er tut nichts, und er wird wieder richtig, wenn der Nutzer die Umbenennung zuruecknimmt.
 * Gehoert er dagegen inzwischen einer ANDEREN Definition, ist er scharf fuer die falsche Schicht;
 * dann muessen die reinen Namenslisten geraeumt werden (siehe [ShiftViewModel] -
 * `zieheRegelmusterNach`).
 *
 * EXAKTER Vergleich, bewusst anders als bei den Blockade-Gruenden daneben: Die Namenslisten pruefen
 * Mengen-Zugehoerigkeit ohne Toleranz. Ein Eintrag, der sich nur in der Schreibweise von der neuen
 * Eigentuemerin unterscheidet, gehoert ihr also gerade NICHT und darf nicht mit ihr begruendet
 * geloescht werden.
 */
internal data class BlockierteUmbenennung(
    val umbenennung: SchichtUmbenennung,
    val grund: String,
    val alterNameGehoertJetztAnderer: Boolean
)

internal data class SchichtUmbenennungsPlan(
    val umbenennungen: List<SchichtUmbenennung>,
    val blockiert: List<BlockierteUmbenennung>
)

/**
 * PURE, TESTBAR: Welche Umbenennungen muessen in Dimmer-/Hue-Regelmustern nachgezogen werden?
 *
 * Verglichen wird ueber die stabile `id` - nur so laesst sich eine Umbenennung von einer
 * geloeschten plus neu angelegten Definition unterscheiden. Eine geloeschte Definition ist
 * ausdruecklich KEINE Umbenennung: ihre Regeln sollen dort stehen bleiben, wo sie sind, statt
 * auf eine fremde Schicht zu wandern.
 *
 * AUCH EINE REINE SCHREIBWEISENAENDERUNG ("abrufdienst" -> "Abrufdienst") IST EINE UMBENENNUNG.
 * Bis zum 21.08.2026 stieg diese Funktion hier aus, mit der Begruendung, der Vergleich in
 * `DimRuleUseCase.findRuleForShift` und `HueRuleUseCase.findApplicableRules` sei
 * gross-/kleinschreibungsblind - ein Nachzug also ein Schreibvorgang ohne Nutzen. Fuer die beiden
 * REGELARTEN stimmt das bis heute. Fuer die drei reinen NAMENSLISTEN stimmt es nicht: sie werden
 * EXAKT geprueft (`it.shiftName in onCallShifts` in `DndOnCallCutoffResolver`,
 * `alarm.shiftName in excludedShifts` in `DndShiftSpanResolver` und in `DimScheduleUseCase`).
 * Korrigiert der Nutzer nur den Kasus, bleibt dort der alte stehen und trifft nie wieder - der
 * Rufbereitschaft-Cutoff faellt aus, und "Nicht stoeren" bleibt in der Nacht vor der
 * Rufbereitschaft ueber 05:00 hinaus an. Deshalb gilt jetzt der EXAKTE Vergleich als Abbruch:
 * gleicher Name heisst zeichengleicher Name. Fuer die Regeln bleibt der zusaetzliche
 * Schreibvorgang folgenlos - sie trafen vorher und treffen nachher.
 *
 * DIE VIER BLOCKADEN sind kein Beiwerk, sondern der Unterschied zwischen "Regel gerettet" und
 * "Regel bei der falschen Schicht":
 *  - Sondermuster ("alle Schichten", "freie Tage") meinen KEINEN Namen. Zoege man sie mit, wuerde
 *    aus einer Regel fuer alle Tage eine fuer genau eine Schicht - der Nutzer verloere still das
 *    Dimmen/Licht an allen uebrigen.
 *  - Heisst eine ANDERE Definition jetzt genauso, waere die Zuordnung mehrdeutig (beide Schichten
 *    zoegen dieselbe Regel).
 *  - Gehoert der ALTE Name jetzt einer anderen Definition (Namenstausch), gehoeren die Regeln mit
 *    diesem Muster ab sofort ihr - sie duerfen nicht mitwandern.
 *  - Hiess bisher eine andere Definition so wie diese jetzt, tragen deren Regeln das Zielmuster
 *    bereits; ein Zusammenlegen waere nicht rueckgaengig zu machen (und
 *    `findRuleForShift` nimmt ohnehin nur den ersten Treffer).
 * In allen vier Faellen ist Nichtstun plus Meldung die einzige ehrliche Antwort - eine falsch
 * zugeordnete Regel schaltet Licht und Verdunkelung zur falschen Zeit.
 */
internal fun planeSchichtUmbenennungen(
    vorher: ShiftConfig?,
    nachher: ShiftConfig
): SchichtUmbenennungsPlan {
    if (vorher == null) return SchichtUmbenennungsPlan(emptyList(), emptyList())

    val altePerId = vorher.definitions.associateBy { it.id }
    val umbenennungen = mutableListOf<SchichtUmbenennung>()
    val blockiert = mutableListOf<BlockierteUmbenennung>()

    nachher.definitions.forEach { neu ->
        val alt = altePerId[neu.id] ?: return@forEach
        val alterName = alt.name
        val neuerName = neu.name
        if (alterName.isBlank() || neuerName.isBlank()) return@forEach
        // EXAKT, nicht `ignoreCase`: siehe KDoc - eine reine Schreibweisenaenderung MUSS nachgezogen
        // werden, weil die drei Namenslisten exakt vergleichen.
        if (alterName == neuerName) return@forEach

        val andereJetzt = nachher.definitions.filter { it.id != neu.id }.map { it.name }
        val andereVorher = vorher.definitions.filter { it.id != neu.id }.map { it.name }

        val grund = when {
            istReserviertesMuster(neuerName) || istReserviertesMuster(alterName) ->
                "der Name ist ein reserviertes Regelmuster"

            andereJetzt.any { it.equals(neuerName, ignoreCase = true) } ->
                "eine andere Schicht heisst jetzt ebenfalls '$neuerName'"

            andereJetzt.any { it.equals(alterName, ignoreCase = true) } ->
                "der bisherige Name '$alterName' gehoert jetzt einer anderen Schicht"

            andereVorher.any { it.equals(neuerName, ignoreCase = true) } ->
                "'$neuerName' war bisher der Name einer anderen Schicht"

            else -> null
        }

        val umbenennung = SchichtUmbenennung(alterName, neuerName)
        if (grund == null) {
            umbenennungen += umbenennung
        } else {
            // EXAKT: die Namenslisten vergleichen exakt, also gehoert der gespeicherte Altname nur
            // dann wirklich einer anderen Schicht, wenn deren Name zeichengleich ist. Unabhaengig
            // davon berechnet, welcher Blockade-Grund oben zuerst gegriffen hat - die Gruende
            // schliessen einander nicht aus, und der gefaehrliche Fall darf nicht davon abhaengen,
            // welcher `when`-Zweig zuerst dran war.
            blockiert += BlockierteUmbenennung(
                umbenennung = umbenennung,
                grund = grund,
                alterNameGehoertJetztAnderer = nachher.definitions.any {
                    it.id != neu.id && it.name == alterName
                }
            )
        }
    }

    return SchichtUmbenennungsPlan(umbenennungen, blockiert)
}

/**
 * Namen, die als Regelmuster eine Sonderbedeutung tragen und deshalb nie wie ein Schichtname
 * behandelt werden duerfen. Die Konstanten kommen aus den Regel-Modellen selbst - ein zweites
 * Literal hier waere eine zweite Wahrheit.
 */
private fun istReserviertesMuster(name: String): Boolean =
    name.equals(DimRule.SHIFT_UNIVERSAL, ignoreCase = true) ||
        name.equals(DimRule.SHIFT_FREE, ignoreCase = true) ||
        name.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true)
