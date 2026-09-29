package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.CalendarOperationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.PermissionState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState

/** Auth-Zustand der Oberfläche, gegliedert in vier Sub-States. */
data class AuthState(
    val userAuth: UserAuthState = UserAuthState.EMPTY,
    val permissions: PermissionState = PermissionState.EMPTY,
    val calendarOps: CalendarOperationState = CalendarOperationState.EMPTY,
    val errors: AppErrorState = AppErrorState.EMPTY
) {
    val isSignedIn: Boolean get() = userAuth.isSignedIn
    val userEmail: String? get() = userAuth.userEmail
    val displayName: String? get() = userAuth.displayName
    val accessToken: String? get() = userAuth.accessToken
    val androidCalendarPermissionGranted: Boolean get() = permissions.androidCalendarPermissionGranted
    val error: String? get() = errors.error
    
    val isFullyAuthenticated: Boolean get() = userAuth.isFullyAuthenticated
    val isOperational: Boolean get() = calendarOps.isOperational
    val canProceedToCalendarSelection: Boolean get() = 
        userAuth.isAuthenticated && permissions.isPermissionGranted
    val isReadyForAlarms: Boolean get() = 
        canProceedToCalendarSelection && calendarOps.isReady
    
    companion object {
        val EMPTY = AuthState()
        
        fun authenticated(
            email: String,
            displayName: String,
            accessToken: String
        ) = AuthState(
            userAuth = UserAuthState.authenticated(email, displayName, accessToken)
        )
        
        fun withPermissions() = AuthState(
            permissions = PermissionState.granted()
        )
        
        fun fullyConfigured(
            email: String,
            displayName: String,
            accessToken: String
        ) = AuthState(
            userAuth = UserAuthState.authenticated(email, displayName, accessToken),
            permissions = PermissionState.granted(),
            calendarOps = CalendarOperationState.configured()
        )
    }
}
