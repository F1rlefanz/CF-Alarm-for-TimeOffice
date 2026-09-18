package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Die Zeitrechnung der stuendlichen Rufbereitschafts-Abfrage.
 *
 * DER VORFALL, gegen den sie gebaut ist (16.09.2026): Rufbereitschaft den ganzen Tag, um 08:51 ein
 * nachgetragener Spaetdienst mit Weckzeit 12:30, die 6h-Wartung lief um 08:10 und 14:10 - der
 * Wecker blieb stumm. Mit stuendlicher Abfrage haetten 09:00, 10:00, 11:00 und 12:00 den Dienst
 * gesehen. Die Tests unten halten genau diesen Tag fest.
 */
class RufbereitschaftAbfrageTest {

    private val h = TimeUnit.HOURS.toMillis(1)
    private val min = TimeUnit.MINUTES.toMillis(1)

    // Ein "Mitternacht"-Anker; die Rechnung ist rein relativ.
    private val tag0 = 1_789_509_600_000L

    private fun ganztags(name: String = "Abrufdienst") = ShiftSpan(
        shiftName = name, startTime = tag0, endTime = tag0 + 24 * h, alarmTriggerTime = tag0 + 5 * h
    )

    @Test
    fun `der reale Tag - um 08 51 ist die naechste Abfrage 09 00`() {
        val now = tag0 + 8 * h + 51 * min

        val next = RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags()), setOf("Abrufdienst"), now)

        assertEquals(tag0 + 9 * h, next)
    }

    @Test
    fun `nach dem Tick um 09 00 ist die naechste 10 00 - nie derselbe Termin noch einmal`() {
        // Der Tick plant seinen Nachfolger im finally der Wartung. Liegt "jetzt" ein paar
        // Millisekunden VOR dem gerade gefeuerten Termin (Uhren-Schlupf), darf nicht 09:00 wieder
        // herauskommen - das waere ein sofortiger zweiter Wartungslauf.
        val kurzVor = tag0 + 9 * h - 5
        val kurzNach = tag0 + 9 * h + 400

        assertEquals(tag0 + 10 * h, RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags()), setOf("Abrufdienst"), kurzVor))
        assertEquals(tag0 + 10 * h, RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags()), setOf("Abrufdienst"), kurzNach))
    }

    @Test
    fun `ohne Rufbereitschafts-Schicht gibt es keine Abfrage`() {
        val now = tag0 + 8 * h

        assertNull(RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags()), emptySet(), now))
        assertNull(RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags("Spaetschicht")), setOf("Abrufdienst"), now))
    }

    @Test
    fun `der Name wird EXAKT verglichen - wie der DND-Cutoff`() {
        val now = tag0 + 8 * h

        assertNull(RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags("abrufdienst")), setOf("Abrufdienst"), now))
    }

    @Test
    fun `vor Beginn der Spanne ist der Beginn selbst der erste Termin`() {
        val abends = ShiftSpan("Bereitschaft", tag0 + 21 * h, tag0 + 30 * h, tag0 + 20 * h)

        val next = RufbereitschaftAbfrage.naechsteAbfrage(listOf(abends), setOf("Bereitschaft"), tag0 + 18 * h)

        assertEquals(tag0 + 21 * h, next)
    }

    @Test
    fun `das Raster laeuft ab Spannenbeginn, nicht ab Wanduhr-Stunde`() {
        val abends = ShiftSpan("Bereitschaft", tag0 + 21 * h + 30 * min, tag0 + 30 * h, tag0 + 20 * h)

        val next = RufbereitschaftAbfrage.naechsteAbfrage(listOf(abends), setOf("Bereitschaft"), tag0 + 21 * h + 45 * min)

        assertEquals(tag0 + 22 * h + 30 * min, next)
    }

    @Test
    fun `nach dem Ende der Spanne kommt nichts mehr`() {
        // Letzter Rasterpunkt einer Ganztagesspanne ist 23:00; 24:00 ist das Ende und zaehlt nicht.
        assertEquals(tag0 + 23 * h, RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags()), setOf("Abrufdienst"), tag0 + 22 * h + 30 * min))
        assertNull(RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags()), setOf("Abrufdienst"), tag0 + 23 * h + 30 * min))
        assertNull(RufbereitschaftAbfrage.naechsteAbfrage(listOf(ganztags()), setOf("Abrufdienst"), tag0 + 30 * h))
    }

    @Test
    fun `ueber mehrere Spannen gewinnt der frueheste Termin`() {
        val heute = ganztags()
        val morgen = ShiftSpan("Abrufdienst", tag0 + 24 * h, tag0 + 48 * h, tag0 + 29 * h)

        val next = RufbereitschaftAbfrage.naechsteAbfrage(listOf(morgen, heute), setOf("Abrufdienst"), tag0 + 10 * h)

        assertEquals(tag0 + 11 * h, next)
    }

    @Test
    fun `eine Spanne innerhalb des Mindestabstands rutscht auf den naechsten Rasterpunkt`() {
        val gleich = ShiftSpan("Bereitschaft", tag0 + 10 * h, tag0 + 20 * h, tag0 + 9 * h)
        val now = tag0 + 10 * h - 10_000 // 10 s vor Beginn

        assertEquals(tag0 + 11 * h, RufbereitschaftAbfrage.naechsteAbfrage(listOf(gleich), setOf("Bereitschaft"), now))
    }

    @Test
    fun `eine kaputte Spanne mit Ende vor Beginn wird ignoriert`() {
        val kaputt = ShiftSpan("Abrufdienst", tag0 + 5 * h, tag0 + 2 * h, tag0)

        assertNull(RufbereitschaftAbfrage.naechsteAbfrage(listOf(kaputt), setOf("Abrufdienst"), tag0))
    }
}
