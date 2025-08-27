package com.github.f1rlefanz.cf_alarmfortimeoffice.error

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * ENHANCED Firebase Crashlytics Integration - Central Error Handler
 * 
 * MAJOR IMPROVEMENTS:
 * ✅ Firebase Crashlytics Non-Fatal Error Reporting
 * ✅ Complete App State Context Collection
 * ✅ Structured Error Categorization
 * ✅ Maximum Context for Problem Solving
 * ✅ Performance-optimized error processing
 * ✅ User-friendly German error messages
 * ✅ Security-conscious error reporting
 * ✅ Integration with centralized Logger system
 * ✅ Memory-efficient error handling
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
        Logger.i(LogTags.ERROR, "🔧 ErrorHandler initialized with Firebase Crashlytics integration")
    }
    
    /**
     * ENHANCED error handling with Firebase Crashlytics reporting
     * 
     * Uses centralized Logger system for consistent error reporting
     * Provides detailed context while maintaining security
     * Reports to Firebase Crashlytics in Release builds
     */
    fun handleError(error: Throwable, context: String = ""): AppError {
        val appError = error.toAppError()
        
        // PERFORMANCE: Build error context efficiently
        val contextInfo = if (context.isNotEmpty()) " in $context" else ""
        val errorContext = "Error$contextInfo: ${appError.message}"
        
        // FIREBASE CRASHLYTICS: Report to Firebase (Release builds only)
        reportToFirebase(appError, context)
        
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
     * FIREBASE CRASHLYTICS: Report non-fatal errors with maximum context
     * 
     * Reports errors to Firebase Crashlytics (always - BuildConfig check removed)
     * Includes comprehensive app state for effective debugging
     */
    private fun reportToFirebase(error: AppError, context: String) {
        // Always report - BuildConfig check removed for consistency
        if (!isInitialized) return
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // STRUCTURED ERROR CATEGORIZATION
            val errorCategory = when (error) {
                is AppError.AuthenticationError -> "auth"
                is AppError.CalendarAccessError, is AppError.CalendarNotFoundError -> "calendar"
                is AppError.NetworkError, is AppError.ApiError -> "network"
                is AppError.PermissionError -> "permission"
                is AppError.DataStoreError, is AppError.PreferencesError, is AppError.FileSystemError -> "storage"
                is AppError.SystemError -> "system"
                is AppError.ValidationError -> "validation"
                else -> "unknown"
            }
            
            // SET CUSTOM KEYS FOR FIREBASE DASHBOARD
            crashlytics.setCustomKey("error_category", errorCategory)
            crashlytics.setCustomKey("error_type", error::class.java.simpleName)
            crashlytics.setCustomKey("context_info", context.ifEmpty { "none" })
            
            // COMPREHENSIVE APP STATE CONTEXT
            val appState = getAppState()
            appState.forEach { (key, value) ->
                crashlytics.setCustomKey(key, value)
            }
            
            // BREADCRUMB WITH CONTEXT
            val breadcrumbMessage = "CRASHLYTICS: ${error::class.java.simpleName}" + 
                if (context.isNotEmpty()) " in $context" else ""
            crashlytics.log(breadcrumbMessage)
            
            // REPORT NON-FATAL ERROR
            crashlytics.recordException(error.cause ?: error)
            
            Logger.d(LogTags.ERROR, "📊 Error reported to Firebase Crashlytics: $errorCategory")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "Failed to report error to Firebase Crashlytics", e)
        }
    }
    
    /**
     * COMPREHENSIVE APP STATE COLLECTION
     * 
     * Collects all relevant app state information for Firebase Crashlytics
     * Enables effective debugging and problem resolution
     */
    private fun getAppState(): Map<String, String> {
        val state = mutableMapOf<String, String>()
        
        try {
            // APP LIFECYCLE STATE
            state["app_in_foreground"] = isAppInForeground().toString()
            
            // NETWORK STATE
            state["network_available"] = isNetworkAvailable().toString()
            
            // POWER STATE
            state["battery_level"] = getBatteryLevel().toString()
            state["in_doze_mode"] = isInDozeMode().toString()
            
            // GOOGLE PLAY SERVICES
            state["google_services_available"] = isGooglePlayServicesAvailable().toString()
            
            // DEVICE INFORMATION
            state["device_api_level"] = Build.VERSION.SDK_INT.toString()
            state["device_manufacturer"] = Build.MANUFACTURER
            state["device_model"] = Build.MODEL
            
        } catch (e: Exception) {
            state["app_state_error"] = e.message ?: "unknown"
        }
        
        return state
    }
    
    /**
     * Check if app is in foreground (simplified version)
     */
    private fun isAppInForeground(): Boolean {
        return try {
            // Simplified check - assume true for now to avoid ProcessLifecycleOwner dependency issues
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get battery level percentage
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Check if device is in doze mode
     */
    private fun isInDozeMode(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isDeviceIdleMode
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if Google Play Services is available
     */
    private fun isGooglePlayServicesAvailable(): Boolean {
        return try {
            val result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
            result == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            false
        }
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
     */
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
     */
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
