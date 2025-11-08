package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import kotlinx.coroutines.flow.Flow

/**
 * Repository-Interface für Token-Persistence
 * 
 * Ermöglicht verschiedene Storage-Implementationen:
 * - DataStoreTokenRepository (Production)
 * - LegacyTokenRepository (Migration)
 * - InMemoryTokenRepository (Testing)
 * 
 * Design Pattern: Repository Pattern für Separation of Concerns
 */
interface TokenRepository {
    
    /**
     * Lädt aktuelles Token
     * @return TokenData oder null wenn kein Token vorhanden
     */
    suspend fun get(): TokenData?
    
    /**
     * Speichert Token
     * @param token Token zum Speichern
     * @return Result mit Erfolg oder Fehler
     */
    suspend fun save(token: TokenData): Result<Unit>
    
    /**
     * Löscht gespeichertes Token (Logout)
     * @return Result mit Erfolg oder Fehler
     */
    suspend fun clear(): Result<Unit>
    
    /**
     * Reactive Token-Updates
     * @return Flow der bei Token-Änderungen emitted
     */
    fun observe(): Flow<TokenData?>
    
    /**
     * Optional: Prüft ob Token existiert (schneller als get())
     * @return true wenn Token vorhanden
     */
    suspend fun hasToken(): Boolean = get() != null
}
