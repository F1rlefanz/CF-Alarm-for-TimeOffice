package com.github.f1rlefanz.cf_alarmfortimeoffice.error

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * SIMPLIFIED Central Error Handler - Focus on Logging
 * 
 * CORE FEATURES:
 * ✅ Structured Error Categorization
 * ✅ Integration with centralized Logger system
 * ✅ User-friendly German error messages
 * ✅ Security-conscious error reporting
 * ✅ Memory-efficient error handling
 * 
 * REMOVED: Firebase Crashlytics integration (not needed for debugging)
 */
object ErrorHandler {
    
    private lateinit var appContext: Context
    private var isInitialized = false
    
    /**
     * REQUIRED: Initialize ErrorHandler with Application Context
     * Call this in CFAlarmApplication.onCreate()
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        isInitialized = true
        Logger.i(LogTags.ERROR, "🔧 ErrorHandler initialized")
    }
    
    /**
     * SIMPLIFIED error handling with structured logging
     * 
     * Uses centralized Logger system for consistent error reporting
     * Provides detailed context while maintaining security
     */
    fun handleError(error: Throwable, context: String = ""): AppError {
        val appError = error.toAppError()
        
        // PERFORMANCE: Build error context efficiently
        val contextInfo = if (context.isNotEmpty()) " in $context" else ""
        val errorContext = "Error$contextInfo: ${appError.message}"
        
        // STRUCTURED ERROR LOGGING: Use appropriate log levels based on error severity
        when (appError) {
            // NETWORK & API ERRORS: Usually recoverable, log as warnings
            is AppError.NetworkError -> {
                Logger.w(LogTags.NETWORK, "🌐❌ $errorContext", appError)
                appError.cause?.let { Logger.d(LogTags.NETWORK, "Network error cause: ${it.message}") }
            }
            is AppError.ApiError -> {
                Logger.w(LogTags.NETWORK, "🔌❌ API $errorContext", appError)
                appError.cause?.let { Logger.d(LogTags.NETWORK, "API error details: ${it.message}") }
            }
            
            // STORAGE ERRORS: Critical for app functionality, log as errors
            is AppError.DataStoreError -> {
                Logger.e(LogTags.DATASTORE, "💾❌ $errorContext", appError)
            }
            is AppError.PreferencesError -> {
                Logger.e(LogTags.PREFERENCES, "⚙️❌ $errorContext", appError)
            }
            is AppError.FileSystemError -> {
                Logger.e(LogTags.FILE_SYSTEM, "📁❌ $errorContext", appError)
            }
            
            // AUTHENTICATION & PERMISSION ERRORS: Security-critical
            is AppError.AuthenticationError -> {
                Logger.e(LogTags.AUTH, "🔐❌ $errorContext", appError)
                // SECURITY: Don't log sensitive auth details in production
            }
            is AppError.PermissionError -> {
                Logger.e(LogTags.PERMISSIONS, "🚫❌ Permission denied for ${appError.permission}$contextInfo", appError)
            }
            
            // CALENDAR ERRORS: Business logic related, log as warnings
            is AppError.CalendarAccessError -> {
                Logger.w(LogTags.CALENDAR, "📅❌ $errorContext", appError)
            }
            is AppError.CalendarNotFoundError -> {
                Logger.w(LogTags.CALENDAR, "📅❌ Calendar not found$contextInfo: ${appError.calendarId}", appError)
            }
            
            // VALIDATION & SYSTEM ERRORS: Unexpected issues, log as errors
            is AppError.ValidationError -> {
                Logger.e(LogTags.VALIDATION, "✅❌ $errorContext", appError)
            }
            is AppError.SystemError -> {
                Logger.e(LogTags.SYSTEM, "⚡❌ $errorContext", appError)
            }
            is AppError.UnknownError -> {
                Logger.e(LogTags.ERROR, "❓❌ Unknown $errorContext", appError)
            }
        }
        
        return appError
    }
    
    /**
     * PERFORMANCE-OPTIMIZED user-friendly error message retrieval
     * 
     * Provides German error messages optimized for end users
     * Handles security-sensitive information appropriately
     */
    fun getErrorMessage(error: Throwable): String {
        val appError = (error as? AppError) ?: error.toAppError()
        return getUserMessage(appError)
    }
    
    /**
     * LOCALIZED user-friendly error messages (German)
     * 
     * SECURITY: Sanitized messages that don't expose internal details
     * PERFORMANCE: Pre-computed message mapping for fast lookup
     */
    fun getUserMessage(error: AppError): String = when (error) {
        // NETWORK ERRORS
        is AppError.NetworkError -> "Keine Internetverbindung. Bitte überprüfen Sie Ihre Verbindung und versuchen Sie es erneut."
        is AppError.ApiError -> "Serverfehler (${error.code ?: "Unbekannt"}). Der Service ist möglicherweise vorübergehend nicht verfügbar."
        
        // STORAGE ERRORS
        is AppError.DataStoreError -> "Einstellungen konnten nicht gespeichert werden. Bitte starten Sie die App neu."
        is AppError.PreferencesError -> "Konfiguration konnte nicht geladen werden. Die App wird mit Standardeinstellungen fortgesetzt."
        is AppError.FileSystemError -> "Dateizugriff fehlgeschlagen. Überprüfen Sie den verfügbaren Speicherplatz."
        
        // AUTHENTICATION & PERMISSIONS
        is AppError.AuthenticationError -> "Anmeldung fehlgeschlagen. Bitte melden Sie sich erneut an."
        is AppError.PermissionError -> when (error.permission) {
            "android.permission.READ_CALENDAR" -> "Kalenderzugriff verweigert. Bitte erlauben Sie den Zugriff in den App-Einstellungen."
            "android.permission.POST_NOTIFICATIONS" -> "Benachrichtigungen sind deaktiviert. Bitte aktivieren Sie diese für Alarme."
            "android.permission.SCHEDULE_EXACT_ALARM" -> "Exakte Alarme sind nicht erlaubt. Bitte aktivieren Sie diese in den Einstellungen."
            else -> "Berechtigung '${error.permission}' verweigert. Bitte überprüfen Sie die App-Einstellungen."
        }
        
        // CALENDAR ERRORS
        is AppError.CalendarAccessError -> "Auf den Kalender konnte nicht zugegriffen werden. Überprüfen Sie die Berechtigung."
        is AppError.CalendarNotFoundError -> "Der Kalender '${error.calendarId ?: "Unbekannt"}' wurde nicht gefunden oder ist nicht verfügbar."
        
        // VALIDATION & SYSTEM ERRORS
        is AppError.ValidationError -> "Ungültige Eingabe: ${error.field ?: "Unbekanntes Feld"}. Bitte überprüfen Sie Ihre Daten."
        is AppError.SystemError -> "Systemfehler aufgetreten. Bitte starten Sie die App neu."
        is AppError.UnknownError -> "Ein unerwarteter Fehler ist aufgetreten. Bitte versuchen Sie es erneut."
    }
    
    /**
     * PERFORMANCE-OPTIMIZED CoroutineExceptionHandler creation
     * 
     * Creates memory-efficient exception handlers for coroutine scopes
     * Integrates with centralized error handling and logging
     * 
     * Note: Currently not used but kept for future coroutine error handling needs
     */
    @Suppress("unused")
    fun createCoroutineExceptionHandler(
        context: String,
        onError: ((AppError) -> Unit)? = null
    ): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val appError = handleError(throwable, context)
        onError?.invoke(appError)
    }
    
    /**
     * MEMORY-EFFICIENT error severity classification
     * 
     * Used for prioritizing error handling and logging
     * 
     * Note: Currently not used but kept for future error prioritization features
     */
    @Suppress("unused")
    fun getErrorSeverity(error: AppError): ErrorSeverity = when (error) {
        is AppError.SystemError,
        is AppError.AuthenticationError,
        is AppError.DataStoreError -> ErrorSeverity.CRITICAL
        
        is AppError.PermissionError,
        is AppError.ValidationError,
        is AppError.FileSystemError -> ErrorSeverity.HIGH
        
        is AppError.ApiError,
        is AppError.CalendarAccessError,
        is AppError.PreferencesError -> ErrorSeverity.MEDIUM
        
        is AppError.NetworkError,
        is AppError.CalendarNotFoundError -> ErrorSeverity.LOW
        
        is AppError.UnknownError -> ErrorSeverity.CRITICAL
    }
    
    /**
     * Error severity levels for prioritization
     */
    enum class ErrorSeverity {
        LOW,     // Recoverable, temporary issues
        MEDIUM,  // Service disruptions, degraded functionality
        HIGH,    // Core functionality affected
        CRITICAL // App stability threatened
    }
}
