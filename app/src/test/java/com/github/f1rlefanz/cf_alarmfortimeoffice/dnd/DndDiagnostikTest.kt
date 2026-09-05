package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Die Zustandszeile der Ruhezeit.
 *
 * WAS HIER GESCHUETZT WIRD: Diese Zeile ist das einzige, was am Tag DANACH noch beantwortet, ob
 * "Nicht stoeren" nachts an war und warum. Am 05.09.2026 liess sich genau das nicht belegen - die
 * App protokollierte nur den naechsten geplanten Wechsel, und Androids Zen-Protokoll wird vom
 * Digital Wellbeing im Minutentakt geflutet. Wer die Zeile spaeter verkuerzt, nimmt ihr genau die
 * Angaben, um derentwillen sie existiert; deshalb pruefen die Tests den INHALT, nicht nur, dass
 * ueberhaupt etwas dasteht.
 */
class DndDiagnostikTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun millis(tag: Int, stunde: Int, minute: Int): Long =
        LocalDateTime.of(2026, 9, tag, stunde, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `aktives Fenster nennt Uhrzeit und Quelle`() {
        val zeile = DndDiagnostik.zustandszeile(
            aktiv = DndFenster(
                range = millis(4, 22, 0)..millis(5, 5, 30),
                quelle = DndQuelle.FOLGT_DIMMER
            ),
            grund = DndDiagnostik.AusGrund.AUSSERHALB,
            fensterGesamt = 3,
            zone = zone
        )
        assertEquals("🔕 Ruhezeit AN - Fenster 22:00-05:30 (folgt dem Dimmer)", zeile)
    }

    @Test
    fun `die Dienstzeit ist als Quelle unterscheidbar`() {
        // Genau diese Unterscheidung war die offene Frage am 05.09.2026: um 05:30 endete das
        // Nachtfenster (Modus 1), um 06:00 begann die Dienstzeit - zwei verschiedene Gruende,
        // beide "AN", und im alten Log nicht auseinanderzuhalten.
        val zeile = DndDiagnostik.zustandszeile(
            aktiv = DndFenster(
                range = millis(5, 6, 0)..millis(5, 14, 12),
                quelle = DndQuelle.DIENSTZEIT
            ),
            grund = DndDiagnostik.AusGrund.AUSSERHALB,
            fensterGesamt = 3,
            zone = zone
        )
        assertEquals("🔕 Ruhezeit AN - Fenster 06:00-14:12 (Dienstzeit)", zeile)
    }

    @Test
    fun `ein geklipptes Fenster sagt das dazu`() {
        val zeile = DndDiagnostik.zustandszeile(
            aktiv = DndFenster(
                range = millis(4, 22, 0)..millis(5, 5, 0),
                quelle = DndQuelle.FOLGT_DIMMER,
                geklippt = true
            ),
            grund = DndDiagnostik.AusGrund.AUSSERHALB,
            fensterGesamt = 1,
            zone = zone
        )
        assertTrue(
            "Ohne diesen Zusatz sieht ein durch die Rufbereitschaft verkuerztes Fenster aus wie " +
                "ein falsch berechnetes: zeile=$zeile",
            zeile.contains("Rufbereitschaft-Cutoff")
        )
    }

    @Test
    fun `jeder Aus-Grund hat einen eigenen, unterscheidbaren Text`() {
        val texte = DndDiagnostik.AusGrund.entries.map { grund ->
            DndDiagnostik.zustandszeile(aktiv = null, grund = grund, fensterGesamt = 2, zone = zone)
        }
        texte.forEach { assertTrue("Kein AUS-Text: $it", it.startsWith("🔔 Ruhezeit AUS - ")) }
        assertEquals(
            "Zwei Aus-Gruende mit demselben Text machen die Zeile wertlos - dann steht im Log " +
                "wieder nur, DASS es aus war, nicht warum",
            DndDiagnostik.AusGrund.entries.size,
            texte.toSet().size
        )
    }

    @Test
    fun `ausserhalb nennt die Zahl der Fenster`() {
        // Damit "aus" von "es gab gar nichts zu schalten" unterscheidbar bleibt.
        val zeile = DndDiagnostik.zustandszeile(
            aktiv = null,
            grund = DndDiagnostik.AusGrund.AUSSERHALB,
            fensterGesamt = 4,
            zone = zone
        )
        assertTrue(zeile, zeile.contains("4"))
    }
}
