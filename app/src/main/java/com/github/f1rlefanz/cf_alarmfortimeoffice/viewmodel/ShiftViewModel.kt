package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.LetzterSchichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
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
import kotlinx.coroutines.CancellationException
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
        ShiftCodeSuggester.SuggestionResult(emptyList(), 0),
    /**
     * Was der letzte Abgleich ueber die Schichten wusste - NUR fuer die Anzeige, wenn der Kalender
     * gerade nicht erreichbar ist (Karte "Naechste Schicht"). `null` = nicht lesbar oder noch nicht
     * gelesen; die Karte zeigt dann den bisherigen Hinweis. Daraus entsteht nie ein Wecker.
     */
    val letzterSchichtStand: LetzterSchichtStand? = null
)

/**
 * ShiftViewModel - liest Kalender-Events aus dem CalendarStateHolder, nicht vom CalendarViewModel.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftUseCase: IShiftUseCase,
    private val alarmUseCase: IAlarmUseCase,
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
    private val armierer: ZeitkettenArmierer,
    /**
     * Die DRITTE Stelle, die ueber den Schichtnamen bindet: die Dienstzeit-Ausnahmen von
     * "Nicht stoeren" ([DndPrefs.renameShiftName]). Sie wurde beim Nachzug in v1.30.0
     * uebersehen - warum das teuer ist, steht an der Funktion. Die Rufbereitschaft-Auswahl war
     * bis v1.40.8 eine weitere solche Liste; sie ist heute ein Flag AM Schichttyp
     * (`ShiftDefinition.isOnCall`) und reist bei einer Umbenennung von selbst mit.
     *
     * Die Nacht-Ausnahmen des Dimmers waren einmal eine VIERTE solche Stelle; mit dem
     * eingebauten Nacht-Standard ist auch seine Namensliste entfallen. Der Dimmer bindet nur
     * noch ueber `DimRule.shiftPattern`, und das zieht `dimRuleUseCase` oben nach.
     *
     * Ebenfalls `dagger.Lazy`, aus demselben Grund wie oben: dieses ViewModel entsteht beim
     * App-Start, und die Klasse soll erst angefasst werden, wenn wirklich umbenannt wird.
     */
    private val dndPrefs: dagger.Lazy<DndPrefs>,
    /**
     * Nur LESEND, fuer [ShiftUiState.letzterSchichtStand]. `dagger.Lazy` wie oben: der Read
     * passiert erst nach der ersten Event-Emission, nicht beim Bauen des ViewModels.
     */
    private val shiftSpanStore: dagger.Lazy<ShiftSpanStore>
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShiftUiState())
    val uiState: StateFlow<ShiftUiState> = _uiState.asStateFlow()

    /**
     * Die Konfiguration, die DIESES ViewModel gerade selbst schreibt - damit
     * [observeExternalConfigChanges] sie nicht fuer eine fremde Aenderung haelt. Gesetzt VOR dem
     * Write (DataStore veroeffentlicht vor `onSuccess`), geloescht beim ersten Treffer.
     * Hergang: schichterkennung.md.
     *
     * MUSS VOR dem `init{}`-Block stehen - der Collector im `init{}` kann synchron anlaufen.
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
     * kamen (z.B. Import), und zieht Anzeige, Schichterkennung UND Alarme nach. Ein zentraler
     * Beobachter deckt jeden Schreiber ab; eigene Writes werden uebersprungen, sonst liefen
     * Erkennung und Sync doppelt und nebenlaeufig. Hergang: schichterkennung.md.
     */
    private fun observeExternalConfigChanges() {
        viewModelScope.launch {
            shiftUseCase.shiftConfig
                .drop(1) // Erste Emission ist der Ist-Zustand, den loadShiftConfig() ohnehin holt.
                .collect { flowConfig ->
                    // EIGENEN Write ueber den VOR dem Write gesetzten Merker erkennen, nicht ueber
                    // `currentShiftConfig` (verliert das Rennen gegen DataStore).
                    if (flowConfig == selfWrittenConfig) {
                        selfWrittenConfig = null
                        return@collect
                    }
                    if (flowConfig == _uiState.value.currentShiftConfig) return@collect

                    // DIE VIERTE TUER: der Flow degradiert bei Defekt auf Standardwerte - nur ein
                    // Erfolg von `getCurrentShiftConfig()` gilt als echte Aenderung.
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
     * Observiert Calendar Events vom StateHolder.
     * distinctUntilChanged() entfernt - StateFlow ist bereits distinct.
     */
    private fun observeCalendarEvents() {
        viewModelScope.launch {
            calendarStateHolder.events
                .debounce(400) // ENHANCED: Längeres Debouncing für teure Shift-Recognition (400ms)
                .collect { events: List<CalendarEvent> ->
                    // Bei JEDER Emission, auch der leeren: offline ist die Liste leer, und genau
                    // dann braucht die Karte den letzten bekannten Stand. Nach einem gelungenen
                    // Abgleich holt die naechste Emission den frisch geschriebenen.
                    aktualisiereLetztenSchichtStand()
                    if (events.isNotEmpty()) {
                        processCalendarEvents(events)
                    } else {
                        // Clear recognized shifts wenn keine Events vorhanden
                        _uiState.value = _uiState.value.copy(
                            recognizedShifts = emptyList(),
                            upcomingShift = null
                        )
                    }
                }
        }
    }

    /**
     * Liest [ShiftSpanStore.letzterStand] fuer die Offline-Anzeige. Ein Fehlschlag (oder ein
     * fehlender Store) laesst den Wert auf `null` - die Karte faellt dann auf den bisherigen,
     * wahren Hinweis zurueck, statt einen Stand zu erfinden.
     */
    private fun aktualisiereLetztenSchichtStand() {
        viewModelScope.launch {
            val stand = try {
                shiftSpanStore.get().letzterStand().getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(LogTags.SHIFT, "Letzter Schichtstand nicht lesbar", e)
                null
            }
            _uiState.value = _uiState.value.copy(letzterSchichtStand = stand)
        }
    }

    private fun loadShiftConfig() {
        viewModelScope.launch {
            shiftUseCase.getCurrentShiftConfig()
                .onSuccess { config ->
                    _uiState.value = _uiState.value.copy(currentShiftConfig = config)
                    Logger.business(LogTags.SHIFT_CONFIG, "✅ SINGLETON-STARTUP: ShiftConfig loaded successfully - autoAlarm=${config.autoAlarmEnabled}, definitions=${config.definitions.size}")
                }
                .onFailure { error ->
                    // KEIN Default-Fallback, der SCHREIBT: Fehlschlag heisst "Konfiguration defekt",
                    // Ueberschreiben waere Datenverlust; der Weg zum Default (Knopf "Auf
                    // Standardwerte zurücksetzen") gehoert dem Nutzer.
                    // Hergang: schichterkennung.md.
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
                        val currentEvents = calendarStateHolder.events.value
                        if (currentEvents.isNotEmpty()) {
                            // Small delay to ensure config is fully persisted
                            kotlinx.coroutines.delay(200)

                            processCalendarEvents(currentEvents)
                        }

                        // 🚨 CRITICAL FIX: Trigger automatic alarm creation after shift config update!
                        // Unconditional (auch ohne Events): ein Ausschalten von "Automatische Alarme"
                        // muss die Alarme sofort raeumen, nicht nur wenn gerade Events geladen sind.
                        triggerAlarmCreationFromConfigUpdate(config, nacharmieren)
                    } finally {
                        // Beide finally-Bloecke (dieser und der von
                        // `triggerAlarmCreationFromConfigUpdate`) stossen das Nacharmieren an;
                        // `NacharmierBedarf.beanspruche()` sorgt dafuer, dass genau einmal armiert
                        // wird. Dieser Aufruf ist die Versicherung fuer den Fall, dass der Abbruch
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
     * UMBENANNT wurde: Dimm-Regeln, Hue-Regeln und die Dienstzeit-Ausnahmen von "Nicht stoeren".
     * Wer eine WEITERE Stelle ergaenzt, die einen Schichtnamen persistent speichert, gehoert hierher.
     *
     * `NonCancellable`: stellt einen konsistenten Zustand HER (sonst halb migriert).
     * Fehler werden ueber [ShiftUiState.regelNachzugHinweis] gemeldet, NICHT ueber `error`.
     * Zwei Aufrufer: [updateShiftConfig] UND [observeExternalConfigChanges] (fremde Schreiber wie
     * Import und Ruecksicherung).
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
        // Das Rufbereitschaft-Flag ist KEINE Namensliste mehr, aber es hat dieselben Leser: den
        // DND-Cutoff und die stuendliche Kalender-Abfrage. Aendert sich die Menge der
        // Rufbereitschafts-Schichten, muss die DND-Kette (und mit ihr die Abfrage, siehe
        // ZeitkettenArmierer) neu armiert werden - frueher tat das der Chip-Tap im DND-Bildschirm
        // selbst. Ohne Vorher-Stand (erster Load) gibt es keine Aenderung zu erkennen.
        val rufbereitschaftGeaendert = vorher != null &&
            rufbereitschaftsNamen(vorher) != rufbereitschaftsNamen(nachher)
        if (plan.umbenennungen.isEmpty() && plan.blockiert.isEmpty()) {
            return NacharmierBedarf(dimmer = false, dnd = rufbereitschaftGeaendert)
        }

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

                // Die uebersehene Namensliste (Befund 21.08.2026): die Dienstzeit-Ausnahmen in
                // "Nicht stoeren". Die Rufbereitschaft-Auswahl daneben ist seit dem Flag am
                // Schichttyp keine Liste mehr; die dritte lag im Dimmer (Nacht-Ausnahmen) und ist
                // mit dem eingebauten Nacht-Standard entfallen.
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

            // BLOCKIERTE UMBENENNUNGEN: Gehoert der Altname jetzt einer ANDEREN Definition, ist ein
            // Namenslisten-Eintrag scharf fuer die falsche Schicht und wird geraeumt. Bei den
            // uebrigen Blockaden zeigt er auf keine Definition mehr - Loeschen waere Datenverlust.
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
        // ZUSAMMENGESETZT statt `when`: beide Planlisten koennen gleichzeitig gefuellt sein. Der
        // Text nennt die betroffene Schicht und die BILDSCHIRME, nicht die Speicherschluessel.
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
        // WARUM DIE DND-AUSWAHL NUR DIE DND-KETTE NACHARMIERT: `dnd_shift_excluded_shifts` (und
        // das Rufbereitschaft-Flag) liest ausschliesslich `DndScheduleUseCase` (Dienstzeit-Fenster
        // und Rufbereitschaft-Cutoff). Der Dimmer kennt sie nicht - ihn mit zu armieren
        // dafuer waere Arbeit ohne jede Wirkung. Umgekehrt zieht ein geaendertes Dimm-Fenster die
        // DND-Kette sehr wohl mit: im Modus "folgt dem Dimmer" ist die Dimm-Zeitleiste die
        // Fensterquelle von "Nicht stoeren".
        return NacharmierBedarf(
            dimmer = dimmGeaendert > 0,
            dnd = dimmGeaendert > 0 || dndGeaendert > 0 || rufbereitschaftGeaendert
        )
    }

    private fun rufbereitschaftsNamen(config: ShiftConfig): Set<String> =
        config.definitions.filter { it.isOnCall }.map { it.name }.toSet()

    /**
     * Armiert die Dimm- und DND-Zeitketten neu, nachdem eine Umbenennung nachgezogen wurde:
     * erst die Spannen (`syncAlarms()`), dann armieren - im `finally` von
     * [triggerAlarmCreationFromConfigUpdate], weil der Sync oft ausfaellt. Hergang: schichterkennung.md.
     */
    private suspend fun armiereZeitkettenNeu(bedarf: NacharmierBedarf) {
        if (!bedarf.beanspruche()) return
        armierer.armiere("UMBENENNUNG", dimmer = bedarf.dimmer, dnd = bedarf.dnd)
    }

    /**
     * `suspend`, kein fire-and-forget `launch`: damit die Erkennung endet, bevor
     * `triggerAlarmCreationFromConfigUpdate()` startet. Hergang: schichterkennung.md.
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
                        alarmTime = match.calculatedAlarmTime
                    )
                }

                // Upcoming shift calculation
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
     * Triggers alarm creation after shift config updates.
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

            // Events aus dem CalendarStateHolder - aber NUR, wenn sie nachweislich der vollstaendige
            // Bestand sind (dort liegt oft das Lazy-Praefix). Hergang: kalender-datenfluss.md.
            val currentEvents = calendarStateHolder.events.value
            // Der Horizont reist mit der Liste (#51) - zusammen mit ihr gelesen, nicht spaeter.
            val currentHorizontEnde = calendarStateHolder.horizontEnde.value

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
            alarmUseCase.syncAlarms(currentEvents, config, currentHorizontEnde)
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
 * AUCH EINE REINE SCHREIBWEISENAENDERUNG IST EINE UMBENENNUNG: die Dienstzeit-Ausnahmen
 * vergleichen EXAKT (`DndShiftSpanResolver`); fuer die Regeln ist der Schreibvorgang folgenlos.
 *
 * DIE VIER BLOCKADEN (Nichtstun plus Meldung, sonst schaltet eine Regel zur falschen Schicht):
 *  - Sondermuster ("alle Schichten", "freie Tage") meinen keinen Namen.
 *  - Eine ANDERE Definition heisst jetzt genauso (mehrdeutig).
 *  - Der ALTE Name gehoert jetzt einer anderen Definition (Namenstausch).
 *  - Der neue Name gehoerte bisher einer anderen Definition (Zusammenlegen nicht umkehrbar).
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
        // werden, weil die Namensliste (Dienstzeit-Ausnahmen) exakt vergleicht.
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
