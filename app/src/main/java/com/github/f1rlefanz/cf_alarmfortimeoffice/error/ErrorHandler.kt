package com.github.f1rlefanz.cf_alarmfortimeoffice.error

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Zentrale Fehlerbehandlung: ordnet Ausnahmen einem [AppError] zu, loggt nach Schwere und liefert
 * deutsche Nutzertexte.
 */
object ErrorHandler {
    
    /** Ordnet [error] einem [AppError] zu und loggt ihn mit passendem Level; [context] erscheint im Log. */
    fun handleError(error: Throwable, context: String = ""): AppError {
        val appError = error.toAppError()
        
        val contextInfo = if (context.isNotEmpty()) " in $context" else ""
        val errorContext = "Error$contextInfo: ${appError.message}"
        
        // STRUCTURED ERROR LOGGING: Use appropriate log levels based on error severity
        when (appError) {
            // NETWORK & API ERRORS: Usually recoverable, log as warnings
            is AppError.NetworkError -> {
                Logger.w(LogTags.NETWORK, "🌐❌ $errorContext", appError)
                appError.cause?.let { Logger.d(LogTags.NETWORK, "Network error cause: ${it.message}") }
            }
            
            // STORAGE ERRORS: Critical for app functionality, log as errors
            is AppError.DataStoreError -> {
                Logger.e(LogTags.DATASTORE, "💾❌ $errorContext", appError)
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
            
            // VALIDATION & SYSTEM ERRORS: Unexpected issues, log as errors
            is AppError.ValidationError -> {
                Logger.e(LogTags.VALIDATION, "✅❌ $errorContext", appError)
            }
            is AppError.UnknownError -> {
                Logger.e(LogTags.ERROR, "❓❌ Unknown $errorContext", appError)
            }
        }
        
        return appError
    }
    
    /** Deutscher, fuer Nutzer bestimmter Text zu [error] - ohne interne Details. */
    fun getErrorMessage(error: Throwable): String {
        val appError = (error as? AppError) ?: error.toAppError()
        return getUserMessage(appError)
    }
    
    private fun getUserMessage(error: AppError): String = when (error) {
        // NETWORK ERRORS
        is AppError.NetworkError -> "Keine Internetverbindung. Prüfe deine Verbindung und versuche es erneut."
        
        // STORAGE ERRORS
        is AppError.DataStoreError -> "Einstellungen konnten nicht gespeichert werden. Bitte starte die App neu."
        is AppError.FileSystemError -> "Dateizugriff fehlgeschlagen. Prüfe, ob noch Speicherplatz frei ist."
        
        // AUTHENTICATION & PERMISSIONS
        is AppError.AuthenticationError -> "Anmeldung fehlgeschlagen. Bitte melde dich erneut an."
        is AppError.PermissionError -> when (error.permission) {
            "android.permission.READ_CALENDAR" -> "Kalenderzugriff verweigert. Bitte erlaube den Zugriff in den App-Einstellungen."
            "android.permission.POST_NOTIFICATIONS" -> "Benachrichtigungen sind deaktiviert. Bitte schalte sie für die Wecker ein."
            "android.permission.SCHEDULE_EXACT_ALARM" -> "Exakte Alarme sind nicht erlaubt. Bitte erlaube sie in den Android-Einstellungen."
            // permission == null: kein Android-Runtime-Permission-Fall, sondern eine
            // serverseitige Ablehnung (z.B. Kalender nicht freigegeben). Dann traegt
            // message den verstaendlichen Text - frueher stand hier stattdessen
            // "Berechtigung 'null' verweigert".
            null -> error.message
            else -> "Berechtigung '${error.permission}' verweigert. Bitte prüfe die App-Einstellungen."
        }
        
        // CALENDAR ERRORS
        is AppError.CalendarAccessError -> "Auf den Kalender konnte nicht zugegriffen werden. Prüfe die Berechtigung."
        
        // VALIDATION & SYSTEM ERRORS
        is AppError.ValidationError -> "Ungültige Eingabe: ${error.field ?: "Unbekanntes Feld"}. Bitte prüfe deine Eingabe."
        is AppError.UnknownError -> "Ein unerwarteter Fehler ist aufgetreten. Bitte versuche es erneut."
    }
    
    /**
     * Erzeugt einen CoroutineExceptionHandler, der die Ausnahme durch die zentrale
     * Fehlerbehandlung schickt.
     *
     * NICHT ENTFERNEN: `HueBridgeConnectionManager.healthCheckScope` nutzt sie; ein `SupervisorJob`
     * allein laesst die Ausnahme den Prozess beenden (CLAUDE.md).
     */
    fun createCoroutineExceptionHandler(
        context: String,
        onError: ((AppError) -> Unit)? = null
    ): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val appError = handleError(throwable, context)
        onError?.invoke(appError)
    }
    
}
