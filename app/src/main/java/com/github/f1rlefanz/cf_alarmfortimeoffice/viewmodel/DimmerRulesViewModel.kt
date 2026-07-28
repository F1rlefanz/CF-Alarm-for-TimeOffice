package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val rules: StateFlow<List<DimRule>> = dimRuleUseCase.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _shiftNames = MutableStateFlow<List<String>>(emptyList())
    val shiftNames: StateFlow<List<String>> = _shiftNames.asStateFlow()

    init {
        viewModelScope.launch {
            _shiftNames.value = shiftUseCase.getCurrentShiftConfig().getOrNull()
                ?.definitions?.map { it.name } ?: emptyList()
        }
    }

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
     */
    fun previewRule(strength: Int, warmth: Int, seconds: Int = 5) = viewModelScope.launch {
        prefs.setActiveOverlay(true, strength, warmth)
        delay(seconds * 1000L)
        dimSchedule.applyCurrentState()
    }

    private val _timeline = MutableStateFlow<List<DimWindowResolver.ResolvedInterval>>(emptyList())
    val timeline: StateFlow<List<DimWindowResolver.ResolvedInterval>> = _timeline.asStateFlow()

    /** Berechnet die Dimm-Vorschau (Wellness + Regeln + Nacht-Standard) neu - siehe
     * [DimScheduleUseCase.previewTimeline]. Ohne Seiteneffekt auf den echten Scheduler. */
    fun refreshTimeline() = viewModelScope.launch {
        _timeline.value = dimSchedule.previewTimeline()
    }
}
