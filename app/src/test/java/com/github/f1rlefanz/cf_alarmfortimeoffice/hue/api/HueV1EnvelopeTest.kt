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
    // parseControl - Steuer-PUTs: TEILERFOLG IST ERFOLG
    // =====================================================================================

    /**
     * DER Regressionsfall der strengen Auswertung: Die Bridge lehnt EINZELNE Attribute ab und
     * wendet die anderen trotzdem an - `ct` an einer Lampe ohne Farbtemperatur (Hue White,
     * Fremdlampe) ist error type 6, das Licht geht aber an. Galt das als Totalausfall, stieg
     * `HueLightUseCase.startSunrise` nach Schritt 1 mit `return initial` aus und die eigentliche
     * Aufhell-Rampe (Schritt 2) lief nie: Die Lampe blieb am Wecktag auf bri=1 stehen.
     */
    @Test
    fun `teilweise angenommener PUT gilt als angenommen`() {
        val partialResponse =
            """[{"success":{"/lights/4/state/on":true}},
               {"success":{"/lights/4/state/bri":1}},
               {"error":{"type":6,"address":"/lights/4/state/ct","description":"parameter, ct, not available"}}]"""

        // Der Beweis, dass hier wirklich zwei Urteile gebraucht werden: die strenge Variante,
        // mit der die Steuerung bis zu diesem Fix ausgewertet wurde, kippt bei genau diesem
        // Body - und genau das brach die Rampe ab.
        assertTrue(
            "parseAll MUSS hier streng bleiben (Huellen-Waechter der GETs)",
            HueV1Envelope.parseAll(partialResponse).isFailure
        )

        val result = HueV1Envelope.parseControl(partialResponse)

        assertTrue(
            "Ein angenommenes on/bri neben einem abgelehnten ct ist KEIN Totalausfall",
            result.isSuccess
        )
        val rejections = result.getOrThrow()
        assertEquals("Genau ein Attribut wurde abgelehnt", 1, rejections.size)
        assertTrue(
            "Das abgelehnte Attribut muss loggbar benannt sein: $rejections",
            rejections.single().contains("ct, not available")
        )
    }

    /** "Aus" plus gewaehlte Farbe: `on` greift, `ct` scheitert an der ausgeschalteten Lampe. */
    @Test
    fun `Ausschalten mit abgelehnter Farbe bleibt ein Erfolg`() {
        val result = HueV1Envelope.parseControl(
            """[{"success":{"/lights/4/state/on":false}},
               {"error":{"type":201,"address":"/lights/4/state/ct","description":"parameter, ct, is not modifiable. Device is set to off."}}]"""
        )

        assertTrue("Das Licht ging korrekt aus - das darf nicht als Fehlschlag gelten", result.isSuccess)
    }

    /** Der eigentliche Zielfall der Body-Auswertung bleibt vollstaendig abgedeckt. */
    @Test
    fun `nur Fehler-Eintraege sind ein echter Fehlschlag`() {
        val result = HueV1Envelope.parseControl(
            """[{"error":{"type":1,"address":"/lights","description":"unauthorized user"}}]"""
        )

        assertTrue("Entzogener Whitelist-Eintrag: HTTP 200, aber nichts ging an", result.isFailure)
        assertTrue(
            "Die Beschreibung der Bridge gehoert in die Meldung",
            result.exceptionOrNull()?.message?.contains("unauthorized user") == true
        )
    }

    @Test
    fun `mehrere Fehler ohne einen Erfolg sind ein Fehlschlag`() {
        val result = HueV1Envelope.parseControl(
            """[{"error":{"type":6,"description":"parameter, ct, not available"}},
               {"error":{"type":6,"description":"parameter, sat, not available"}}]"""
        )

        assertTrue("Kein einziges Attribut angenommen = Fehlschlag", result.isFailure)
    }

    @Test
    fun `parseControl behandelt leere Antwort Muell und Eintraege ohne Urteil als Failure`() {
        assertTrue(HueV1Envelope.parseControl("[]").isFailure)
        assertTrue(HueV1Envelope.parseControl("").isFailure)
        assertTrue(HueV1Envelope.parseControl("<html>Router-Login</html>").isFailure)
        assertTrue(HueV1Envelope.parseControl("""[{"weder":"noch"}]""").isFailure)
    }

    @Test
    fun `parseControl meldet einen glatten Erfolg ohne Ablehnungen`() {
        val result = HueV1Envelope.parseControl(
            """[{"success":{"/lights/4/state/on":true}},{"success":{"/lights/4/state/bri":200}}]"""
        )

        assertTrue(result.isSuccess)
        assertTrue("Ohne Ablehnung darf nichts zu loggen sein", result.getOrThrow().isEmpty())
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

    // =====================================================================================
    // parseControl - der Szenen-PUT (PUT /groups/<id>/action mit {"scene": ...})
    // =====================================================================================

    /**
     * Gegen die echte Bridge gemessen (BSB002, apiversion 1.78.0, 25.08.2026): Ein Szenen-PUT
     * antwortet mit GENAU EINEM Eintrag fuer den ganzen Aufruf - nicht mit einem pro Lampe.
     */
    @Test
    fun `der Szenen-PUT liefert genau einen Erfolgseintrag`() {
        val body = """[{"success":{"/groups/2/action/scene":"3JXxZqxyctKtYXK"}}]"""

        val result = HueV1Envelope.parseControl(body)

        assertTrue("Die Bridge hat die Szene angenommen", result.isSuccess)
        assertTrue("Kein abgelehntes Attribut zu melden", result.getOrThrow().isEmpty())
    }

    /**
     * Warum hier `parseControl` und nicht `parseAll` steht: Die Bridge darf einzelne Attribute
     * ablehnen, waehrend sie die uebrigen anwendet - real etwa `ct` an einer Lampe ohne
     * Farbtemperatur. Mit `parseAll` waere daraus ein Fehlschlag geworden, und der Aufrufer
     * haette das Licht fuer "nicht angegangen" gehalten, obwohl es brennt.
     */
    @Test
    fun `ein abgelehntes Einzelattribut kippt den Aufruf nicht`() {
        val body = """[{"success":{"/groups/2/action/on":true}},
                       {"error":{"type":6,"address":"/groups/2/action/ct",
                                 "description":"parameter, ct, not available"}}]"""

        val result = HueV1Envelope.parseControl(body)

        assertTrue("Mindestens ein Erfolg reicht", result.isSuccess)
        assertEquals("Die Ablehnung wird trotzdem gemeldet", 1, result.getOrThrow().size)
    }

    /** Die Gegenprobe: NUR Fehler ist auch fuer parseControl ein Fehlschlag. */
    @Test
    fun `ein Szenen-PUT ohne jeden Erfolg ist ein Fehlschlag`() {
        val body = """[{"error":{"type":3,"address":"/groups/99/action",
                                 "description":"resource, /groups/99, not available"}}]"""

        assertTrue(HueV1Envelope.parseControl(body).isFailure)
    }
}
