package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.CalendarOperationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState

/** Auth-Zustand der Oberfläche, gegliedert in drei Sub-States. */
data class AuthState(
    val userAuth: UserAuthState = UserAuthState.EMPTY,
    val calendarOps: CalendarOperationState = CalendarOperationState.EMPTY,
    val errors: AppErrorState = AppErrorState.EMPTY,
    /**
     * Beim Start wird gerade ein Restore-Schluessel vom alten Geraet gelesen (#55, gedeckelt auf
     * 5 s). MainActivity zeigt dann einen eigenen Ladetext statt des Anmeldebildschirms.
     *
     * Bewusst HIER und nicht in [UserAuthState]: `userAuth` wird von den Auth-Daten-Beobachtern
     * im AuthViewModel jeweils ganz neu gebaut - dort ginge das Feld bei der naechsten Emission
     * still verloren.
     */
    val wiederherstellungLaeuft: Boolean = false
) {
    val isSignedIn: Boolean get() = userAuth.isSignedIn
    val userEmail: String? get() = userAuth.userEmail
    val error: String? get() = errors.error

    companion object {
        val EMPTY = AuthState()
    }
}
