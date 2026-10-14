package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die reinen Entscheidungen der Standortabfrage (#140): welcher Provider, wie alt darf es sein. */
class StandortAbfrageTest {

    private val alleAn: (String) -> Boolean = { true }

    @Test
    fun `ab API 31 gewinnt fused, wenn vorhanden`() {
        assertEquals("fused", StandortAbfrage.waehleProvider(31, hatFused = true, istAktiv = alleAn))
    }

    @Test
    fun `ohne fused - Netzwerk vor GPS`() {
        assertEquals("network", StandortAbfrage.waehleProvider(31, hatFused = false, istAktiv = alleAn))
        assertEquals("network", StandortAbfrage.waehleProvider(29, hatFused = true, istAktiv = alleAn))
    }

    @Test
    fun `GPS nur ab API 31 - davor verlangt es FINE, und die App fragt nur COARSE`() {
        assertEquals("gps", StandortAbfrage.waehleProvider(31, hatFused = false, istAktiv = { it == "gps" }))
        assertNull(StandortAbfrage.waehleProvider(29, hatFused = false, istAktiv = { it == "gps" }))
    }

    @Test
    fun `alles aus heisst abgeschaltet`() {
        assertNull(StandortAbfrage.waehleProvider(34, hatFused = true, istAktiv = { false }))
    }

    @Test
    fun `ein werfender Provider-Check zaehlt als aus`() {
        assertEquals(
            "network",
            StandortAbfrage.waehleProvider(34, hatFused = true, istAktiv = { if (it == "fused") error("weg") else true })
        )
    }

    @Test
    fun `letzte bekannte Position gilt bis sieben Tage`() {
        val jetzt = 1_000_000_000_000L
        assertTrue(StandortAbfrage.istFrischGenug(jetzt - 1000, jetzt))
        assertTrue(StandortAbfrage.istFrischGenug(jetzt - StandortAbfrage.MAX_ALTER_MS, jetzt))
        assertFalse(StandortAbfrage.istFrischGenug(jetzt - StandortAbfrage.MAX_ALTER_MS - 1, jetzt))
        assertFalse("Zeit 0 = unbekannt", StandortAbfrage.istFrischGenug(0, jetzt))
        assertFalse("Zukunft = Uhr verstellt", StandortAbfrage.istFrischGenug(jetzt + 60_000, jetzt))
    }
}
