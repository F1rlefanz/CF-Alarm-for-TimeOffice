package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPauseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Duennes ViewModel fuer den Master-Pause-Schalter - delegiert vollstaendig an
 * [MasterPauseUseCase], keine eigene Logik.
 */
@HiltViewModel
class MasterPauseViewModel @Inject constructor(
    private val masterPauseUseCase: MasterPauseUseCase
) : ViewModel() {

    val paused: StateFlow<Boolean> = masterPauseUseCase.paused
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setPaused(value: Boolean) {
        viewModelScope.launch {
            if (value) masterPauseUseCase.pause() else masterPauseUseCase.resume()
        }
    }
}
