package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Authentication Data Store Repository Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: Abstraktion statt konkrete Implementierung
 * - Testbarkeit: UseCase/ViewModel kann mit Mock-Repository getestet werden
 * - Flexibilität: Implementierung austauschbar (DataStore/SharedPrefs/InMemory)
 */
interface IAuthDataStoreRepository {
    
    /**
     * Flow für reaktive Beobachtung der Authentifizierungsdaten
     * 
     * @return Flow<AuthData> der bei Änderungen automatisch emittiert
     */
    val authData: Flow<AuthData>
    
    /**
     * Speichert oder aktualisiert Authentifizierungsdaten
     * 
     * @param authData Neue Authentifizierungsdaten
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun updateAuthData(authData: AuthData): Result<Unit>
    
    /**
     * Löscht alle Authentifizierungsdaten (Logout)
     * 
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun clearAuthData(): Result<Unit>
    
    /**
     * Prüft ob gültige Authentifizierungsdaten vorhanden sind
     * 
     * @return Result mit Boolean (true wenn authentifiziert) oder Fehler
     */
    suspend fun isAuthenticated(): Result<Boolean>
    
    /**
     * Lädt aktuelle Authentifizierungsdaten (einmalig)
     * 
     * @return Result mit aktuellen AuthData oder Fehler
     */
    suspend fun getCurrentAuthData(): Result<AuthData>
    
    /**
     * Migriert alte Token-Expiry-Daten falls nötig
     * 
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun migrateTokenExpiryIfNeeded(): Result<Unit>
}
