package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt die Auswertung der ANTWORT-HUELLE der Hue-V1-API fest.
 *
 * Die zentrale Falle dieser API: Sie antwortet **auch bei Ablehnung mit HTTP 200** - das Urteil
 * steht ausschliesslich im Body. Und sie liefert "success" je Endpunkt in ZWEI Formen:
 *
 *  - als OBJEKT:  `[{"success":{"id":"3"}}]`                  (POST /schedules)
 *  - als STRING:  `[{"success":"/schedules/1 deleted"}]`      (DELETE /schedules/<id>)
 *
 * DER ECHTE FEHLER (Geraetelog 05.08.2026): Die Auswertung las den Erfolg als
 * `first["success"] as? Map<*, *>`. Beim DELETE scheiterte dieser Cast, der Code fiel in den
 * "weder success noch error"-Zweig und meldete `Result.failure` - im Log stand
 * `WARN Alt-Zeitplan id=1 nicht entfernt`, OBWOHL die Bridge mit 200 geantwortet und den
 * Zeitplan wirklich geloescht hatte. Zwei echte Folgen: die Aufraeum-Logik war falsch
 * informiert (und aufgeraeumt werden MUSS, sonst haeuft jeder Snooze einen Timer an und der
 * aelteste schaltet zu frueh aus), und echte Fehlschlaege gingen im Rauschen falscher
 * Warnungen unter.
 */
class HueV1EnvelopeTest {

    // =====================================================================================
    // parseFirst - Endpunkte mit genau EINEM Ergebnis (POST/DELETE /schedules)
    // =====================================================================================

    @Test
    fun `Erfolg als Objekt liefert die Nutzlast`() {
        val result = HueV1Envelope.parseFirst("""[{"success":{"id":"7"}}]""")

        assertTrue("Ein Objekt-Erfolg muss ein Erfolg sein", result.isSuccess)
        assertEquals("7", result.getOrThrow()["id"])
    }

    /** DER Regressionsfall: `success` ist beim DELETE ein STRING, kein Objekt. */
    @Test
    fun `Erfolg als String gilt als Erfolg und nicht als Fehler`() {
        val result = HueV1Envelope.parseFirst("""[{"success":"/schedules/1 deleted"}]""")

        assertTrue(
            "Ein String-Erfolg darf NICHT als Fehlschlag durchfallen - das war der Bug",
            result.isSuccess
        )
        assertEquals(
            "/schedules/1 deleted",
            result.getOrThrow()[HueV1Envelope.KEY_MESSAGE]
        )
    }

    @Test
    fun `Fehler-Huelle wird zum Failure mit Typ und Beschreibung`() {
        val result = HueV1Envelope.parseFirst(
            """[{"error":{"type":7,"address":"/schedules/localtime","description":"invalid value"}}]"""
        )

        assertTrue("Eine Ablehnung darf niemals als Erfolg gelten", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("Der Fehlertyp gehoert in die Meldung: $message", message.contains("7"))
        assertTrue("Die Beschreibung der Bridge gehoert in die Meldung: $message", message.contains("invalid value"))
    }

    @Test
    fun `leere Antwort ist ein Failure`() {
        assertTrue(HueV1Envelope.parseFirst("[]").isFailure)
    }

    @Test
    fun `Muell ist ein Failure und wirft nicht`() {
        assertTrue(HueV1Envelope.parseFirst("nicht mal JSON").isFailure)
        assertTrue(HueV1Envelope.parseFirst("").isFailure)
        // Ein JSON-OBJEKT ist keine Huelle - z.B. wenn ein Endpunkt seine Ressource liefert.
        assertTrue(HueV1Envelope.parseFirst("""{"1":{"name":"Wohnzimmer"}}""").isFailure)
    }

    @Test
    fun `Eintrag ohne success und ohne error ist ein Failure`() {
        assertTrue(HueV1Envelope.parseFirst("""[{"irgendwas":1}]""").isFailure)
    }

    // =====================================================================================
    // parseAll - Steuer-Endpunkte: EIN EINTRAG PRO GEAENDERTEM ATTRIBUT
    // =====================================================================================

    @Test
    fun `mehrere Erfolgs-Eintraege eines PUT gelten als Erfolg`() {
        val result = HueV1Envelope.parseAll(
            """[{"success":{"/lights/4/state/on":true}},{"success":{"/lights/4/state/bri":200}}]"""
        )

        assertTrue("Ein PUT liefert einen Eintrag pro Attribut - das ist Erfolg", result.isSuccess)
    }

    /**
     * Die Verengung auf den ERSTEN Eintrag wuerde eine Ablehnung in Eintrag 2 uebersehen -
     * genau deshalb lautet die Regel "kein Eintrag enthaelt error".
     */
    @Test
    fun `Ablehnung im zweiten Eintrag wird erkannt`() {
        val result = HueV1Envelope.parseAll(
            """[{"success":{"/lights/4/state/on":true}},{"error":{"type":3,"description":"resource not available"}}]"""
        )

        assertTrue("Ein error in einem spaeteren Eintrag ist ein Fehlschlag", result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("resource not available") == true
        )
    }

    @Test
    fun `unauthorized user gilt nicht als erfolgreiche Steuerung`() {
        val result = HueV1Envelope.parseAll(
            """[{"error":{"type":1,"address":"/lights","description":"unauthorized user"}}]"""
        )

        assertTrue(
            "Entzogener Whitelist-Eintrag: HTTP 200, aber nichts ging an",
            result.isFailure
        )
    }

    @Test
    fun `parseAll akzeptiert auch einen String-Erfolg`() {
        assertTrue(HueV1Envelope.parseAll("""[{"success":"/schedules/1 deleted"}]""").isSuccess)
    }

    @Test
    fun `parseAll behandelt leere Antwort und Muell als Failure`() {
        assertTrue(HueV1Envelope.parseAll("[]").isFailure)
        assertTrue(HueV1Envelope.parseAll("").isFailure)
        assertTrue(HueV1Envelope.parseAll("<html>Router-Login</html>").isFailure)
        assertTrue(HueV1Envelope.parseAll("""[{"weder":"noch"}]""").isFailure)
    }

    // =====================================================================================
    // looksLikeEnvelope - "Huelle statt Ressource?" fuer die GET-Endpunkte
    // =====================================================================================

    @Test
    fun `Huelle wird an der Array-Form erkannt`() {
        assertTrue(HueV1Envelope.looksLikeEnvelope("""[{"error":{"type":1}}]"""))
        assertTrue("Fuehrende Leerzeichen duerfen nicht taeuschen", HueV1Envelope.looksLikeEnvelope("\n  [{}]"))
        assertFalse(HueV1Envelope.looksLikeEnvelope("""{"1":{"name":"Wohnzimmer"}}"""))
        assertFalse(HueV1Envelope.looksLikeEnvelope(""))
    }
}
