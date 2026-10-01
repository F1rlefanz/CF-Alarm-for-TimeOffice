package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager

import com.google.android.gms.common.api.CommonStatusCodes
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException

/**
 * Was heisst eine Antwort des `AuthorizationClient` fuer die App: Token, Aussetzer, oder muss der
 * Nutzer zustimmen?
 *
 * WARUM ES DIESE FUNKTION GIBT: Bis v1.43.7 holte die App ihr Token ueber `GoogleAuthUtil`, und
 * dessen Vertrag trennte die beiden Faelle sauber - `IOException` = voruebergehend,
 * `GoogleAuthException` = endgueltig. Daran haengt die ganze Einstufung in `WartungTokenFehler`,
 * `AuthUseCase.hasCalendarAuthorization()` und `CalendarUseCase` (alle ueber
 * `WartungTokenFehler.istNetzursache`). Der `AuthorizationClient` hat diesen Vertrag NICHT:
 *  - er wirft nie eine `IOException`, sondern `ApiException(Status)` ohne Ursache (Bytecode);
 *  - und ein FUNKLOCH kommt ueberhaupt nicht als Fehler, sondern als ERFOLG mit
 *    `hasResolution() == true`, `accessToken == null` - dieselbe Form wie eine entzogene Zustimmung
 *    (Spike am Emulator, 30.09.2026, Messung S3: Flugmodus nach `clearToken`, 49 ms, wiederholbar;
 *    wieder online kam ohne jedes Zutun ein frisches Token - die Zustimmung bestand die ganze Zeit).
 *
 * Wer `hasResolution` ungeprueft als "Anmeldung" liest, baut den Fehlalarm vom 09.09.2026
 * ("Anmeldung erforderlich" im Funkloch) nach - diesmal bei JEDEM Offline-Refresh, und der
 * Refresh-Pfad verwirft bei "Zustimmung noetig" obendrein das Token.
 *
 * WARUM "VALIDIERT" ALLEIN NICHT REICHT (adversariale Review 01.10.2026): Android nimmt
 * `VALIDATED` verzoegert zurueck - am 09.09.2026 meldete der Netzbeobachter "kein Internet" erst
 * eine Sekunde NACH dem Netzfehler, und ein Netzwechsel kann genau in die GMS-Anfrage fallen. Eine
 * einzelne Momentaufnahme darf deshalb kein Token kosten: der Aufrufer (`OAuth2TokenManager.hole`)
 * misst vor UND nach dem Aufruf und laesst eine Resolution im Hintergrund erst nach einem zweiten,
 * zeitversetzten Abruf als Zustimmungsfall gelten.
 *
 * DIE REGEL: **ohne validiertes Netz ist nichts endgueltig.** Weder eine Resolution noch ein
 * Statuscode taugt offline als Beleg gegen die Anmeldung; mit Netz kommt die Wahrheit beim
 * naechsten Versuch ohnehin. Mit Netz: Resolution = Zustimmung noetig; Netz-, Timeout- und
 * Verbindungs-Statuscodes = voruebergehend; alles Uebrige = endgueltig (wie frueher eine
 * `GoogleAuthException`).
 *
 * Android-frei (die Statuscodes sind Compile-Zeit-Konstanten), Tests in
 * `AutorisierungsEinstufungTest`.
 */
internal object AutorisierungsEinstufung {

    sealed interface Ausgang {
        data class Token(val accessToken: String) : Ausgang

        /** Spaeter erneut versuchen; das bestehende Token bleibt. */
        data class Voruebergehend(val grund: String) : Ausgang

        /** Der Nutzer muss (erneut) zustimmen - nur mit validiertem Netz. */
        data class ZustimmungNoetig(val grund: String) : Ausgang

        /** Weder Netz noch Zustimmung - das, was frueher eine GoogleAuthException war. */
        data class Endgueltig(val grund: String) : Ausgang
    }

    /**
     * Statuscodes, die einen Aussetzer der Verbindung oder der Play-Dienste melden - nicht des
     * Kontos. 20-22 sind die Wiederverbindungs-Faelle (u. a. "Play-Dienste aktualisieren sich").
     * 14/16/19 (unterbrochen, abgebrochen, Verbindung zu den Play-Diensten verloren) stehen hier,
     * weil GoogleAuthUtil genau diese Faelle als `IOException("Error on service connection.")`
     * meldete - also voruebergehend (adversariale Review 01.10.2026).
     */
    private val VORUEBERGEHENDE_CODES = setOf(
        CommonStatusCodes.NETWORK_ERROR,
        CommonStatusCodes.INTERNAL_ERROR,
        CommonStatusCodes.INTERRUPTED,
        CommonStatusCodes.TIMEOUT,
        CommonStatusCodes.CANCELED,
        CommonStatusCodes.API_NOT_CONNECTED,
        CommonStatusCodes.REMOTE_EXCEPTION,
        CommonStatusCodes.CONNECTION_SUSPENDED_DURING_CALL,
        CommonStatusCodes.RECONNECTION_TIMED_OUT_DURING_UPDATE,
        CommonStatusCodes.RECONNECTION_TIMED_OUT
    )

    /** Statuscodes, die "der Nutzer muss etwas tun" bedeuten. */
    private val ZUSTIMMUNGS_CODES = setOf(
        CommonStatusCodes.SIGN_IN_REQUIRED,
        CommonStatusCodes.RESOLUTION_REQUIRED
    )

    /** Ein ERFOLGREICH abgeschlossener `authorize()`-Aufruf. */
    fun ausErgebnis(hatResolution: Boolean, accessToken: String?, netzValidiert: Boolean): Ausgang =
        when {
            hatResolution && !netzValidiert -> Ausgang.Voruebergehend(
                "Zustimmung verlangt, aber ohne validiertes Netz - so meldet GMS ein Funkloch"
            )
            hatResolution -> Ausgang.ZustimmungNoetig("Zustimmung verlangt (mit validiertem Netz)")
            !accessToken.isNullOrBlank() -> Ausgang.Token(accessToken)
            !netzValidiert -> Ausgang.Voruebergehend("Leeres Token ohne validiertes Netz")
            else -> Ausgang.Endgueltig("Leeres Token ohne Zustimmungsanfrage")
        }

    /**
     * Ein GESCHEITERTER Aufruf. `Tasks.await` wickelt den eigentlichen Fehler in eine
     * `ExecutionException` - deshalb wird die Ursachenkette durchlaufen.
     *
     * @param statusCodeVon liefert den Statuscode einer `ApiException`, sonst `null`. Als
     *   Parameter, damit der Test ohne die Play-Dienste-Klassen auskommt.
     */
    fun ausFehler(
        fehler: Throwable,
        netzValidiert: Boolean,
        statusCodeVon: (Throwable) -> Int?
    ): Ausgang {
        val gesehen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var aktuell: Throwable? = fehler
        var urteil: Ausgang? = null
        while (aktuell != null && gesehen.add(aktuell) && urteil == null) {
            urteil = when {
                aktuell is TimeoutException -> Ausgang.Voruebergehend("Zeitueberschreitung")
                aktuell is IOException -> Ausgang.Voruebergehend("IOException: ${aktuell.message}")
                aktuell is InterruptedException -> Ausgang.Voruebergehend("Unterbrochen")
                else -> statusCodeVon(aktuell)?.let { code -> ausStatusCode(code) }
            }
            aktuell = aktuell.cause
        }
        val ergebnis = urteil ?: Ausgang.Endgueltig("Unbekannter Fehler: ${fehler.javaClass.simpleName}")
        // Offline ist nichts endgueltig - siehe Klassenkommentar.
        return if (!netzValidiert && ergebnis !is Ausgang.Voruebergehend) {
            Ausgang.Voruebergehend("Ohne validiertes Netz nicht beurteilbar (${grundVon(ergebnis)})")
        } else {
            ergebnis
        }
    }

    private fun ausStatusCode(code: Int): Ausgang = when (code) {
        in VORUEBERGEHENDE_CODES -> Ausgang.Voruebergehend("Statuscode $code")
        in ZUSTIMMUNGS_CODES -> Ausgang.ZustimmungNoetig("Statuscode $code")
        else -> Ausgang.Endgueltig("Statuscode $code")
    }

    private fun grundVon(ausgang: Ausgang): String = when (ausgang) {
        is Ausgang.Token -> "Token"
        is Ausgang.Voruebergehend -> ausgang.grund
        is Ausgang.ZustimmungNoetig -> ausgang.grund
        is Ausgang.Endgueltig -> ausgang.grund
    }
}
