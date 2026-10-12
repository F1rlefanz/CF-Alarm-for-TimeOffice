package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Wann bleibt der Termin der 6h-Wartungskette stehen, wann rueckt er auf jetzt + 6 h?
 *
 * BEFUND (12.10.2026, am Fairphone belegt): Jeder Eintritt in den Hauptbereich stellte die Kette
 * auf jetzt + 6 h. Der regulaere Lauf von 14:17 haette um 20:17 wiederkommen sollen; weil der
 * Nutzer die App um 15:03, 16:47, 18:18, 18:54 und 19:29 oeffnete, kam er nie - der naechste stand
 * auf 01:29:36 (= Wiedereintritt 19:29:36 + 6 h). Ein kurzfristig eingetragener Dienst blieb
 * dadurch Stunden laenger unbemerkt.
 *
 * Die Regel steht in [WartungsKettenPlanung.zielTermin]; diese Tests halten sie fest - samt der
 * Grenzen, weil genau ein zu grosses Fenster den Fehler zurueckholt.
 */
class WartungsKettenZielterminTest {

    private val jetzt = 1_800_000_000_000L
    private val sechsStunden = TimeUnit.HOURS.toMillis(WartungsKettenPlanung.INTERVALL_STUNDEN)
    private val karenz = TimeUnit.MINUTES.toMillis(WartungsKettenPlanung.WACHHUND_KARENZ_MINUTEN)

    private fun stunden(h: Long) = TimeUnit.HOURS.toMillis(h)
    private fun minuten(m: Long) = TimeUnit.MINUTES.toMillis(m)

    // ------------------------------------------------------------------ Termin behalten

    @Test
    fun `ein gemerkter Termin in den naechsten 6 h bleibt stehen`() {
        val gemerkt = jetzt + stunden(2)

        assertEquals(
            "Das Oeffnen der App darf den faelligen Lauf nicht wegschieben",
            gemerkt,
            WartungsKettenPlanung.zielTermin(jetzt, gemerkt, frueherenTerminBehalten = true)
        )
    }

    @Test
    fun `die Obergrenze gehoert noch dazu - genau jetzt + 6 h bleibt stehen`() {
        val gemerkt = jetzt + sechsStunden

        assertEquals(gemerkt, WartungsKettenPlanung.zielTermin(jetzt, gemerkt, frueherenTerminBehalten = true))
    }

    @Test
    fun `knapp ueber dem Mindestvorlauf bleibt der Termin stehen`() {
        val gemerkt = jetzt + WartungsKettenPlanung.BEHALTEN_MINDESTVORLAUF_MS + 1

        assertEquals(gemerkt, WartungsKettenPlanung.zielTermin(jetzt, gemerkt, frueherenTerminBehalten = true))
    }

    /**
     * Das Fenster vor dem faelligen Lauf, in dem Oeffnen ihn doch noch verschiebt, ist genau der
     * Mindestvorlauf. Eine Minute (wie bei der Rufbereitschafts-Abfrage) hiesse: wer die App in
     * der Minute vor dem Lauf oeffnet, bekommt ihn erst sechs Stunden spaeter.
     */
    @Test
    fun `der Mindestvorlauf bleibt im Sekundenbereich`() {
        assertEquals(1_000L, WartungsKettenPlanung.BEHALTEN_MINDESTVORLAUF_MS)
    }

    // ------------------------------------------------------------------ Termin verfallen / fehlt / zu weit

    @Test
    fun `genau auf dem Mindestvorlauf gilt der Termin als verfallen`() {
        val gemerkt = jetzt + WartungsKettenPlanung.BEHALTEN_MINDESTVORLAUF_MS

        assertEquals(
            jetzt + sechsStunden,
            WartungsKettenPlanung.zielTermin(jetzt, gemerkt, frueherenTerminBehalten = true)
        )
    }

    @Test
    fun `ein vergangener Termin wird nicht wieder gestellt`() {
        // Der Lauf laeuft gerade (sein finally plant ohnehin neu) oder die Kette ist tot - einen
        // Termin in der Vergangenheit zu stellen, liesse ihn sofort ein zweites Mal feuern.
        for (gemerkt in listOf(jetzt - minuten(5), jetzt - stunden(30), jetzt)) {
            assertEquals(
                "gemerkt=$gemerkt",
                jetzt + sechsStunden,
                WartungsKettenPlanung.zielTermin(jetzt, gemerkt, frueherenTerminBehalten = true)
            )
        }
    }

    @Test
    fun `ohne gemerkten Termin gilt jetzt + 6 h`() {
        assertEquals(
            jetzt + sechsStunden,
            WartungsKettenPlanung.zielTermin(jetzt, gemerkterTermin = null, frueherenTerminBehalten = true)
        )
    }

    @Test
    fun `ein Termin weiter als 6 h voraus wird nicht behalten`() {
        // Entsteht nur nach einem Zurueckstellen der Uhr - die Kette darf dadurch nicht laenger
        // als ihr Intervall schweigen.
        val gemerkt = jetzt + sechsStunden + 1

        assertEquals(
            jetzt + sechsStunden,
            WartungsKettenPlanung.zielTermin(jetzt, gemerkt, frueherenTerminBehalten = true)
        )
    }

    // ------------------------------------------------------------------ Default-Aufrufer

    @Test
    fun `ohne Behalten plant jeder andere Aufrufer ab jetzt - auch mit gueltigem Termin`() {
        // Wartungs-finally, Boot, Master-Pause resume(), Wachhund: dort IST jetzt der Anlass.
        assertEquals(
            jetzt + sechsStunden,
            WartungsKettenPlanung.zielTermin(jetzt, jetzt + stunden(2), frueherenTerminBehalten = false)
        )
    }

    @Test
    fun `die alten Aufrufe von zeitpunkte bleiben bei jetzt + 6 h`() {
        val zeitpunkte = WartungsKettenPlanung.zeitpunkte(jetzt)

        assertEquals(jetzt + sechsStunden, zeitpunkte.regulaer)
        assertEquals(jetzt + sechsStunden + karenz, zeitpunkte.wiederanlauf)
    }

    // ------------------------------------------------------------------ Wachhund folgt dem Ziel

    @Test
    fun `der Wachhund folgt dem behaltenen Termin, nicht jetzt`() {
        val gemerkt = jetzt + stunden(1)

        val zeitpunkte = WartungsKettenPlanung.zeitpunkte(jetzt, gemerkt, frueherenTerminBehalten = true)

        assertEquals(gemerkt, zeitpunkte.regulaer)
        assertEquals(
            "Vom jetzt gerechnet laege der Wachhund 5 h hinter dem Lauf, den er absichern soll",
            gemerkt + karenz,
            zeitpunkte.wiederanlauf
        )
    }

    @Test
    fun `der Wachhund folgt auch dem verworfenen Termin - immer Ziel plus Karenz`() {
        val zeitpunkte = WartungsKettenPlanung.zeitpunkte(jetzt, jetzt - minuten(1), frueherenTerminBehalten = true)

        assertEquals(jetzt + sechsStunden, zeitpunkte.regulaer)
        assertEquals(zeitpunkte.regulaer + karenz, zeitpunkte.wiederanlauf)
    }

    // ------------------------------------------------------------------ der Tag aus dem Befund

    /**
     * Der 12.10.2026 nachgespielt: Lauf um 14:17, danach fuenf Eintritte in die App. Mit der Regel
     * bleibt der Termin bei 20:17 - vorher stand er am Ende auf 01:29:36.
     */
    @Test
    fun `fuenfmal Oeffnen am 12-10 laesst den 20-17-Lauf stehen`() {
        val lauf1417 = 0L // Bezugspunkt, nur Abstaende zaehlen
        var termin = WartungsKettenPlanung.zielTermin(lauf1417, null, frueherenTerminBehalten = false)
        val erwartet = lauf1417 + stunden(6) // 20:17

        val eintritte = listOf(
            minuten(46), // 15:03
            minuten(150), // 16:47
            minuten(241), // 18:18
            minuten(277), // 18:54
            minuten(312) + 36_000L // 19:29:36
        )
        for (eintritt in eintritte) {
            termin = WartungsKettenPlanung.zielTermin(eintritt, termin, frueherenTerminBehalten = true)
            assertEquals("Eintritt bei +${eintritt / 60_000} min hat den Termin verschoben", erwartet, termin)
        }
    }

    // ------------------------------------------------------------------ Anzeige

    @Test
    fun `angezeigt wird nur ein bevorstehender Termin`() {
        assertEquals(jetzt + 1, WartungsKettenPlanung.anzeigeTermin(jetzt + 1, jetzt))
        assertEquals(jetzt + stunden(5), WartungsKettenPlanung.anzeigeTermin(jetzt + stunden(5), jetzt))
    }

    @Test
    fun `fehlt der Termin oder ist er vorbei, entfaellt die Zeile`() {
        assertNull(WartungsKettenPlanung.anzeigeTermin(null, jetzt))
        assertNull(WartungsKettenPlanung.anzeigeTermin(jetzt, jetzt))
        assertNull(WartungsKettenPlanung.anzeigeTermin(jetzt - minuten(10), jetzt))
    }
}
