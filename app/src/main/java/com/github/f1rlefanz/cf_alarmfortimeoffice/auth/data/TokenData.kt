package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Enhanced Token Data Model with Token Rotation and Provider Support
 * 
 * Features (Modernisierung 2025):
 * - ✅ Explizites googleAccountEmail Feld
 * - ✅ Token Provider Enum (klar dokumentiert)
 * - ✅ Token Rotation Support (Security)
 */
@Serializable
data class TokenData(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long,
    val scope: String,
    val tokenType: String = "Bearer",
    val issuedAt: Long = System.currentTimeMillis(),
    
    // Google Account Email für Token-Refresh via Play Services
    val googleAccountEmail: String? = null,
    val tokenProvider: TokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES,
    
    // Token Rotation Support
    val rotationId: String = UUID.randomUUID().toString(),
    val previousRotationId: String? = null,
    val rotationCount: Int = 0,
    val lastRotationAt: Long = System.currentTimeMillis()
) {
    
    /**
     * Checks if token is expired or will expire within buffer time.
     */
    fun isExpiredOrExpiringSoon(bufferMinutes: Long = TOKEN_REFRESH_BUFFER_MINUTES): Boolean {
        val bufferMs = bufferMinutes * 60 * 1000
        val currentTime = System.currentTimeMillis()
        val effectiveExpirationTime = expiresAt - bufferMs
        
        val isExpiring = currentTime >= effectiveExpirationTime
        
        if (isExpiring) {
            Logger.d(LogTags.AUTH, "Token expiring soon: current=${formatTimestamp(currentTime)}, expires=${formatTimestamp(expiresAt)}, buffer=${bufferMinutes}min")
        }
        
        return isExpiring
    }
    
    /**
     * Checks if token is completely valid and not expiring soon.
     */
    fun isValid(): Boolean {
        val hasValidAccessToken = accessToken.isNotBlank()
        val notExpiring = !isExpiredOrExpiringSoon()
        return hasValidAccessToken && notExpiring
    }
    
    /**
     * Checks if refresh is possible.
     */
    fun canRefresh(): Boolean {
        return when (tokenProvider) {
            TokenProvider.GOOGLE_PLAY_SERVICES -> !googleAccountEmail.isNullOrBlank()
            TokenProvider.OAUTH2_STANDARD -> !refreshToken.isNullOrBlank()
        }
    }
    
    /**
     * Gets remaining lifetime in minutes.
     */
    fun getRemainingLifetimeMinutes(): Long {
        val remainingMs = expiresAt - System.currentTimeMillis()
        return if (remainingMs > 0) remainingMs / (60 * 1000) else 0
    }
    
    /**
     * ✅ NEU: Creates rotated token with new access token
     */
    fun rotate(newAccessToken: String, newExpiresAt: Long = System.currentTimeMillis() + 3600000): TokenData {
        return copy(
            accessToken = newAccessToken,
            expiresAt = newExpiresAt,
            rotationId = UUID.randomUUID().toString(),
            previousRotationId = this.rotationId,
            rotationCount = this.rotationCount + 1,
            lastRotationAt = System.currentTimeMillis()
        )
    }
    
    /**
     * ✅ NEU: Validates rotation chain for security
     */
    fun validateRotation(expectedPreviousId: String?): Boolean {
        return previousRotationId == expectedPreviousId
    }

    /**
     * Ist DIESES (soeben aus dem Store gelesene) Token ein legitimer Nachfolger von [previous],
     * dem Stand, den der Aufrufer zuletzt kannte?
     *
     * Drei legitime Faelle - der dritte fehlte und hat einen frisch geholten, gueltigen Token
     * geloescht:
     *  1. Identisch: seit dem Lesen von [previous] hat niemand rotiert (Normalfall).
     *  2. Direkt aus [previous] rotiert: ein gleichzeitiger Aufrufer war schneller
     *     ([OAuth2TokenManager] ist ein @Singleton ohne Mutex, und AlarmMaintenanceService,
     *     CalendarPreAlarmRefreshWorker, CalendarUseCase sowie AuthUseCase rufen unabhaengig) -
     *     die Kette ist intakt, nur zeitlich ueberholt.
     *  3. FRISCHE NEU-AUTORISIERUNG: `authorize()` speichert ein ueber
     *     [Companion.fromOAuthResponse] gebautes Token; das rotiert NICHT, sondern beginnt eine
     *     neue Kette (previousRotationId = null, rotationCount = 0). Es stammt damit
     *     zwangslaeufig nicht von [previous] ab. Landete dieser Write zwischen dem Lesen von
     *     [previous] und dieser Pruefung, galt er als Kettenbruch - und der Diebstahls-Zweig
     *     loeschte ausgerechnet das Token, das der Nutzer sich soeben per
     *     "Kalender-Zugriff erneuern" geholt hatte. Danach zeigte die Oberflaeche weiter
     *     "angemeldet", waehrend jeder Wartungslauf ohne Token abbrach.
     *
     * Alles andere ist ein echter Kettenbruch: ein Token, das weder von dem abstammt, was dieser
     * Aufrufer kannte, noch eine erkennbar neuere Anmeldung ist.
     */
    fun isLegitimateSuccessorOf(previous: TokenData): Boolean {
        if (rotationId == previous.rotationId) return true
        if (validateRotation(previous.rotationId)) return true
        val isFreshAuthorization = previousRotationId == null && rotationCount == 0
        return isFreshAuthorization && lastRotationAt >= previous.lastRotationAt
    }



    /**
     * Creates a sanitized version for logging.
     */
    fun toLogString(): String {
        val truncatedAccessToken = if (accessToken.length > 10) {
            "${accessToken.take(6)}...${accessToken.takeLast(4)}"
        } else {
            "***"
        }
        
        return "TokenData(" +
                "accessToken=$truncatedAccessToken, " +
                "hasRefreshToken=${!refreshToken.isNullOrBlank()}, " +
                "provider=$tokenProvider, " +
                "expiresAt=${formatTimestamp(expiresAt)}, " +
                "remainingMin=${getRemainingLifetimeMinutes()}, " +
                "rotations=$rotationCount)"
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        return try {
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()
            )
            dateTime.format(DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME_SECONDS))
        } catch (_: Exception) {
            "Invalid: $timestamp"
        }
    }
    
    companion object {
        const val TOKEN_REFRESH_BUFFER_MINUTES = 15L
        
        /**
         * Creates TokenData from OAuth2 response.
         */
        fun fromOAuthResponse(
            accessToken: String,
            refreshToken: String?,
            expiresInSeconds: Long,
            scope: String,
            googleAccountEmail: String? = null,
            tokenProvider: TokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES
        ): TokenData {
            val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
            
            return TokenData(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                scope = scope,
                googleAccountEmail = googleAccountEmail,
                tokenProvider = tokenProvider
            )
        }
        
        /**
         * Creates an empty/invalid token.
         */
        fun empty(): TokenData = TokenData(
            accessToken = "",
            refreshToken = null,
            expiresAt = 0,
            scope = ""
        )
    }
}

/**
 * ✅ NEU: Token Provider Enum
 * 
 * Dokumentiert klar welcher OAuth2-Flow verwendet wird
 */
@Serializable
enum class TokenProvider {
    /**
     * Google Play Services Flow:
     * - GoogleAuthUtil.clearToken() + getToken()
     * - Kein separater Refresh Token
     * - Benötigt googleAccountEmail
     */
    GOOGLE_PLAY_SERVICES,
    
    /**
     * Standard OAuth2 Flow:
     * - Verwendet Refresh Token
     * - Standard-konform
     */
    OAUTH2_STANDARD
}
