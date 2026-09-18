package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel des Dimmer-Tabs — und der ist seit dem Ein-Modell-Umbau sehr klein: EIN Schalter,
 * sonst nichts. Alles Weitere läuft über die Regeln und damit über `DimmerRulesViewModel`.
 *
 * Der Schalter verschiebt FENSTERGRENZEN (aus ist aus), deshalb zieht er beide Zeitketten nach —
 * Dimmer und DND; siehe [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer].
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
    private val armierer: ZeitkettenArmierer
) : ViewModel() {

    data class DimmerUiState(val dimEnabled: Boolean = false)

    val uiState: StateFlow<DimmerUiState> =
        prefs.toggles
            .map { DimmerUiState(dimEnabled = it.dimEnabled) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DimmerUiState())

    /**
     * WARN, damit die Zeile im Release-Log steht: Der Schalter ist eine DAUERHAFTE Einstellung,
     * keine Nachtpause - und er war bis 1.41.1 der einzige Schreiber von `dim_enabled`, der
     * nichts protokollierte. Am 16.09.2026 liess sich ein "warum war heute Nacht kein Dimmen"
     * nur ueber einen Tab-Wechsel und den Abschaltgrund `DIMMER_AUS` rekonstruieren.
     */
    fun setDimEnabled(enabled: Boolean) = viewModelScope.launch {
        Logger.w(LogTags.DIMMER, "Dimmer per Hauptschalter ${if (enabled) "AN" else "AUS"} - gilt dauerhaft, bis er zurueckgestellt wird")
        prefs.setDimEnabled(enabled)
        armierer.armiere("DIMMER")
    }
}
