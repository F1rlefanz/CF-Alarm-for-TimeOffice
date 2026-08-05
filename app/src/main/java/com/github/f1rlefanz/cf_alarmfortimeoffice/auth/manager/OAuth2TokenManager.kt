package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager

import android.app.Activity
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simplified OAuth2 Token Manager
 * 
 * Fokussiert auf OAuth2-Logic, delegiert Storage an TokenRepository.
 * 
 * Verbesserungen gegenüber ModernOAuth2TokenManager:
 * - ✅ Single Responsibility: Nur OAuth2-Operations
 * - ✅ Dependency Injection: TokenRepository wird injiziert
 * - ✅ Keine Workarounds: Saubere Implementation
 * - ✅ Proper Exception Handling: CancellationException wird propagiert
 * - ✅ Token Rotation: Security-Feature integriert
 */
class OAuth2TokenManager(
    private val context: Context,
    private val tokenRepository: TokenRepository
) {
    
    companion object {
        const val REQUEST_CODE_CALENDAR_AUTHORIZATION = 1001
    }
    
    @Volatile
    private var pendingAuthCallback: ((Boolean) -> Unit)? = null

    // Email der schwebenden Autorisierung: nötig, um nach erteilter Zustimmung den Token
    // per erneutem getToken()-Aufruf tatsächlich abzuholen (siehe handlePermissionResult).
    @Volatile
    private var pendingAuthEmail: String? = null
    
    /**
     * Lädt gültiges Token, refresht automatisch wenn nötig
     */
    suspend fun getValidToken(): Result<TokenData> = withContext(Dispatchers.IO) {
        try {
            val currentToken = tokenRepository.get()
            
            when {
                // Kein Token vorhanden
                currentToken == null -> {
                    Logger.w(LogTags.TOKEN, "No token available - authorization required")
                    Result.failure(TokenException.NoTokenAvailable("Authorization required"))
                }
                
                // Token ist noch gültig
                currentToken.isValid() -> {
                    Logger.d(LogTags.TOKEN, "✅ Token is valid (${currentToken.getRemainingLifetimeMinutes()}min remaining)")
                    Result.success(currentToken)
                }
                
                // Token ist abgelaufen aber refresh möglich
                currentToken.canRefresh() -> {
                    Logger.i(LogTags.TOKEN, "🔄 Token expired, attempting refresh...")
                    refresh(currentToken)
                }
                
                // Token abgelaufen und refresh nicht möglich
                else -> {
                    Logger.w(LogTags.TOKEN, "❌ Token expired and cannot be refreshed")
                    Result.failure(TokenException.AuthorizationExpired("Re-authorization required"))
                }
            }
            
        } catch (e: CancellationException) {
            throw e  // ✅ KRITISCH: Propagieren!
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error getting valid token", e)
            Result.failure(e)
        }
    }
    
    /**
     * Autorisiert Calendar-Zugriff
     */
    suspend fun authorize(
        userEmail: String,
        activity: Activity? = null,
        onResult: ((Boolean) -> Unit)? = null
    ): Result<TokenData> = withContext(Dispatchers.IO) {
        try {
            Logger.d(LogTags.TOKEN, "🔐 Starting Calendar authorization for: $userEmail")
            
            val account = android.accounts.Account(userEmail, "com.google")
            val scope = "oauth2:${CalendarScopes.CALENDAR_READONLY}"
            
            val accessToken = try {
                GoogleAuthUtil.getToken(context, account, scope)
            } catch (e: UserRecoverableAuthException) {
                // User Permission required
                if (activity != null) {
                    Logger.i(LogTags.TOKEN, "🚀 Launching permission dialog...")
                    pendingAuthCallback = onResult
                    pendingAuthEmail = userEmail

                    withContext(Dispatchers.Main) {
                        activity.startActivityForResult(
                            e.intent,
                            REQUEST_CODE_CALENDAR_AUTHORIZATION
                        )
                    }
                    
                    return@withContext Result.failure(
                        TokenException.PendingAuthorization("Permission dialog launched")
                    )
                } else {
                    Logger.e(LogTags.TOKEN, "❌ No activity context for permission dialog")
                    return@withContext Result.failure(
                        TokenException.NoActivityContext("Activity required for authorization", e.intent)
                    )
                }
            }
            
            if (accessToken.isBlank()) {
                Logger.e(LogTags.TOKEN, "❌ Empty access token received")
                return@withContext Result.failure(TokenException.AuthorizationFailed("Empty token"))
            }
            
            Logger.i(LogTags.TOKEN, "✅ Access token obtained successfully")
            
            // Create TokenData
            val tokenData = TokenData.fromOAuthResponse(
                accessToken = accessToken,
                refreshToken = null,  // Google Play Services manages this
                expiresInSeconds = 3600,
                scope = CalendarScopes.CALENDAR_READONLY,
                googleAccountEmail = userEmail,
                tokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES
            )
            
            // Save token
            val saveResult = tokenRepository.save(tokenData)
            if (saveResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ Failed to save token", saveResult.exceptionOrNull())
                return@withContext Result.failure(
                    TokenException.StorageFailed("Failed to save token")
                )
            }
            
            Logger.i(LogTags.TOKEN, "✅ Token saved successfully")
            onResult?.invoke(true)
            
            Result.success(tokenData)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Authorization failed", e)
            onResult?.invoke(false)
            Result.failure(TokenException.AuthorizationFailed(e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Verwirft das Token ENDGUELTIG - lokal UND im GoogleAuthUtil-Cache der Play Services.
     *
     * WARUM BEIDES: Der GoogleAuthUtil-Cache liegt in den Play Services, nicht im App-Speicher.
     * Er wird ohne Server-Rueckfrage bedient. Wurde der Zugriff serverseitig entzogen, merkt
     * GMS davon nichts und gibt weiter munter das tote Token heraus - ohne
     * Zustimmungsdialog, weil es fuer GMS ja gueltig aussieht. Nur clearToken() raeumt diesen
     * Cache ab. Danach liefert der naechste getToken() NEED_REMOTE_CONSENT, der Dialog kommt,
     * und der Nutzer landet wieder in einem funktionierenden Zustand.
     *
     * Ohne diesen Aufruf dreht sich die App im Kreis: 401 -> neu laden -> dasselbe tote Token
     * aus dem Cache -> 401 -> ...
     *
     * Fehler beim Leeren werden nur geloggt: das lokale Token MUSS trotzdem weg, sonst
     * benutzt die App weiter ein Token, von dem wir sicher wissen, dass es tot ist.
     */
    suspend fun invalidate(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val current = tokenRepository.get()
            if (current != null && current.accessToken.isNotBlank()) {
                try {
                    GoogleAuthUtil.clearToken(context, current.accessToken)
                    Logger.i(LogTags.TOKEN, "🧹 GoogleAuthUtil-Cache geleert (Play Services)")
                } catch (e: Exception) {
                    // z.B. kein Netz: der lokale Teil muss trotzdem laufen.
                    Logger.w(LogTags.TOKEN, "⚠️ GoogleAuthUtil-Cache konnte nicht geleert werden", e)
                }
            }
            tokenRepository.clear()
            Logger.i(LogTags.TOKEN, "🧹 Gespeichertes Token verworfen - Neuanmeldung erforderlich")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Token konnte nicht verworfen werden", e)
            Result.failure(e)
        }
    }

    /**
     * Refresht Token mit Rotation-Support
     */
    suspend fun refresh(currentToken: TokenData): Result<TokenData> = withContext(Dispatchers.IO) {
        try {
            Logger.i(LogTags.TOKEN, "🔄 Starting token refresh...")
            
            // Validate rotation chain (Security Check)
            //
            // Zwei legitime Faelle, KEIN Security Violation:
            // 1) storedToken.rotationId == currentToken.rotationId: seit dem Einlesen von
            //    currentToken hat niemand sonst rotiert - Normalfall.
            // 2) storedToken.validateRotation(currentToken.rotationId) == true, d.h.
            //    storedToken.previousRotationId == currentToken.rotationId: ein GLEICHZEITIGER
            //    Aufrufer (z.B. AlarmMaintenanceService und CalendarPreAlarmRefreshWorker teilen
            //    sich diesen @Singleton ohne Mutex) hat currentToken bereits erfolgreich zu
            //    storedToken rotiert - die Kette ist intakt, nur zeitlich ueberholt.
            //
            // Nur wenn storedToken WEDER identisch mit currentToken ist NOCH direkt aus ihm
            // hervorgegangen ist, handelt es sich um eine echte Ketten-Verletzung (Replay/Diebstahl):
            // ein Token, das nicht von dem abstammt, was dieser Aufrufer zuletzt kannte.
            val storedToken = tokenRepository.get()
            if (storedToken != null) {
                val isSameToken = storedToken.rotationId == currentToken.rotationId
                val isLegitimateConcurrentRotation = storedToken.validateRotation(currentToken.rotationId)
                if (!isSameToken && !isLegitimateConcurrentRotation) {
                    Logger.e(LogTags.TOKEN, "⚠️ Token rotation chain broken - possible theft!")
                    tokenRepository.clear()
                    return@withContext Result.failure(
                        TokenException.SecurityViolation("Token rotation invalid")
                    )
                }
            }
            
            // Refresh based on provider
            val newAccessToken = when (currentToken.tokenProvider) {
                TokenProvider.GOOGLE_PLAY_SERVICES -> refreshViaGooglePlayServices(currentToken)
                TokenProvider.OAUTH2_STANDARD -> refreshViaOAuth2Standard(currentToken)
            }
            
            // Create rotated token
            val rotatedToken = currentToken.rotate(
                newAccessToken = newAccessToken,
                newExpiresAt = System.currentTimeMillis() + 3600000
            )
            
            // Save rotated token
            val saveResult = tokenRepository.save(rotatedToken)
            if (saveResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ Failed to save rotated token", saveResult.exceptionOrNull())
                return@withContext Result.failure(
                    TokenException.StorageFailed("Failed to save token")
                )
            }
            
            Logger.i(LogTags.TOKEN, "✅ Token refreshed and rotated (rotation #${rotatedToken.rotationCount})")

            Result.success(rotatedToken)

        } catch (e: CancellationException) {
            throw e  // ✅ KRITISCH: Propagieren!
        } catch (e: TokenException.ConsentRequired) {
            // Das gespeicherte Token ist endgueltig tot - Google akzeptiert es nicht mehr und
            // wird es auch beim naechsten Versuch nicht akzeptieren. Es MUSS weg, sonst laeuft
            // jeder weitere getValidToken() erneut in canRefresh() -> refresh() -> dieselbe
            // Exception: eine Endlosschleife ohne Ausweg.
            //
            // Nach dem Loeschen meldet getValidToken() sauber NoTokenAvailable, und die App
            // faellt in denselben regulaeren Sign-in-Pfad, der bei einer Neuinstallation
            // funktioniert (dort gibt es schlicht kein Token, das im Weg steht).
            Logger.w(LogTags.TOKEN, "🔐 Zustimmung entzogen - verwerfe totes Token und verlange Neuanmeldung")
            invalidate()
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Token refresh failed", e)
            Result.failure(TokenException.RefreshFailed(e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Refresh via Google Play Services
     * NOTE: GoogleAuthUtil.getToken() is a blocking call, not a suspend function
     */
    private fun refreshViaGooglePlayServices(token: TokenData): String {
        require(token.tokenProvider == TokenProvider.GOOGLE_PLAY_SERVICES) {
            "Token provider must be GOOGLE_PLAY_SERVICES"
        }
        require(!token.googleAccountEmail.isNullOrBlank()) {
            "googleAccountEmail is required for Google Play Services refresh"
        }

        try {
            // Clear old token
            GoogleAuthUtil.clearToken(context, token.accessToken)
            Logger.d(LogTags.TOKEN, "Old token cleared")

            // Get fresh token
            val account = android.accounts.Account(token.googleAccountEmail, "com.google")
            val scope = "oauth2:${CalendarScopes.CALENDAR_READONLY}"

            val newToken = GoogleAuthUtil.getToken(context, account, scope)

            if (newToken.isBlank()) {
                throw TokenException.RefreshFailed("Empty token received")
            }

            return newToken

        } catch (e: UserRecoverableAuthException) {
            // "Recoverable" ist woertlich zu nehmen: die Exception traegt einen Intent, der zum
            // Zustimmungsdialog fuehrt. Frueher fing der generische catch-Block unten sie mit ab
            // und warf sie als RefreshFailed weiter - der Intent ging verloren und die App hatte
            // keinen Weg zurueck. Typischer Ausloeser: Zugriff im Google-Konto entzogen
            // ("NeedRemoteConsent").
            Logger.w(LogTags.TOKEN, "🔐 Google verlangt erneute Zustimmung: ${e.message}")
            throw TokenException.ConsentRequired(
                "Erneute Zustimmung erforderlich: ${e.message}",
                e.intent
            )
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Google Play Services refresh failed", e)
            throw TokenException.RefreshFailed("Google refresh failed: ${e.message}")
        }
    }
    
    /**
     * Refresh via standard OAuth2 (not needed - using Google Play Services)
     */
    private fun refreshViaOAuth2Standard(token: TokenData): String {
        require(token.tokenProvider == TokenProvider.OAUTH2_STANDARD) {
            "Token provider must be OAUTH2_STANDARD"
        }
        require(!token.refreshToken.isNullOrBlank()) {
            "refreshToken is required for OAuth2 standard refresh"
        }
        
        // Not implemented - app uses Google Play Services OAuth2
        throw NotImplementedError("OAuth2 standard refresh not needed - using Google Play Services")
    }
    
    /**
     * Handles activity result from permission dialog.
     *
     * KRITISCH: Eine erteilte Zustimmung (RESULT_OK) liefert noch KEINEN Token. Der erste
     * GoogleAuthUtil.getToken()-Aufruf warf UserRecoverableAuthException nur, um den Consent-Screen
     * auszulösen – der Token entsteht erst, wenn getToken() JETZT (mit vorliegender Zustimmung)
     * erneut aufgerufen wird. Deshalb starten wir authorize() hier noch einmal (activity = null,
     * damit kein weiterer Dialog aufpoppen kann) und melden Erfolg erst, wenn der Token wirklich
     * abgeholt und gespeichert wurde. Ohne diesen zweiten Aufruf bliebe der Token-Store leer und
     * jeder folgende getValidToken() scheiterte mit "No token available".
     */
    suspend fun handlePermissionResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE_CALENDAR_AUTHORIZATION) {
            return false
        }

        val callback = pendingAuthCallback
        val email = pendingAuthEmail
        pendingAuthCallback = null
        pendingAuthEmail = null

        if (resultCode != Activity.RESULT_OK) {
            Logger.i(LogTags.TOKEN, "Permission result: DENIED")
            callback?.invoke(false)
            return false
        }

        if (email.isNullOrBlank()) {
            Logger.e(LogTags.TOKEN, "❌ Permission granted, aber keine pending-Email gecached – Token kann nicht abgeholt werden")
            callback?.invoke(false)
            return false
        }

        Logger.i(LogTags.TOKEN, "Permission result: GRANTED – Token wird mit erteilter Zustimmung abgeholt")

        // Zustimmung liegt jetzt vor → dieser Aufruf erreicht den Save-Pfad statt erneut zu werfen.
        val tokenResult = authorize(userEmail = email, activity = null, onResult = null)
        val success = tokenResult.isSuccess

        if (success) {
            Logger.i(LogTags.TOKEN, "✅ Token nach Zustimmung erhalten und gespeichert")
        } else {
            Logger.e(LogTags.TOKEN, "❌ Token-Abruf nach erteilter Zustimmung fehlgeschlagen", tokenResult.exceptionOrNull())
        }

        callback?.invoke(success)
        return success
    }
}
