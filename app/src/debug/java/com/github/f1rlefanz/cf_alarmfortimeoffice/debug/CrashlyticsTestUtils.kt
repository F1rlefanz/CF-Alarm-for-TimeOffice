package com.github.f1rlefanz.cf_alarmfortimeoffice.debug

import android.content.Context
import androidx.credentials.exceptions.NoCredentialException
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CancellationException

/**
 * DEBUG-ONLY Firebase Crashlytics Test Utilities
 * 
 * Diese Utilities simulieren die kritischen 3 Fehlertypen für Firebase Testing.
 * Nur im debug sourceset verfügbar - automatisch aus Release builds ausgeschlossen.
 */
object CrashlyticsTestUtils {
    
    /**
     * Simuliert NoCredentialException (Google Auth Failure)
     * 
     * Testet die Firebase Crashlytics Integration für Auth-Probleme
     */
    fun simulateAuthFailure(context: Context) {
        if (!BuildConfig.DEBUG) {
            Logger.w(LogTags.ERROR, "CrashlyticsTestUtils only available in DEBUG builds")
            return
        }
        
        Logger.i(LogTags.ERROR, "🧪 CRASHLYTICS-TEST: Simulating NoCredentialException")
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Set custom keys for Firebase Dashboard filtering
            crashlytics.setCustomKey("auth_flow_step", "getCredential")
            crashlytics.setCustomKey("auth_error_type", "no_credential_found")
            crashlytics.setCustomKey("auth_google_services_available", true)
            crashlytics.setCustomKey("test_scenario", "crashlytics_debug_test")
            
            // Set breadcrumb
            crashlytics.log("AUTH CRITICAL: No Google credentials found. User cannot sign in.")
            
            // Create and report the exception
            val testException = NoCredentialException("TEST: No Google credentials found")
            crashlytics.recordException(testException)
            
            Logger.i(LogTags.ERROR, "📊 NoCredentialException test sent to Firebase Crashlytics")
            Logger.i(LogTags.ERROR, "💡 Check Firebase Console in 5-10 minutes for the test report")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "Failed to simulate auth failure test", e)
        }
    }
    
    /**
     * Simuliert Calendar Empty List Issue
     * 
     * Testet die Firebase Crashlytics Integration für Calendar-Probleme
     */
    fun simulateCalendarEmptyList() {
        if (!BuildConfig.DEBUG) {
            Logger.w(LogTags.ERROR, "CrashlyticsTestUtils only available in DEBUG builds")
            return
        }
        
        Logger.i(LogTags.ERROR, "🧪 CRASHLYTICS-TEST: Simulating Calendar Empty List")
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Set custom keys for Firebase Dashboard filtering
            crashlytics.setCustomKey("calendar_issue_type", "empty_list_on_success")
            crashlytics.setCustomKey("calendar_response_etag", "test_etag_12345")
            crashlytics.setCustomKey("calendar_token_is_valid_format", true)
            crashlytics.setCustomKey("test_scenario", "crashlytics_debug_test")
            
            // Set breadcrumb
            crashlytics.log("CALENDAR CRITICAL: Might indicate account with no calendars or API scope issue.")
            
            // Create and report the exception
            val testException = IllegalStateException("TEST: Calendar API returned empty list for valid account")
            crashlytics.recordException(testException)
            
            Logger.i(LogTags.ERROR, "📊 Calendar Empty List test sent to Firebase Crashlytics")
            Logger.i(LogTags.ERROR, "💡 Check Firebase Console in 5-10 minutes for the test report")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "Failed to simulate calendar empty list test", e)
        }
    }
    
    /**
     * Simuliert Hue Bridge Timeout
     * 
     * Testet die Firebase Crashlytics Integration für Hue Bridge Probleme
     */
    fun simulateHueTimeout() {
        if (!BuildConfig.DEBUG) {
            Logger.w(LogTags.ERROR, "CrashlyticsTestUtils only available in DEBUG builds")
            return
        }
        
        Logger.i(LogTags.ERROR, "🧪 CRASHLYTICS-TEST: Simulating Hue Bridge Timeout")
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Set custom keys for Firebase Dashboard filtering
            crashlytics.setCustomKey("hue_issue_type", "connection_timeout")
            crashlytics.setCustomKey("hue_timeout_duration_s", 10)
            crashlytics.setCustomKey("hue_bridge_ip_attempted", "192.168.1.100")
            crashlytics.setCustomKey("hue_last_success_hours_ago", 12)
            crashlytics.setCustomKey("test_scenario", "crashlytics_debug_test")
            
            // Set breadcrumb
            crashlytics.log("HUE TIMEOUT: Bridge did not respond. Last successful connection was 12h ago.")
            
            // Create and report the exception
            val testException = RuntimeException("TEST: Bridge connection timed out after 10 seconds (simulated TimeoutCancellationException)")
            crashlytics.recordException(testException)
            
            Logger.i(LogTags.ERROR, "📊 Hue Bridge Timeout test sent to Firebase Crashlytics")
            Logger.i(LogTags.ERROR, "💡 Check Firebase Console in 5-10 minutes for the test report")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "Failed to simulate hue timeout test", e)
        }
    }
    
    /**
     * Führt alle Test-Szenarien nacheinander aus
     * 
     * Praktisch für vollständige Firebase Crashlytics Tests
     */
    fun runAllCrashlyticsTests(context: Context) {
        if (!BuildConfig.DEBUG) {
            Logger.w(LogTags.ERROR, "CrashlyticsTestUtils only available in DEBUG builds")
            return
        }
        
        Logger.i(LogTags.ERROR, "🧪 CRASHLYTICS-TEST: Running complete test suite")
        
        simulateAuthFailure(context)
        Thread.sleep(2000) // 2 second delay between tests
        
        simulateCalendarEmptyList()
        Thread.sleep(2000) // 2 second delay between tests
        
        simulateHueTimeout()
        
        Logger.i(LogTags.ERROR, "✅ CRASHLYTICS-TEST: All tests completed")
        Logger.i(LogTags.ERROR, "💡 Check Firebase Console in 5-10 minutes for all test reports")
        Logger.i(LogTags.ERROR, "🔍 Filter by 'test_scenario = crashlytics_debug_test' to see only test data")
    }
    
    /**
     * Test der ErrorHandler Integration
     * 
     * Testet, ob der zentrale ErrorHandler korrekt mit Firebase integriert ist
     */
    fun testErrorHandlerIntegration(context: Context) {
        if (!BuildConfig.DEBUG) {
            Logger.w(LogTags.ERROR, "CrashlyticsTestUtils only available in DEBUG builds")
            return
        }
        
        Logger.i(LogTags.ERROR, "🧪 CRASHLYTICS-TEST: Testing ErrorHandler Firebase integration")
        
        try {
            // Simuliere einen AppError über den ErrorHandler
            val testException = RuntimeException("TEST: ErrorHandler Firebase integration test")
            com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler.handleError(
                testException, 
                "CrashlyticsTestUtils.testErrorHandlerIntegration"
            )
            
            Logger.i(LogTags.ERROR, "📊 ErrorHandler integration test completed")
            Logger.i(LogTags.ERROR, "💡 Check Firebase Console for ErrorHandler test report")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "Failed to test ErrorHandler integration", e)
        }
    }
}
