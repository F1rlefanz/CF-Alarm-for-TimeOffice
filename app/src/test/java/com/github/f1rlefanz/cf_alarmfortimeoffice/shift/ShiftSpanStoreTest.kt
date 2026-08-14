package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Rueckschau-Grenze von [ShiftSpanStore.prune] - der einzige Teil dieses Speichers mit echter
 * Logik (der Rest ist ein duenner DataStore-Wrapper, der hier per Konvention nicht getestet wird).
 *
 * Warum das nicht kosmetisch ist: die Spannen sind seit v1.25.2 die Quelle der Dienstzeit-Fenster
 * von "Nicht stoeren" und der schicht-bezogenen Dimm-Fenster. Wird zu frueh aufgeraeumt, ist die
 * laufende Nacht nach dem Datumswechsel ploetzlich "kein Fenster" - exakt die Fehlerklasse, gegen
 * die `DimWindowResolver.LOOKBACK_DAYS` existiert. Wird nie aufgeraeumt, waechst der Eintrag
 * unbegrenzt.
 */
class ShiftSpanStoreTest {

    private val now = 1_770_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun span(name: String, endTime: Long) =
        ShiftSpan(shiftName = name, startTime = endTime - 8 * hour, endTime = endTime, alarmTriggerTime = endTime - 9 * hour)

    @Test
    fun `Eine laufende Schicht bleibt erhalten`() {
        val kept = ShiftSpanStore.prune(listOf(span("Frueh", now + 2 * hour)), now)
        assertEquals(1, kept.size)
    }

    @Test
    fun `Eine gestern beendete Schicht bleibt erhalten - die Rueckschau ist der Sinn der Sache`() {
        // Ein am Vorabend begonnenes Fenster muss nach dem Datumswechsel noch auffindbar sein,
        // sonst haelt die naechste Neuberechnung nach 00:00 die laufende Nacht fuer leer.
        val kept = ShiftSpanStore.prune(listOf(span("Nacht", now - 12 * hour)), now)
        assertEquals(1, kept.size)
    }

    @Test
    fun `Eine laenger als 24h beendete Schicht wird verworfen`() {
        val kept = ShiftSpanStore.prune(listOf(span("Alt", now - 25 * hour)), now)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `Genau auf der Grenze wird verworfen, eine Millisekunde darueber bleibt`() {
        assertTrue(ShiftSpanStore.prune(listOf(span("Rand", now - ShiftSpanStore.RETENTION_MS)), now).isEmpty())
        assertEquals(
            1,
            ShiftSpanStore.prune(listOf(span("Rand", now - ShiftSpanStore.RETENTION_MS + 1)), now).size
        )
    }

    @Test
    fun `Aufraeumen greift pro Eintrag, nicht auf die ganze Liste`() {
        val kept = ShiftSpanStore.prune(
            listOf(span("Alt", now - 30 * hour), span("Heute", now + hour), span("Gestern", now - 5 * hour)),
            now
        )
        assertEquals(listOf("Heute", "Gestern"), kept.map { it.shiftName })
    }
}
