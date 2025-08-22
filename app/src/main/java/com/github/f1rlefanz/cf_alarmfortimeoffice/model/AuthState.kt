package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.PermissionState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.CalendarOperationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState

/**
 * REFACTORED: Modular AuthState with Sub-States
 * 
 * GRANULAR STATE MODELING:
 * ✅ Aufgeteilt in logische Sub-States für bessere Wartbarkeit
 * ✅ Single Responsibility: Jeder Sub-State hat klare Verantwortung
 * ✅ Strukturelle Gleichheit: Data class ermöglicht effiziente Vergleiche
 * ✅ Computed Properties: Delegiert an Sub-States für bessere API
 * ✅ Reduzierte Komplexität: Statt 15+ Properties jetzt 4 logische Gruppen
 */
data class AuthState(
    val userAuth: UserAuthState = UserAuthState.EMPTY,
    val permissions: PermissionState = PermissionState.EMPTY,
    val calendarOps: CalendarOperationState = CalendarOperationState.EMPTY,
    val errors: AppErrorState = AppErrorState.EMPTY
) {
    // BACKWARD COMPATIBILITY: Legacy API für bestehenden Code
    val isSignedIn: Boolean get() = userAuth.isSignedIn
    val userEmail: String? get() = userAuth.userEmail
    val displayName: String? get() = userAuth.displayName
    val accessToken: String? get() = userAuth.accessToken
    val androidCalendarPermissionGranted: Boolean get() = permissions.androidCalendarPermissionGranted
    val showAndroidCalendarPermissionRationale: Boolean get() = permissions.showAndroidCalendarPermissionRationale
    val calendarPermissionDenied: Boolean get() = permissions.calendarPermissionDenied
    val calendarsLoading: Boolean get() = calendarOps.calendarsLoading
    val autoAlarmEnabled: Boolean get() = calendarOps.autoAlarmEnabled
    val nextShiftAlarm: String? get() = calendarOps.nextShiftAlarm
    val error: String? get() = errors.error
    
    // ENHANCED API: Neue computed properties für bessere Business Logic
    val isFullyAuthenticated: Boolean get() = userAuth.isFullyAuthenticated
    val isOperational: Boolean get() = calendarOps.isOperational
    val hasPermissionIssues: Boolean get() = !permissions.isPermissionGranted
    val canProceedToCalendarSelection: Boolean get() = 
        userAuth.isAuthenticated && permissions.isPermissionGranted
    val isReadyForAlarms: Boolean get() = 
        canProceedToCalendarSelection && calendarOps.isReady
    
    companion object {
        val EMPTY = AuthState()
        
        // FACTORY METHODS: Für häufige State-Kombinationen
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
