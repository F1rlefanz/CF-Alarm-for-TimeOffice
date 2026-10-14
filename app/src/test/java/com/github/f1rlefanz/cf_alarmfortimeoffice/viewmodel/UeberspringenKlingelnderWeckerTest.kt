package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.DIENST_FAELLT_AUS_ERKLAERUNG
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmSkipUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * „Nicht wecken“ (bis 1.49 "Überspringen") trifft den angezeigten KLINGELNDEN Wecker und steht an
 * genau dessen Zeile (seit 1.48.0, mit #139 in die Tagesliste gewandert), und die Erklaerung zu
 * „Dienst fällt aus“ (bis 1.49 "Tag freigeben") nennt die Nebenwirkungen.
 *
 * DER BEFUND (12.10.2026, Rufdienst-Tag): stiller Rufdienst-Eintrag 05:00, eigener Wecker 05:30.
 * Die Karte zeigte "Nächster klingelnder Wecker: 05:30", die Zeile darunter "Nächsten Alarm
 * einmalig überspringen:" - und der Knopf haette den stillen 05:00-Eintrag geloescht, also hoerbar
 * nichts veraendert. Die Tests hier halten fest:
 *  - Anzeige und Skip-Ziel laufen ueber DIESELBE Funktion
 *    (`AlarmSkipUseCase.naechsterKlingelnderWecker`), auch mit uebersprungenem Eintrag im Bestand.
 *  - „Nicht wecken“ steht in der Tagesliste an genau diesem Wecker; ohne klingelnden Wecker steht
 *    es nirgends, statt einen zu versprechen.
 *  - Die Erklaerung am Knopf „Dienst fällt aus“ nennt die Nebenwirkungen, mit echten Umlauten.
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
    fun `Stehen nur stille Eintraege an, gibt es kein Ziel und nirgends Nicht wecken`() {
        val jetzt = millis(2026, 10, 12, 20, 0)
        val alarme = listOf(
            alarm(1, millis(2026, 10, 13, 5, 0), stumm = true),
            alarm(5, millis(2026, 10, 14, 5, 0), stumm = true)
        )

        val anzeige = berechneWeckerAnzeige(alarme, skippedAlarmId = null, jetzt = jetzt)

        assertNull(AlarmSkipUseCase.naechsterKlingelnderWecker(alarme, jetzt))
        assertNull(anzeige.naechsterKlingelnder)
        assertNull(anzeige.naechsterKlingelnderZeitpunkt)
        assertTrue(
            "Ohne klingelnden Wecker darf keine Zeile „Nicht wecken“ anbieten",
            wecker(alarme, jetzt, zone).none { it.nichtWeckenMoeglich || it.naechster }
        )
    }

    // --- „Nicht wecken“ in der Tagesliste ---

    @Test
    fun `Nicht wecken steht an DEM Wecker, den die Anzeige als naechsten klingelnden nennt`() {
        // Der Befund vom 12.10. in der neuen Liste: der stille 05:00 steht davor, „Nicht wecken“
        // gehoert trotzdem an den 05:30 - dieselbe Auswahl wie das Ziel von skipNextAlarm().
        val jetzt = millis(2026, 10, 12, 20, 0)
        val alarme = listOf(
            alarm(1, millis(2026, 10, 13, 5, 0), stumm = true),
            alarm(2, millis(2026, 10, 13, 5, 30), stumm = false),
            alarm(3, millis(2026, 10, 14, 5, 30), stumm = false)
        )
        val anzeige = berechneWeckerAnzeige(alarme, skippedAlarmId = null, jetzt = jetzt)

        val mitKnopf = wecker(alarme, jetzt, zone).filter { it.nichtWeckenMoeglich }
        assertEquals("Genau EINE Zeile bietet „Nicht wecken“ an (Variante A)", 1, mitKnopf.size)
        assertEquals(anzeige.naechsterKlingelnderZeitpunkt, mitKnopf.single().triggerTime)
    }

    @Test
    fun `Die Uhrzeit der Zeile ist die Wanduhr der uebergebenen Zeitzone`() {
        // Derselbe Zeitpunkt, andere Wanduhr: die Zeile muss die Ortszeit des Geraets nennen,
        // denn dort klingelt der Wecker. 05:30 in Berlin (MESZ, UTC+2) ist 03:30 UTC.
        val jetzt = millis(2026, 10, 12, 20, 0)
        val alarme = listOf(alarm(2, millis(2026, 10, 13, 5, 30), stumm = false))

        assertEquals(LocalTime.of(5, 30), wecker(alarme, jetzt, zone).single().uhrzeit)
        assertEquals(LocalTime.of(3, 30), wecker(alarme, jetzt, ZoneId.of("UTC")).single().uhrzeit)
    }

    private fun wecker(alarme: List<AlarmInfo>, jetzt: Long, zone: ZoneId): List<WeckerEintrag.Wecker> =
        baueWeckerTage(alarme, emptyList(), emptyList(), SkipLage.KEIN, emptyList(), jetzt, zone, emptyMap())
            .flatMap { it.eintraege }
            .filterIsInstance<WeckerEintrag.Wecker>()

    // --- Erklaerung am Knopf „Dienst fällt aus“ ---

    @Test
    fun `Die Erklaerung nennt Rufdienst-Abfrage, spaetere Dienstplan-Aenderungen und eigene Wecker`() {
        // Die drei Nebenwirkungen, die man sonst nur am Geraet sieht - Belege an der Konstante.
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("Rufdienst-Abfrage entfällt"))
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("auch nicht nach späteren Änderungen im Dienstplan"))
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("Deine eigenen Wecker bleiben"))
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("Termin im Kalender"))
    }

    @Test
    fun `Die Erklaerung verspricht kein Dimmen-Aus - eine Regel fuer freie Tage wirkt weiter`() {
        // Der Tag zaehlt als freier Tag (FreieTageStore.filtereSpannen) - die Regel fuer freie
        // Tage gilt dann. "kein Dimmen" waere eine Zusage, die nicht eintritt.
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("freier Tag"))
        assertFalse(DIENST_FAELLT_AUS_ERKLAERUNG.contains("kein Dimmen"))
    }

    @Test
    fun `Die Erklaerung schreibt echte Umlaute`() {
        // Projektregel fuer Nutzertexte: echte Umlaute, keine Ersatzschreibung.
        listOf("stündliche", "entfällt", "späteren", "Änderungen", "fällt").forEach { wort ->
            assertTrue("'$wort' fehlt", DIENST_FAELLT_AUS_ERKLAERUNG.contains(wort))
        }
        listOf("stuendliche", "entfaellt", "spaeteren", "Aenderungen", "faellt").forEach { ersatz ->
            assertFalse("Ersatzschreibung '$ersatz'", DIENST_FAELLT_AUS_ERKLAERUNG.contains(ersatz))
        }
    }
}
