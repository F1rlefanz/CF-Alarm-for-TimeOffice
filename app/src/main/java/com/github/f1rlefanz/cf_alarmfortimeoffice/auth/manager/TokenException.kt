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
