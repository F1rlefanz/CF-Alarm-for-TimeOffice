package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Sonnenzeiten gegen die Referenztabelle aus dem Umsetzungsplan #140 (§3, NOAA, Toleranz ±2 Min).
 *
 * Verglichen wird IMMER ueber `ZonedDateTime.ofInstant` in der echten Zone - nie mit einem von
 * Hand addierten Versatz. Genau so faellt ein Sommerzeit-Fehler auf (eine Stunde daneben), ein
 * Vorzeichenfehler der Laenge (asymmetrisch ~45 Min) oder ein vertauschter Stundenwinkel
 * (Daemmerung nach Sonnenaufgang).
 */
class SonnenstandRechnerTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val wien = ZoneId.of("Europe/Vienna")
    private val zuerich = ZoneId.of("Europe/Zurich")
    private val oslo = ZoneId.of("Europe/Oslo")

    private data class Stadt(val breite: Double, val laenge: Double, val zone: ZoneId)

    private val muenchen = Stadt(48.14, 11.58, berlin)
    private val berlinStadt = Stadt(52.52, 13.40, berlin)
    private val wienStadt = Stadt(48.21, 16.37, wien)
    private val zuerichStadt = Stadt(47.37, 8.54, zuerich)
    private val tromsoe = Stadt(69.65, 18.96, oslo)

    private fun daemmerung(s: Stadt, datum: LocalDate) =
        SonnenstandRechner.daemmerungsBeginn(datum, s.breite, s.laenge, s.zone)

    private fun aufgang(s: Stadt, datum: LocalDate) =
        SonnenstandRechner.sonnenaufgang(datum, s.breite, s.laenge, s.zone)

    private fun assertLokal(erwartet: String, ist: Instant?, zone: ZoneId, datum: LocalDate) {
        assertNotNull("Ereignis erwartet um $erwartet", ist)
        val lokal = ZonedDateTime.ofInstant(ist, zone)
        assertEquals("Kalendertag des Ergebnisses", datum, lokal.toLocalDate())
        val soll = LocalTime.parse(erwartet)
        val abweichungMin = abs(Duration.between(soll, lokal.toLocalTime()).toMinutes())
        assertTrue("erwartet $erwartet, war ${lokal.toLocalTime()} (Abweichung $abweichungMin Min)", abweichungMin <= 2)
    }

    private fun pruefe(s: Stadt, datum: String, daemmerung: String, aufgang: String) {
        val d = LocalDate.parse(datum)
        val dm = daemmerung(s, d)
        val sa = aufgang(s, d)
        assertLokal(daemmerung, dm, s.zone, d)
        assertLokal(aufgang, sa, s.zone, d)
        assertTrue("Daemmerung liegt vor dem Sonnenaufgang", dm!! < sa!!)
    }

    @Test fun `Muenchen 14 Oktober`() = pruefe(muenchen, "2026-10-14", "07:00", "07:31")
    @Test fun `Muenchen Sommersonnenwende`() = pruefe(muenchen, "2026-06-21", "04:32", "05:13")
    @Test fun `Muenchen Wintersonnenwende`() = pruefe(muenchen, "2026-12-21", "07:25", "08:01")
    @Test fun `Muenchen Tag vor der Zeitumstellung`() = pruefe(muenchen, "2026-03-28", "05:29", "06:00")
    @Test fun `Muenchen Beginn der Sommerzeit`() = pruefe(muenchen, "2026-03-29", "06:27", "06:58")
    @Test fun `Muenchen Ende der Sommerzeit`() = pruefe(muenchen, "2026-10-25", "06:16", "06:48")

    @Test fun `Berlin 14 Oktober`() = pruefe(berlinStadt, "2026-10-14", "06:55", "07:30")
    @Test fun `Berlin Sommersonnenwende`() = pruefe(berlinStadt, "2026-06-21", "03:53", "04:43")
    @Test fun `Berlin Wintersonnenwende`() = pruefe(berlinStadt, "2026-12-21", "07:33", "08:15")
    @Test fun `Berlin Beginn der Sommerzeit`() = pruefe(berlinStadt, "2026-03-29", "06:14", "06:48")
    @Test fun `Berlin letzter Sommerzeittag`() = pruefe(berlinStadt, "2026-10-24", "07:13", "07:48")
    @Test fun `Berlin Ende der Sommerzeit`() = pruefe(berlinStadt, "2026-10-25", "06:14", "06:50")

    @Test fun `Wien 14 Oktober`() = pruefe(wienStadt, "2026-10-14", "06:41", "07:12")
    @Test fun `Zuerich 14 Oktober`() = pruefe(zuerichStadt, "2026-10-14", "07:12", "07:43")

    @Test
    fun `Tromsoe im Dezember - Daemmerung ja, Sonnenaufgang nein (Polarnacht)`() {
        val d = LocalDate.parse("2026-12-21")
        assertLokal("09:31", daemmerung(tromsoe, d), oslo, d)
        assertNull(aufgang(tromsoe, d))
    }

    @Test
    fun `Tromsoe im Juni - weder Daemmerung noch Sonnenaufgang (Mitternachtssonne)`() {
        val d = LocalDate.parse("2026-06-21")
        assertNull(daemmerung(tromsoe, d))
        assertNull(aufgang(tromsoe, d))
    }

    @Test
    fun `Sommerzeit-Sprung - lokal rund plus 58 Minuten im Maerz, minus 58 im Oktober`() {
        fun lokalMin(datum: String): Long {
            val z = ZonedDateTime.ofInstant(aufgang(muenchen, LocalDate.parse(datum))!!, berlin)
            return z.hour * 60L + z.minute
        }
        val maerz = lokalMin("2026-03-29") - lokalMin("2026-03-28")
        val oktober = lokalMin("2026-10-25") - lokalMin("2026-10-24")
        assertTrue("Maerz: $maerz", maerz in 56..60)
        assertTrue("Oktober: $oktober", oktober in -60..-56)
    }

    @Test
    fun `hellAb - IMMER ist keine Schwelle, die anderen waehlen das Ereignis`() {
        val d = LocalDate.parse("2026-10-14")
        val ort = Ort(48.14, 11.58, OrtQuelle.PLZ, "München")
        assertNull(SonnenstandRechner.hellAb(d, ort, berlin, HellAb.IMMER))
        assertEquals(daemmerung(muenchen, d), SonnenstandRechner.hellAb(d, ort, berlin, HellAb.DAEMMERUNG))
        assertEquals(aufgang(muenchen, d), SonnenstandRechner.hellAb(d, ort, berlin, HellAb.SONNENAUFGANG))
    }

    @Test
    fun `kein Ereignis wird JE Wahl entschieden - Tromsoe Dezember schraenkt nur mit Daemmerung ein`() {
        val d = LocalDate.parse("2026-12-21")
        val ort = Ort(tromsoe.breite, tromsoe.laenge, OrtQuelle.STANDORT, null)
        assertNotNull(SonnenstandRechner.hellAb(d, ort, oslo, HellAb.DAEMMERUNG))
        assertNull(SonnenstandRechner.hellAb(d, ort, oslo, HellAb.SONNENAUFGANG))
    }

    @Test
    fun `ganzes Jahr in DE AT CH - Ergebnis immer am Eingabetag, Daemmerung immer vor Sonnenaufgang`() {
        val staedte = listOf(muenchen, berlinStadt, wienStadt, zuerichStadt, Stadt(54.78, 9.44, berlin), Stadt(47.40, 9.74, wien))
        var d = LocalDate.parse("2026-01-01")
        while (d.year == 2026) {
            for (s in staedte) {
                val dm = daemmerung(s, d)!!
                val sa = aufgang(s, d)!!
                assertEquals(d, ZonedDateTime.ofInstant(dm, s.zone).toLocalDate())
                assertEquals(d, ZonedDateTime.ofInstant(sa, s.zone).toLocalDate())
                assertTrue(dm < sa)
            }
            d = d.plusDays(1)
        }
    }
}
