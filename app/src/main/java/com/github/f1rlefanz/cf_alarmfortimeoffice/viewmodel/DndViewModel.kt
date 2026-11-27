package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.waehlbareSchichtNamen
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel von „Nicht stören“ als Geraete-Einstellung (#225): [DndPrefs.Policy] - wer trotzdem
 * durchkommt, gilt fuer alle Typen. WANN stummgeschaltet wird, steht am Schichttyp (#218). Die
 * Freigabe-Pruefung (ACCESS_NOTIFICATION_POLICY) braucht Context und lebt bewusst in der UI-Schicht
 * ([com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper]), nicht hier
 * (Projekt-Konvention: ViewModels injizieren keinen Context).
 */
@HiltViewModel
class DndViewModel @Inject constructor(
    private val prefs: DndPrefs,
    private val dndSchedule: DndScheduleUseCase,
    private val shiftUseCase: IShiftUseCase
) : ViewModel() {

    /** [gelesen] `false` = [policy] ist noch die Vorgabe, keine Auskunft ueber das Geraet. */
    data class DndUiState(
        val policy: DndPrefs.Policy = DndPrefs.Policy(),
        val gelesen: Boolean = false
    )

    val uiState: StateFlow<DndUiState> =
        prefs.policy
            .map { DndUiState(policy = it, gelesen = true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DndUiState())

    /**
     * Namen der waehlbaren Schicht-Definitionen. Ohne Bedienoberflaeche seit #225 (die
     * Ausnahme-Chips sind weg); bleibt bis #226, weil `KrankTypListenTest` und
     * `SchichttypAuswahlTest` bis dahin darauf bauen.
     */
    val shiftNames: StateFlow<List<String>> =
        shiftUseCase.shiftConfig
            // #142/#171: ein Krankmeldungs-, Ignorieren- oder ausgeschalteter Typ hat keine
            // Dienstzeit - als Ausnahme waehlbar, wuerde er nie greifen.
            .map { config -> config.definitions.waehlbareSchichtNamen() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Policy-Aenderungen wirken auf die registrierte Zen-Regel, nicht auf die Fenster-Berechnung -
    // applyCurrentState() reicht (aktualisiert die Regel sofort), kein Reschedule noetig.
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
