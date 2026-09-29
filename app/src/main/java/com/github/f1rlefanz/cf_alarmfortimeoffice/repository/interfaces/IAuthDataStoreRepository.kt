package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Authentication Data Store Repository Operations
 */
interface IAuthDataStoreRepository {
    
    /**
     * Flow für reaktive Beobachtung der Authentifizierungsdaten
     */
    val authData: Flow<AuthData>
    
    /**
     * Speichert oder aktualisiert Authentifizierungsdaten
     */
    suspend fun updateAuthData(authData: AuthData): Result<Unit>
    
    /**
     * Löscht alle Authentifizierungsdaten (Logout)
     */
    suspend fun clearAuthData(): Result<Unit>
    
    /**
     * Prüft ob gültige Authentifizierungsdaten vorhanden sind
     */
    suspend fun isAuthenticated(): Result<Boolean>
    
    /**
     * Lädt aktuelle Authentifizierungsdaten (einmalig)
     */
    suspend fun getCurrentAuthData(): Result<AuthData>
}
