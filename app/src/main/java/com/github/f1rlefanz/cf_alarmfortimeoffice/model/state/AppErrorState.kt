package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

import androidx.compose.runtime.Immutable

/** Sub-State: aktueller Fehler mit Typ und Wiederholbarkeit. */
@Immutable
data class AppErrorState(
    val error: String? = null,
    val errorType: ErrorType = ErrorType.NONE,
    val isRecoverable: Boolean = true,
    val showError: Boolean = false
) {
    enum class ErrorType {
        NONE,
        AUTHENTICATION,
        CALENDAR_API,
        NETWORK,
        VALIDATION,
        UNKNOWN
    }
    
    companion object {
        val EMPTY = AppErrorState()
        
        fun authenticationError(message: String) = AppErrorState(
            error = message,
            errorType = ErrorType.AUTHENTICATION,
            isRecoverable = true,
            showError = true
        )
        
        fun networkError(message: String) = AppErrorState(
            error = message,
            errorType = ErrorType.NETWORK,
            isRecoverable = true,
            showError = true
        )
        
        fun validationError(message: String) = AppErrorState(
            error = message,
            errorType = ErrorType.VALIDATION,
            isRecoverable = false,
            showError = true
        )
    }
}
