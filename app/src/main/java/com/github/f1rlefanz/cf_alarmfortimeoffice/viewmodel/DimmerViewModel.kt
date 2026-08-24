package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel des Dimmer-Tabs. Drei unabhängige Modi (Wellness/Wind-down, eingebauter Nacht-Standard
 * und Schicht-Regeln), gemeinsame Verdunkelung/Wärme. Jede Änderung stößt [DimScheduleUseCase.enable]
 * an (das den rollenden Alarm self-cleaning neu plant bzw. abbestellt) — und jede Änderung, die
 * FENSTERGRENZEN verschiebt, zusätzlich [DndScheduleUseCase.enable]; siehe
 * [armiereFensterkettenNeu].
 */
@HiltViewModel
class DimmerViewModel @Inject constructor(
    private val prefs: DimOverlayPrefs,
    private val dimSchedule: DimScheduleUseCase,
    // Lazy wie in ShiftViewModel: DND haengt am Dimmer, nicht umgekehrt - die Injektion ist
    // zyklusfrei, aber der Graph soll sie erst bauen, wenn wirklich nacharmiert wird.
    private val dndSchedule: dagger.Lazy<DndScheduleUseCase>,
    private val shiftUseCase: IShiftUseCase
) : ViewModel() {

    data class DimmerUiState(
        val wellnessEnabled: Boolean = false,
        val rulesEnabled: Boolean = false,
        val nightDefaultEnabled: Boolean = false,
        val strength: Int = DimOverlayPrefs.DEFAULT_STRENGTH,
        val warmth: Int = DimOverlayPrefs.DEFAULT_WARMTH,
        val windDownMinutes: Int = DimOverlayPrefs.DEFAULT_WINDDOWN_MIN,
        val nightDefaultStartMinutes: Int = DimOverlayPrefs.DEFAULT_NIGHT_DEFAULT_START_MIN,
        val nightDefaultFreeEndMinutes: Int = DimOverlayPrefs.DEFAULT_NIGHT_DEFAULT_FREE_END_MIN,
        val nightDefaultExcludedShifts: Set<String> = emptySet(),
        val nightDefaultStrength: Int = DimOverlayPrefs.DEFAULT_STRENGTH,
        val nightDefaultWarmth: Int = DimOverlayPrefs.DEFAULT_WARMTH
    )

    val uiState: StateFlow<DimmerUiState> =
        combine(
            combine(prefs.toggles, prefs.strength, prefs.warmth, prefs.windDownMinutes, ::PrefsCore),
            combine(
                prefs.nightDefaultStartMinutes,
                prefs.nightDefaultFreeEndMinutes,
                prefs.nightDefaultExcludedShifts,
                prefs.nightDefaultStrength,
                prefs.nightDefaultWarmth,
                ::NightDefaultExtra
            )
        ) { core, night ->
            DimmerUiState(
                wellnessEnabled = core.toggles.wellnessEnabled,
                rulesEnabled = core.toggles.rulesEnabled,
                nightDefaultEnabled = core.toggles.nightDefaultEnabled,
                strength = core.strength,
                warmth = core.warmth,
                windDownMinutes = core.windDownMinutes,
                nightDefaultStartMinutes = night.startMinutes,
                nightDefaultFreeEndMinutes = night.freeEndMinutes,
                nightDefaultExcludedShifts = night.excludedShifts,
                nightDefaultStrength = night.strength,
                nightDefaultWarmth = night.warmth
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DimmerUiState())

    private data class PrefsCore(
        val toggles: DimOverlayPrefs.Toggles,
        val strength: Int,
        val warmth: Int,
        val windDownMinutes: Int
    )

    private data class NightDefaultExtra(
        val startMinutes: Int,
        val freeEndMinutes: Int,
        val excludedShifts: Set<String>,
        val strength: Int,
        val warmth: Int
    )

    /** Namen der erkannten Schicht-Definitionen, fuer die Ausnahme-Chips an der Nacht-Standard-Karte.
     * Reaktiv statt Einmal-Snapshot - eine Schicht-Umbenennung/-Neuanlage ohne App-Neustart muss
     * sofort sichtbar werden. */
    val shiftNames: StateFlow<List<String>> =
        shiftUseCase.shiftConfig
            .map { config -> config.definitions.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Armiert BEIDE Zeitketten neu - Dimmer, dann DND. Fuer jeden Setter, der FENSTERGRENZEN
     * verschiebt.
     *
     * WARUM DND HIER MIT MUSS (Befund 23.08.2026, am Fairphone reproduziert): DND-Modus 1 („folgt
     * dem Dimmer") hat keine eigene Fensterquelle - er liest ueber
     * [DimScheduleUseCase.previewTimelineWithStatus] die Dimm-Zeitleiste. Verschiebt ein Setter
     * die Dimm-Fenster, ohne DND nachzuarmieren, bleibt „Nicht stoeren" auf dem alten Plan stehen,
     * bis der eigene DND-Tick faellt. Real gemessen: Nacht-Standard aus + Regeln an um 09:59, der
     * Dimmer schaltete sofort ab, `zen_mode` blieb bis zum naechsten DND-Tick um 12:30 auf 1 -
     * knapp drei Stunden „Nicht stoeren" ohne Grund. In einer Rufbereitschaftsnacht waeren das
     * verlorene Anrufe. Die Invariante steht seit der Schicht-Umbenennung im Code
     * (`ShiftViewModel`, „ein geaendertes Dimm-Fenster zieht die DND-Kette sehr wohl mit"), war
     * aber nur dort und im Import umgesetzt.
     *
     * REIHENFOLGE DIMMER → DND ist tragend: `DndScheduleUseCase.computeWindows()` liest die
     * Dimm-Zeitleiste LIVE. `dataStore.edit {}` kehrt erst nach persistiertem Write zurueck, das
     * anschliessende `enable()` sieht also den neuen Stand - aber nur in dieser Reihenfolge.
     * Vorbild: `MasterPauseUseCase`, `ConfigBackupUseCase`, `TagFreigabeUseCase`.
     *
     * [NonCancellable], weil das hier einen Zustand HERSTELLT: die Setter haengen am
     * [viewModelScope], und genau der stirbt, wenn der Nutzer die App direkt nach dem Toggle
     * verlaesst - der Prefs-Wert waere geschrieben, die Ketten haengen hinterher. Dieselbe Falle
     * wie bei der frueheren Entprellung (siehe Kommentar bei [setStrength]). Beide Aufrufe einzeln
     * gefangen: ein Fehlschlag der einen Kette darf die andere nicht mitreissen, und beide haben
     * ihren eigenen Master-Pause-Backstop.
     */
    private suspend fun armiereFensterkettenNeu() = withContext(NonCancellable) {
        runCatching { dimSchedule.enable() }
            .onFailure { Logger.w(LogTags.DIMMER, "⚠️ Dimm-Kette nicht neu armiert", it) }
        runCatching { dndSchedule.get().enable() }
            .onFailure { Logger.w(LogTags.DND, "⚠️ DND-Kette nicht neu armiert", it) }
    }

    /** Schaltet eine Schicht als Ausnahme vom Nacht-Standard ein/aus - keine DimRule dafuer noetig. */
    fun toggleNightDefaultExcludedShift(shiftName: String) = viewModelScope.launch {
        prefs.toggleNightDefaultExcludedShift(shiftName)
        armiereFensterkettenNeu()
    }

    fun setWellnessEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setWellnessEnabled(enabled)
        armiereFensterkettenNeu()
    }

    fun setRulesEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setRulesEnabled(enabled)
        armiereFensterkettenNeu()
    }

    fun setNightDefaultEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setNightDefaultEnabled(enabled)
        armiereFensterkettenNeu()
    }

    fun setNightDefaultStartMinutes(value: Int) = viewModelScope.launch {
        prefs.setNightDefaultStartMinutes(value)
        armiereFensterkettenNeu()
    }

    fun setNightDefaultFreeEndMinutes(value: Int) = viewModelScope.launch {
        prefs.setNightDefaultFreeEndMinutes(value)
        armiereFensterkettenNeu()
    }

    // Verdunkelung/Waerme aendern keine FENSTERGRENZEN - deshalb rufen die folgenden vier Setter
    // bewusst `dimSchedule.enable()` und NICHT [armiereFensterkettenNeu]: „Nicht stoeren" kennt nur
    // die Fenster einer Zeitleiste, nicht ihre Farbe. Ein DND-`enable()` waere hier eine komplette
    // zusaetzliche Fensterberechnung ohne jede Wirkung - dieselbe Begruendung wie umgekehrt in
    // `ShiftViewModel` fuer die reinen DND-Namenslisten.
    //
    // Sehr wohl aendern sie die Darstellung des gerade
    // laufenden Fensters, und die faerbt der Dienst NICHT von allein reaktiv nach: er beobachtet
    // ausschliesslich DimOverlayPrefs.renderState, und das liest KEY_RENDER_STRENGTH/-WARMTH mit den
    // globalen Slidern nur als FALLBACK. Die Render-Keys schreiben einzig setActiveOverlay() und
    // setPreviewOverlay(), also nur applyCurrentState()/die Vorschau - nach dem ersten Lauf greift der Fallback
    // nie mehr. Ohne enable() blieb ein mitten in der Nacht verstellter Regler bis zur naechsten
    // Fenstergrenze (typischerweise das Fenster-ENDE am Morgen) wirkungslos - dieselbe Falle wie
    // beim Korrektur-Notification-Toggle (v1.22.1). Siehe Invariante in CLAUDE.md:
    // "Jeder Setter, der einen DimOverlayPrefs-Wert schreibt, MUSS direkt danach enable() rufen".
    //
    // Das enable() laeuft hier bewusst UNENTPRELLT, genau wie bei allen anderen Settern dieser
    // Klasse: `DimmerTabContent.CommitOnReleaseSlider` meldet den Wert erst beim LOSLASSEN nach oben
    // (`onValueChangeFinished`), also genau EINMAL pro Reglerbewegung - der frueher befuerchtete
    // Frame-Sturm entsteht strukturell nicht mehr. Eine zusaetzliche Entprellung hier hatte deshalb
    // keinen Nutzen mehr, riss aber ein Loch in die Invariante: der Entprellungs-Job hing am
    // viewModelScope und starb beim Verlassen der App vor seinem delay() - der Prefs-Wert war
    // geschrieben, das laufende Overlay behielt bis zur naechsten Fenstergrenze die alte
    // Verdunkelung. Wer die Entprellung wieder einbaut, muss sie ausserhalb des viewModelScope
    // aufhaengen - besser: es beim commit-on-release der UI belassen.
    fun setStrength(value: Int) = viewModelScope.launch {
        prefs.setStrength(value)
        dimSchedule.enable()
    }

    fun setWarmth(value: Int) = viewModelScope.launch {
        prefs.setWarmth(value)
        dimSchedule.enable()
    }

    fun setNightDefaultStrength(value: Int) = viewModelScope.launch {
        prefs.setNightDefaultStrength(value)
        dimSchedule.enable()
    }

    fun setNightDefaultWarmth(value: Int) = viewModelScope.launch {
        prefs.setNightDefaultWarmth(value)
        dimSchedule.enable()
    }

    fun setWindDownMinutes(value: Int) = viewModelScope.launch {
        prefs.setWindDownMinutes(value)
        armiereFensterkettenNeu() // Wellness-Fenster verschieben sich -> beide Ketten neu planen
    }

    /**
     * Scope fuer das Aufraeumen der Vorschau - bewusst GETRENNT vom [viewModelScope], Vorbild
     * `HueLightUseCase.followUpScope` ("Der Abbruch-Timer und das Vorschau-Auto-Aus haengen an
     * followUpScope, nicht am Aufrufer").
     *
     * Der [CoroutineExceptionHandler] ist Pflicht und nicht durch den [SupervisorJob] gedeckt: der
     * isoliert nur Geschwister-Jobs, eine ungefangene Exception laeuft trotzdem zum
     * Thread-Default-Handler und beendet den PROZESS - denselben Prozess, der die Wecker haelt.
     * Eine gescheiterte Dimm-Vorschau darf das nie.
     */
    private val previewScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Logger.w(LogTags.DIMMER, "Dimmer-Vorschau: Aufraeumen fehlgeschlagen", e)
        }
    )

    /** Laufende Vorschau, damit ein zweiter Tipp die erste sauber abloest (siehe [previewDim]). */
    private var previewJob: Job? = null

    /**
     * Zeigt das Overlay kurz mit den aktuellen Werten – zum Ausprobieren OHNE Schicht/Alarm.
     * Der Bedienungshilfen-Dienst muss aktiv sein. Danach regulären Zustand wiederherstellen.
     *
     * Laeuft bewusst NICHT im [viewModelScope]: `setPreviewOverlay(…)` schreibt einen
     * PERSISTENTEN Zustand, den `DimAccessibilityService` beobachtet - und der Dienst hat eine vom
     * ViewModel voellig unabhaengige Lebensdauer. Hing das Zuruecksetzen am viewModelScope, dann
     * genuegte es, die App waehrend der 5 Sekunden zu verlassen (zweimal Zurueck / aus den Recents
     * wischen): `onCleared()` cancelte das `delay()`, `applyCurrentState()` lief NIE, und der
     * Bildschirm blieb systemweit bis zu 85 % verdunkelt. Geheilt haette das erst der naechste
     * Dimm-Tick - und wenn keine der drei Fenster-Quellen (Wellness/Regeln/Nacht-Standard) an ist
     * (der typische Zustand von jemandem, der die Vorschau zum Ausprobieren nutzt, BEVOR er etwas
     * einschaltet), kommt dieser Tick unter Umstaenden gar nicht.
     *
     * Deshalb drei Dinge: eigener [previewScope], das Zuruecksetzen im `finally` (greift auch bei
     * Exception oder Cancellation) und dort `NonCancellable` - dieselbe Ueberlegung wie bei
     * `MasterPauseUseCase.pause()/resume()`: hier wird ein Zustand HERGESTELLT, nicht nur ein
     * Schalter umgelegt. Ein zweiter Tipp laesst zuerst das Aufraeumen der laufenden Vorschau zu
     * Ende laufen (`cancelAndJoin`), sonst schaltete deren `finally` die gerade neu eingeschaltete
     * Vorschau sofort wieder aus.
     *
     * Diese drei decken aber nur Coroutine-CANCELLATION ab. Stirbt der PROZESS im Vorschau-Fenster
     * (Absturz, "Beenden erzwingen", App-Update), laeuft kein `finally` - und Android bindet den
     * `DimAccessibilityService` danach neu, der den persistierten `overlayOn = true` vorfindet und
     * sofort wieder verdunkelt. Deshalb geht der Ablaufzeitpunkt ueber
     * [DimOverlayPrefs.setPreviewOverlay] MIT auf die Platte: jeder spaetere Leser setzt das Ende
     * der Vorschau von allein durch, auch wenn hier nie wieder etwas laeuft.
     */
    fun previewDim(seconds: Int = 5): Job {
        val running = previewJob
        val job = previewScope.launch {
            running?.cancelAndJoin()
            val durationMs = seconds * 1000L
            try {
                // Vorschau zeigt die GLOBALEN Darstellungswerte (die Slider, die der Nutzer gerade sieht).
                prefs.setPreviewOverlay(
                    prefs.strengthNow(),
                    prefs.warmthNow(),
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
}
