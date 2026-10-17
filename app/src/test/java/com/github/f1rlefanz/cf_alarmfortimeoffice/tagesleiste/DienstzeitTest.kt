package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Die Dienstzeit eines Typs auf dem Mustertag (Umbau #193): welche Spanne gewaehlt wird und wie ihre
 * Uhrzeiten auf den Mustertag fallen. Erwartet wird gegen LITERALE, nie gegen eine zweite Rechnung.
 */
class DienstzeitTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun span(name: String, von: LocalDateTime, bis: LocalDateTime) = ShiftSpan(
        shiftName = name,
        startTime = von.atZone(berlin).toInstant().toEpochMilli(),
        endTime = bis.atZone(berlin).toInstant().toEpochMilli(),
        alarmTriggerTime = 0L
    )

    private fun zeit(monat: Int, tag: Int, stunde: Int, minute: Int = 0) =
        LocalDateTime.of(2026, monat, tag, stunde, minute)

    private fun jetzt(monat: Int, tag: Int, stunde: Int) =
        zeit(monat, tag, stunde).atZone(berlin).toInstant().toEpochMilli()

    @Test
    fun `Nachtdienst ueber Mitternacht - Uhrzeiten, nicht das Datum, landen auf dem Mustertag`() {
        val nacht = span("N", zeit(8, 10, 20, 45), zeit(8, 11, 7))

        val dz = dienstzeitAus(listOf(nacht), "N", jetzt(8, 1, 12), berlin)!!

        assertEquals(LocalTime.of(20, 45), dz.beginn)
        assertEquals(615, dz.dauerMin)
        assertFalse(dz.ganztaegig)
        // Mustertag-Beginn 360 + 20:45 (1245) = 1605; Ende 07:00 am Folgetag = 1800 + 420 = 2220.
        assertEquals(1605, dz.beginnMin)
        assertEquals(2220, dz.endeMin)
    }

    @Test
    fun `Zeitumstellung aendert die Dauer nicht - gerechnet wird auf der Wanduhr`() {
        // 29.03.2026 02:00 -> 03:00 (Sommerzeit, verstrichen 9 h 15) und 25.10.2026 03:00 -> 02:00
        // (Winterzeit, verstrichen 11 h 15): auf der Uhr sind es beide Male 20:45 bis 07:00.
        val fruehjahr = span("N", zeit(3, 28, 20, 45), zeit(3, 29, 7))
        val herbst = span("N", zeit(10, 24, 20, 45), zeit(10, 25, 7))

        assertEquals(615, dienstzeitAus(listOf(fruehjahr), "N", 0L, berlin)!!.dauerMin)
        assertEquals(615, dienstzeitAus(listOf(herbst), "N", 0L, berlin)!!.dauerMin)
    }

    @Test
    fun `gleiche Uhrzeit heisst 24 Stunden, und mehrere Tage zeigen ihren Tag`() {
        val ruf = span("AD", zeit(8, 12, 7), zeit(8, 13, 7))
        val ueberDasWochenende = span("AD", zeit(8, 14, 7), zeit(8, 17, 7))

        assertEquals(1440, dienstzeitAus(listOf(ruf), "AD", 0L, berlin)!!.dauerMin)
        assertEquals(1440, dienstzeitAus(listOf(ueberDasWochenende), "AD", 0L, berlin)!!.dauerMin)
    }

    @Test
    fun `eine Spanne von einem Tag oder laenger ist hoechstens 24 Stunden lang - auch bei verschiedenen Uhrzeiten`() {
        // 25 h und ein Wochenende mit unterschiedlichen Enden: nicht "modulo einem Tag" (60 min, 16 h),
        // sondern der volle Tag ab dem Beginn.
        val fuenfUndZwanzig = span("AD", zeit(8, 12, 8), zeit(8, 13, 9))
        val wochenende = span("AD", zeit(8, 14, 16), zeit(8, 17, 8))

        assertEquals(1440, dienstzeitAus(listOf(fuenfUndZwanzig), "AD", 0L, berlin)!!.dauerMin)
        assertEquals(1440, dienstzeitAus(listOf(wochenende), "AD", 0L, berlin)!!.dauerMin)
        // Knapp unter einem Tag bleibt, wie es ist.
        val dreiundzwanzig = span("AD", zeit(8, 12, 8), zeit(8, 13, 7, 59))
        assertEquals(1439, dienstzeitAus(listOf(dreiundzwanzig), "AD", 0L, berlin)!!.dauerMin)
    }

    @Test
    fun `Ende am selben Tag und Ende nach Mitternacht`() {
        val spaet = span("S", zeit(8, 10, 13, 45), zeit(8, 10, 22))
        val nacht = span("N", zeit(8, 10, 22), zeit(8, 11, 6))

        assertEquals(495, dienstzeitAus(listOf(spaet), "S", 0L, berlin)!!.dauerMin)
        assertEquals(480, dienstzeitAus(listOf(nacht), "N", 0L, berlin)!!.dauerMin)
    }

    @Test
    fun `Ganztagstermin steht als 00-00 bis 23-59 und ist ganztaegig - ein Termin 00-00 bis 00-00 nicht`() {
        // CalendarEventConverter: Ende = end.date (exklusiv) minus eine Minute, also 23:59 des LETZTEN Tages.
        val ganztags = span("U", zeit(8, 10, 0), zeit(8, 12, 23, 59))
        val eintaegig = span("U", zeit(8, 10, 0), zeit(8, 10, 23, 59))
        val zeitgebunden = span("AD", zeit(8, 10, 0), zeit(8, 11, 0))

        val g = dienstzeitAus(listOf(ganztags), "U", 0L, berlin)!!
        assertTrue(g.ganztaegig)
        assertTrue(g.ganzerTag)
        assertEquals(1440, g.dauerMin)
        assertEquals(360, g.beginnMin)
        assertEquals(1800, g.endeMin)
        assertTrue(dienstzeitAus(listOf(eintaegig), "U", 0L, berlin)!!.ganztaegig)

        // Gleiche Gestalt auf der Leiste, aber `weckzeitpunkt()` stellt eine andere Frage: nur ein
        // echter Ganztagstermin weckt immer am Tag selbst.
        val z = dienstzeitAus(listOf(zeitgebunden), "AD", 0L, berlin)!!
        assertFalse(z.ganztaegig)
        assertTrue(z.ganzerTag)
    }

    @Test
    fun `gewaehlt wird die naechste anstehende Spanne, auch eine laufende`() {
        val beendet = span("N", zeit(8, 9, 18), zeit(8, 10, 2))
        val laufend = span("N", zeit(8, 10, 20, 45), zeit(8, 11, 7))
        val spaeter = span("N", zeit(8, 11, 21, 15), zeit(8, 12, 7, 30))
        val spans = listOf(spaeter, beendet, laufend)

        // 10.08. 23:00: die erste ist vorbei, die zweite laeuft, die dritte steht an.
        val dz = dienstzeitAus(spans, "N", jetzt(8, 10, 23), berlin)!!

        assertEquals(LocalTime.of(20, 45), dz.beginn)
    }

    @Test
    fun `liegt alles in der Vergangenheit, gilt die letzte Spanne`() {
        val alt = span("F", zeit(8, 3, 6), zeit(8, 3, 14, 15))
        val juengst = span("F", zeit(8, 7, 5, 30), zeit(8, 7, 13, 45))

        val dz = dienstzeitAus(listOf(juengst, alt), "F", jetzt(8, 20, 8), berlin)!!

        assertEquals(LocalTime.of(5, 30), dz.beginn)
        assertEquals(495, dz.dauerMin)
    }

    @Test
    fun `ohne Spanne dieses Typs gibt es keine Dienstzeit - auch nicht bei anderem Namen oder Schreibweise`() {
        val fremd = span("S", zeit(8, 10, 13, 45), zeit(8, 10, 22))

        assertNull(dienstzeitAus(emptyList(), "N", 0L, berlin))
        assertNull(dienstzeitAus(listOf(fremd), "N", 0L, berlin))
        // Genauer Vergleich wie bei der Ruf-Abfrage: ein Entwurf mit neuem Namen hat noch keine Spanne.
        assertNull(dienstzeitAus(listOf(fremd), "s", 0L, berlin))
    }

    @Test
    fun `eine Spanne unter einer Minute ist keine Dienstzeit`() {
        val kaputt = ShiftSpan("N", 1_000_000L, 1_030_000L, 0L)

        assertNull(dienstzeitAus(listOf(kaputt), "N", 0L, berlin))
    }

    @Test
    fun `eine Spanne in der doppelten Stunde der Rueckstellung hat keine Wanduhr-Dauer - keine Dienstzeit, kein Absturz`() {
        // 25.10.2026: 02:30 Sommerzeit (00:30 UTC) bis 02:30 Winterzeit (01:30 UTC) - verstrichen 1 h, auf der Uhr 0.
        val rueckstellung = ShiftSpan(
            "N",
            java.time.Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
            java.time.Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(),
            alarmTriggerTime = 0L
        )

        assertNull(dienstzeitAus(listOf(rueckstellung), "N", 0L, berlin))
    }

    @Test
    fun `Frei, Urlaub, Fortbildung und Krank ohne Spanne gelten als ganztaegig - Dienst und Ruf nicht`() {
        listOf(ShiftKategorie.FREI, ShiftKategorie.URLAUB, ShiftKategorie.FORTBILDUNG, ShiftKategorie.KRANK).forEach {
            assertEquals("$it", Dienstzeit.GANZTAEGIG, wirksameDienstzeit(it, null))
        }
        assertNull(wirksameDienstzeit(ShiftKategorie.DIENST, null))
        assertNull(wirksameDienstzeit(ShiftKategorie.RUFBEREITSCHAFT, null))
        assertNull(wirksameDienstzeit(ShiftKategorie.IGNORIEREN, null))
    }

    @Test
    fun `eine Fortbildung mit Uhrzeit behaelt sie, Ignorieren hat nie eine Dienstzeit`() {
        val acht = Dienstzeit(LocalTime.of(8, 0), 480, ganztaegig = false)

        assertEquals(acht, wirksameDienstzeit(ShiftKategorie.FORTBILDUNG, acht))
        assertEquals(acht, wirksameDienstzeit(ShiftKategorie.DIENST, acht))
        assertNull(wirksameDienstzeit(ShiftKategorie.IGNORIEREN, acht))
    }
}
