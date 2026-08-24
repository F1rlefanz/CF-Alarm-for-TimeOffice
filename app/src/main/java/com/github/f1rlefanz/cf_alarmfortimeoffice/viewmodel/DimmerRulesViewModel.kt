package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel für Regel-Liste und Regel-Editor. Liefert die Regeln + die Namen der erkannten
 * Schicht-Definitionen (fürs Dropdown) und stößt nach jedem Speichern/Löschen BEIDE Zeitketten
 * neu an ([armiereFensterkettenNeu]) — eine geänderte Regel verschiebt Dimm-Fenster, und
 * „Nicht stören" im Modus „folgt dem Dimmer" hat keine andere Fensterquelle.
 */
@HiltViewModel
class DimmerRulesViewModel @Inject constructor(
    private val dimRuleUseCase: DimRuleUseCase,
    private val shiftUseCase: IShiftUseCase,
    private val dimSchedule: DimScheduleUseCase,
    // Lazy wie in ShiftViewModel/DimmerViewModel - zyklusfrei, aber erst bei Bedarf gebaut.
    private val dndSchedule: dagger.Lazy<DndScheduleUseCase>,
    private val prefs: DimOverlayPrefs,
    private val shiftSpanStore: ShiftSpanStore
) : ViewModel() {

    /**
     * Eigener Scope fuer das Aufraeumen der Regel-Vorschau — bewusst NICHT der `viewModelScope`
     * und bewusst ohne `cancel()` in `onCleared()`: genau das waere der Bug (siehe [previewRule]).
     *
     * Der [CoroutineExceptionHandler] ist Pflicht und nicht durch den [SupervisorJob] gedeckt.
     * Ein SupervisorJob isoliert nur Geschwister; die Exception liefe trotzdem zum
     * Thread-Default-Handler und beendete den PROZESS — also den, der die Wecker haelt. Fuer eine
     * Wecker-App ist das die falsche Reihenfolge der Wichtigkeit (dieselbe Lehre wie bei
     * `HueBridgeConnectionManager.healthCheckScope`).
     */
    private val previewScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Logger.e(LogTags.DIMMER, "Regel-Vorschau: Aufraeumen fehlgeschlagen", e)
        }
    )

    private var previewJob: Job? = null

    val rules: StateFlow<List<DimRule>> = dimRuleUseCase.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Namen der erkannten Schicht-Definitionen, fuer das Schicht-Muster-Dropdown im Regel-Editor.
     * Reaktiv statt Einmal-Snapshot - eine Schicht-Umbenennung/-Neuanlage ohne App-Neustart muss
     * sofort sichtbar werden. */
    val shiftNames: StateFlow<List<String>> =
        shiftUseCase.shiftConfig
            .map { config -> config.definitions.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun ruleById(id: String?): DimRule? =
        id?.let { rid -> rules.value.firstOrNull { it.id == rid } }

    /**
     * Armiert BEIDE Zeitketten neu - Dimmer, dann DND. Begruendung, Reihenfolge und die
     * [NonCancellable]-Falle ausfuehrlich in `DimmerViewModel.armiereFensterkettenNeu`; hier gilt
     * sie unveraendert, nur ist der Ausloeser eine geaenderte oder geloeschte Regel statt eines
     * Toggles. Der Weg aus dem Regel-Editor zurueck ist besonders anfaellig: Speichern und
     * Verlassen des Bildschirms liegen einen Fingertipp auseinander.
     */
    private suspend fun armiereFensterkettenNeu() = withContext(NonCancellable) {
        runCatching { dimSchedule.enable() }
            .onFailure { Logger.w(LogTags.DIMMER, "⚠️ Dimm-Kette nicht neu armiert", it) }
        runCatching { dndSchedule.get().enable() }
            .onFailure { Logger.w(LogTags.DND, "⚠️ DND-Kette nicht neu armiert", it) }
    }

    fun saveRule(rule: DimRule) = viewModelScope.launch {
        dimRuleUseCase.saveRule(rule)
        armiereFensterkettenNeu()
    }

    fun deleteRule(id: String) = viewModelScope.launch {
        dimRuleUseCase.deleteRule(id)
        armiereFensterkettenNeu()
    }

    /**
     * Zeigt das Overlay kurz mit den Werten AUS DEM FORMULAR (auch ungespeichert) – analog zu
     * [DimmerViewModel.previewDim], aber mit den Regel-eigenen statt den globalen Wellness-Werten.
     * Der Bedienungshilfen-Dienst muss aktiv sein. Danach den regulären Zustand wiederherstellen.
     *
     * **Das Aufräumen darf NICHT am `viewModelScope` hängen** — exakt dieselbe Falle wie in
     * [DimmerViewModel.previewDim], hier ein zweites Mal. `setPreviewOverlay(…)` schreibt
     * einen PERSISTENTEN Zustand; `DimAccessibilityService` beobachtet nur
     * `DimOverlayPrefs.renderState` und hat eine vom ViewModel völlig unabhängige Lebensdauer.
     * Wird der `viewModelScope` während des `delay()` gecancelt (Nutzer verlässt die App
     * innerhalb der 5 s — zweimal Zurück beendet die Activity), lief `applyCurrentState()` nie,
     * der Schreibvorgang aber schon: der Bildschirm bleibt systemweit verdunkelt. Geheilt würde
     * das erst beim nächsten Dimm-Tick — und wer die Vorschau zum Ausprobieren nutzt, hat
     * typischerweise noch gar keine Fenster-Quelle aktiv, es kommt also unter Umständen keiner.
     *
     * Deshalb dieselben drei Maßnahmen wie nebenan: eigener [previewScope], Zurücksetzen im
     * `finally` (greift auch bei Exception und Cancellation) und dort `NonCancellable`.
     * Vorbild ist `HueLightUseCase.followUpScope` — auch dort muss das Aufräumen feuern, wenn
     * der auslösende Bildschirm längst verlassen wurde.
     *
     * Und ebenfalls wie nebenan: die drei decken nur Coroutine-Cancellation ab. Ein PROZESSTOD im
     * Vorschau-Fenster führt kein `finally` aus, der neu gebundene `DimAccessibilityService` liest
     * den persistierten `overlayOn = true` und verdunkelt weiter. Deshalb schreibt
     * [DimOverlayPrefs.setPreviewOverlay] den Ablaufzeitpunkt mit auf die Platte — jeder spätere
     * Leser setzt das Ende der Vorschau dann von allein durch.
     */
    fun previewRule(strength: Int, warmth: Int, seconds: Int = 5): Job {
        val running = previewJob
        val job = previewScope.launch {
            // Eine noch laufende Vorschau ZUERST zu Ende aufräumen lassen: sonst schaltet deren
            // finally die gerade neu eingeschaltete Vorschau sofort wieder aus.
            running?.cancelAndJoin()
            val durationMs = seconds * 1000L
            try {
                prefs.setPreviewOverlay(
                    strength,
                    warmth,
                    System.currentTimeMillis() + durationMs + DimOverlayPrefs.PREVIEW_EXPIRY_GRACE_MS
                )
                delay(durationMs)
            } finally {
                withContext(NonCancellable) { dimSchedule.applyCurrentState() }
            }
        }
        previewJob = job
        return job
    }

    private val _timeline = MutableStateFlow<List<DimWindowResolver.ResolvedInterval>>(emptyList())
    val timeline: StateFlow<List<DimWindowResolver.ResolvedInterval>> = _timeline.asStateFlow()

    /** Berechnet die Dimm-Vorschau (Wellness + Regeln + Nacht-Standard) neu - siehe
     * [DimScheduleUseCase.previewTimeline]. Ohne Seiteneffekt auf den echten Scheduler. */
    fun refreshTimeline() = viewModelScope.launch {
        _timeline.value = dimSchedule.previewTimeline()
    }

    /**
     * Eine Regel wird an [tage] Kalendertagen des Planungshorizonts von einer anderen verdraengt;
     * dort gilt stattdessen eine der Regeln aus [gewinnerNamen] (Roh-Namen aus [DimRule.name], ein
     * leerer Name bleibt leer - die Ersatzbeschriftung dafuer ist ein Nutzertext und gehoert in den
     * Bildschirm, nicht ins ViewModel).
     */
    data class RegelVerdraengt(val tage: Int, val gewinnerNamen: List<String>)

    private val _verdraengteRegeln = MutableStateFlow<Map<String, RegelVerdraengt>>(emptyMap())

    /**
     * Je Regel-ID: an wie vielen Tagen sie hinter einer anderen Regel zurueckstehen muss, und
     * hinter welcher. Leer = kein Konflikt (oder die Regel-Quelle ist ganz aus).
     *
     * **Warum dieses Feld existiert:** [DimWindowResolver.buildRuleSpans] entscheidet den Konflikt
     * zweier Regeln an einem Tag zugunsten der frueheren Schicht - bis dahin sichtbar
     * ausschliesslich als Logzeile. Die Regelliste zeigte beide Regeln unveraendert als aktiv:
     * "angezeigt, wirkt nicht". Genau diese Karte rendert den Wert (siehe `DimmerSettingsScreen`);
     * ein Zustand, den niemand rendert, waere hier so falsch wie eine Renderstelle ohne Zustand.
     */
    val verdraengteRegeln: StateFlow<Map<String, RegelVerdraengt>> = _verdraengteRegeln.asStateFlow()

    /**
     * Rechnet [verdraengteRegeln] neu - reine Auskunft, ohne Seiteneffekt auf den Scheduler.
     *
     * Gerechnet wird auf derselben Grundlage wie der echte Scheduler: die Schichtspannen aus dem
     * [ShiftSpanStore] (NICHT der Alarm-Bestand - ein Alarm ueberlebt die Weckzeit nicht) und
     * dieselbe Regelauswahl ueber [DimRuleUseCase.findRuleForShift].
     *
     * Zwei bewusste Leer-Ausgaenge, beide in Richtung "nichts behaupten":
     * - **Regel-Quelle aus** (`toggles.rulesEnabled == false`): dann wirkt KEINE Regel, und ein
     *   Hinweis "diese hier wirkt an 3 Tagen nicht" waere eine Halbwahrheit, die die eigentliche
     *   Ursache verdeckt.
     * - **Schichtspannen nicht lesbar**: ohne Dienstplan ist unbekannt, an welchen Tagen mehrere
     *   Dienste zusammentreffen. Lieber kein Hinweis als ein falscher - dieselbe Richtung wie der
     *   fail-open des Schedulers.
     */
    fun refreshVerdraengteRegeln() = viewModelScope.launch {
        val alleRegeln = dimRuleUseCase.getAllRules()
        val spans = shiftSpanStore.spansNow().getOrNull()
        if (spans == null || !prefs.togglesNow().rulesEnabled) {
            _verdraengteRegeln.value = emptyMap()
            return@launch
        }
        val zone = ZoneId.systemDefault()
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = spans.map { DimWindowResolver.AlarmSlot(it.alarmTriggerTime, it.shiftName, it.endTime) },
            horizonDays = DimWindowResolver.KONFLIKT_HORIZONT_TAGE,
            today = LocalDate.now(zone),
            zone = zone,
            ruleForShift = { name -> dimRuleUseCase.findRuleForShift(name, alleRegeln) },
        )
        val namen = alleRegeln.associate { it.id to it.name }
        _verdraengteRegeln.value = konflikte
            .flatMap { k -> k.shadowedRuleIds.map { id -> id to k } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, tage) ->
                RegelVerdraengt(
                    // Distinct: ein Tag zaehlt einmal, auch wenn dort mehrere Schichten dieselbe
                    // Regel verdraengen.
                    tage = tage.map { it.date }.distinct().size,
                    gewinnerNamen = tage.mapNotNull { namen[it.winningRuleId] }.distinct().sorted()
                )
            }
    }
}
