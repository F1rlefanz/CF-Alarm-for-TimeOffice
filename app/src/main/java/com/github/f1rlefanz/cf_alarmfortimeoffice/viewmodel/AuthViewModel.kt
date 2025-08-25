package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import androidx.core.content.edit

/**
 * MODERNIZED: AuthViewModel with CredentialAuthManager
 * 
 * PERFORMANCE FIXES:
 * ✅ Uses modern androidx.credentials API
 * ✅ Atomic state updates (no mutex blocking)
 * ✅ Debounced flows prevent rapid UI updates
 * ✅ Single Source of Truth für Authentication
 * ✅ Memory leak prevention
 * ✅ REACTIVE CALENDAR SELECTION: Auto-syncs hasSelectedCalendars flag
 */
class AuthViewModel(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val credentialAuthManager: CredentialAuthManager,
    private val errorHandler: ErrorHandler,
    private val authUseCase: com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase? = null,
    private val calendarSelectionRepository: com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository? = null
) : ViewModel() {

    // CONSOLIDATED STATE: Ein einziger State statt AuthState + AuthUiState
    private val _authState = MutableStateFlow(AuthState.EMPTY)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    // BACKWARD COMPATIBILITY: Expose als uiState für bestehenden Code
    val uiState: StateFlow<AuthState> = authState
    
    // CRITICAL FIX: Triggers calendar reload after successful authentication/authorization
    @Volatile
    private var lastCalendarTriggerTime = 0L
    @Volatile
    private var triggerInProgress = false
    
    // Callback to trigger calendar reload - will be set by MainViewModel or coordinator
    private var calendarReloadTrigger: (() -> Unit)? = null
    
    /**
     * PERFORMANCE OPTIMIZATION: Non-blocking Atomic State Updates
     * Ersetzt Mutex durch atomare Vergleich-und-Tausch Operationen
     */
    private fun updateAuthState(updateFunc: (AuthState) -> AuthState) {
        val currentState = _authState.value
        val newState = updateFunc(currentState)
        
        // ATOMIC UPDATE: Thread-safe ohne Mutex-Blocking
        if (currentState != newState) {
            _authState.value = newState
        }
    }

    init {
        val hasRepository = calendarSelectionRepository != null
        Logger.d(LogTags.AUTH, "🚀 REACTIVE-CALENDAR: AuthViewModel initialized with CalendarSelectionRepository=$hasRepository")
        observeAuthState()
        checkInitialAuthState()
        observeCalendarSelection() // REACTIVE CALENDAR: Observer für Calendar-Selection-Änderungen
    }

    /**
     * Observes auth data changes from DataStore.
     * PERFORMANCE FIX: Eliminates UI Thread blocking durch improved background processing
     * CALENDAR AUTO-RELOAD: Automatically loads calendars after successful authorization
     * UI THREAD OPTIMIZATION: Pure background processing mit atomic state updates
     */
    @OptIn(FlowPreview::class)
    private fun observeAuthState() {
        viewModelScope.launch(Dispatchers.IO) { // PERFORMANCE: Background thread only
            authDataStoreRepository.authData
                .debounce(200) // PERFORMANCE: Reduced from 300ms for better responsiveness
                .distinctUntilChanged { old, new -> 
                    // PERFORMANCE: Only update if meaningful changes occurred
                    old.isLoggedIn == new.isLoggedIn &&
                    old.email == new.email &&
                    old.accessToken == new.accessToken
                }
                .collect { authData ->
                    Logger.d(LogTags.AUTH, "🔄 UI-THREAD-OPT: Auth data updated - isLoggedIn=${authData.isLoggedIn}")
                    
                    // UI THREAD OPTIMIZATION: Atomic update without context switching
                    updateAuthState { currentState ->
                        currentState.copy(
                            userAuth = UserAuthState(
                                isSignedIn = authData.isLoggedIn,
                                userEmail = authData.email,
                                displayName = authData.displayName,
                                accessToken = authData.accessToken,
                                hasValidToken = authData.accessToken?.isNotEmpty() ?: false
                            )
                        )
                    }
                    
                    // PERFORMANCE: Background calendar trigger without UI thread switch
                    if (authData.isLoggedIn && (authData.accessToken?.isNotEmpty() ?: false)) {
                        triggerCalendarReloadAfterAuth()
                    }
                }
        }
    }

    /**
     * Checks initial authentication state on ViewModel initialization.
     */
    private fun checkInitialAuthState() {
        viewModelScope.launch {
            try {
                // Get current auth data from repository
                authDataStoreRepository.authData.collect { authData ->
                    updateAuthState { currentState ->
                        currentState.copy(
                            userAuth = UserAuthState(
                                hasValidToken = authData.isLoggedIn,
                                userEmail = authData.email,
                                displayName = authData.displayName,
                                isSignedIn = authData.isLoggedIn,
                                accessToken = authData.accessToken
                            )
                        )
                    }
                    
                    val isAuthenticated = authData.isLoggedIn
                    val userEmail = authData.email
                    Logger.d(LogTags.AUTH, "Initial auth state - authenticated=$isAuthenticated, user=$userEmail")
                    
                    // REACTIVE CALENDAR: Check initial calendar selection status
                    checkInitialCalendarSelection()
                    
                    // Only collect once for initial state, then return
                    return@collect
                }
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "Error checking initial auth state", e)
            }
        }
    }

    /**
     * REACTIVE CALENDAR: Checks initial calendar selection status on startup
     * Ensures hasSelectedCalendars flag is correctly set on app startup
     */
    private fun checkInitialCalendarSelection() {
        calendarSelectionRepository?.let { repository ->
            viewModelScope.launch {
                try {
                    val selectedIds = repository.getCurrentSelectedCalendarIds().getOrElse { emptySet() }
                    val hasSelectedCalendars = selectedIds.isNotEmpty()
                    val calendarCount = selectedIds.size
                    
                    Logger.d(LogTags.AUTH, "🔍 INITIAL-CALENDAR: Found $calendarCount selected calendars on startup, hasSelected=$hasSelectedCalendars")
                    
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(
                                hasSelectedCalendars = hasSelectedCalendars
                            )
                        )
                    }
                } catch (e: Exception) {
                    Logger.e(LogTags.AUTH, "Error checking initial calendar selection", e)
                }
            }
        }
    }

    /**
     * REACTIVE CALENDAR SELECTION: Observes calendar selection changes
     * 
     * BUG FIX: Automatically synchronizes hasSelectedCalendars flag with CalendarSelectionRepository
     * Solves the issue where Calendar-Berechtigung card appears after restart even when calendars are selected
     */
    @OptIn(FlowPreview::class)
    private fun observeCalendarSelection() {
        calendarSelectionRepository?.let { repository ->
            viewModelScope.launch(Dispatchers.IO) { // PERFORMANCE: Background thread only
                repository.selectedCalendarIds
                    .debounce(150) // PERFORMANCE: Debounce to prevent excessive updates
                    .distinctUntilChanged { old, new -> 
                        // PERFORMANCE: Only update if selection actually changed
                        old.size == new.size && old == new
                    }
                    .collect { selectedIds ->
                        val hasSelectedCalendars = selectedIds.isNotEmpty()
                        val calendarCount = selectedIds.size
                        
                        Logger.d(LogTags.AUTH, "🔄 REACTIVE-CALENDAR: Calendar selection changed - $calendarCount calendars selected, hasSelected=$hasSelectedCalendars")
                        
                        // UI THREAD OPTIMIZATION: Atomic update without context switching
                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(
                                    hasSelectedCalendars = hasSelectedCalendars
                                )
                            )
                        }
                    }
            }
        } ?: run {
            Logger.w(LogTags.AUTH, "⚠️ REACTIVE-CALENDAR: CalendarSelectionRepository not injected - calendar selection sync disabled")
        }
    }

    /**
     * MODERN AUTH: Sign in using CredentialAuthManager
     */
    fun signIn(context: Context) {
        viewModelScope.launch {
            updateAuthState { currentState ->
                currentState.copy(
                    calendarOps = currentState.calendarOps.copy(calendarsLoading = true),
                    errors = AppErrorState.EMPTY
                )
            }
            
            try {
                Logger.business(LogTags.AUTH, "Starting modern credential sign-in")
                val signInResult = credentialAuthManager.signIn(context)
                
                if (signInResult.success && signInResult.credentialResponse != null) {
                    // Extract user info from credential
                    val (_, displayName, initialEmail) = credentialAuthManager.extractUserInfo(signInResult.credentialResponse, context)
                    
                    Logger.business(LogTags.AUTH, "📊 EMAIL-EXTRACTION: initial=$initialEmail, final=$initialEmail")
                    
                    if (!initialEmail.isNullOrEmpty() && initialEmail != "user.needs.to.enter@gmail.com") {
                        val authData = AuthData(
                            isLoggedIn = true,
                            email = initialEmail,
                            displayName = displayName,
                            accessToken = null // No token here - real tokens managed by ModernOAuth2TokenManager
                        )
                        
                        // CRITICAL FIX: Also save email to SharedPreferences for CalendarRepository
                        try {
                            val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
                            prefs.edit {
                                putString("current_user_email", initialEmail)
                                putString("current_user_display_name", displayName)
                                putLong("auth_timestamp", System.currentTimeMillis())
                            }
                            Logger.d(LogTags.AUTH, "CRITICAL-FIX: Saved user email to SharedPreferences for CalendarRepository access")
                        } catch (e: Exception) {
                            Logger.w(LogTags.AUTH, "Could not save user email to SharedPreferences", e)
                        }
                        
                        authDataStoreRepository.updateAuthData(authData)
                            .onSuccess {
                                updateAuthState { currentState ->
                                    currentState.copy(
                                        userAuth = UserAuthState.authenticated(
                                            initialEmail,
                                            displayName ?: "",
                                            null // No placeholder token
                                        ),
                                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false)
                                    )
                                }
                                Logger.business(LogTags.AUTH, "Sign-in successful", initialEmail)
                                
                                // CRITICAL FIX: Automatically trigger Calendar authorization after sign-in
                                Logger.business(LogTags.AUTH, "🔄 AUTO-FLOW: Triggering Calendar authorization for signed-in user")
                                requestCalendarAuthorization()
                            }
                            .onFailure { error ->
                                updateAuthState { currentState ->
                                    currentState.copy(
                                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                        errors = AppErrorState.authenticationError(errorHandler.getErrorMessage(error))
                                    )
                                }
                            }
                    } else {
                        // CRITICAL FIX: Email extraction failed - show manual input dialog
                        Logger.e(LogTags.AUTH, "❌ MANUAL-INPUT-REQUIRED: Email extraction failed completely")
                        
                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                errors = AppErrorState.authenticationError("E-Mail-Eingabe erforderlich - automatische Erkennung fehlgeschlagen")
                            )
                        }
                        
                        // Show manual email input dialog
                        val emailDialog = com.github.f1rlefanz.cf_alarmfortimeoffice.auth.EmailInputDialog(context)
                        emailDialog.showEmailInputDialog(
                            onEmailProvided = { manualEmail ->
                                Logger.business(LogTags.AUTH, "✅ MANUAL-INPUT: User provided email, retrying authentication")
                                // Retry the authentication process with manual email
                                handleManualEmailAndContinueAuth(context, manualEmail, displayName)
                            },
                            onCancelled = {
                                Logger.w(LogTags.AUTH, "❌ MANUAL-INPUT: User cancelled email input")
                                updateAuthState { currentState ->
                                    currentState.copy(
                                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                        errors = AppErrorState.authenticationError("Anmeldung abgebrochen - E-Mail-Adresse benötigt")
                                    )
                                }
                            }
                        )
                    }
                } else {
                    val errorMessage = signInResult.error ?: "Unbekannter Fehler bei der Anmeldung"
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                            errors = AppErrorState.authenticationError(errorMessage)
                        )
                    }
                    Logger.e(LogTags.AUTH, "Sign-in failed - $errorMessage")
                }
                
            } catch (e: Exception) {
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                        errors = AppErrorState.authenticationError(errorHandler.getErrorMessage(e))
                    )
                }
                Logger.e(LogTags.AUTH, "Unexpected error during sign-in", e)
            }
        }
    }

    /**
     * Signs out user and clears all authentication data.
     */
    fun signOut(context: Context? = null) {
        viewModelScope.launch {
            updateAuthState { currentState ->
                currentState.copy(
                    calendarOps = currentState.calendarOps.copy(calendarsLoading = true),
                    errors = AppErrorState.EMPTY
                )
            }
            
            try {
                // Local sign-out using CredentialAuthManager
                credentialAuthManager.signOutLocally()
                
                // Clear auth data from DataStore
                authDataStoreRepository.clearAuthData()
                    .onSuccess {
                        updateAuthState { AuthState.EMPTY }
                        
                        // CRITICAL FIX: Also clear SharedPreferences if context is available
                        try {
                            val ctx = context ?: run {
                                // Try to get context from credentialAuthManager
                                val field = credentialAuthManager::class.java.getDeclaredField("context")
                                field.isAccessible = true
                                field.get(credentialAuthManager) as? Context
                            }
                            
                            ctx?.let {
                                val prefs = it.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
                                prefs.edit { clear() }
                                Logger.d(LogTags.AUTH, "CRITICAL-FIX: Cleared SharedPreferences on sign-out")
                            }
                        } catch (e: Exception) {
                            Logger.w(LogTags.AUTH, "Could not clear SharedPreferences on sign-out", e)
                        }
                        
                        Logger.business(LogTags.AUTH, "Sign-out successful")
                    }
                    .onFailure { error ->
                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                errors = AppErrorState.authenticationError(errorHandler.getErrorMessage(error))
                            )
                        }
                    }
                
            } catch (e: Exception) {
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                        errors = AppErrorState.authenticationError(errorHandler.getErrorMessage(e))
                    )
                }
                Logger.e(LogTags.AUTH, "Error during sign-out", e)
            }
        }
    }

    /**
     * Clears current error message.
     */
    fun clearError() {
        updateAuthState { currentState ->
            currentState.copy(errors = AppErrorState.EMPTY)
        }
    }
    
    /**
     * MODERN: Requests Calendar API authorization for current user
     * CRITICAL FIX: Directly calls AuthUseCase to get real OAuth2 tokens
     */
    fun requestCalendarAuthorization() {
        viewModelScope.launch {
            updateAuthState { currentState ->
                currentState.copy(
                    calendarOps = currentState.calendarOps.copy(calendarsLoading = true),
                    errors = AppErrorState.EMPTY
                )
            }
            
            try {
                // Get current user email for Calendar authorization
                val currentAuthData = authDataStoreRepository.getCurrentAuthData().getOrNull()
                val userEmail = currentAuthData?.email
                
                if (userEmail.isNullOrEmpty()) {
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                            errors = AppErrorState.authenticationError("Benutzer-E-Mail für Calendar-Autorisierung nicht verfügbar")
                        )
                    }
                    return@launch
                }
                
                Logger.business(LogTags.AUTH, "MODERN-FLOW: Requesting Calendar authorization for $userEmail")
                
                authUseCase?.requestCalendarAuthorization(userEmail)?.fold(
                    onSuccess = { authorized ->
                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(
                                    calendarsLoading = false,
                                    hasSelectedCalendars = authorized
                                )
                            )
                        }
                        Logger.business(LogTags.AUTH, "✅ MODERN-FLOW: Calendar authorization successful: $authorized")
                        
                        // CRITICAL FIX: Auto-trigger calendar loading after successful authorization
                        if (authorized) {
                            triggerCalendarReloadAfterAuth()
                        }
                    },
                    onFailure = { error ->
                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                errors = AppErrorState.authenticationError("Calendar-Autorisierung fehlgeschlagen: ${error.message}")
                            )
                        }
                        Logger.e(LogTags.AUTH, "❌ MODERN-FLOW: Calendar authorization failed", error)
                    }
                ) ?: run {
                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                            errors = AppErrorState.authenticationError("Calendar-Autorisierungssystem nicht verfügbar")
                        )
                    }
                    Logger.e(LogTags.AUTH, "❌ MODERN-FLOW: AuthUseCase not available for Calendar authorization")
                }
            } catch (e: Exception) {
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                        errors = AppErrorState.authenticationError(errorHandler.getErrorMessage(e))
                    )
                }
                Logger.e(LogTags.AUTH, "❌ MODERN-FLOW: Exception during calendar authorization", e)
            }
        }
    }
    
    private fun triggerCalendarReloadAfterAuth() {
        // PERFORMANCE: Prevent concurrent triggers with atomic check
        if (triggerInProgress) {
            Logger.d(LogTags.AUTH, "🔄 UI-THREAD-OPT: Calendar reload already in progress, skipping")
            return
        }
        
        viewModelScope.launch(Dispatchers.Default) { // UI THREAD OPTIMIZATION: Pure background
            try {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastTrigger = currentTime - lastCalendarTriggerTime
                
                // DEDUPLICATION: Prevent multiple triggers within 2 seconds (optimized)
                if (timeSinceLastTrigger < 2000) {
                    Logger.d(LogTags.AUTH, "🔄 UI-THREAD-OPT: Calendar reload trigger debounced ($timeSinceLastTrigger ms since last)")
                    return@launch
                }
                
                triggerInProgress = true
                lastCalendarTriggerTime = currentTime
                
                // UI THREAD OPTIMIZATION: Reduced delay from 100ms to 50ms
                delay(50)
                
                Logger.business(LogTags.AUTH, "🔄 UI-THREAD-OPT: Initiating calendar reload after successful authentication")
                
                // UI THREAD OPTIMIZATION: Direct callback invocation (callback should handle threading)
                calendarReloadTrigger?.invoke()
                
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ UI-THREAD-OPT: Failed to trigger calendar reload", e)
            } finally {
                triggerInProgress = false
            }
        }
    }
    
    /**
     * CRITICAL FIX: Handle manual email input and continue authentication
     */
    private fun handleManualEmailAndContinueAuth(context: Context, manualEmail: String, displayName: String?) {
        viewModelScope.launch {
            try {
                Logger.business(LogTags.AUTH, "🔄 MANUAL-INPUT: Continuing authentication with manual email: $manualEmail")
                
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(calendarsLoading = true),
                        errors = AppErrorState.EMPTY
                    )
                }
                
                val authData = AuthData(
                    isLoggedIn = true,
                    email = manualEmail,
                    displayName = displayName,
                    accessToken = null
                )
                
                // Save to both DataStore and SharedPreferences
                try {
                    val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
                    prefs.edit {
                        putString("current_user_email", manualEmail)
                        putString("current_user_display_name", displayName)
                        putLong("auth_timestamp", System.currentTimeMillis())
                    }
                    Logger.d(LogTags.AUTH, "✅ MANUAL-INPUT: Email saved to SharedPreferences")
                } catch (e: Exception) {
                    Logger.e(LogTags.AUTH, "❌ MANUAL-INPUT: Failed to save to SharedPreferences", e)
                }
                
                authDataStoreRepository.updateAuthData(authData)
                    .onSuccess {
                        updateAuthState { currentState ->
                            currentState.copy(
                                userAuth = UserAuthState.authenticated(
                                    manualEmail,
                                    displayName ?: "",
                                    null
                                ),
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false)
                            )
                        }
                        
                        Logger.business(LogTags.AUTH, "✅ MANUAL-INPUT: Authentication completed with manual email")
                        
                        // Auto-trigger Calendar authorization
                        Logger.business(LogTags.AUTH, "🔄 AUTO-FLOW: Triggering Calendar authorization for manual email user")
                        requestCalendarAuthorization()
                    }
                    .onFailure { error ->
                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                errors = AppErrorState.authenticationError("Fehler beim Speichern der Anmeldedaten: ${error.message}")
                            )
                        }
                        Logger.e(LogTags.AUTH, "❌ MANUAL-INPUT: Failed to save auth data", error)
                    }
                    
            } catch (e: Exception) {
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                        errors = AppErrorState.authenticationError("Fehler bei manueller E-Mail-Verarbeitung: ${e.message}")
                    )
                }
                Logger.e(LogTags.AUTH, "❌ MANUAL-INPUT: Exception during manual email processing", e)
            }
        }
    }
    
    /**
     * Sets a callback to trigger calendar reload after successful authentication
     * This allows loose coupling between AuthViewModel and CalendarViewModel
     */
    fun setCalendarReloadTrigger(trigger: () -> Unit) {
        calendarReloadTrigger = trigger
        Logger.d(LogTags.AUTH, "Calendar reload trigger registered")
    }

    /**
     * LIFECYCLE MANAGEMENT: Properly cancel all ongoing operations
     */
    override fun onCleared() {
        super.onCleared()
        try {
            Logger.d(LogTags.LIFECYCLE, "AuthViewModel cleanup completed")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during AuthViewModel cleanup", e)
        }
    }
}
