package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Die EINE Dienstzeit-Formatierung (Tagesliste, „Nächster Wecker“, „Nächste Schicht“). Auslöser:
 * „Abrufdienst · 00:00–00:00“ am Fairphone, 14.10.2026. Feste Zone, damit die Zeitumstellung
 * pruefbar ist.
 */
class DienstzeitTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val nbsp = ' '

    private fun millis(j: Int, m: Int, t: Int, h: Int, min: Int): Long =
        LocalDateTime.of(j, m, t, h, min).atZone(zone).toInstant().toEpochMilli()

    /** Normale Leerzeichen zurueck, damit die Erwartungen lesbar bleiben. */
    private fun text(beginn: Long, ende: Long) = dienstzeitText(beginn, ende, zone)?.replace(nbsp, ' ')

    @Test
    fun `ganztaegig statt 00-00 bis 00-00`() {
        assertEquals("ganztägig", text(millis(2026, 10, 17, 0, 0), millis(2026, 10, 18, 0, 0)))
    }

    @Test
    fun `mehrtaegig ganztaegig nennt den letzten Tag, nicht den Folgetag des Endes`() {
        // Fr 16.10. 00:00 bis So 18.10. 24:00 (= Mo 19.10. 00:00): der Dienst laeuft bis Sonntag.
        assertEquals(
            "ganztägig bis So 18.10.",
            text(millis(2026, 10, 16, 0, 0), millis(2026, 10, 19, 0, 0))
        )
    }

    @Test
    fun `ganztaegig auch an den Tagen der Zeitumstellung - 25 und 23 Stunden`() {
        // So 25.10.2026: Uhr wird zurueckgestellt, der Tag hat 25 h.
        assertEquals("ganztägig", text(millis(2026, 10, 25, 0, 0), millis(2026, 10, 26, 0, 0)))
        // So 29.03.2026: Uhr wird vorgestellt, der Tag hat 23 h - ein ">= 24 h"-Vergleich fiele hier durch.
        assertEquals("ganztägig", text(millis(2026, 3, 29, 0, 0), millis(2026, 3, 30, 0, 0)))
    }

    @Test
    fun `normaler Dienst und Nachtdienst bleiben eine einfache Spanne`() {
        assertEquals("06:00–14:12", text(millis(2026, 10, 14, 6, 0), millis(2026, 10, 14, 14, 12)))
        assertEquals("22:00–06:00", text(millis(2026, 10, 14, 22, 0), millis(2026, 10, 15, 6, 0)))
        // Ab Mitternacht, aber kein ganzer Tag: Uhrzeiten, nicht "ganztägig".
        assertEquals("00:00–08:00", text(millis(2026, 10, 14, 0, 0), millis(2026, 10, 14, 8, 0)))
    }

    @Test
    fun `ein Dienst ueber mehr als eine Nacht nennt sein Ende mit Datum`() {
        assertEquals(
            "06:00 bis So 18.10. 06:00",
            text(millis(2026, 10, 16, 6, 0), millis(2026, 10, 18, 6, 0))
        )
        // 25 h ueber eine Nacht: am Folgetag NACH der Beginnzeit -> nicht mehr "06:00–07:00".
        assertEquals(
            "06:00 bis Do 15.10. 07:00",
            text(millis(2026, 10, 14, 6, 0), millis(2026, 10, 15, 7, 0))
        )
    }

    @Test
    fun `ein 24-Stunden-Dienst ist keine Spanne der Laenge null`() {
        // Rettungsdienst/Feuerwehr: Mi 07:00 bis Do 07:00 - nicht "07:00–07:00".
        assertEquals(
            "07:00 bis Do 15.10. 07:00",
            text(millis(2026, 10, 14, 7, 0), millis(2026, 10, 15, 7, 0))
        )
    }

    @Test
    fun `unbekannt wird nicht erfunden`() {
        assertNull(text(0L, millis(2026, 10, 14, 14, 0)))
        assertEquals("ab 06:00", text(millis(2026, 10, 14, 6, 0), 0L))
        // Ende nicht nach dem Beginn: keine Spanne, die rueckwaerts liefe.
        assertEquals("ab 06:00", text(millis(2026, 10, 14, 6, 0), millis(2026, 10, 14, 6, 0)))
    }

    @Test
    fun `umbrechen darf die Dienstzeit nur um bis - Datum und Uhrzeit bleiben am Stueck`() {
        // 320 dp, Schrift 1,3: als EIN Wort braeche „Nachtdienst · 22:00 bis So 18.10. 06:00“
        // mitten im Wort. Normale Leerzeichen stehen deshalb nur direkt vor und nach „bis“.
        listOf(
            dienstzeitText(millis(2026, 10, 16, 0, 0), millis(2026, 10, 19, 0, 0), zone)!!,
            dienstzeitText(millis(2026, 10, 16, 6, 0), millis(2026, 10, 18, 6, 0), zone)!!
        ).forEach { text ->
            assertEquals(text, 2, text.count { it == ' ' })
            assertTrue(text, text.contains(" bis "))
        }
        assertFalse(dienstzeitText(millis(2026, 10, 16, 6, 0), 0L, zone)!!.contains(' '))
    }

    @Test
    fun `Naechste Schicht nennt Datum und Dienstzeit`() {
        assertEquals(
            "Sa 17.10. · ganztägig",
            schichtTerminText(millis(2026, 10, 17, 0, 0), millis(2026, 10, 18, 0, 0), zone).replace(nbsp, ' ')
        )
        assertEquals(
            "Mi 14.10. · 06:00–14:12",
            schichtTerminText(millis(2026, 10, 14, 6, 0), millis(2026, 10, 14, 14, 12), zone).replace(nbsp, ' ')
        )
    }

    @Test
    fun `kurzesDatum ohne Punkt am Wochentag`() {
        assertEquals("Mi 14.10.", kurzesDatum(LocalDate.of(2026, 10, 14)))
    }

    @Test
    fun `die Karte Naechster Wecker nutzt dieselbe Formatierung`() {
        val alarm = AlarmInfo(
            id = 1, shiftId = "abruf", shiftName = "Abrufdienst", triggerTime = millis(2026, 10, 16, 23, 0),
            formattedTime = "x", shiftStartTime = millis(2026, 10, 17, 0, 0), shiftEndTime = millis(2026, 10, 18, 0, 0)
        )
        assertEquals("Abrufdienst${TRENNER}ganztägig", schichtZeile(alarm, zone))
    }

    @Test
    fun `in der Karte Naechster Wecker bleibt auch die mehrtaegige Dienstzeit am Stueck`() {
        // Die Karte bricht nur hinter ihrem Trenner um - auch „ganztägig bis So 18.10.“ nicht.
        val alarm = AlarmInfo(
            id = 1, shiftId = "abruf", shiftName = "Abrufdienst", triggerTime = millis(2026, 10, 15, 23, 0),
            formattedTime = "x", shiftStartTime = millis(2026, 10, 16, 0, 0), shiftEndTime = millis(2026, 10, 19, 0, 0)
        )
        val teile = schichtZeile(alarm, zone)!!.split(TRENNER)
        assertEquals(2, teile.size)
        assertTrue(teile.none { it.contains(' ') })
    }
}
