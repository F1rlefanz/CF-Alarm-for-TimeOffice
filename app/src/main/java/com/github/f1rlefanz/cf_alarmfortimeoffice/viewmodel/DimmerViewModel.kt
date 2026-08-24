package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
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

    fun setDimEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setDimEnabled(enabled)
        armierer.armiere("DIMMER")
    }
}
