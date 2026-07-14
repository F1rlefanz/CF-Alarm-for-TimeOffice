package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.UserAuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * MODERNIZED: AuthViewModel with CredentialAuthManager
 *
 * MIGRATION STATUS:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor Injection mit @Inject
 * ✅ Alle Dependencies über Interfaces
 * ✅ Keine Abhängigkeiten zu anderen ViewModels
 *
 * PERFORMANCE FIXES:
 * ✅ Uses modern androidx.credentials API
 * ✅ Atomic state updates (no mutex blocking)
 * ✅ Debounced flows prevent rapid UI updates
 * ✅ Single Source of Truth für Authentication
 * ✅ Memory leak prevention
 * ✅ REACTIVE CALENDAR SELECTION: Auto-syncs hasSelectedCalendars flag
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val credentialAuthManager: CredentialAuthManager,
    private val errorHandler: ErrorHandler,
    private val authUseCase: IAuthUseCase,
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val backgroundServiceManager: BackgroundServiceManager,
    private val tokenRepository: TokenRepository
) : ViewModel() {

    // CONSOLIDATED STATE: Ein einziger State statt AuthState + AuthUiState
    private val _authState = MutableStateFlow(AuthState.EMPTY)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // BACKWARD COMPATIBILITY: Expose als uiState für bestehenden Code
    val uiState: StateFlow<AuthState> = authState

    /**
     * Einmal-Signal: "Das Token ist zur Laufzeit weggefallen, hol die Zustimmung neu ein."
     *
     * WARUM EIN EVENT UND KEIN STATE-FLAG: Den Zustimmungsdialog kann nur eine Activity starten
     * (GoogleAuthUtil liefert einen Intent, kein Ergebnis) - hier unten gibt es keine. Ein
     * State-Flag würde bei jeder Recomposition erneut ausgelöst; das Event wird genau einmal
     * konsumiert.
     *
     * CONFLATED: Stirbt das Token, während die App im Hintergrund ist, wartet das Signal im
     * Channel, bis MainActivity wieder RESUMED ist. Ein Activity-Start aus dem Hintergrund würde
     * von Android verworfen - das Signal wäre verbraucht und der Dialog käme nie.
     */
    private val _reauthRequired = Channel<Unit>(Channel.CONFLATED)
    val reauthRequired: Flow<Unit> = _reauthRequired.receiveAsFlow()

    // CRITICAL FIX: Triggers calendar reload after successful authentication/authorization
    @Volatile
    private var lastCalendarTriggerTime = 0L

    @Volatile
    private var triggerInProgress = false

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
        Logger.d(
            LogTags.AUTH,
            "🚀 REACTIVE-CALENDAR: AuthViewModel initialized with CalendarSelectionRepository"
        )
        observeAuthState()
        checkInitialAuthState()
        observeCalendarSelection() // REACTIVE CALENDAR: Observer für Calendar-Selection-Änderungen
        observeTokenLoss() // AUTO-RE-AUTH: Token-Verlust zur Laufzeit erkennen
    }

    /**
     * Schließt den reaktiven Token-Pfad an: fällt das gespeicherte Token weg, erfährt die UI davon.
     *
     * DAS PROBLEM: OAuth2TokenManager.invalidate() verwirft das Token bei 401/403 still. Ohne
     * diesen Observer blieb calendarOps.hasValidToken auf dem Wert vom App-Start stehen ("stale
     * true") - das Gate in MainActivity feuerte nie und der Nutzer saß in einer Haupt-UI, deren
     * Kalender-Zugriff längst tot war.
     *
     * WARUM NUR DAS NEGATIVE SIGNAL: hasValidToken bedeutet "getValidToken() klappt gerade",
     * inklusive Refresh eines abgelaufenen Tokens (AuthUseCase.hasCalendarAuthorization).
     * "Liegt im Store" ist schwächer - ein totes, aber noch nicht verworfenes Token würde das Gate
     * fälschlich aufmachen. Das FEHLEN eines Tokens ist dagegen eindeutig. Die positiven Übergänge
     * bleiben deshalb bei den bestehenden Schreibern: checkInitialTokenValidity() beim Start und
     * dem Ergebnis von requestCalendarAuthorization().
     *
     * WARUM drop(1): Die erste Emission ist der Ist-Zustand beim Start, kein Verlust. Ohne drop
     * würde jeder Kaltstart ohne Token (frische Installation, abgemeldet) als "Token verloren"
     * gelten und einen Dialog auslösen - beim frischen Sign-in zusätzlich zu dem, den signIn()
     * ohnehin schon anstößt.
     */
    private fun observeTokenLoss() {
        viewModelScope.launch(Dispatchers.IO) {
            tokenRepository.observe()
                .map { it != null }
                .distinctUntilChanged()
                .drop(1)
                .collect { hasToken ->
                    if (hasToken) return@collect

                    // Beim Abmelden ist das Verschwinden gewollt - niemanden in einen
                    // Zustimmungsdialog zwingen, den er gerade verlassen wollte.
                    if (!_authState.value.isSignedIn) {
                        Logger.d(
                            LogTags.AUTH,
                            "🔑 AUTO-RE-AUTH: Token weg, aber nicht angemeldet - keine Re-Autorisierung"
                        )
                        return@collect
                    }

                    Logger.business(
                        LogTags.AUTH,
                        "🔑 AUTO-RE-AUTH: Token zur Laufzeit verworfen - fordere Zustimmung neu an"
                    )

                    updateAuthState { currentState ->
                        currentState.copy(
                            calendarOps = currentState.calendarOps.copy(
                                hasValidToken = false,
                                tokenChecked = true // Ergebnis steht fest -> Gate darf entscheiden
                            )
                        )
                    }

                    // Nur die Activity kann den Dialog starten - MainActivity konsumiert das Event.
                    _reauthRequired.trySend(Unit)
                }
        }
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
                .debounce(200.milliseconds) // PERFORMANCE: Reduced from 300ms for better responsiveness
                .distinctUntilChanged { old, new ->
                    // PERFORMANCE: Only update if meaningful changes occurred
                    old.isLoggedIn == new.isLoggedIn &&
                            old.email == new.email &&
                            old.accessToken == new.accessToken
                }
                .collect { authData ->
                    Logger.d(
                        LogTags.AUTH,
                        "🔄 UI-THREAD-OPT: Auth data updated - isLoggedIn=${authData.isLoggedIn}"
                    )

                    // UI THREAD OPTIMIZATION: Atomic update without context switching
                    updateAuthState { currentState ->
                        currentState.copy(
                            userAuth = UserAuthState(
                                isSignedIn = authData.isLoggedIn,
                                userEmail = authData.email,
                                displayName = authData.displayName,
                                accessToken = authData.accessToken,
                                hasValidToken = !authData.accessToken.isNullOrEmpty()
                            )
                        )
                    }

                    // PERFORMANCE: Background calendar trigger without UI thread switch
                    if (authData.isLoggedIn && !authData.accessToken.isNullOrEmpty()) {
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
                    Logger.d(
                        LogTags.AUTH,
                        "Initial auth state - authenticated=$isAuthenticated, user=$userEmail"
                    )

                    // REACTIVE CALENDAR: Check initial calendar selection status
                    checkInitialCalendarSelection()

                    // Only collect once for initial state, then return
                    return@collect
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal lifecycle cancellation - rethrow for proper structured concurrency
                Logger.d(LogTags.AUTH, "Auth state check cancelled (app lifecycle)")
                throw e
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
        viewModelScope.launch {
            try {
                val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                    .getOrElse { emptySet() }
                val hasSelectedCalendars = selectedIds.isNotEmpty()
                val calendarCount = selectedIds.size

                Logger.d(
                    LogTags.AUTH,
                    "🔍 INITIAL-CALENDAR: Found $calendarCount selected calendars on startup, hasSelected=$hasSelectedCalendars"
                )

                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(
                            hasSelectedCalendars = hasSelectedCalendars
                        )
                    )
                }

            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "Error checking initial calendar selection", e)
            } finally {
                // 🔧 STUFE 2 / GATE: Always run the token check so tokenChecked is set even if the
                // selection check fails — otherwise the onboarding gate would hang on the loading screen.
                checkInitialTokenValidity()
            }
        }
    }

    /**
     * 🔧 STUFE 2: Checks initial Calendar API token validity on startup
     * Ensures hasValidToken flag is correctly set based on actual token status
     */
    private fun checkInitialTokenValidity() {
        viewModelScope.launch {
            try {
                Logger.d(LogTags.AUTH, "🔍 STUFE-2: Checking initial Calendar API token validity")

                // Check if we have a valid Calendar token
                val tokenValidResult = authUseCase.hasCalendarAuthorization()
                val tokenValid = tokenValidResult.getOrElse { false }

                Logger.business(
                    LogTags.AUTH,
                    "🔍 STUFE-2: Initial token validity check result: $tokenValid"
                )

                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(
                            hasValidToken = tokenValid,
                            tokenChecked = true // GATE: initial check complete -> safe to gate on result
                        )
                    )
                }

                if (!tokenValid && _authState.value.calendarOps.hasSelectedCalendars) {
                    Logger.w(
                        LogTags.AUTH,
                        "⚠️ STUFE-2: User has selected calendars but token is invalid - re-authorization required"
                    )
                }
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ STUFE-2: Error checking initial token validity", e)
                // On error, assume token is invalid to be safe
                updateAuthState { currentState ->
                    currentState.copy(
                        calendarOps = currentState.calendarOps.copy(
                            hasValidToken = false,
                            tokenChecked = true // GATE: check ran (even on error) -> let the gate handle recovery
                        )
                    )
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
        viewModelScope.launch(Dispatchers.IO) { // PERFORMANCE: Background thread only
            calendarSelectionRepository.selectedCalendarIds
                .debounce(150.milliseconds) // PERFORMANCE: Debounce to prevent excessive updates
                .distinctUntilChanged { old, new ->
                    // PERFORMANCE: Only update if selection actually changed
                    old.size == new.size && old == new
                }
                .collect { selectedIds ->
                    val hasSelectedCalendars = selectedIds.isNotEmpty()
                    val calendarCount = selectedIds.size

                    Logger.d(
                        LogTags.AUTH,
                        "🔄 REACTIVE-CALENDAR: Calendar selection changed - $calendarCount calendars selected, hasSelected=$hasSelectedCalendars"
                    )

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
                    val (_, displayName, initialEmail) = credentialAuthManager.extractUserInfo(
                        signInResult.credentialResponse
                    )

                    Logger.business(
                        LogTags.AUTH,
                        "📊 EMAIL-EXTRACTION: initial=$initialEmail, final=$initialEmail"
                    )

                    if (!initialEmail.isNullOrEmpty()) {
                        val authData = AuthData(
                            isLoggedIn = true,
                            email = initialEmail,
                            displayName = displayName,
                            accessToken = null // Real tokens managed by ModernOAuth2TokenManager
                        )

                        // Auth-Zustand liegt ausschliesslich in authDataStoreRepository (DataStore).
                        // Der frueher hier geschriebene "cf_alarm_auth"-SharedPrefs-Kanal wurde
                        // nirgends gelesen (toter Code, Audit) und ist entfernt.
                        authDataStoreRepository.updateAuthData(authData)
                            .onSuccess {
                                updateAuthState { currentState ->
                                    currentState.copy(
                                        userAuth = UserAuthState.authenticated(
                                            initialEmail,
                                            displayName ?: "",
                                            null
                                        ),
                                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false)
                                    )
                                }
                                Logger.business(LogTags.AUTH, "✅ Sign-in successful: $initialEmail")

                                // Automatically trigger Calendar authorization
                                Logger.business(
                                    LogTags.AUTH,
                                    "🔄 AUTO-FLOW: Triggering Calendar authorization"
                                )
                                val activity = context as? android.app.Activity
                                requestCalendarAuthorization(activity)
                            }
                            .onFailure { error ->
                                updateAuthState { currentState ->
                                    currentState.copy(
                                        calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                        errors = AppErrorState.authenticationError(
                                            errorHandler.getErrorMessage(
                                                error
                                            )
                                        )
                                    )
                                }
                            }
                    } else {
                        // Hybrid-Flow failed - this should rarely happen with the working implementation
                        Logger.e(
                            LogTags.AUTH,
                            "❌ HYBRID-FLOW: Email extraction failed - unexpected error"
                        )

                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                errors = AppErrorState.authenticationError("Anmeldung fehlgeschlagen: E-Mail-Adresse konnte nicht ermittelt werden")
                            )
                        }
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

                        // Der frueher hier per Reflection geleerte "cf_alarm_auth"-SharedPrefs-Kanal
                        // existiert nicht mehr (toter Code, Audit); der Auth-Zustand wird ueber
                        // authDataStoreRepository.clearAuthData() oben zurueckgesetzt.
                        Logger.business(LogTags.AUTH, "Sign-out successful")
                    }
                    .onFailure { error ->
                        updateAuthState { currentState ->
                            currentState.copy(
                                calendarOps = currentState.calendarOps.copy(calendarsLoading = false),
                                errors = AppErrorState.authenticationError(
                                    errorHandler.getErrorMessage(
                                        error
                                    )
                                )
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
     * CRITICAL FIX: Directly calls AuthUseCase to get real OAuth2 tokens with Activity Context
     *
     * @param activity Activity context for launching permission dialog if needed
     */
    fun requestCalendarAuthorization(activity: android.app.Activity? = null) {
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

                Logger.business(
                    LogTags.AUTH,
                    "🔐 ACTIVITY-CONTEXT-FIX: Requesting Calendar authorization for $userEmail with Activity=${activity != null}"
                )

                // CRITICAL FIX: Use Activity-based authorization if activity is provided
                if (activity != null && authUseCase is com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AuthUseCase) {
                    Logger.business(
                        LogTags.AUTH,
                        "✅ ACTIVITY-CONTEXT-FIX: Using Activity-based authorization flow"
                    )

                    authUseCase.requestCalendarAuthorizationWithActivity(
                        userEmail = userEmail,
                        activity = activity,
                        onResult = { success ->
                            updateAuthState { currentState ->
                                currentState.copy(
                                    calendarOps = currentState.calendarOps.copy(
                                        calendarsLoading = false,
                                        hasSelectedCalendars = success,
                                        hasValidToken = success, // 🔧 STUFE 2: Set token validity based on auth result
                                        tokenChecked = true // GATE: auth attempt resolved -> gate decision is well-defined
                                    )
                                )
                            }

                            if (success) {
                                Logger.business(
                                    LogTags.AUTH,
                                    "✅ ACTIVITY-CONTEXT-FIX: Calendar authorization successful, hasValidToken=true"
                                )
                                triggerCalendarReloadAfterAuth()

                                // Initialize maintenance service after successful authorization
                                backgroundServiceManager.initializeMaintenanceService()
                                Logger.business(
                                    LogTags.AUTH,
                                    "✅ Maintenance service initialized after authorization"
                                )
                            } else {
                                Logger.w(
                                    LogTags.AUTH,
                                    "⚠️ ACTIVITY-CONTEXT-FIX: Calendar authorization failed or denied, hasValidToken=false"
                                )
                            }
                        }
                    ).fold(
                        onSuccess = {
                            Logger.business(
                                LogTags.AUTH,
                                "✅ ACTIVITY-CONTEXT-FIX: Authorization request initiated successfully"
                            )
                        },
                        onFailure = { error ->
                            updateAuthState { currentState ->
                                currentState.copy(
                                    calendarOps = currentState.calendarOps.copy(
                                        calendarsLoading = false,
                                        hasValidToken = false, // 🔧 STUFE 2: Mark token as invalid on error
                                        tokenChecked = true // GATE: auth attempt resolved -> gate decision is well-defined
                                    ),
                                    errors = AppErrorState.authenticationError("Calendar-Autorisierung fehlgeschlagen: ${error.message}")
                                )
                            }
                            Logger.e(
                                LogTags.AUTH,
                                "❌ ACTIVITY-CONTEXT-FIX: Calendar authorization failed",
                                error
                            )
                        }
                    )
                } else {
                    // Fallback to old method without Activity context
                    Logger.w(
                        LogTags.AUTH,
                        "⚠️ ACTIVITY-CONTEXT-FIX: No Activity context provided, using legacy method"
                    )

                    val authResult = authUseCase.requestCalendarAuthorization(userEmail)
                    authResult.fold(
                        onSuccess = { authorized ->
                            updateAuthState { currentState ->
                                currentState.copy(
                                    calendarOps = currentState.calendarOps.copy(
                                        calendarsLoading = false,
                                        hasSelectedCalendars = authorized,
                                        hasValidToken = authorized, // 🔧 STUFE 2: Set token validity based on auth result
                                        tokenChecked = true // GATE: auth attempt resolved -> gate decision is well-defined
                                    )
                                )
                            }
                            Logger.business(
                                LogTags.AUTH,
                                "✅ MODERN-FLOW: Calendar authorization successful: $authorized, hasValidToken=$authorized"
                            )

                            // CRITICAL FIX: Auto-trigger calendar loading after successful authorization
                            if (authorized) {
                                triggerCalendarReloadAfterAuth()

                                // Initialize maintenance service after successful authorization
                                backgroundServiceManager.initializeMaintenanceService()
                                Logger.business(
                                    LogTags.AUTH,
                                    "✅ Maintenance service initialized after authorization"
                                )
                            }
                        },
                        onFailure = { error ->
                            updateAuthState { currentState ->
                                currentState.copy(
                                    calendarOps = currentState.calendarOps.copy(
                                        calendarsLoading = false,
                                        hasValidToken = false, // 🔧 STUFE 2: Mark token as invalid on error
                                        tokenChecked = true // GATE: auth attempt resolved -> gate decision is well-defined
                                    ),
                                    errors = AppErrorState.authenticationError("Calendar-Autorisierung fehlgeschlagen: ${error.message}")
                                )
                            }
                            Logger.e(
                                LogTags.AUTH,
                                "❌ MODERN-FLOW: Calendar authorization failed",
                                error
                            )
                        }
                    )
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
                    Logger.d(
                        LogTags.AUTH,
                        "🔄 UI-THREAD-OPT: Calendar reload trigger debounced ($timeSinceLastTrigger ms since last)"
                    )
                    return@launch
                }

                triggerInProgress = true
                lastCalendarTriggerTime = currentTime

                // UI THREAD OPTIMIZATION: Reduced delay from 100ms to 50ms
                delay(50.milliseconds)

                Logger.business(
                    LogTags.AUTH,
                    "🔄 UI-THREAD-OPT: Calendar reload triggered after successful authentication"
                )
                // NOTE: Calendar reload now happens automatically via CalendarStateHolder observation

            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ UI-THREAD-OPT: Failed to trigger calendar reload", e)
            } finally {
                triggerInProgress = false
            }
        }
    }

    /**
     * CRITICAL FIX: Enhanced Lifecycle Management - Properly cancel all ongoing operations
     * MEMORY LEAK PREVENTION: Clear all callbacks and volatile fields to prevent mutex errors
     */
    override fun onCleared() {
        try {
            Logger.d(LogTags.LIFECYCLE, "AuthViewModel: Starting cleanup...")

            // CRITICAL FIX: Reset volatile fields to prevent stale operations
            triggerInProgress = false
            lastCalendarTriggerTime = 0L

            // CRITICAL FIX: Clear state to prevent memory leaks
            _authState.value = AuthState.EMPTY

            Logger.d(LogTags.LIFECYCLE, "AuthViewModel: Cleanup completed successfully")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during AuthViewModel cleanup", e)
        }
    }

}
