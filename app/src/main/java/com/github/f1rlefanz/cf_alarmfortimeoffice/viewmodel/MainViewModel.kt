package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainViewModel koordiniert den globalen App-Zustand - und NUR den.
 *
 * Es fasst Anmeldung, Kalender-Auswahl und aktive Alarme zu einem groben Ist-Zustand zusammen
 * ([MainUiState]), an dem die Navigation entscheiden kann. Es lädt selbst nichts.
 *
 * KEIN ZWEITER LADEPFAD (Audit): Hier lagen früher refreshAll() und Geschwister, die Events
 * luden und nur in den CalendarStateHolder schrieben. Da die CalendarUiState nie aus dem
 * StateHolder liest, sah Home von diesen Ladevorgängen nichts - der "Aktualisieren"-Knopf und
 * der Snackbar-Retry wirkten tot. Das Laden gehört ausschliesslich dem CalendarViewModel; ein
 * konkurrierender Pfad hier war die Ursache und kommt nicht zurück.
 *
 * MIGRATION STATUS:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor Injection mit @Inject
 * ✅ Keine Abhängigkeiten zu anderen ViewModels
 *
 * REFACTORED: Interface-basierte Abhängigkeiten für bessere Testbarkeit
 * ✅ Verwendet Interface-Abhängigkeiten (Dependency Inversion)
 * ✅ Mock-fähig für Unit Tests
 * ✅ Folgt Clean Architecture Principles
 * ✅ Zentrale Koordination ohne Mixed Responsibilities
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authUseCase: IAuthUseCase,
    private val alarmUseCase: IAlarmUseCase,
    private val calendarSelectionRepository: ICalendarSelectionRepository
) : ViewModel() {

    data class MainUiState(
        val isAuthenticated: Boolean = false,
        val hasSelectedCalendars: Boolean = false,
        val hasShiftConfig: Boolean = false,
        val isProcessingShifts: Boolean = false,
        val hasUpcomingShift: Boolean = false,
        val hasActiveAlarms: Boolean = false
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeAppState()
    }

    private fun observeAppState() {
        viewModelScope.launch {
            combine(
                authUseCase.authData
                    .distinctUntilChanged(),
                alarmUseCase.activeAlarms
                    .debounce(200)
                    .distinctUntilChanged(),
                calendarSelectionRepository.selectedCalendarIds
                    .debounce(150)
                    .distinctUntilChanged()
            ) { authData, activeAlarms, selectedCalendarIds ->
                MainUiState(
                    isAuthenticated = authData.isLoggedIn,
                    hasSelectedCalendars = selectedCalendarIds.isNotEmpty(),
                    hasActiveAlarms = activeAlarms.isNotEmpty()
                )
            }.distinctUntilChanged()
            .debounce(75)
            .collect { state ->
                _uiState.value = state

                Logger.d(LogTags.NAVIGATION, "🔄 UI-DEBOUNCE: Main state updated - authenticated=${state.isAuthenticated}, hasSelected=${state.hasSelectedCalendars}")
            }
        }
    }

    // ENTFERNT (Audit): refreshAll() / loadEventsForSelectedCalendars() /
    // forceRefreshCalendarEvents(). Sie luden Events und schrieben sie ausschliesslich in den
    // CalendarStateHolder - eine Einbahnstrasse zum ShiftViewModel, aus der die CalendarUiState
    // nie liest. Der Snackbar-Retry und der "Aktualisieren"-Knopf auf Home haengen deshalb jetzt
    // an CalendarViewModel.refreshData(forceRefresh = true): das aktualisiert BEIDES - die
    // CalendarUiState, die Home rendert, und den StateHolder fuer die Schichterkennung. Und es
    // meldet ein erneutes Scheitern als Fehler, statt es nur zu loggen.
    //
    // Ein zweiter Ladepfad neben dem des CalendarViewModel war genau die Falle, die den stummen
    // Retry erzeugt hat - deshalb bleibt hier keiner zurueck. MainViewModel ist jetzt reiner
    // Zustands-Koordinator, wie oben beschrieben.

    /**
     * MEMORY LEAK PREVENTION: Comprehensive resource cleanup
     * PERFORMANCE OPTIMIZATION: Clear all state and references
     */
    override fun onCleared() {
        try {
            _uiState.value = MainUiState()
            Logger.d(LogTags.LIFECYCLE, "MainViewModel cleared - cleaning up state references and resources")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during MainViewModel cleanup", e)
        }
    }
    
}
