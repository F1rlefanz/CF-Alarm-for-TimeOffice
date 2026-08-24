package com.github.f1rlefanz.cf_alarmfortimeoffice.ui

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.FREIGEBEN_HINWEIS
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.FREIGEBEN_HINWEIS_DETAIL
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.FREIGEBEN_HINWEIS_KURZ
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.formatiereFreienTag
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.freigabeAbschnittSichtbar
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.berechneWeckerAnzeige
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Anzeige-Zusicherungen von "Tag freigeben".
 *
 * Der wichtigste Test hier ist der Rueckweg: eine Freigabe LOESCHT die Wecker des Tages. War es
 * der einzige, gibt es danach keinen naechsten Wecker mehr - und der Abschnitt duerfte trotzdem
 * nicht verschwinden, sonst ist der Zustand nicht mehr umkehrbar. Genau diese Falle hat das
 * Ueberspringen bis v1.26.2 gehabt.
 */
class TagFreigabeAnzeigeTest {

    private val heute: LocalDate = LocalDate.of(2026, 8, 24)

    @Test
    fun `Der Abschnitt bleibt sichtbar, wenn nach der Freigabe kein Wecker mehr uebrig ist`() {
        assertTrue(freigabeAbschnittSichtbar(naechsterAlarmTag = null, freieTage = listOf(heute)))
    }

    @Test
    fun `Ohne Wecker und ohne Freigabe gibt es nichts zu zeigen`() {
        assertFalse(freigabeAbschnittSichtbar(naechsterAlarmTag = null, freieTage = emptyList()))
    }

    @Test
    fun `Mit anstehendem Wecker ist der Abschnitt sichtbar`() {
        assertTrue(freigabeAbschnittSichtbar(naechsterAlarmTag = heute, freieTage = emptyList()))
    }

    @Test
    fun `Der Kurztext steht auch im Gesamttext - eine Quelle, keine Dublette`() {
        assertTrue(FREIGEBEN_HINWEIS.startsWith(FREIGEBEN_HINWEIS_KURZ))
        assertTrue(FREIGEBEN_HINWEIS.endsWith(FREIGEBEN_HINWEIS_DETAIL))
    }

    @Test
    fun `Der Erklaertext benennt beide Gesten und ihre Wirkung`() {
        // Der Text IST die Zusicherung: er muss sagen, was jeweils wegfaellt - sonst greift der
        // Nutzer wieder zur falschen Geste, wie am 24.08.2026.
        assertTrue(FREIGEBEN_HINWEIS.contains("Überspringen"))
        assertTrue(FREIGEBEN_HINWEIS.contains("Tag freigeben"))
        assertTrue(FREIGEBEN_HINWEIS.contains("Nicht stören"))
        assertTrue(FREIGEBEN_HINWEIS.contains("Kalender"))
    }

    @Test
    fun `Ein freigegebener Tag wird mit Wochentag und Datum angezeigt`() {
        // Ohne Wochentag ist ein Datum in einer Wecker-App schwer zu pruefen.
        val text = formatiereFreienTag(heute)
        assertTrue(text, text.contains("24.08.2026"))
        assertTrue(text, text.first().isLetter())
    }

    @Test
    fun `Der Tag des naechsten Weckers kommt aus der Weckzeit`() {
        // Der Knopf gibt den Tag frei, den die Karte darueber anzeigt - dieselbe Auswahl, derselbe
        // Anker. Waeren es zwei Ableitungen, gaebe der Knopf einen anderen Tag frei als angezeigt.
        val zone = ZoneId.systemDefault()
        val weckzeit = heute.atTime(5, 30).atZone(zone).toInstant().toEpochMilli()
        val anzeige = berechneWeckerAnzeige(
            alarms = listOf(
                AlarmInfo(
                    id = 1,
                    shiftId = "early",
                    shiftName = "Frueh",
                    triggerTime = weckzeit,
                    formattedTime = "05:30"
                )
            ),
            skippedAlarmId = null,
            jetzt = weckzeit - 60_000L
        )
        assertEquals(heute, anzeige.naechsterEintragTag)
    }

    @Test
    fun `Ohne anstehenden Wecker gibt es keinen Tag zum Freigeben`() {
        val anzeige = berechneWeckerAnzeige(alarms = emptyList(), skippedAlarmId = null, jetzt = 0L)
        assertEquals(null, anzeige.naechsterEintragTag)
    }
}
