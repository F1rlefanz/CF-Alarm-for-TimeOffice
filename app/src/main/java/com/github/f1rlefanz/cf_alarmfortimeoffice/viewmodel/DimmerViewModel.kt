package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel des Dimmer-Tabs. Zwei unabhängige Modi (Wellness/Wind-down und Schicht-Regeln),
 * gemeinsame Verdunkelung/Wärme. Jede Änderung stößt [DimScheduleUseCase.enable] an (das den
 * rollenden Alarm self-cleaning neu plant bzw. abbestellt).
 */
@HiltViewModel
class DimmerViewModel @Inject constructor(
    private val prefs: DimOverlayPrefs,
    private val dimSchedule: DimScheduleUseCase
) : ViewModel() {

    data class DimmerUiState(
        val wellnessEnabled: Boolean = false,
        val rulesEnabled: Boolean = false,
        val nightDefaultEnabled: Boolean = false,
        val strength: Int = DimOverlayPrefs.DEFAULT_STRENGTH,
        val warmth: Int = DimOverlayPrefs.DEFAULT_WARMTH,
        val windDownMinutes: Int = DimOverlayPrefs.DEFAULT_WINDDOWN_MIN,
        val nightDefaultStartMinutes: Int = DimOverlayPrefs.DEFAULT_NIGHT_DEFAULT_START_MIN,
        val nightDefaultFreeEndMinutes: Int = DimOverlayPrefs.DEFAULT_NIGHT_DEFAULT_FREE_END_MIN
    )

    val uiState: StateFlow<DimmerUiState> =
        combine(
            combine(prefs.toggles, prefs.strength, prefs.warmth, prefs.windDownMinutes, ::PrefsCore),
            prefs.nightDefaultStartMinutes,
            prefs.nightDefaultFreeEndMinutes
        ) { core, nightStart, nightFreeEnd ->
            DimmerUiState(
                wellnessEnabled = core.toggles.wellnessEnabled,
                rulesEnabled = core.toggles.rulesEnabled,
                nightDefaultEnabled = core.toggles.nightDefaultEnabled,
                strength = core.strength,
                warmth = core.warmth,
                windDownMinutes = core.windDownMinutes,
                nightDefaultStartMinutes = nightStart,
                nightDefaultFreeEndMinutes = nightFreeEnd
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DimmerUiState())

    private data class PrefsCore(
        val toggles: DimOverlayPrefs.Toggles,
        val strength: Int,
        val warmth: Int,
        val windDownMinutes: Int
    )

    fun setWellnessEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setWellnessEnabled(enabled)
        dimSchedule.enable()
    }

    fun setRulesEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setRulesEnabled(enabled)
        dimSchedule.enable()
    }

    fun setNightDefaultEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setNightDefaultEnabled(enabled)
        dimSchedule.enable()
    }

    fun setNightDefaultStartMinutes(value: Int) = viewModelScope.launch {
        prefs.setNightDefaultStartMinutes(value)
        dimSchedule.enable()
    }

    fun setNightDefaultFreeEndMinutes(value: Int) = viewModelScope.launch {
        prefs.setNightDefaultFreeEndMinutes(value)
        dimSchedule.enable()
    }

    // Verdunkelung/Wärme ändern keine Fenster – der Service färbt reaktiv neu, kein Reschedule.
    fun setStrength(value: Int) = viewModelScope.launch { prefs.setStrength(value) }
    fun setWarmth(value: Int) = viewModelScope.launch { prefs.setWarmth(value) }

    fun setWindDownMinutes(value: Int) = viewModelScope.launch {
        prefs.setWindDownMinutes(value)
        dimSchedule.enable() // Wellness-Fenster verschieben sich -> neu planen
    }

    /**
     * Zeigt das Overlay kurz mit den aktuellen Werten – zum Ausprobieren OHNE Schicht/Alarm.
     * Der Bedienungshilfen-Dienst muss aktiv sein. Danach regulären Zustand wiederherstellen.
     */
    fun previewDim(seconds: Int = 5) = viewModelScope.launch {
        // Vorschau zeigt die GLOBALEN Darstellungswerte (die Slider, die der Nutzer gerade sieht).
        prefs.setActiveOverlay(true, prefs.strengthNow(), prefs.warmthNow())
        delay(seconds * 1000L)
        dimSchedule.applyCurrentState()
    }
}
