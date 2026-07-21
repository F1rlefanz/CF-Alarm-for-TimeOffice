package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel des Dimmer-Tabs. Liest/schreibt [DimOverlayPrefs] und stoesst bei Bedarf den
 * [DimScheduleUseCase] an (der die Dimm-Fenster aus den Alarmen plant).
 */
@HiltViewModel
class DimmerViewModel @Inject constructor(
    private val prefs: DimOverlayPrefs,
    private val dimSchedule: DimScheduleUseCase
) : ViewModel() {

    data class DimmerUiState(
        val featureEnabled: Boolean = false,
        val strength: Int = DimOverlayPrefs.DEFAULT_STRENGTH,
        val warmth: Int = DimOverlayPrefs.DEFAULT_WARMTH,
        val windDownMinutes: Int = DimOverlayPrefs.DEFAULT_WINDDOWN_MIN
    )

    val uiState: StateFlow<DimmerUiState> =
        combine(prefs.state, prefs.windDownMinutes) { s, windDown ->
            DimmerUiState(
                featureEnabled = s.featureEnabled,
                strength = s.strength,
                warmth = s.warmth,
                windDownMinutes = windDown
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DimmerUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setFeatureEnabled(enabled)
        if (enabled) dimSchedule.enable() else dimSchedule.disable()
    }

    // Farbe/Staerke aendern KEINE Fenster -> der Service faerbt reaktiv neu, kein Reschedule.
    fun setStrength(value: Int) = viewModelScope.launch { prefs.setStrength(value) }
    fun setWarmth(value: Int) = viewModelScope.launch { prefs.setWarmth(value) }

    fun setWindDownMinutes(value: Int) = viewModelScope.launch {
        prefs.setWindDownMinutes(value)
        if (prefs.snapshot().featureEnabled) dimSchedule.enable() // Fenster verschieben -> neu planen
    }

    /** Beim Anzeigen des Tabs den Zeitplan mit dem aktuellen Alarm-Bestand synchronisieren. */
    fun syncSchedule() = viewModelScope.launch {
        if (prefs.snapshot().featureEnabled) dimSchedule.enable()
    }

    /**
     * Zeigt das Overlay kurz mit den aktuellen Werten – zum Ausprobieren OHNE Schicht/Alarm.
     * Der Bedienungshilfen-Dienst muss aktiv sein, sonst ist nichts zu sehen. Danach wird der
     * regulaere Zustand wiederhergestellt.
     */
    fun previewDim(seconds: Int = 5) = viewModelScope.launch {
        prefs.setOverlayOn(true)
        delay(seconds * 1000L)
        dimSchedule.applyCurrentState()
    }
}
