package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotificationPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel der "Benachrichtigungen"-Karte im Settings-Tab. Kombiniert die zwei bislang nicht an
 * einen Screen angebundenen Toggle-Keys aus Feature B ([ShiftChangeNotificationPrefs]) und
 * Feature C ([DimOverlayPrefs] Korrektur-Notification) - siehe Plan-Abschnitt "Gemeinsame
 * UI-Anbindung (Feature B + C)". Beide Prefs bleiben unabhaengige Quellen (kein gemeinsamer
 * DataStore-Key), dieses ViewModel ist nur ein duenner UI-Zusammenschluss.
 */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val shiftChangeNotificationPrefs: ShiftChangeNotificationPrefs,
    private val dimOverlayPrefs: DimOverlayPrefs
) : ViewModel() {

    data class NotificationSettingsUiState(
        val shiftChangeNotificationEnabled: Boolean = true,
        val dimCorrectionNotificationEnabled: Boolean = false
    )

    val uiState: StateFlow<NotificationSettingsUiState> = combine(
        shiftChangeNotificationPrefs.enabled,
        dimOverlayPrefs.correctionNotificationEnabled
    ) { shiftChangeEnabled, dimCorrectionEnabled ->
        NotificationSettingsUiState(
            shiftChangeNotificationEnabled = shiftChangeEnabled,
            dimCorrectionNotificationEnabled = dimCorrectionEnabled
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsUiState())

    fun setShiftChangeNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch { shiftChangeNotificationPrefs.setEnabled(enabled) }
    }

    fun setDimCorrectionNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch { dimOverlayPrefs.setCorrectionNotificationEnabled(enabled) }
    }
}
