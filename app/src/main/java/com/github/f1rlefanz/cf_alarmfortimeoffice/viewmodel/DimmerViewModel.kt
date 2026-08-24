package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel des Dimmer-Tabs — und der ist seit dem Ein-Modell-Umbau sehr klein: EIN Schalter,
 * sonst nichts. Alles Weitere läuft über die Regeln und damit über `DimmerRulesViewModel`.
 *
 * Der Schalter verschiebt FENSTERGRENZEN (aus ist aus), deshalb zieht er beide Zeitketten nach —
 * Dimmer und DND; siehe [armiereFensterkettenNeu].
 *
 * WAS HIER BEWUSST NICHT MEHR STEHT: Verdunkelung/Wärme-Setter, die Namen der Schichtdefinitionen
 * und die 5-Sekunden-Vorschau. Sie stammten aus der alten Drei-Karten-Oberfläche; seit die weg ist,
 * hatten sie keinen Aufrufer mehr. Die Vorschau lebt weiter in
 * [DimmerRulesViewModel.previewRule] — mit derselben Konstruktion und derselben Lehre
 * (eigener Scope, `NonCancellable` im `finally`, persistierter Ablaufzeitpunkt).
 */
@HiltViewModel
class DimmerViewModel @Inject constructor(
    private val prefs: DimOverlayPrefs,
    private val dimSchedule: DimScheduleUseCase,
    // Lazy wie in ShiftViewModel: DND haengt am Dimmer, nicht umgekehrt - die Injektion ist
    // zyklusfrei, aber der Graph soll sie erst bauen, wenn wirklich nacharmiert wird.
    private val dndSchedule: dagger.Lazy<DndScheduleUseCase>
) : ViewModel() {

    data class DimmerUiState(val dimEnabled: Boolean = false)

    val uiState: StateFlow<DimmerUiState> =
        prefs.toggles
            .map { DimmerUiState(dimEnabled = it.dimEnabled) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DimmerUiState())

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
     * wie bei der frueheren, entfernten Slider-Entprellung (Hergang im Skill
     * `cfalarm-dimmer-und-dnd`). Beide Aufrufe einzeln
     * gefangen: ein Fehlschlag der einen Kette darf die andere nicht mitreissen, und beide haben
     * ihren eigenen Master-Pause-Backstop.
     */
    private suspend fun armiereFensterkettenNeu() = withContext(NonCancellable) {
        runCatching { dimSchedule.enable() }
            .onFailure { Logger.w(LogTags.DIMMER, "⚠️ Dimm-Kette nicht neu armiert", it) }
        runCatching { dndSchedule.get().enable() }
            .onFailure { Logger.w(LogTags.DND, "⚠️ DND-Kette nicht neu armiert", it) }
    }

    /** DER Dimmer-Schalter. Schaltet die einzige Fenster-Quelle (die Regeln) an und aus. */
    fun setDimEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setDimEnabled(enabled)
        armiereFensterkettenNeu()
    }
}
