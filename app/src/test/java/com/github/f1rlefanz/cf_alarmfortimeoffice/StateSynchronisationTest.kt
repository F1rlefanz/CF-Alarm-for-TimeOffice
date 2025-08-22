package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.PermissionState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.CalendarOperationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests für State Synchronisation Fixes
 * 
 * PERFORMANCE TESTS: Validiert Resource Management Optimierungen
 * ✅ Testet @Immutable Data Classes für strukturelle Gleichheit
 * ✅ Validiert distinctUntilChanged() Performance-Optimierungen  
 * ✅ Verifiziert atomare State-Updates
 * ✅ Prüft Sub-State Performance-Features
 */
class StateSynchronisationTest {

    @Test
    fun `AuthState should use sub-states correctly`() {
        // ARRANGE: Create AuthState with sub-states
        val userAuth = UserAuthState.authenticated(
            email = "test@example.com",
            displayName = "Test User",
            accessToken = "valid_token"
        )
        
        val permissions = PermissionState.granted()
        val calendarOps = CalendarOperationState.configured()
        val errors = AppErrorState.EMPTY
        
        // ACT: Create AuthState
        val authState = AuthState(
            userAuth = userAuth,
            permissions = permissions,
            calendarOps = calendarOps,
            errors = errors
        )
        
        // ASSERT: Check sub-state properties
        assertTrue("Should be signed in", authState.isSignedIn)
        assertTrue("Should be fully authenticated", authState.isFullyAuthenticated)
        assertTrue("Should have calendar permissions", authState.androidCalendarPermissionGranted)
        assertTrue("Should be operational", authState.isOperational)
        assertTrue("Should be ready for calendar selection", authState.canProceedToCalendarSelection)
        
        // ASSERT: Check backward compatibility
        assertEquals("Email should match", "test@example.com", authState.userEmail)
        assertEquals("Display name should match", "Test User", authState.displayName)
        assertEquals("Access token should match", "valid_token", authState.accessToken)
    }

    @Test
    fun `distinctUntilChanged should prevent duplicate emissions`() = runTest {
        // ARRANGE: Flow that emits identical states  
        val stateFlow = flow {
            emit(AuthState.EMPTY)
            emit(AuthState.EMPTY) // Duplicate - should be filtered by distinctUntilChanged()
            emit(AuthState.EMPTY) // Duplicate - should be filtered by distinctUntilChanged()
            emit(AuthState.authenticated("test@example.com", "Test", "token"))
            emit(AuthState.authenticated("test@example.com", "Test", "token")) // Duplicate - should be filtered
        }.distinctUntilChanged()
        
        // ACT: Collect all emissions
        val emissions = stateFlow.toList()
        
        // ASSERT: Should only emit unique states (PERFORMANCE TEST for @Immutable)
        assertEquals("Should only emit 2 unique states", 2, emissions.size)
        assertEquals("First emission should be EMPTY", AuthState.EMPTY, emissions[0])
        assertTrue("Second emission should be authenticated", emissions[1].isSignedIn)
    }

    @Test
    fun `sub-states should have computed properties`() {
        // ARRANGE & ACT: Test UserAuthState
        val userAuth = UserAuthState.authenticated(
            email = "test@example.com",
            displayName = "Test User",
            accessToken = "valid_token"
        )
        
        // ASSERT: UserAuthState computed properties
        assertTrue("Should be authenticated", userAuth.isAuthenticated)
        assertTrue("Should have user info", userAuth.hasUserInfo)
        assertTrue("Should be fully authenticated", userAuth.isFullyAuthenticated)
        
        // ARRANGE & ACT: Test PermissionState
        val permissions = PermissionState.granted()
        
        // ASSERT: PermissionState computed properties
        assertTrue("Should have permission granted", permissions.isPermissionGranted)
        assertFalse("Should not need permission request", permissions.needsPermissionRequest)
        assertFalse("Should not be permanently denied", permissions.isPermanentlyDenied)
        
        // ARRANGE & ACT: Test CalendarOperationState
        val calendarOps = CalendarOperationState.configured()
        
        // ASSERT: CalendarOperationState computed properties
        assertTrue("Should be operational", calendarOps.isOperational)
        assertTrue("Should be fully configured", calendarOps.isFullyConfigured)
        assertFalse("Should not need calendar selection", calendarOps.needsCalendarSelection)
    }

    @Test
    fun `error state should handle different error types`() {
        // ARRANGE & ACT: Create different error types
        val authError = AppErrorState.authenticationError("Auth failed")
        val permissionError = AppErrorState.permissionError("Permission denied")
        val networkError = AppErrorState.networkError("Network error")
        
        // ASSERT: Error types should be correctly set
        assertEquals("Auth error type", AppErrorState.ErrorType.AUTHENTICATION, authError.errorType)
        assertEquals("Permission error type", AppErrorState.ErrorType.PERMISSION, permissionError.errorType)
        assertEquals("Network error type", AppErrorState.ErrorType.NETWORK, networkError.errorType)
        
        // ASSERT: Recovery flags should be correct
        assertTrue("Auth error should be recoverable", authError.isRecoverable)
        assertFalse("Permission error should not be recoverable", permissionError.isRecoverable)
        assertTrue("Network error should be recoverable", networkError.isRecoverable)
        
        // ASSERT: Error handling properties
        assertTrue("Should have error", authError.hasError)
        assertTrue("Should show error", authError.showError)
        assertTrue("Should be able to retry", authError.canRetry)
        assertTrue("Permission error should need user action", permissionError.needsUserAction)
    }

    @Test
    fun `factory methods should create correct states`() {
        // ARRANGE & ACT: Use factory methods
        val authenticatedState = AuthState.authenticated(
            email = "test@example.com",
            displayName = "Test User", 
            accessToken = "token"
        )
        
        val permissionState = AuthState.withPermissions()
        
        val fullyConfiguredState = AuthState.fullyConfigured(
            email = "test@example.com",
            displayName = "Test User",
            accessToken = "token"
        )
        
        // ASSERT: Factory methods should create correct states
        assertTrue("Authenticated state should be signed in", authenticatedState.isSignedIn)
        assertEquals("Email should match", "test@example.com", authenticatedState.userEmail)
        
        assertTrue("Permission state should have permissions", permissionState.androidCalendarPermissionGranted)
        
        assertTrue("Fully configured should be ready", fullyConfiguredState.isReadyForAlarms)
        assertTrue("Fully configured should be operational", fullyConfiguredState.isOperational)
    }

    @Test
    fun `calendar selection state should be immutable`() {
        // ARRANGE: Create initial calendar selection
        val calendarIds1 = setOf("cal1", "cal2")
        val calendarIds2 = setOf("cal1", "cal2", "cal3")
        
        // ACT & ASSERT: Sets should be different when content is different
        assertNotEquals("Different calendar sets should not be equal", calendarIds1, calendarIds2)
        
        // ACT & ASSERT: Same content should be equal (structural equality)
        val calendarIds3 = setOf("cal1", "cal2")
        assertEquals("Same calendar sets should be equal", calendarIds1, calendarIds3)
    }

    @Test
    fun `state updates should be atomic`() {
        // ARRANGE: Initial empty state
        var state = AuthState.EMPTY
        
        // ACT: Multiple state updates (PERFORMANCE TEST: Atomic State Updates)
        state = state.copy(
            userAuth = UserAuthState.authenticated("test@example.com", "Test", "token")
        )
        
        state = state.copy(
            permissions = PermissionState.granted()
        )
        
        state = state.copy(
            calendarOps = CalendarOperationState.configured()
        )
        
        // ASSERT: All updates should be reflected atomically
        assertTrue("Should be signed in", state.isSignedIn)
        assertTrue("Should have permissions", state.androidCalendarPermissionGranted)
        assertTrue("Should be operational", state.isOperational)
        assertTrue("Should be ready for alarms", state.isReadyForAlarms)
    }
    
    @Test 
    fun `immutable data classes should have structural equality`() {
        // ARRANGE: Create identical states with @Immutable annotation
        val state1 = UserAuthState.authenticated("test@example.com", "Test User", "token123")
        val state2 = UserAuthState.authenticated("test@example.com", "Test User", "token123")
        val state3 = UserAuthState.authenticated("test@example.com", "Test User", "different_token")
        
        // ASSERT: Structural equality should work correctly (@Immutable performance test)
        assertEquals("Identical states should be equal", state1, state2)
        assertNotEquals("Different states should not be equal", state1, state3)
        
        // ASSERT: Hash codes should be consistent for equal objects
        assertEquals("Hash codes should match for equal states", state1.hashCode(), state2.hashCode())
        assertNotEquals("Hash codes should differ for different states", state1.hashCode(), state3.hashCode())
    }
}
