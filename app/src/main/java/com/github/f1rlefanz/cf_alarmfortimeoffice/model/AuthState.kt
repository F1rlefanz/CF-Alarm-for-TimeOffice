package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.CalendarOperationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState

/** Auth-Zustand der Oberfläche, gegliedert in drei Sub-States. */
data class AuthState(
    val userAuth: UserAuthState = UserAuthState.EMPTY,
    val calendarOps: CalendarOperationState = CalendarOperationState.EMPTY,
    val errors: AppErrorState = AppErrorState.EMPTY
) {
    val isSignedIn: Boolean get() = userAuth.isSignedIn
    val userEmail: String? get() = userAuth.userEmail
    val error: String? get() = errors.error

    companion object {
        val EMPTY = AuthState()
    }
}
