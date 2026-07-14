package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager

import android.content.Intent

/**
 * Token Exception Hierarchy
 * 
 * Strukturierte Exception-Hierarchie für besseres Error-Handling
 * und intelligente Retry-Logic
 */
sealed class TokenException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    
    /**
     * Kein Token verfügbar - Authorization required
     */
    class NoTokenAvailable(message: String) : TokenException(message)
    
    /**
     * Token ist abgelaufen - Re-Authorization required
     */
    class AuthorizationExpired(message: String) : TokenException(message)
    
    /**
     * Authorization fehlgeschlagen
     */
    class AuthorizationFailed(message: String) : TokenException(message)
    
    /**
     * Token Refresh fehlgeschlagen
     */
    class RefreshFailed(message: String) : TokenException(message)

    /**
     * Der Nutzer muss der App erneut zustimmen (GoogleAuthUtil: "NeedRemoteConsent").
     *
     * Tritt auf, wenn der Zugriff im Google-Konto entzogen wurde. Wichtig: der GoogleAuthUtil-
     * Token-Cache liegt in den Play Services, NICHT im App-Speicher - "Speicher loeschen"
     * raeumt ihn also nicht mit weg, und die App versucht danach weiter, mit einem toten
     * Token zu refreshen.
     *
     * [intent] ist der von [UserRecoverableAuthException] mitgelieferte Recovery-Intent. Er
     * fuehrt zum Zustimmungsdialog und ist der einzige Weg zurueck in einen gueltigen Zustand.
     * Frueher wurde diese Exception als generisches RefreshFailed weitergeworfen und der Intent
     * verworfen - die App landete in einer Sackgasse ohne Ausweg.
     */
    class ConsentRequired(message: String, val intent: Intent? = null) : TokenException(message)
    
    /**
     * Storage Operation fehlgeschlagen
     */
    class StorageFailed(message: String) : TokenException(message)
    
    /**
     * Permission Dialog ist pending (User muss Action durchführen)
     */
    class PendingAuthorization(message: String) : TokenException(message)
    
    /**
     * Kein Activity Context verfügbar für Permission Dialog
     */
    class NoActivityContext(message: String, val intent: Intent? = null) : TokenException(message)
    
    /**
     * Security Violation (z.B. Token Rotation Chain broken)
     */
    class SecurityViolation(message: String) : TokenException(message)
    
    /**
     * Network Error (Retry sinnvoll)
     */
    class NetworkError(message: String) : TokenException(message)
    
    /**
     * Transient Error (temporär, Retry sinnvoll)
     */
    class TransientError(message: String) : TokenException(message)
}
