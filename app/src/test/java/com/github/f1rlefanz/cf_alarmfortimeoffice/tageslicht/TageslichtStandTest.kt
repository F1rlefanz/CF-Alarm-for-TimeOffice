package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Fail-safe-Richtung: jede Luecke heisst "keine Einschraenkung" (null). */
class TageslichtStandTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val datum = LocalDate.parse("2026-10-14")
    private val muenchen = Ort(48.14, 11.58, OrtQuelle.PLZ, "München")

    @Test
    fun `IMMER ergibt keine Schwelle`() {
        assertNull(TageslichtStand(muenchen, berlin).hellZeitpunkt(datum, HellAb.IMMER))
    }

    @Test
    fun `ohne Ort ergibt keine Schwelle, fuer jede Wahl`() {
        val stand = TageslichtStand.ohneOrt(berlin)
        HellAb.entries.forEach { assertNull(stand.hellZeitpunkt(datum, it)) }
    }

    @Test
    fun `mit Ort - Epoch-ms des gewaehlten Ereignisses`() {
        val stand = TageslichtStand(muenchen, berlin)
        val erwartet = SonnenstandRechner.sonnenaufgang(datum, muenchen.breite, muenchen.laenge, berlin)!!.toEpochMilli()
        assertEquals(erwartet, stand.hellZeitpunkt(datum, HellAb.SONNENAUFGANG))
        assertNotNull(stand.hellZeitpunkt(datum, HellAb.DAEMMERUNG))
    }

    @Test
    fun `kein Ereignis am Tag ergibt keine Schwelle`() {
        val tromsoe = TageslichtStand(Ort(69.65, 18.96, OrtQuelle.STANDORT, null), ZoneId.of("Europe/Oslo"))
        assertNull(tromsoe.hellZeitpunkt(LocalDate.parse("2026-06-21"), HellAb.SONNENAUFGANG))
    }

    @Test
    fun `TageslichtQuelle OHNE liefert einen Stand ohne Ort`() = runTest {
        assertNull(TageslichtQuelle.OHNE.stand().ort)
    }
}
