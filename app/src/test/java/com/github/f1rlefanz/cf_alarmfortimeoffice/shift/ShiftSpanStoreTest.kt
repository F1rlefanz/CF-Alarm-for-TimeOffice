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

    /**
     * Seit der Blockposition (18.09.2026) reicht ein Tag Rueckschau nicht mehr: ob der heutige
     * Nachtdienst der LETZTE eines Blocks ist, entscheidet der VORVORTAG mit - am Morgen nach der
     * dritten Nacht muss die zweite noch sichtbar sein, sonst wird der letzte Tag zum einzelnen.
     */
    @Test
    fun `Eine vor zwei Tagen beendete Schicht bleibt erhalten - die Blockposition braucht sie`() {
        val kept = ShiftSpanStore.prune(listOf(span("Vorvorgestern", now - 50 * hour)), now)
        assertEquals(1, kept.size)
    }

    @Test
    fun `Eine laenger als drei Tage beendete Schicht wird verworfen`() {
        val kept = ShiftSpanStore.prune(listOf(span("Alt", now - 73 * hour)), now)
        assertTrue(kept.isEmpty())
    }

    // --- mische: der Kalender liefert nur Laufendes und Kuenftiges ---

    /**
     * Der Kalender-Abruf beginnt bei "jetzt" und kennt beendete Dienste nicht mehr. Ein
     * Vollersatz wuerfe sie mit jedem Sync weg - und mit ihnen die Nachbarschaft, aus der die
     * Blockposition entsteht. Beendete Spannen des alten Bestands werden deshalb behalten.
     */
    @Test
    fun `Beendete Spannen des alten Bestands ueberleben den frischen Kalenderstand`() {
        val gestern = span("Nacht", now - 12 * hour)
        val heute = span("Nacht", now + 12 * hour)

        val ergebnis = ShiftSpanStore.mische(alt = listOf(gestern), neu = listOf(heute), now = now)

        assertEquals(listOf(heute, gestern), ergebnis)
    }

    /** Fuer alles, was noch laeuft oder bevorsteht, bleibt der Kalender die einzige Wahrheit. */
    @Test
    fun `Laufende und kuenftige Spannen kommen NUR aus dem frischen Stand`() {
        val altLaufend = span("Gestrichen", now + 2 * hour)
        val altKuenftig = span("Verschoben", now + 30 * hour)
        val neu = span("Frueh", now + 20 * hour)

        val ergebnis = ShiftSpanStore.mische(alt = listOf(altLaufend, altKuenftig), neu = listOf(neu), now = now)

        assertEquals(listOf(neu), ergebnis)
    }

    @Test
    fun `Eine beendete Spanne, die der frische Stand noch mitbringt, erscheint nur einmal`() {
        val beendet = span("Nacht", now - hour)

        val ergebnis = ShiftSpanStore.mische(alt = listOf(beendet), neu = listOf(beendet), now = now)

        assertEquals(listOf(beendet), ergebnis)
    }

    @Test
    fun `mische raeumt zu alte Spannen des Bestands mit weg`() {
        val uralt = span("Uralt", now - 100 * hour)

        val ergebnis = ShiftSpanStore.mische(alt = listOf(uralt), neu = emptyList(), now = now)

        assertTrue(ergebnis.isEmpty())
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
            listOf(span("Alt", now - 80 * hour), span("Heute", now + hour), span("Gestern", now - 5 * hour)),
            now
        )
        assertEquals(listOf("Heute", "Gestern"), kept.map { it.shiftName })
    }
}
