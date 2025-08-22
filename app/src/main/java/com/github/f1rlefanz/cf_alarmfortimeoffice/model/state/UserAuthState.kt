package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

import androidx.compose.runtime.Immutable

/**
 * IMMUTABLE Sub-State für User Authentication Information
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ GRANULAR STATE MODELING: Fokussiert auf User-spezifische Auth-Daten
 * ✅ Single Responsibility: Nur User Authentication
 * ✅ Immutable: Data class für strukturelle Gleichheit
 * ✅ Validation: Built-in computed properties
 */
@Immutable
data class UserAuthState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val displayName: String? = null,
    val accessToken: String? = null,
    val hasValidToken: Boolean = false
) {
    // Computed properties für bessere API
    val isAuthenticated: Boolean get() = isSignedIn && hasValidToken
    val hasUserInfo: Boolean get() = userEmail != null && displayName != null
    val isFullyAuthenticated: Boolean get() = isAuthenticated && hasUserInfo
    
    companion object {
        val EMPTY = UserAuthState()
        
        fun authenticated(
            email: String,
            displayName: String,
            accessToken: String?
        ) = UserAuthState(
            isSignedIn = true,
            userEmail = email,
            displayName = displayName,
            accessToken = accessToken,
            hasValidToken = accessToken?.isNotEmpty() == true
        )
    }
}
