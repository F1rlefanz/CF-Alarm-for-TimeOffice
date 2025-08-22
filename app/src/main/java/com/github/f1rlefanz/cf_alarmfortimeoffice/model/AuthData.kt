package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * IMMUTABLE Auth Data Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ Serializable für DataStore-Persistence
 */
@Immutable
@Serializable
data class AuthData(
    val isLoggedIn: Boolean = false,
    val email: String? = null,
    val displayName: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiryTime: Long? = null
) {
    companion object {
        val EMPTY = AuthData()
    }
}
