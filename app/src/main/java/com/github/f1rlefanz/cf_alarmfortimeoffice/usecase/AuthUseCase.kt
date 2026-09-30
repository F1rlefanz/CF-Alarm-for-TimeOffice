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
 * 1. Credential Manager für Benutzer-Authentifizierung (wer bist du?)
 * 2. OAuth2TokenManager für API-Autorisierung (was darfst du?)
 */
class AuthUseCase @Inject constructor(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val oauth2TokenManager: OAuth2TokenManager
) : IAuthUseCase {
    
    override val authData: Flow<AuthData> = authDataStoreRepository.authData

    /**
     * Requests Calendar API authorization for signed-in user
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
                Logger.business(LogTags.AUTH, "✅ MODERN-TOKEN: Calendar authorization successful - real OAuth2 token obtained")
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
     * Request Calendar authorization with Activity context for permission flow
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
     * Checks if Calendar authorization is available
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
     * REIHENFOLGE: invalidate() zuerst - es braucht den noch gespeicherten Access-Token für
     * GoogleAuthUtil.clearToken(). Scheitert das Verwerfen, wird das nur geloggt: die Abmeldung
     * MUSS trotzdem durchlaufen.
     *
     * Einzige Fehlerquelle ist `clearAuthData()`. Ein Failure heisst deshalb "Token weg,
     * Auth-Daten noch da" - der Aufrufer MUSS ihn genauso behandeln wie den Erfolg.
     *
     * ALLEIN KEIN VOLLSTAENDIGES ABMELDEN: `AuthViewModel.signOut()` raeumt danach in beiden
     * Zweigen Wecker und Hintergrundarbeit ab (`stopScheduledWorkForSignOut()`); jede neue
     * Aufrufstelle muss ebenso raeumen. Aufruf UND Aufraeumen legt der Aufrufer zusammen in
     * `withContext(NonCancellable)`. Das Aufraeumen liegt beim Aufrufer, weil `Result<Unit>`
     * nur "die Abmeldung selbst ist gelungen" meldet und kein zweites Ergebnis traegt.
     *
     * Hergang: Skill cfalarm-persistenz-und-auth, reference/auth-und-token.md.
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
}
