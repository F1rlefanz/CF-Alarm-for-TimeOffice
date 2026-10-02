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

    /**
     * Ob fuer die aktuelle Anmeldung schon ein Restore-Schluessel angelegt wurde (#55). Liegt
     * absichtlich in `auth_prefs`: die Datei reist weder per Backup noch per Geraetetransfer mit
     * (auf dem neuen Geraet fehlt der Merker also, und der Schluessel wird dort neu angelegt), und
     * [clearAuthData] raeumt ihn beim Abmelden mit ab. Ein Lesefehler ist ein Failure - der
     * Aufrufer legt dann NICHTS an, statt aus "unlesbar" ein "fehlt" zu machen.
     */
    suspend fun istWiederherstellungsSchluesselAngelegt(): Result<Boolean>

    /** Merkt sich, dass der Restore-Schluessel angelegt ist (siehe oben). */
    suspend fun merkeWiederherstellungsSchluesselAngelegt(): Result<Unit>
}
