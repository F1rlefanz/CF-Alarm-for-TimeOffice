package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [GeklingelteWeckzeiten] - der Merker, der einen MANUELLEN Wecker nach dem Klingeln in der
 * Weckzeit-Zeitleiste des Dimmers haelt.
 *
 * DER AUSLOESENDE FALL (Fairphone, 13.10.2026, Datei-Log): Nachtfenster 22:00-05:30, beendet vom
 * manuellen 05:30-Wecker. Um 05:36 kam das Auto-Update, die Neuberechnung fand den verstrichenen
 * Wecker nicht mehr im Alarm-Bestand und dimmte wieder bis 07:00. Schichtwecker hatten das Problem
 * nie, ihre Weckzeit haelt der ShiftSpanStore drei Tage vor.
 */
class GeklingelteWeckzeitenTest {

    private val tag = 24 * 60 * 60_000L
    private val jetzt = 1_800_000_000_000L

    @Test
    fun `Rueckschau entspricht der der Schichtspannen`() {
        assertEquals(com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore.RETENTION_MS, GeklingelteWeckzeiten.RUECKSCHAU_MS)
    }

    @Test
    fun `aeltere Eintraege als die Rueckschau fallen weg, juengere bleiben sortiert und ohne Dubletten`() {
        val alt = jetzt - 4 * tag
        val gestern = jetzt - tag
        val heute = jetzt - 60_000L
        assertEquals(
            listOf(gestern, heute),
            GeklingelteWeckzeiten.behalte(listOf(heute, alt, gestern, heute), jetzt)
        )
    }

    @Test
    fun `merken haengt an und deckelt die Anzahl`() {
        val viele = (1..100).map { jetzt - it * 60_000L }
        val neu = GeklingelteWeckzeiten.mitNeuemEintrag(viele, jetzt, jetzt)
        assertEquals(GeklingelteWeckzeiten.MAX_EINTRAEGE, neu.size)
        assertEquals("der neueste Eintrag ueberlebt den Deckel", jetzt, neu.last())
    }

    @Test
    fun `Kodierung ist umkehrbar und kaputter Inhalt degradiert auf das Lesbare`() {
        val zeiten = listOf(jetzt - tag, jetzt)
        assertEquals(zeiten, GeklingelteWeckzeiten.dekodiere(GeklingelteWeckzeiten.kodiere(zeiten)))
        assertEquals(listOf(jetzt), GeklingelteWeckzeiten.dekodiere("abc,$jetzt,,"))
        assertEquals(emptyList<Long>(), GeklingelteWeckzeiten.dekodiere(null))
    }

    // ------------------------------------------------------------------ Verdrahtung

    private fun quelle(pfad: String): String =
        listOf(File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad"),
            File("app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad"))
            .first { it.exists() }.readText()

    @Test
    fun `der AlarmReceiver merkt erst NACH der Skip-Pruefung`() {
        // Ein uebersprungener Wecker darf kein Dimm-Fenster beenden.
        val src = quelle("AlarmReceiver.kt")
        val skip = src.indexOf("skipUseCase.checkAndProcessSkip(")
        val merke = src.indexOf("GeklingelteWeckzeiten.merke(")
        assertTrue("Skip-Pruefung gefunden", skip > 0)
        assertTrue("merke() nach der Skip-Pruefung", merke > skip)
    }

    @Test
    fun `der Dimmer liest den Merker in seine Weckzeit-Zeitleiste`() {
        assertTrue(quelle("dimmer/DimScheduleUseCase.kt").contains("GeklingelteWeckzeiten.lies(context)"))
    }
}
