package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel der DND-Einstellungen. Zwei unabhaengige Schalter (siehe [DndScheduleUseCase]), keine
 * Intensitaet (DND ist binaer). Die Freigabe-Pruefung (ACCESS_NOTIFICATION_POLICY) braucht Context
 * und lebt bewusst in der UI-Schicht ([com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper]),
 * nicht hier (Projekt-Konvention: ViewModels injizieren keinen Context).
 */
@HiltViewModel
class DndViewModel @Inject constructor(
    private val prefs: DndPrefs,
    private val dndSchedule: DndScheduleUseCase,
    private val shiftUseCase: IShiftUseCase
) : ViewModel() {

    data class DndUiState(
        val followDimmerEnabled: Boolean = false,
        val duringShiftEnabled: Boolean = false,
        val shiftExcludedShifts: Set<String> = emptySet()
    )

    val uiState: StateFlow<DndUiState> =
        combine(prefs.toggles, prefs.shiftExcludedShifts) { toggles, excluded ->
            DndUiState(
                followDimmerEnabled = toggles.followDimmerEnabled,
                duringShiftEnabled = toggles.duringShiftEnabled,
                shiftExcludedShifts = excluded
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DndUiState())

    private val _shiftNames = MutableStateFlow<List<String>>(emptyList())
    /** Namen der erkannten Schicht-Definitionen, fuer die Ausnahme-Chips an der Dienstzeit-Karte. */
    val shiftNames: StateFlow<List<String>> = _shiftNames.asStateFlow()

    init {
        viewModelScope.launch {
            _shiftNames.value = shiftUseCase.getCurrentShiftConfig().getOrNull()
                ?.definitions?.map { it.name } ?: emptyList()
        }
    }

    fun setFollowDimmerEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setFollowDimmerEnabled(enabled)
        dndSchedule.enable()
    }

    fun setDuringShiftEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setDuringShiftEnabled(enabled)
        dndSchedule.enable()
    }

    /** Schaltet eine Schicht als Ausnahme vom "Waehrend der Dienstzeit"-Trigger ein/aus. */
    fun toggleShiftExcludedShift(shiftName: String) = viewModelScope.launch {
        val current = prefs.shiftExcludedShiftsNow()
        prefs.setShiftExcludedShifts(
            if (shiftName in current) current - shiftName else current + shiftName
        )
        dndSchedule.enable()
    }
}
