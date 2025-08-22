package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Authentication UseCase Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: ViewModel abhängig von Abstraktion
 * - Testbarkeit: ViewModel kann mit Mock-UseCase getestet werden
 * - Business Logic Separation: Kapselt Auth-spezifische Geschäftslogik
 * - Clean Architecture: Domain Layer Interface
 * 
 * MODERN ADDITIONS: Calendar authorization support
 */
interface IAuthUseCase {
    
    /**
     * Flow für reaktive Beobachtung der Authentifizierungsdaten
     * 
     * @return Flow<AuthData> der bei Änderungen automatisch emittiert
     */
    val authData: Flow<AuthData>
    
    /**
     * Aktualisiert Authentifizierungsdaten
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
    
    /**
     * MODERN: Requests Calendar API authorization for signed-in user
     * 
     * @param userEmail Optional email address (uses current user if null)
     * @return Result with Boolean (true if authorized) or error
     */
    suspend fun requestCalendarAuthorization(userEmail: String? = null): Result<Boolean>
    
    /**
     * MODERN: Checks if Calendar authorization is available
     * 
     * @return Result with Boolean (true if calendar access authorized) or error
     */
    suspend fun hasCalendarAuthorization(): Result<Boolean>
}
