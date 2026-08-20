package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
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
    private val dndScheduleUseCase: dagger.Lazy<DndScheduleUseCase>
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
                    zieheRegelmusterNach(vorherigeConfig, config)

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
                    // dabei schon den neuen Namen tragen.
                    zieheRegelmusterNach(vorherigeConfig, config)

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

    /**
     * Zieht Dimmer- und Hue-Regeln nach, wenn eine Schichtdefinition UMBENANNT wurde.
     *
     * WARUM (Pruefrunde 8, Befund 2): Beide Regelarten binden ueber den NAMEN der Definition
     * (`DimRule.shiftPattern`, `HueSchedule.shiftPattern`), waehrend der Editor den Namen bei
     * gleichbleibender `id` frei aendern laesst. Ohne diesen Nachzug legt eine reine
     * Beschriftungsaenderung beide Regeln lautlos still - die Regellisten zeigen sie weiter als
     * aktiv, das Licht bleibt am Wecktag aus, das Dimm-Fenster verschwindet, und im DND-Modus
     * "folgt dem Dimmer" faellt das Nachtfenster gleich mit weg.
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
     */
    private suspend fun zieheRegelmusterNach(vorher: ShiftConfig?, nachher: ShiftConfig) {
        val plan = planeSchichtUmbenennungen(vorher, nachher)
        if (plan.umbenennungen.isEmpty() && plan.blockiert.isEmpty()) return

        val fehlgeschlagen = mutableListOf<String>()
        var nachgezogen = 0
        var dimmGeaendert = 0

        withContext(NonCancellable) {
            for (umbenennung in plan.umbenennungen) {
                dimRuleUseCase.get().renameShiftPattern(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { dimmGeaendert += it }
                    .onFailure { fehlgeschlagen += "Dimmer" }

                hueRuleUseCase.get().renameShiftPattern(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { nachgezogen += it }
                    .onFailure { fehlgeschlagen += "Hue" }
            }
            nachgezogen += dimmGeaendert

            // Erst NACH dem Schreiben, und nur wenn sich wirklich etwas geaendert hat: die
            // Dimm-Fenster werden aus den Regeln berechnet, der naechste Tick war also auf den
            // ALTEN Plan gesetzt. Ohne dieses Nacharmieren stuende die gerettete Regel im Store,
            // waehrend bis zur naechsten 6h-Wartung weiter nach dem alten Plan gedimmt wird.
            // Best-effort und einzeln gefangen (Vorbild ConfigBackupUseCase): beide enable() haben
            // ihren eigenen Master-Pause-Backstop, aber ein Fehlschlag hier darf die bereits
            // erfolgreich umgeschriebene Regel nicht als gescheitert melden.
            if (dimmGeaendert > 0) {
                runCatching { dimScheduleUseCase.get().enable() }
                    .onFailure { Logger.w(LogTags.DIMMER, "⚠️ UMBENENNUNG: Dimmer-Kette nicht neu armiert", it) }
                runCatching { dndScheduleUseCase.get().enable() }
                    .onFailure { Logger.w(LogTags.DND, "⚠️ UMBENENNUNG: DND-Kette nicht neu armiert", it) }
            }
        }

        plan.blockiert.forEach { blockade ->
            Logger.w(
                LogTags.SHIFT_CONFIG,
                "⚠️ UMBENENNUNG '${blockade.umbenennung.alterName}' -> " +
                    "'${blockade.umbenennung.neuerName}': Regelmuster NICHT nachgezogen " +
                    "(${blockade.grund})"
            )
        }

        if (nachgezogen > 0) {
            Logger.business(
                LogTags.SHIFT_CONFIG,
                "🔁 SCHICHT UMBENANNT: $nachgezogen Dimmer-/Hue-Regel(n) auf den neuen Namen nachgezogen"
            )
        }

        // Der Nutzer erfaehrt es - sonst haelt er eine Regel fuer aktiv, die es nicht mehr ist.
        // Beschreibt die WIRKUNG, nicht die Innerei, und sagt, was zu tun ist.
        val meldung = when {
            fehlgeschlagen.isNotEmpty() -> "Die Schicht wurde umbenannt, aber die zugehörigen " +
                "${fehlgeschlagen.distinct().joinToString("- und ")}-Regeln konnten nicht auf den " +
                "neuen Namen umgestellt werden. Sie wirken für diese Schicht erst wieder, wenn du " +
                "dort den neuen Namen auswählst."

            plan.blockiert.isNotEmpty() -> "Die Schicht wurde umbenannt. Die Dimmer- und " +
                "Hue-Regeln dazu wurden NICHT mitgezogen, weil der Name jetzt nicht mehr eindeutig " +
                "ist. Wähle in den betroffenen Regeln die Schicht neu aus – sonst wirken sie nicht."

            else -> null
        }
        if (meldung != null) {
            // EIGENES Feld, nicht `error`: die unmittelbar folgende `processCalendarEvents()`
            // setzt `error` auf null (siehe [ShiftUiState.regelNachzugHinweis]) - die Meldung war
            // weg, bevor sie jemand lesen konnte.
            _uiState.value = _uiState.value.copy(regelNachzugHinweis = meldung)
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

/** Eine Umbenennung, deren Regelmuster bewusst NICHT nachgezogen wird - mit dem Warum. */
internal data class BlockierteUmbenennung(
    val umbenennung: SchichtUmbenennung,
    val grund: String
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
 * NUR was das Matching wirklich bricht: Der Vergleich in `DimRuleUseCase.findRuleForShift` und
 * `HueRuleUseCase.findApplicableRules` ist gross-/kleinschreibungsblind, eine reine
 * Schreibweisenaenderung ("ad1" -> "AD1") legt also nichts stumm. Sie zu migrieren waere ein
 * Schreibvorgang ohne Nutzen - und jeder Schreibvorgang kann scheitern.
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
        if (alterName.equals(neuerName, ignoreCase = true)) return@forEach

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
            blockiert += BlockierteUmbenennung(umbenennung, grund)
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
