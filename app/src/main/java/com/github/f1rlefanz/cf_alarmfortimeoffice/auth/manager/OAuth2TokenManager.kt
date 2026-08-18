package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
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
    private val tokenRepository: TokenRepository,
    private val pendingAuthStore: PendingAuthStore = SharedPrefsPendingAuthStore(context)
) {
    
    companion object {
        // WARUM DIESE ZAHL EXKLUSIV SEIN MUSS: BatteryOptimizationHelper.
        // REQUEST_CODE_BATTERY_EXEMPTION war zahlengleich (1001). Beide starten ueber
        // startActivityForResult DERSELBEN MainActivity, und deren einzige Verzweigung prueft
        // genau auf diesen Code - die Rueckkehr aus dem Akku-Ausnahme-Dialog landete deshalb in
        // handlePermissionResult() und wurde als abgelehnte Kalender-Autorisierung gemeldet.
        // Ein neuer Legacy-Request-Code im Modul muss gegen beide Konstanten geprueft werden.
        const val REQUEST_CODE_CALENDAR_AUTHORIZATION = 1001
    }
    
    @Volatile
    private var pendingAuthCallback: ((Boolean) -> Unit)? = null

    // Email der schwebenden Autorisierung: nötig, um nach erteilter Zustimmung den Token
    // per erneutem getToken()-Aufruf tatsächlich abzuholen (siehe handlePermissionResult).
    //
    // NUR DER SCHNELLE WEG: Waehrend der Zustimmungsdialog vorne steht, ist CF-Alarm im
    // Hintergrund und darf beendet werden. Android stellt Activity und Activity-Result danach
    // im NEUEN Prozess zu - dieses Feld ist dann null. Die Wahrheit liegt deshalb zusaetzlich
    // in [pendingAuthStore]; gelesen wird ueber [consumePendingAuthEmail].
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
                    rememberPendingAuth(userEmail)

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
            // Welche Faelle legitim sind (identisch / direkt rotiert / frische Neu-Autorisierung)
            // und warum, steht bei [TokenData.isLegitimateSuccessorOf] - bewusst dort, weil es eine
            // reine Entscheidung ueber zwei Tokens ist und in TokenDataTest festgehalten wird.
            val storedToken = tokenRepository.get()
            if (storedToken != null) {
                if (!storedToken.isLegitimateSuccessorOf(currentToken)) {
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
     *
     * DREI ERGEBNISSE STATT EINES BOOLEAN (siehe [CalendarPermissionOutcome]): frueher hiess
     * "nicht erfolgreich" fuer den Aufrufer immer "der Nutzer hat abgelehnt". Nach einem
     * Prozesstod waehrend des Zustimmungsdialogs ist der Merker aber nur verloren - die App weiss
     * dann nichts. "Unbekannt" heisst deshalb: nachsehen, ob ein Token da ist, und weder Erfolg
     * noch Ablehnung behaupten.
     */
    suspend fun handlePermissionResult(requestCode: Int, resultCode: Int): CalendarPermissionOutcome {
        if (requestCode != REQUEST_CODE_CALENDAR_AUTHORIZATION) {
            // Fremdes Ergebnis: NICHTS anfassen, insbesondere keinen Merker verbrauchen.
            return CalendarPermissionOutcome.UNKNOWN
        }

        val callback = pendingAuthCallback
        pendingAuthCallback = null
        val email = withContext(Dispatchers.IO) { consumePendingAuthEmail() }

        if (resultCode != Activity.RESULT_OK) {
            Logger.i(LogTags.TOKEN, "Permission result: DENIED")
            callback?.invoke(false)
            return CalendarPermissionOutcome.DENIED
        }

        if (email.isNullOrBlank()) {
            // UNBEKANNT IST KEINE ABLEHNUNG: der Merker fehlt in beiden Ablagen (z.B. weil der
            // Prozess starb, bevor er geschrieben werden konnte). Statt eine Verweigerung zu
            // behaupten, die niemand ausgesprochen hat, wird die nachpruefbare Quelle befragt -
            // liegt ein gueltiges Token vor, ist die Zustimmung erteilt.
            val existingToken = runCatching { tokenRepository.get() }.getOrNull()
            return if (existingToken != null && existingToken.isValid()) {
                Logger.i(LogTags.TOKEN, "Permission result: kein Merker, aber gueltiges Token vorhanden")
                callback?.invoke(true)
                grantedOutcome(callback)
            } else {
                Logger.w(LogTags.TOKEN, "Permission result: UNBEKANNT - weder Merker noch gueltiges Token")
                callback?.invoke(false)
                CalendarPermissionOutcome.UNKNOWN
            }
        }

        Logger.i(LogTags.TOKEN, "Permission result: GRANTED – Token wird mit erteilter Zustimmung abgeholt")

        // Zustimmung liegt jetzt vor → dieser Aufruf erreicht den Save-Pfad statt erneut zu werfen.
        val tokenResult = authorize(userEmail = email, activity = null, onResult = null)
        if (tokenResult.isFailure) {
            // Zugestimmt, aber der Abruf scheiterte (Netz, Storage): das ist KEINE Ablehnung.
            // Wer es als solche meldet, fordert den Nutzer auf, eine Berechtigung zu erteilen,
            // die er gerade erteilt hat.
            Logger.e(LogTags.TOKEN, "❌ Token-Abruf nach erteilter Zustimmung fehlgeschlagen", tokenResult.exceptionOrNull())
            callback?.invoke(false)
            return CalendarPermissionOutcome.UNKNOWN
        }

        Logger.i(LogTags.TOKEN, "✅ Token nach Zustimmung erhalten und gespeichert")
        callback?.invoke(true)
        return grantedOutcome(callback)
    }

    /**
     * Fehlt der Callback, hat er den Prozesstod nicht ueberlebt. `hasValidToken` im AuthViewModel
     * haengt ausschliesslich an ihm - der Aufrufer muss den UI-Zustand dann selbst nachziehen.
     */
    private fun grantedOutcome(callback: ((Boolean) -> Unit)?): CalendarPermissionOutcome =
        if (callback == null) {
            CalendarPermissionOutcome.GRANTED_AFTER_RESTART
        } else {
            CalendarPermissionOutcome.GRANTED
        }

    /** Merkt die schwebende Autorisierung im Speicher UND persistent (Prozesstod-fest). */
    private fun rememberPendingAuth(email: String) {
        pendingAuthEmail = email
        pendingAuthStore.remember(email)
    }

    /**
     * Liest den Merker und loescht ihn in BEIDEN Ablagen - auch wenn der Speicherwert noch da war,
     * damit kein verwaister persistenter Rest den naechsten Durchlauf faelschlich beantwortet.
     *
     * `internal` + [VisibleForTesting] statt `private`: die Wiederaufnahme nach Prozesstod ist die
     * eigentliche Zusicherung dieser Klasse und laesst sich sonst nur ueber GoogleAuthUtil pruefen,
     * das im JVM-Test nicht erreichbar ist.
     */
    @VisibleForTesting
    internal fun consumePendingAuthEmail(): String? {
        val fromMemory = pendingAuthEmail
        pendingAuthEmail = null
        val persisted = pendingAuthStore.consume()
        return fromMemory?.takeIf { it.isNotBlank() } ?: persisted?.takeIf { it.isNotBlank() }
    }
}

/**
 * Ergebnis der Rueckkehr aus dem Kalender-Zustimmungsdialog.
 *
 * WARUM KEIN BOOLEAN MEHR: "nicht erfolgreich" wurde pauschal als "der Nutzer hat abgelehnt"
 * gemeldet - samt Toast und der Aufforderung, die Berechtigung in den Einstellungen zu erteilen.
 * Nach einem Prozesstod waehrend des Zustimmungsdialogs WEISS die App aber nichts, und
 * "unbekannt" darf weder als Ablehnung noch als Erfolg ausgegeben werden.
 */
enum class CalendarPermissionOutcome {
    /** Zustimmung erteilt, Token abgeholt; der wartende Callback wurde bedient. */
    GRANTED,

    /**
     * Zustimmung erteilt und Token vorhanden, aber der wartende Callback ist mit dem alten
     * Prozess gestorben. Der Aufrufer muss den Auth-Zustand der Oberflaeche nachziehen.
     */
    GRANTED_AFTER_RESTART,

    /** Der Nutzer hat abgelehnt oder abgebrochen (resultCode != RESULT_OK). */
    DENIED,

    /**
     * Nicht zuordenbar: kein Merker und kein gueltiges Token (oder ein fremder Request-Code).
     * Ausdruecklich KEINE Aussage ueber Zustimmung oder Ablehnung.
     */
    UNKNOWN
}

/**
 * Merker der schwebenden Kalender-Autorisierung. Muss den Prozesstod ueberleben: waehrend der
 * Zustimmungsdialog der Play Services vorne steht, ist CF-Alarm im Hintergrund und regulaer
 * killbar - Android stellt Activity und Activity-Result danach im neuen Prozess zu.
 */
interface PendingAuthStore {
    fun remember(email: String)

    /** Liefert den Merker und loescht ihn. Ein zweiter Aufruf liefert null. */
    fun consume(): String?
}

/**
 * SharedPreferences-Umsetzung. Der Zugriff liegt bewusst in den Methoden und NICHT in einem
 * Property-Initializer: OAuth2TokenManager haengt als @Singleton am Application-Graphen, und
 * CE-Storage ist vor der ersten Entsperrung nicht da.
 */
class SharedPrefsPendingAuthStore(private val context: Context) : PendingAuthStore {

    private fun prefs(): SharedPreferences? = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        Logger.w(LogTags.TOKEN, "Merker der schwebenden Autorisierung nicht erreichbar", e)
        null
    }

    override fun remember(email: String) {
        try {
            // commit(), nicht apply(): unmittelbar nach dieser Zeile startet der Zustimmungsdialog,
            // ab da darf der Prozess sterben. Ein noch nicht geschriebenes apply() waere verloren -
            // dieselbe Ueberlegung wie beim Snooze-Merker.
            prefs()?.edit(commit = true) { putString(KEY_EMAIL, email) }
        } catch (e: Exception) {
            Logger.w(LogTags.TOKEN, "Merker der schwebenden Autorisierung konnte nicht gesichert werden", e)
        }
    }

    override fun consume(): String? = try {
        val prefs = prefs()
        val email = prefs?.getString(KEY_EMAIL, null)
        prefs?.edit(commit = true) { remove(KEY_EMAIL) }
        email
    } catch (e: Exception) {
        Logger.w(LogTags.TOKEN, "Merker der schwebenden Autorisierung nicht lesbar", e)
        null
    }

    private companion object {
        const val PREFS_NAME = "oauth2_pending_auth"
        const val KEY_EMAIL = "pending_auth_email"
    }
}
