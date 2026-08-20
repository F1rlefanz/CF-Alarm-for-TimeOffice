package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import android.app.Activity
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * UseCase für alle Authentication-bezogenen Operationen - implementiert IAuthUseCase
 * 
 * ✅ PHASE 4 MODERNIZED (2025):
 * - Verwendet OAuth2TokenManager statt deprecated ModernOAuth2TokenManager
 * - Smart Retry Logic mit TokenRefreshStrategy (via OAuth2TokenManager)
 * - Token Rotation Support
 * - Improved Exception Handling mit TokenException hierarchy
 * 
 * REFACTORED:
 * ✅ Implementiert IAuthUseCase Interface für bessere Testbarkeit
 * ✅ Verwendet Repository-Interface statt konkrete Implementierung
 * ✅ Kapselt Business Logic von Infrastructure
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Clean Architecture Compliance
 * ✅ MODERN: Integriert OAuth2TokenManager für Calendar-Autorisierung
 * ✅ FIXED: Added Activity-based authorization method for permission flow
 * 
 * AUTHENTICATION FLOW 2024/2025:
 * 1. Credential Manager für Benutzer-Authentifizierung (wer bist du?)
 * 2. OAuth2TokenManager für API-Autorisierung (was darfst du?)
 */
class AuthUseCase @Inject constructor(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val oauth2TokenManager: OAuth2TokenManager
) : IAuthUseCase {
    
    override val authData: Flow<AuthData> = authDataStoreRepository.authData
    
    override suspend fun updateAuthData(authData: AuthData): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.updateAuthData") {
            authDataStoreRepository.updateAuthData(authData).getOrThrow()
            Logger.business(LogTags.AUTH, "✅ AUTH-UPDATE: Auth data updated successfully for ${authData.email}")
            
            // Note: Calendar authorization is now handled by AuthViewModel.requestCalendarAuthorization()
            // to prevent duplicate authorization attempts
        }
    }
    
    /**
     * MODERN: Requests Calendar API authorization for signed-in user
     * 
     * @param userEmail Optional email address (uses current user if null)
     * @return Result with Boolean (true if authorized) or error
     */
    override suspend fun requestCalendarAuthorization(userEmail: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.requestCalendarAuthorization") {
            val emailToUse = userEmail ?: run {
                val currentAuth = authDataStoreRepository.getCurrentAuthData().getOrNull()
                currentAuth?.email ?: throw Exception("No user email available for Calendar authorization")
            }
            
            Logger.business(LogTags.AUTH, "🔐 MODERN-TOKEN: Requesting Calendar authorization for user: $emailToUse")
            
            val calendarAuthResult = oauth2TokenManager.authorize(emailToUse)
            if (calendarAuthResult.isSuccess) {
                val tokenData = calendarAuthResult.getOrThrow()
                Logger.business(LogTags.AUTH, "✅ MODERN-TOKEN: Calendar authorization successful - real OAuth2 token obtained")
                Logger.d(LogTags.AUTH, "📊 Token details: accessToken=${tokenData.accessToken.take(20)}..., expires=${tokenData.getRemainingLifetimeMinutes()}min")
                true
            } else {
                val error = calendarAuthResult.exceptionOrNull()
                when (error) {
                    is TokenException.PendingAuthorization -> {
                        Logger.business(LogTags.AUTH, "⏳ MODERN-TOKEN: Calendar authorization pending - ${error.message}")
                        // Pending state means permission dialog was launched
                        // Return false for now - callback will handle success
                        false
                    }
                    else -> {
                        Logger.e(LogTags.AUTH, "❌ MODERN-TOKEN: Calendar authorization failed", error)
                        throw Exception("Calendar authorization failed: ${error?.message}")
                    }
                }
            }
        }
    }
    
    /**
     * CRITICAL FIX: Request Calendar authorization with Activity context for permission flow
     * 
     * This method properly handles the UserRecoverableAuthException by launching the
     * permission intent when needed.
     * 
     * @param userEmail Email address to authorize
     * @param activity Activity context for launching permission dialog
     * @param onResult Callback for authorization result
     * @return Result indicating if authorization was initiated
     */
    suspend fun requestCalendarAuthorizationWithActivity(
        userEmail: String,
        activity: Activity,
        onResult: (Boolean) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.requestCalendarAuthorizationWithActivity") {
            Logger.business(LogTags.AUTH, "🔐 FIXED-TOKEN: Requesting Calendar authorization with activity context")
            
            // onResult wird HIER NICHT gerufen - authorize() besitzt den Callback und feuert ihn
            // auf jedem seiner Wege genau einmal:
            //   Erfolg sofort        -> authorize() ruft onResult(true)
            //   Zustimmungsdialog    -> handlePermissionResult() ruft ihn nach dem Ergebnis
            //   Fehler               -> authorize()s catch ruft onResult(false)
            //
            // Vorher stand hier ein zweites onResult(true)/onResult(false). Der Callback lief
            // dadurch bei sofortigem Erfolg DOPPELT, und mit ihm alles, was daran haengt: der
            // AlarmMaintenanceService wurde zweimal gestartet, zwei Wartungszyklen teilten sich
            // einen CoroutineScope, und der erste, der fertig wurde, riss ueber stopSelf() ->
            // onDestroy() -> scope.cancel() den anderen mitten in der Arbeit ab
            // (JobCancellationException, Log vom 14.07. 22:07:30). Fuer eine Wecker-App ist das
            // gefaehrlich: ein so abgeschnittener Zyklus koennte gerade Alarme anlegen.
            val authResult = oauth2TokenManager.authorize(
                userEmail,
                activity,
                onResult
            )

            if (authResult.isSuccess) {
                Logger.business(LogTags.AUTH, "✅ FIXED-TOKEN: Calendar authorization successful")
            } else {
                val error = authResult.exceptionOrNull()
                when (error) {
                    is TokenException.PendingAuthorization -> {
                        Logger.business(LogTags.AUTH, "⏳ FIXED-TOKEN: Calendar authorization pending user permission")
                        // Callback will be triggered by onActivityResult
                    }
                    else -> {
                        Logger.e(LogTags.AUTH, "❌ FIXED-TOKEN: Calendar authorization failed", error)
                        // Der Wurf treibt das Result-Failure, das AuthViewModel in seinem
                        // fold(onFailure) auswertet und als Fehlermeldung zeigt.
                        throw Exception(error?.message ?: "Authorization failed")
                    }
                }
            }
        }
    }
    
    /**
     * MODERN: Checks if Calendar authorization is available
     * 
     * @return Result with Boolean (true if calendar access authorized) or error
     */
    override suspend fun hasCalendarAuthorization(): Result<Boolean> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.hasCalendarAuthorization") {
            val tokenResult = oauth2TokenManager.getValidToken()
            tokenResult.isSuccess
        }
    }
    
    /**
     * Meldet ab und lässt nichts zurück, womit die App weiter auf den Kalender käme.
     *
     * WAS VORHER FEHLTE: Diese Methode (damals clearAuthData) räumte nur den
     * authDataStoreRepository ab, und CredentialAuthManager.signOutLocally() ist eine reine
     * Log-Zeile. Das OAuth-Token überlebte die Abmeldung also im verschlüsselten Token-DataStore
     * — und im GMS-Cache, der ohnehin außerhalb des App-Speichers liegt. Zwei Folgen:
     * der Maintenance-Service konnte weiter den Kalender des abgemeldeten Kontos lesen, und wer
     * sich anschließend mit einem ANDEREN Google-Konto anmeldete, wurde von getValidToken() bis
     * zur ersten Autorisierung noch aus dem Token des alten Kontos bedient.
     *
     * REIHENFOLGE: invalidate() zuerst — es braucht den noch gespeicherten Access-Token, um
     * damit GoogleAuthUtil.clearToken() zu rufen. Nach clearAuthData() wäre der Token zwar noch
     * in seinem eigenen Store, aber die Reihenfolge so herum ist die, die auch dann hält, wenn
     * jemand die Stores später zusammenlegt.
     *
     * Scheitert das Verwerfen (z.B. kein Netz für den GMS-Teil), wird das nur geloggt: die
     * Abmeldung MUSS trotzdem durchlaufen. Ein Nutzer, der auf "Abmelden" tippt, darf nicht
     * angemeldet bleiben, weil ein Cache-Aufruf schiefging.
     *
     * DIESE FUNKTION ALLEIN IST KEIN VOLLSTAENDIGES ABMELDEN. Sie verwirft die Anmeldung -
     * gestellte Wecker, Schichtspannen, 6h-Wartung, Dimmer-/DND-Tick, Hue-Planung und
     * Pre-Alarm-Refresh raeumt `AuthViewModel.signOut()` weg, und zwar VOR diesem Aufruf
     * (`stopScheduledWorkForSignOut()`, Pruefrunde 8 / Befund 3). Wer `signOut()` von einer
     * neuen Stelle aus ruft, ohne dort ebenfalls aufzuraeumen, stellt genau den Befund wieder
     * her: armierte Wecker eines abgemeldeten Kontos, die der `BootReceiver` nach jedem Neustart
     * erneut scharf macht - und die App zeigt danach nur noch den Anmeldebildschirm, also keine
     * Oberflaeche mehr, ueber die sich das abstellen liesse.
     *
     * WARUM DAS AUFRAEUMEN NICHT HIER LIEGT: Sein Ergebnis muss den Nutzer erreichen ("es
     * koennen Wecker zurueckgeblieben sein"), ohne die Bedeutung des Rueckgabewerts zu
     * verbiegen. `Result<Unit>` aus [IAuthUseCase.signOut] heisst "die Abmeldung selbst ist
     * gelungen"; ein gescheitertes Aufraeumen darf daraus KEIN Failure machen, denn dann bliebe
     * der Nutzer laut Aufrufer angemeldet. Ein zweites Ergebnis passt nicht in diese Signatur -
     * also orchestriert die Schicht, die ohnehin den Fehlerzustand der Oberflaeche haelt.
     */
    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.signOut") {
            oauth2TokenManager.invalidate().onFailure { error ->
                Logger.w(LogTags.AUTH, "⚠️ Kalender-Token beim Abmelden nicht sauber verworfen", error)
            }
            authDataStoreRepository.clearAuthData().getOrThrow()
            Logger.business(LogTags.AUTH, "Abgemeldet - Auth-Daten und Kalender-Token verworfen")
        }
    }
    
    override suspend fun isAuthenticated(): Result<Boolean> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.isAuthenticated") {
            authDataStoreRepository.isAuthenticated().getOrThrow()
        }
    }
    
    override suspend fun getCurrentAuthData(): Result<AuthData> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.getCurrentAuthData") {
            authDataStoreRepository.getCurrentAuthData().getOrThrow()
        }
    }
    
    override suspend fun migrateTokenExpiryIfNeeded(): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.migrateTokenExpiryIfNeeded") {
            authDataStoreRepository.migrateTokenExpiryIfNeeded().getOrThrow()
            Logger.d(LogTags.DATASTORE, "Token expiry migration completed")
        }
    }
}
