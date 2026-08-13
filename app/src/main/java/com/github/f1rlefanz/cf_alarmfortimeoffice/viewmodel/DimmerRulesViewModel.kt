package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
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
import javax.inject.Inject

/**
 * ViewModel für Regel-Liste und Regel-Editor. Liefert die Regeln + die Namen der erkannten
 * Schicht-Definitionen (fürs Dropdown) und stößt nach jedem Speichern/Löschen den
 * [DimScheduleUseCase] neu an.
 */
@HiltViewModel
class DimmerRulesViewModel @Inject constructor(
    private val dimRuleUseCase: DimRuleUseCase,
    private val shiftUseCase: IShiftUseCase,
    private val dimSchedule: DimScheduleUseCase,
    private val prefs: DimOverlayPrefs
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

    fun saveRule(rule: DimRule) = viewModelScope.launch {
        dimRuleUseCase.saveRule(rule)
        dimSchedule.enable()
    }

    fun deleteRule(id: String) = viewModelScope.launch {
        dimRuleUseCase.deleteRule(id)
        dimSchedule.enable()
    }

    /**
     * Zeigt das Overlay kurz mit den Werten AUS DEM FORMULAR (auch ungespeichert) – analog zu
     * [DimmerViewModel.previewDim], aber mit den Regel-eigenen statt den globalen Wellness-Werten.
     * Der Bedienungshilfen-Dienst muss aktiv sein. Danach den regulären Zustand wiederherstellen.
     *
     * **Das Aufräumen darf NICHT am `viewModelScope` hängen** — exakt dieselbe Falle wie in
     * [DimmerViewModel.previewDim], hier ein zweites Mal. `setActiveOverlay(true, …)` schreibt
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
     */
    fun previewRule(strength: Int, warmth: Int, seconds: Int = 5): Job {
        val running = previewJob
        val job = previewScope.launch {
            // Eine noch laufende Vorschau ZUERST zu Ende aufräumen lassen: sonst schaltet deren
            // finally die gerade neu eingeschaltete Vorschau sofort wieder aus.
            running?.cancelAndJoin()
            try {
                prefs.setActiveOverlay(true, strength, warmth)
                delay(seconds * 1000L)
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
}
