package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data

import kotlinx.serialization.Serializable
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Data class representing OAuth2 token information with automatic expiration handling.
 * Follows immutable design pattern for thread safety.
 */
@Serializable
data class TokenData(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long, // Unix timestamp in milliseconds
    val scope: String,
    val tokenType: String = "Bearer",
    val issuedAt: Long = System.currentTimeMillis()
) {
    
    /**
     * Checks if token is expired or will expire within buffer time.
     * @param bufferMinutes Buffer time before actual expiration (default: 15 minutes)
     * @return true if token should be refreshed
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
     * @return true if token can be used safely
     */
    fun isValid(): Boolean {
        val hasValidAccessToken = accessToken.isNotBlank()
        val notExpiring = !isExpiredOrExpiringSoon()
        
        return hasValidAccessToken && notExpiring
    }
    
    /**
     * Checks if refresh is possible (refresh token exists).
     * @return true if token can be refreshed
     */
    fun canRefresh(): Boolean = !refreshToken.isNullOrBlank()
    
    /**
     * Gets remaining lifetime in minutes.
     * @return minutes until expiration, or 0 if already expired
     */
    fun getRemainingLifetimeMinutes(): Long {
        val remainingMs = expiresAt - System.currentTimeMillis()
        return if (remainingMs > 0) remainingMs / (60 * 1000) else 0
    }
    
    /**
     * Creates a copy with new access token (typically after refresh).
     * Preserves refresh token and updates expiration time.
     */
    fun withRefreshedAccessToken(
        newAccessToken: String,
        newExpiresAt: Long,
        newScope: String? = null
    ): TokenData = copy(
        accessToken = newAccessToken,
        expiresAt = newExpiresAt,
        scope = newScope ?: scope,
        issuedAt = System.currentTimeMillis()
    )
    
    /**
     * Creates a sanitized version for logging (access token shortened).
     * NEVER logs refresh token for security.
     */
    fun toLogString(): String {
        val truncatedAccessToken = if (accessToken.length > 10) {
            "${accessToken.take(6)}...${accessToken.takeLast(4)}"
        } else {
            "***"
        }
        
        return "TokenData(accessToken=$truncatedAccessToken, " +
                "hasRefreshToken=${!refreshToken.isNullOrBlank()}, " +
                "expiresAt=${formatTimestamp(expiresAt)}, " +
                "scope=$scope, " +
                "remainingMin=${getRemainingLifetimeMinutes()})"
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        return try {
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()
            )
            dateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
        } catch (e: Exception) {
            "Invalid timestamp: $timestamp"
        }
    }
    
    companion object {
        const val TOKEN_REFRESH_BUFFER_MINUTES = 15L
        
        /**
         * Creates TokenData from OAuth2 response parameters.
         * @param accessToken The access token
         * @param refreshToken The refresh token (optional)
         * @param expiresInSeconds Token lifetime in seconds
         * @param scope Token scope
         * @return TokenData instance
         */
        fun fromOAuthResponse(
            accessToken: String,
            refreshToken: String?,
            expiresInSeconds: Long,
            scope: String
        ): TokenData {
            val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
            
            return TokenData(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                scope = scope
            )
        }
        
        /**
         * Creates an empty/invalid token for initialization.
         */
        fun empty(): TokenData = TokenData(
            accessToken = "",
            refreshToken = null,
            expiresAt = 0,
            scope = ""
        )
    }
}
