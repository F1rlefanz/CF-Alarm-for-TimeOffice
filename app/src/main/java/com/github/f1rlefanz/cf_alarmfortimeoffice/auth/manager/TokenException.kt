package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager

import android.app.PendingIntent

/**
 * Die Fehler von [OAuth2TokenManager]. Ob ein Fehlschlag voruebergehend oder endgueltig ist,
 * entscheidet `WartungTokenFehler` anhand von Typ und Ursache (siehe [RefreshFailed]).
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
     * Authorization fehlgeschlagen.
     *
     * [cause] traegt bei einem Aussetzer (offline, Zeitueberschreitung) eine `IOException` - wie
     * bei [RefreshFailed]. Heute wertet das nur das Log aus: `WartungTokenFehler.einstufe` stuft
     * diesen Typ ohne Blick auf die Ursache als ANMELDUNG ein, und `authorize()` laeuft nie in der
     * Wartung (die geht ueber `getValidToken()`).
     */
    class AuthorizationFailed(message: String, cause: Throwable? = null) : TokenException(message, cause)
    
    /**
     * Token Refresh fehlgeschlagen.
     *
     * [cause] MUSS mitgegeben werden, wo eine vorliegt — daran haengt eine Entscheidung, nicht
     * nur die Lesbarkeit des Logs: `WartungTokenFehler` unterscheidet den voruebergehenden vom
     * endgueltigen Fehlschlag genau an dieser Ursache. GoogleAuthUtil sichert dafuer einen
     * klaren Vertrag zu — `IOException` heisst "voruebergehend, spaeter erneut versuchen",
     * `GoogleAuthException` heisst "endgueltig, der Nutzer muss handeln". Der seit Oktober 2026
     * genutzte AuthorizationClient hat diesen Vertrag NICHT; `AutorisierungsEinstufung` stellt ihn
     * her und setzt die `IOException` fuer jeden voruebergehenden Fehlschlag. Bis v1.40.2 wurde nur
     * `e.message` in den Text uebernommen und die Ursache verworfen; ein Funkloch war danach
     * nicht mehr von einem entzogenen Zugriff zu unterscheiden, und die Wartung meldete beides
     * als "Anmeldung erforderlich".
     */
    class RefreshFailed(message: String, cause: Throwable? = null) : TokenException(message, cause)

    /**
     * Der Nutzer muss der App erneut zustimmen (AuthorizationClient: `hasResolution()` MIT
     * validiertem Netz - ohne Netz ist dieselbe Antwort ein Funkloch, siehe
     * `AutorisierungsEinstufung`).
     *
     * Tritt auf, wenn der Zugriff im Google-Konto entzogen wurde. Wichtig: der
     * Token-Cache liegt in den Play Services, NICHT im App-Speicher - "Speicher loeschen"
     * raeumt ihn also nicht mit weg, und die App versucht danach weiter, mit einem toten
     * Token zu refreshen.
     *
     * [intent] ist der vom AuthorizationClient mitgelieferte Zustimmungsdialog (bis Oktober 2026 der
     * Intent einer `UserRecoverableAuthException`). **Er wird derzeit von NIEMANDEM gelesen**, und das ist
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
    class ConsentRequired(message: String, val intent: PendingIntent? = null) : TokenException(message)
    
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
    class NoActivityContext(message: String, val intent: PendingIntent? = null) : TokenException(message)
    
    /**
     * Security Violation (z.B. Token Rotation Chain broken)
     */
    class SecurityViolation(message: String) : TokenException(message)

    // KEINE NetworkError/TransientError mehr (bis v1.34.3): sealed-Subtypen ohne Erzeuger und ohne
    // `when`-Zweig - Exhaustiveness konnte nicht brechen. Nicht zu verwechseln mit
    // `AppError.NetworkError`, das sehr wohl benutzt wird.
}
