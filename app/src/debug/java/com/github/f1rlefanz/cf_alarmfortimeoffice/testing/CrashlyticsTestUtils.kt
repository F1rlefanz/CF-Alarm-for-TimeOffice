package com.github.f1rlefanz.cf_alarmfortimeoffice.testing

import androidx.credentials.exceptions.NoCredentialException
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * 🧪 FIREBASE CRASHLYTICS TEST UTILITIES
 * 
 * DEBUG BUILD ONLY: Test utilities for validating Firebase Crashlytics integration
 * Simulates critical error scenarios to ensure proper Firebase reporting
 * 
 * ⚠️ IMPORTANT: Only available in DEBUG builds for testing purposes
 * Release builds will never include these test functions
 */
object CrashlyticsTestUtils {
    
    /**
     * 🔐 TEST: Simulate Auth NoCredentialException
     * Tests the critical Google Auth failure point
     */
    fun simulateAuthFailure() {
        Logger.i(LogTags.ERROR, "🧪 TEST: Simulating NoCredentialException for Firebase Crashlytics")
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Set test scenario marker
            crashlytics.setCustomKey("test_scenario", "auth_no_credential_simulation")
            crashlytics.setCustomKey("auth_flow_step", "getCredential")
            crashlytics.setCustomKey("auth_error_type", "no_credential_found")
            crashlytics.setCustomKey("auth_google_services_available", true) // Assume available in test
            
            crashlytics.log("AUTH CRITICAL TEST: No Google credentials found. User cannot sign in.")
            
            // Create and report the exception
            val simulatedException = NoCredentialException("TEST: Simulated NoCredentialException for Firebase validation")
            crashlytics.recordException(simulatedException)
            
            Logger.i(LogTags.ERROR, "✅ TEST: Auth failure simulation reported to Firebase Crashlytics")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "❌ TEST: Failed to simulate auth failure", e)
        }
    }
    
    /**
     * 📅 TEST: Simulate Calendar Empty List Exception
     * Tests the critical Calendar API empty response scenario
     */
    fun simulateCalendarEmptyList() {
        Logger.i(LogTags.ERROR, "🧪 TEST: Simulating Calendar empty list for Firebase Crashlytics")
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Set test scenario marker
            crashlytics.setCustomKey("test_scenario", "calendar_empty_list_simulation")
            crashlytics.setCustomKey("calendar_issue_type", "empty_list_on_success")
            crashlytics.setCustomKey("calendar_response_etag", "test-etag-12345")
            crashlytics.setCustomKey("calendar_token_is_valid_format", true) // Assume valid token in test
            
            crashlytics.log("CALENDAR CRITICAL TEST: Might indicate account with no calendars or API scope issue.")
            
            // Create and report the exception
            val simulatedException = IllegalStateException("TEST: Calendar API returned empty list for valid account - Simulation")
            crashlytics.recordException(simulatedException)
            
            Logger.i(LogTags.ERROR, "✅ TEST: Calendar empty list simulation reported to Firebase Crashlytics")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "❌ TEST: Failed to simulate calendar empty list", e)
        }
    }
    
    /**
     * 🌉 TEST: Simulate Hue Bridge Timeout
     * Tests the critical Hue Bridge connection timeout scenario
     */
    fun simulateHueTimeout() {
        Logger.i(LogTags.ERROR, "🧪 TEST: Simulating Hue Bridge timeout for Firebase Crashlytics")
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Set test scenario marker
            crashlytics.setCustomKey("test_scenario", "hue_timeout_simulation")
            crashlytics.setCustomKey("hue_issue_type", "connection_timeout")
            crashlytics.setCustomKey("hue_timeout_duration_s", 10) // CRITICAL_RECOVERY_TIMEOUT
            crashlytics.setCustomKey("hue_bridge_ip_attempted", "192.168.1.100") // Test IP
            crashlytics.setCustomKey("hue_last_success_hours_ago", 24) // 24 hours ago simulation
            
            crashlytics.log("HUE TIMEOUT TEST: Bridge did not respond. Last successful connection was 24h ago.")
            
            // Create and report the exception (using generic Exception since TimeoutCancellationException constructor is internal)
            val simulatedException = Exception("TEST: Hue Bridge connection timeout simulation (TimeoutCancellationException)")
            crashlytics.recordException(simulatedException)
            
            Logger.i(LogTags.ERROR, "✅ TEST: Hue Bridge timeout simulation reported to Firebase Crashlytics")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "❌ TEST: Failed to simulate Hue Bridge timeout", e)
        }
    }
    
    /**
     * 🚀 TEST: Run All Simulations
     * Convenience method to test all critical failure scenarios at once
     */
    fun runAllSimulations() {
        Logger.i(LogTags.ERROR, "🧪 TEST: Running all Firebase Crashlytics simulations")
        
        // Add small delays between simulations to avoid overwhelming Firebase
        simulateAuthFailure()
        Thread.sleep(1000)
        
        simulateCalendarEmptyList()
        Thread.sleep(1000)
        
        simulateHueTimeout()
        Thread.sleep(1000)
        
        Logger.i(LogTags.ERROR, "✅ TEST: All Firebase Crashlytics simulations completed")
        Logger.i(LogTags.ERROR, "📊 TEST: Check Firebase Console in 5-10 minutes for non-fatal errors")
        Logger.i(LogTags.ERROR, "🔗 TEST: Firebase Console: https://console.firebase.google.com/")
    }
    
    /**
     * 🔍 TEST: Verify Firebase Crashlytics Setup
     * Basic test to ensure Firebase Crashlytics is properly initialized
     */
    fun verifyFirebaseSetup() {
        Logger.i(LogTags.ERROR, "🔍 TEST: Verifying Firebase Crashlytics setup")
        
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Set a test custom key
            crashlytics.setCustomKey("test_verification", "firebase_crashlytics_working")
            crashlytics.log("FIREBASE TEST: Crashlytics setup verification")
            
            Logger.i(LogTags.ERROR, "✅ TEST: Firebase Crashlytics setup appears to be working")
            Logger.i(LogTags.ERROR, "💡 TEST: Use runAllSimulations() to test actual error reporting")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ERROR, "❌ TEST: Firebase Crashlytics setup verification failed", e)
        }
    }
}
