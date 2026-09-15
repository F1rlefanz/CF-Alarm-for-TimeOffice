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
 *
 * ENTFERNT (Aufraeumrunde 24): `updateAuthData`, `isAuthenticated`, `getCurrentAuthData` und
 * `migrateTokenExpiryIfNeeded` - alle vier waren reine Durchreichen an
 * `IAuthDataStoreRepository`, und JEDER Konsument ruft dort direkt an
 * (`AuthViewModel`, `CalendarUseCase`, `BootReceiver`). Ueber diesen UseCase lief keine einzige
 * Aufrufstelle. Die Doppelung war die Gefahr: zwei Wege zur selben Auth-Wahrheit, von denen nur
 * einer benutzt wurde - wer den anderen faende, haette eine zweite Fehlersemantik geerbt
 * (`getOrThrow()` statt `getOrElse { false }`).
 *
 * Was hier BLEIBT, hat Aufrufer: [authData], [signOut], [requestCalendarAuthorization],
 * [hasCalendarAuthorization].
 */
interface IAuthUseCase {

    /**
     * Flow für reaktive Beobachtung der Authentifizierungsdaten
     *
     * @return Flow<AuthData> der bei Änderungen automatisch emittiert
     */
    val authData: Flow<AuthData>

    /**
     * Meldet den Nutzer ab: verwirft die Auth-Daten UND das Kalender-Token.
     *
     * HIESS FRÜHER clearAuthData() und räumte nur die Auth-Daten ab — das Kalender-Token
     * überlebte die Abmeldung. Der Name ist jetzt der Wahrheit angepasst: Abmelden heißt, dass
     * nichts zurückbleibt, womit die App weiter auf den Kalender zugreifen könnte.
     *
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun signOut(): Result<Unit>

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
