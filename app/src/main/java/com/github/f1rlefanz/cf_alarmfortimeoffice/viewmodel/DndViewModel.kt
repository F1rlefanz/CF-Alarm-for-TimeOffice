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
 * ViewModel der DND-Einstellungen. Zwei unabhaengige Fenster-Schalter (siehe [DndScheduleUseCase])
 * plus [DndPrefs.Policy] - was genau stummgeschaltet wird, ist Nutzer-Entscheidung, nicht hart
 * codiert (siehe [DndPrefs.Policy]-Klassenkommentar). Die Freigabe-Pruefung
 * (ACCESS_NOTIFICATION_POLICY) braucht Context und lebt bewusst in der UI-Schicht
 * ([com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper]), nicht hier
 * (Projekt-Konvention: ViewModels injizieren keinen Context).
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
        val shiftExcludedShifts: Set<String> = emptySet(),
        val onCallShifts: Set<String> = emptySet(),
        val onCallCutoffMinutes: Int = DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN,
        val policy: DndPrefs.Policy = DndPrefs.Policy()
    )

    val uiState: StateFlow<DndUiState> =
        combine(
            prefs.toggles,
            prefs.shiftExcludedShifts,
            prefs.onCallShifts,
            prefs.onCallCutoffMinutes,
            prefs.policy
        ) { toggles, excluded, onCallShifts, onCallCutoffMinutes, policy ->
            DndUiState(
                followDimmerEnabled = toggles.followDimmerEnabled,
                duringShiftEnabled = toggles.duringShiftEnabled,
                shiftExcludedShifts = excluded,
                onCallShifts = onCallShifts,
                onCallCutoffMinutes = onCallCutoffMinutes,
                policy = policy
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
        prefs.toggleShiftExcludedShift(shiftName)
        dndSchedule.enable()
    }

    /** Schaltet eine Schicht als Rufbereitschaft (On-Call-Cutoff) ein/aus. */
    fun toggleOnCallShift(shiftName: String) = viewModelScope.launch {
        prefs.toggleOnCallShift(shiftName)
        dndSchedule.enable()
    }

    /** Setzt den Cutoff (Minuten seit Mitternacht) fuer Rufbereitschafts-Tage. */
    fun setOnCallCutoffMinutes(minutes: Int) = viewModelScope.launch {
        prefs.setOnCallCutoffMinutes(minutes)
        dndSchedule.enable()
    }

    // Policy-Aenderungen wirken auf die registrierte Zen-Regel, nicht auf die Fenster-Berechnung -
    // applyCurrentState() reicht (aktualisiert die Regel sofort), kein Reschedule noetig. Analog zu
    // DimmerViewModel.setStrength/setWarmth (Darstellung aendert keine Fenster).
    fun setBlockCalls(v: Boolean) = viewModelScope.launch {
        prefs.setBlockCalls(v); dndSchedule.applyCurrentState()
    }
    fun setAllowRepeatCallers(v: Boolean) = viewModelScope.launch {
        prefs.setAllowRepeatCallers(v); dndSchedule.applyCurrentState()
    }
    fun setBlockMessages(v: Boolean) = viewModelScope.launch {
        prefs.setBlockMessages(v); dndSchedule.applyCurrentState()
    }
    fun setBlockConversations(v: Boolean) = viewModelScope.launch {
        prefs.setBlockConversations(v); dndSchedule.applyCurrentState()
    }
    fun setBlockReminders(v: Boolean) = viewModelScope.launch {
        prefs.setBlockReminders(v); dndSchedule.applyCurrentState()
    }
    fun setBlockEvents(v: Boolean) = viewModelScope.launch {
        prefs.setBlockEvents(v); dndSchedule.applyCurrentState()
    }
    fun setBlockSystem(v: Boolean) = viewModelScope.launch {
        prefs.setBlockSystem(v); dndSchedule.applyCurrentState()
    }
    fun setBlockMedia(v: Boolean) = viewModelScope.launch {
        prefs.setBlockMedia(v); dndSchedule.applyCurrentState()
    }
    fun setBlockAlarms(v: Boolean) = viewModelScope.launch {
        prefs.setBlockAlarms(v); dndSchedule.applyCurrentState()
    }
}
