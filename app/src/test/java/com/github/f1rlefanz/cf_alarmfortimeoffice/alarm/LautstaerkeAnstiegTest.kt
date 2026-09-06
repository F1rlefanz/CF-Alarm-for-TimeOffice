package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Kurve des sanften Weckton-Anstiegs.
 *
 * WAS HIER GESCHUETZT WIRD: Der Anstieg ist die einzige Stelle dieser App, an der der Wecker
 * ABSICHTLICH leise beginnt. Jeder Rechenfehler in dieser Datei ist damit ein potenziell
 * verschlafener Dienst - und zwar einer, den kein Blick auf die Oberflaeche zeigt, weil die
 * Einstellung ja richtig dasteht. Die beiden wichtigsten Zusicherungen sind deshalb nicht
 * "die Kurve ist huebsch", sondern:
 *
 *  1. **Am Ende steht exakt volle Lautstaerke** - nicht 0,98 davon.
 *  2. **Jede unklare Eingabe ergibt volle Lautstaerke**, nie einen leisen Wecker.
 */
class LautstaerkeAnstiegTest {

    private val genauigkeit = 0.0001f

    // ---- Die beiden Enden der Kurve ------------------------------------------------------

    @Test
    fun `am Anfang steht der eingestellte Startpegel`() {
        assertEquals(0.15f, LautstaerkeAnstieg.pegel(0.15f, 30_000L, 0L), genauigkeit)
    }

    @Test
    fun `am Ende steht exakt volle Lautstaerke`() {
        assertEquals(
            LautstaerkeAnstieg.VOLL,
            LautstaerkeAnstieg.pegel(0.15f, 30_000L, 30_000L),
            0f
        )
    }

    @Test
    fun `nach dem Ende bleibt es bei voller Lautstaerke`() {
        // Der Backstop feuert einen Schritt NACH der Dauer, und die Schrittkette kann durch eine
        // Verzoegerung des Handlers ebenfalls spaeter dran sein. Ueberschreitung darf deshalb
        // nicht ueber 1,0 hinauslaufen - ein Pegel > 1,0 uebersteuert.
        assertEquals(
            LautstaerkeAnstieg.VOLL,
            LautstaerkeAnstieg.pegel(0.15f, 30_000L, 999_000L),
            0f
        )
    }

    // ---- Richtung der Degradation: im Zweifel LAUT ----------------------------------------

    @Test
    fun `ohne Dauer gibt es keinen Anstieg`() {
        assertEquals(LautstaerkeAnstieg.VOLL, LautstaerkeAnstieg.pegel(0.15f, 0L, 0L), 0f)
    }

    @Test
    fun `negative Dauer ergibt volle Lautstaerke`() {
        assertEquals(LautstaerkeAnstieg.VOLL, LautstaerkeAnstieg.pegel(0.15f, -1L, 0L), 0f)
    }

    @Test
    fun `Startpegel null ergibt volle Lautstaerke statt Stille`() {
        // Der gefaehrlichste Fall: 0 hoch irgendwas ist 0. Ohne diese Klemme bliebe der Wecker
        // bis zum letzten Schritt voellig stumm.
        assertEquals(LautstaerkeAnstieg.VOLL, LautstaerkeAnstieg.pegel(0f, 30_000L, 0L), 0f)
    }

    @Test
    fun `negativer Startpegel ergibt volle Lautstaerke`() {
        assertEquals(LautstaerkeAnstieg.VOLL, LautstaerkeAnstieg.pegel(-0.5f, 30_000L, 0L), 0f)
    }

    @Test
    fun `Startpegel NaN ergibt volle Lautstaerke`() {
        assertEquals(LautstaerkeAnstieg.VOLL, LautstaerkeAnstieg.pegel(Float.NaN, 30_000L, 0L), 0f)
    }

    @Test
    fun `Startpegel ab voll ergibt volle Lautstaerke`() {
        assertEquals(LautstaerkeAnstieg.VOLL, LautstaerkeAnstieg.pegel(1f, 30_000L, 0L), 0f)
        assertEquals(LautstaerkeAnstieg.VOLL, LautstaerkeAnstieg.pegel(2f, 30_000L, 15_000L), 0f)
    }

    @Test
    fun `negative vergangene Zeit ergibt den Startpegel, nicht weniger`() {
        assertEquals(0.15f, LautstaerkeAnstieg.pegel(0.15f, 30_000L, -5_000L), genauigkeit)
    }

    // ---- Die Kurve selbst ------------------------------------------------------------------

    @Test
    fun `der Pegel steigt ueber die gesamte Dauer monoton`() {
        var vorher = -1f
        for (ms in 0L..30_000L step 200L) {
            val jetzt = LautstaerkeAnstieg.pegel(0.10f, 30_000L, ms)
            assertTrue("Pegel faellt bei $ms ms: $vorher -> $jetzt", jetzt >= vorher)
            vorher = jetzt
        }
    }

    @Test
    fun `der Pegel bleibt immer zwischen Startpegel und voll`() {
        for (ms in 0L..40_000L step 137L) {
            val pegel = LautstaerkeAnstieg.pegel(0.10f, 30_000L, ms)
            assertTrue("Pegel $pegel bei $ms ms unter dem Startwert", pegel >= 0.10f)
            assertTrue("Pegel $pegel bei $ms ms ueber voll", pegel <= LautstaerkeAnstieg.VOLL)
        }
    }

    @Test
    fun `die Mitte liegt geometrisch, nicht arithmetisch`() {
        // Bei geometrischer Interpolation von 0,01 auf 1,0 steht in der Mitte die Wurzel: 0,1.
        // Arithmetisch waeren es 0,505 - der Unterschied IST die Eigenschaft, um die es geht:
        // ein linear steigender Amplitudenwert klingt, als sei der Anstieg nach einem Viertel
        // der Zeit schon vorbei. Faellt dieser Test, ist die Kurve auf linear zurueckgefallen.
        assertEquals(0.1f, LautstaerkeAnstieg.pegel(0.01f, 20_000L, 10_000L), 0.001f)
    }

    // ---- Backstop --------------------------------------------------------------------------

    @Test
    fun `der Backstop liegt hinter dem letzten regulaeren Schritt`() {
        val dauer = 30_000L
        assertTrue(LautstaerkeAnstieg.backstopVerzoegerungMs(dauer) > dauer)
    }
}
