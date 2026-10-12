package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.FREIGEBEN_HINWEIS
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.FREIGEBEN_HINWEIS_DETAIL
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.FREIGEBEN_NEBENWIRKUNGEN
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.UEBERSPRINGEN_KEIN_ZIEL
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.ueberspringenZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmSkipUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * "Überspringen" trifft den angezeigten KLINGELNDEN Wecker und sagt, welchen (Paket C), und der
 * Hinweis zu "Tag freigeben" nennt die Nebenwirkungen (Paket D) - beides seit 1.48.0.
 *
 * DER BEFUND (12.10.2026, Rufdienst-Tag): stiller Rufdienst-Eintrag 05:00, eigener Wecker 05:30.
 * Die Karte zeigte "Nächster klingelnder Wecker: 05:30", die Zeile darunter "Nächsten Alarm
 * einmalig überspringen:" - und der Knopf haette den stillen 05:00-Eintrag geloescht, also hoerbar
 * nichts veraendert. Die Tests hier halten fest:
 *  - Anzeige und Skip-Ziel laufen ueber DIESELBE Funktion
 *    (`AlarmSkipUseCase.naechsterKlingelnderWecker`), auch mit uebersprungenem Eintrag im Bestand.
 *  - Die Knopfzeile nennt Datum und Uhrzeit genau dieses Weckers; ohne klingelnden Wecker sagt sie
 *    das, statt einen zu versprechen.
 *  - Der neue Freigabe-Satz steht im Hinweis, an der richtigen Stelle, mit echten Umlauten.
 *
 * Der Skip-Vorgang selbst (stiller Eintrag bleibt, klingelnder wird uebersprungen; nur stille ->
 * kein Ziel) ist in `AlarmSkipUseCaseTest` geprueft.
 */
class UeberspringenKlingelnderWeckerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun millis(jahr: Int, monat: Int, tag: Int, stunde: Int, minute: Int): Long =
        LocalDateTime.of(jahr, monat, tag, stunde, minute).atZone(zone).toInstant().toEpochMilli()

    private fun alarm(id: Int, triggerTime: Long, stumm: Boolean) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = if (stumm) "AD1" else "Frueh",
        triggerTime = triggerTime,
        formattedTime = "t$id",
        eventId = "event$id",
        isSilent = stumm
    )

    // --- Eine Regel fuer Anzeige und Ziel ---

    @Test
    fun `Anzeige und Skip-Ziel waehlen denselben Wecker - auch wenn stille Eintraege davor liegen`() {
        val jetzt = millis(2026, 10, 12, 20, 0)
        val alarme = listOf(
            alarm(1, millis(2026, 10, 13, 5, 0), stumm = true),
            alarm(2, millis(2026, 10, 13, 5, 30), stumm = false),
            alarm(3, millis(2026, 10, 13, 14, 30), stumm = false),
            alarm(4, millis(2026, 10, 12, 19, 0), stumm = false) // verstrichen
        )

        val anzeige = berechneWeckerAnzeige(alarme, skippedAlarmId = null, jetzt = jetzt)
        val ziel = AlarmSkipUseCase.naechsterKlingelnderWecker(alarme, jetzt)

        assertEquals(2, ziel?.id)
        assertEquals(ziel?.formattedTime, anzeige.naechsterKlingelnder)
        assertEquals(ziel?.triggerTime, anzeige.naechsterKlingelnderZeitpunkt)
        assertEquals("Der naechste EINTRAG bleibt der stille", "t1", anzeige.naechsterEintrag)
        assertTrue(anzeige.naechsterEintragIstStumm)
    }

    @Test
    fun `Ein liegengebliebener uebersprungener Eintrag zaehlt in der Anzeige nicht als naechster klingelnder`() {
        // Der Ausnahmefall aus `uebernehmeAlarmAnzeige`: der uebersprungene Eintrag ist NICHT
        // armiert, falls er doch im Bestand blieb. Die Anzeige rueckt auf den naechsten weiter -
        // und solange ein Ueberspringen aktiv ist, bietet die Karte keinen Knopf dafuer an.
        val jetzt = millis(2026, 10, 12, 20, 0)
        val alarme = listOf(
            alarm(2, millis(2026, 10, 13, 5, 30), stumm = false),
            alarm(3, millis(2026, 10, 13, 14, 30), stumm = false)
        )

        val anzeige = berechneWeckerAnzeige(alarme, skippedAlarmId = 2, jetzt = jetzt)

        assertEquals("t3", anzeige.naechsterKlingelnder)
        assertEquals(millis(2026, 10, 13, 14, 30), anzeige.naechsterKlingelnderZeitpunkt)
    }

    @Test
    fun `Stehen nur stille Eintraege an, gibt es kein Ziel und keine Knopfzeile mit Wecker`() {
        val jetzt = millis(2026, 10, 12, 20, 0)
        val alarme = listOf(
            alarm(1, millis(2026, 10, 13, 5, 0), stumm = true),
            alarm(5, millis(2026, 10, 14, 5, 0), stumm = true)
        )

        val anzeige = berechneWeckerAnzeige(alarme, skippedAlarmId = null, jetzt = jetzt)

        assertNull(AlarmSkipUseCase.naechsterKlingelnderWecker(alarme, jetzt))
        assertNull("Ohne Ziel ist der Knopf gesperrt (enabled haengt hieran)", anzeige.naechsterKlingelnder)
        assertNull(anzeige.naechsterKlingelnderZeitpunkt)
        assertEquals(UEBERSPRINGEN_KEIN_ZIEL, ueberspringenZeile(anzeige.naechsterKlingelnderZeitpunkt, zone))
    }

    // --- Knopfzeile ---

    @Test
    fun `Die Knopfzeile nennt Datum und Uhrzeit des klingelnden Weckers`() {
        assertEquals(
            "Wecker 13.10.\u00A005:30 einmalig überspringen",
            ueberspringenZeile(millis(2026, 10, 13, 5, 30), zone)
        )
    }

    @Test
    fun `Die Knopfzeile schreibt Tag, Monat und Stunde zweistellig`() {
        assertEquals(
            "Wecker 03.11.\u00A007:05 einmalig überspringen",
            ueberspringenZeile(millis(2026, 11, 3, 7, 5), zone)
        )
    }

    @Test
    fun `Die Knopfzeile rechnet in der uebergebenen Zeitzone`() {
        // Derselbe Zeitpunkt, andere Wanduhr: die Zeile muss die Ortszeit des Geraets nennen,
        // denn dort klingelt der Wecker. 05:30 in Berlin (MESZ, UTC+2) ist 03:30 UTC.
        val zeitpunkt = millis(2026, 10, 13, 5, 30)
        assertEquals(
            "Wecker 13.10.\u00A003:30 einmalig überspringen",
            ueberspringenZeile(zeitpunkt, ZoneId.of("UTC"))
        )
    }

    @Test
    fun `Ohne klingelnden Wecker verspricht die Knopfzeile keinen`() {
        assertEquals(UEBERSPRINGEN_KEIN_ZIEL, ueberspringenZeile(null, zone))
        assertTrue(UEBERSPRINGEN_KEIN_ZIEL.contains("Überspringen"))
    }

    @Test
    fun `Die Knopfzeile nennt denselben Wecker wie die Karte`() {
        // Ende-zu-Ende ueber den ViewModel-Pfad: Zeile aus dem Zeitpunkt, den die Anzeige liefert.
        val jetzt = millis(2026, 10, 12, 20, 0)
        val alarme = listOf(
            alarm(1, millis(2026, 10, 13, 5, 0), stumm = true),
            alarm(2, millis(2026, 10, 13, 5, 30), stumm = false)
        )
        val anzeige = berechneWeckerAnzeige(alarme, skippedAlarmId = null, jetzt = jetzt)

        assertEquals(
            "Wecker 13.10.\u00A005:30 einmalig überspringen",
            ueberspringenZeile(anzeige.naechsterKlingelnderZeitpunkt, zone)
        )
    }

    // --- Freigabe-Hinweis (D) ---

    @Test
    fun `Der Freigabe-Hinweis nennt Rufdienst-Abfrage, spaetere Dienstplan-Aenderungen und eigene Wecker`() {
        assertEquals(
            "Auch die stündliche Rufdienst-Abfrage entfällt, und spätere Änderungen im Dienstplan " +
                "stellen an diesem Tag keinen Wecker mehr. Deine eigenen Wecker bleiben.",
            FREIGEBEN_NEBENWIRKUNGEN
        )
        assertTrue(FREIGEBEN_HINWEIS_DETAIL.contains(FREIGEBEN_NEBENWIRKUNGEN))
        assertTrue(FREIGEBEN_HINWEIS.contains(FREIGEBEN_NEBENWIRKUNGEN))
    }

    @Test
    fun `Der Freigabe-Satz steht im Absatz zu Tag freigeben, vor dem Satz zum Kalendertermin`() {
        val satz = FREIGEBEN_HINWEIS_DETAIL.indexOf(FREIGEBEN_NEBENWIRKUNGEN)
        val absatzFreigeben = FREIGEBEN_HINWEIS_DETAIL.indexOf("Tag freigeben streicht den Dienst selbst.")
        val termin = FREIGEBEN_HINWEIS_DETAIL.indexOf("Der Termin im Kalender bleibt")

        assertTrue("Absatz 'Tag freigeben' fehlt", absatzFreigeben >= 0)
        assertTrue("Satz zum Kalendertermin fehlt", termin >= 0)
        assertTrue("Der Satz gehoert hinter den Anfang des Freigabe-Absatzes", satz > absatzFreigeben)
        assertTrue("Der Satz gehoert vor den Satz zum Kalendertermin", satz < termin)
        assertTrue(
            "Der Satz darf nicht in den Absatz zum Ueberspringen rutschen - dort gilt er nicht",
            FREIGEBEN_HINWEIS_DETAIL.substring(0, absatzFreigeben).contains("Rufdienst").not()
        )
    }

    @Test
    fun `Der Freigabe-Satz schreibt echte Umlaute`() {
        // Projektregel fuer Nutzertexte: echte Umlaute, keine Ersatzschreibung.
        listOf("stündliche", "entfällt", "spätere", "Änderungen").forEach { wort ->
            assertTrue("'$wort' fehlt", FREIGEBEN_NEBENWIRKUNGEN.contains(wort))
        }
        listOf("stuendliche", "entfaellt", "spaetere", "Aenderungen").forEach { ersatz ->
            assertTrue("Ersatzschreibung '$ersatz'", !FREIGEBEN_NEBENWIRKUNGEN.contains(ersatz))
        }
    }
}
