package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimmerModellMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Fenster-Editor muss JEDEN Anker bedienen koennen, den das Modell erzeugt.
 *
 * HERGANG: Der Ein-Modell-Umbau hat die Wellness-Quelle ausgebaut und sie als gewoehnliche Regel
 * ausgedrueckt - ein Fenster `ALARM -X` -> `ALARM +0`. Genau das versprechen seither zwei
 * Nutzertexte (der Erklaertext im Dimmer-Tab und der Schnellstart-Rahmen), und genau das legt die
 * Modellmigration jedem Nutzer an, der Wellness eingeschaltet hatte. Der Editor bot am START aber
 * nur "Feste Uhrzeit" und "Schichtende" an. Folge, zweifach:
 *
 *  1. Die einzige Faehigkeit, die die entfallene Quelle ersetzen soll, hatte kein Bedienelement -
 *     "eine Funktion ohne Bedienoberflaeche gibt es fuer den Nutzer nicht".
 *  2. Ein MIGRIERTES Fenster fiel in den Uhrzeit-Zweig: der Editor zeigte den unbeteiligten
 *     Feld-Default 20:00 ohne markierten Anker, und ein einziger Tipp auf das Zeitfeld schrieb den
 *     Anker dauerhaft auf CLOCK um. Aus "zwei Stunden vor dem Aufstehen" wurde "jede Nacht ab
 *     20:00" - vor einem Spaetdienst mit Weckzeit 12:30 ein Fenster von 16,5 Stunden.
 *
 * Auf `main` war die Luecke folgenlos, weil kein Codepfad ein Fenster mit ALARM-Start erzeugte.
 * Der Ein-Modell-Umbau macht sie scharf.
 */
class DimmerFensterEditorAnkerTest {

    @Test
    fun `der Start bietet die Weckzeit an - sonst ist die Einschlafhilfe nicht anlegbar`() {
        assertTrue(
            "Ohne diesen Knopf verspricht der Erklaertext ein Fenster, das der Editor nicht bauen kann",
            DimAnchor.ALARM in START_ANKER
        )
    }

    /**
     * Der reine ENDE-Anker gehoert NICHT an den Start (siehe sein KDoc) - sonst boete die
     * Oberflaeche zwei Knoepfe an, die dort dasselbe tun.
     */
    @Test
    fun `am Start fehlt allein der reine Ende-Anker`() {
        assertEquals(
            DimAnchor.entries.filterNot { it == DimAnchor.ALARM_SONST_CLOCK },
            START_ANKER
        )
        assertEquals("Am Ende sind alle vier sinnvoll", DimAnchor.entries.toList(), ENDE_ANKER)
    }

    /**
     * Die Feld-Zuordnung ist die eigentliche Zusicherung: ein weckzeit-relativer Rand braucht ein
     * OFFSET-Feld. Mit einem UHRZEIT-Feld zeigte der Editor einen fremden Wert an und ueberschriebe
     * den Anker beim ersten Antippen.
     */
    @Test
    fun `weckzeit- und schichtende-relative Anker bekommen ein Offset-Feld`() {
        assertEquals(AnkerFeld.OFFSET, feldFuerStartAnker(DimAnchor.ALARM))
        assertEquals(AnkerFeld.OFFSET, feldFuerStartAnker(DimAnchor.SHIFT_END))
        assertEquals(AnkerFeld.UHRZEIT, feldFuerStartAnker(DimAnchor.CLOCK))
        // Am Start loest er wie CLOCK auf - also auch dasselbe Feld.
        assertEquals(AnkerFeld.UHRZEIT, feldFuerStartAnker(DimAnchor.ALARM_SONST_CLOCK))
    }

    /**
     * Der Gegencheck gegen die ECHTE Quelle solcher Fenster: was die Modellmigration einem
     * Wellness-Nutzer anlegt, muss der Editor anzeigen und aendern koennen. Ohne diesen Test
     * koennten Migration und Editor unbemerkt auseinanderlaufen.
     */
    @Test
    fun `jedes von der Migration angelegte Fenster ist im Editor bedienbar`() {
        val plan = DimmerModellMigration.plane(
            DimmerModellMigration.AltZustand(
                wellnessAn = true, regelnAn = false, nachtStandardAn = true,
                windDownMinuten = 120, globalStrength = 55, globalWarmth = 40,
                nachtStartMinuten = 22 * 60, nachtEndeMinuten = 7 * 60,
                nachtStrength = 55, nachtWarmth = 40, nachtAusnahmen = emptySet()
            ),
            emptyList<DimRule>()
        )
        val fenster = plan.regeln.flatMap { it.windows }
        assertTrue("Ohne Fenster prueft dieser Test nichts", fenster.isNotEmpty())
        fenster.forEach { w ->
            assertTrue(
                "Start-Anker ${w.startAnchor} wird im Editor nicht angeboten",
                w.startAnchor in START_ANKER
            )
            assertTrue(
                "Ende-Anker ${w.endAnchor} wird im Editor nicht angeboten",
                w.endAnchor in ENDE_ANKER
            )
        }
        assertTrue(
            "Das Wind-down-Fenster ist der Fall, um den es hier geht",
            fenster.any { it.startAnchor == DimAnchor.ALARM && it.startOffsetMinutes == -120 }
        )
    }
}
