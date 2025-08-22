package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

import androidx.compose.runtime.Immutable

/**
 * IMMUTABLE Sub-State für Error Management
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ GRANULAR STATE MODELING: Fokussiert auf Error-spezifische States
 * ✅ Single Responsibility: Nur Error-bezogene Information
 * ✅ Typed Errors: Verschiedene Error-Typen für bessere UX
 * ✅ Recovery Actions: Built-in Error Recovery Logic
 */
@Immutable
data class AppErrorState(
    val error: String? = null,
    val errorType: ErrorType = ErrorType.NONE,
    val isRecoverable: Boolean = true,
    val showError: Boolean = false
) {
    // Computed properties für Error Handling
    val hasError: Boolean get() = error != null && errorType != ErrorType.NONE
    val canRetry: Boolean get() = hasError && isRecoverable
    val needsUserAction: Boolean get() = hasError && !isRecoverable
    
    enum class ErrorType {
        NONE,
        AUTHENTICATION,
        PERMISSION,
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
        
        fun permissionError(message: String) = AppErrorState(
            error = message,
            errorType = ErrorType.PERMISSION,
            isRecoverable = false,
            showError = true
        )
        
        fun calendarError(message: String) = AppErrorState(
            error = message,
            errorType = ErrorType.CALENDAR_API,
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
