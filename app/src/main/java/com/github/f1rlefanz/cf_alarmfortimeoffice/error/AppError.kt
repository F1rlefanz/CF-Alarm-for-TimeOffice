package com.github.f1rlefanz.cf_alarmfortimeoffice.error

/**
 * Sealed class hierarchy for application errors
 */
sealed class AppError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    
    // Network & API Errors
    data class NetworkError(
        override val message: String = "Network connection error",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    data class ApiError(
        val code: Int? = null,
        override val message: String = "API request failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    // Storage Errors
    data class FileSystemError(
        override val message: String = "File system access failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    data class DataStoreError(
        override val message: String = "DataStore operation failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    data class PreferencesError(
        override val message: String = "SharedPreferences operation failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    // Authentication Errors
    data class AuthenticationError(
        override val message: String = "Authentication failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    data class PermissionError(
        val permission: String? = null,
        override val message: String = "Permission denied",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    // Calendar Errors
    data class CalendarAccessError(
        override val message: String = "Calendar access failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    data class CalendarNotFoundError(
        val calendarId: String? = null,
        override val message: String = "Calendar not found",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    // Validation Errors
    data class ValidationError(
        val field: String? = null,
        override val message: String = "Validation failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    // System Errors
    data class SystemError(
        override val message: String = "System operation failed",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
    
    // General Errors
    data class UnknownError(
        override val message: String = "An unknown error occurred",
        override val cause: Throwable? = null
    ) : AppError(message, cause)
}

/**
 * Extension to convert throwables to AppError
 */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this

    // DIE REIHENFOLGE IST TRAGEND: beide Netzwerk-Ausnahmen erben von IOException und muessen
    // deshalb VOR dem IOException-Zweig stehen.
    //
    // Bis zum 18.08.2026 standen sie darunter, und Android Studio meldete sie zu Recht als
    // "'when' branch is never reachable" - toter Code mit Nebenwirkung. Jeder Verbindungsabbruch
    // und jedes Timeout landete im IOException-Zweig und wurde zu einem FileSystemError. Das hat
    // zweierlei gekostet:
    //  - Der Nutzer las eine falsche Meldung. "Keine Internetverbindung. Bitte ueberpruefen Sie
    //    Ihre Verbindung" ist genau der Hinweis, der hier haette kommen muessen.
    //  - Das Log stufte es hoch: NetworkError geht als WARN in den Netz-Tag (Severity LOW, weil
    //    erwartbar und behebbar), der Speicher-Zweig als ERROR. Ein Handy im Funkloch produzierte
    //    also Datei-ERRORs - Rauschen an der Stelle, die den naechsten echten Vorfall auswertbar
    //    halten soll.
    is java.net.UnknownHostException -> AppError.NetworkError(
        message = "No internet connection",
        cause = this
    )
    is java.net.SocketTimeoutException -> AppError.NetworkError(
        message = "Request timed out",
        cause = this
    )

    is java.io.IOException -> when {
        message?.contains("ENOENT") == true || 
        message?.contains("No such file") == true -> 
            AppError.FileSystemError(
                message = "File not found: ${message ?: "Unknown file"}",
                cause = this
            )
        message?.contains("Permission denied") == true -> 
            AppError.PermissionError(
                message = "File access permission denied",
                cause = this
            )
        else -> AppError.FileSystemError(
            message = message ?: "File system error",
            cause = this
        )
    }
    is SecurityException -> AppError.PermissionError(
        message = message ?: "Security permission denied",
        cause = this
    )
    else -> AppError.UnknownError(
        message = message ?: "Unknown error",
        cause = this
    )
}
