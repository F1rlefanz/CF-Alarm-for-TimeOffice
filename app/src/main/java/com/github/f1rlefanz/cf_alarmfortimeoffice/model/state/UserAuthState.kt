package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

import androidx.compose.runtime.Immutable

/** Sub-State: Anmeldung und Kontoinformation des Nutzers. */
@Immutable
data class UserAuthState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val displayName: String? = null,
    val accessToken: String? = null,
    val hasValidToken: Boolean = false
) {
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
