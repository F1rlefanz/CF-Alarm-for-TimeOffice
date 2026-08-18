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
     * [intent] ist der von [UserRecoverableAuthException] mitgelieferte Recovery-Intent, der zum
     * Zustimmungsdialog fuehren wuerde. **Er wird derzeit von NIEMANDEM gelesen**, und das ist
     * Absicht - der Satz "der einzige Weg zurueck in einen gueltigen Zustand" stand hier bis zum
     * 18.08.2026 und war zu diesem Zeitpunkt bereits widerlegt.
     *
     * Der Weg zurueck ist stattdessen der regulaere Sign-in: `OAuth2TokenManager` faengt diese
     * Exception ab, wirft das tote Token per `invalidate()` weg und laesst `getValidToken()`
     * sauber `NoTokenAvailable` melden. Das MUSS so sein - bliebe das Token liegen, liefe jeder
     * weitere Aufruf erneut in canRefresh() -> refresh() -> dieselbe Exception, eine
     * Endlosschleife ohne Ausweg. Am Emulator ist dieser Pfad belegt: nach entzogenem Zugriff
     * genuegte ein Tipp auf "Kalender-Zugriff erlauben".
     *
     * Der Intent bleibt trotzdem am Feld, statt ihn zu loeschen: er ist die Grundlage, falls der
     * Zustimmungsdialog einmal direkt angeboten werden soll (ein Dialog ist angenehmer als eine
     * Neuanmeldung). Wer ihn verwendet, korrigiert bitte diesen Absatz mit.
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
