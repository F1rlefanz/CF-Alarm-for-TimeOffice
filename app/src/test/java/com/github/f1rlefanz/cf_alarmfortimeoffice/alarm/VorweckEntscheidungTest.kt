package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Entscheidungstabelle des Vorweckens.
 *
 * WAS HIER GESCHUETZT WIRD: Der Vorlauf verzoegert das Posten der Wecker-Notification. Das ist auf
 * einem betroffenen Geraet der Unterschied zwischen einem bedienbaren und einem verdraengten
 * Weckbildschirm - auf jedem anderen waere es nur eine Verzoegerung ohne Gegenwert. Deshalb ist
 * die Bedingung eng, und deshalb hat sie einen eigenen Test: wer sie spaeter lockert, verzoegert
 * Wecker auf Geraeten, die das Problem gar nicht haben.
 *
 * Hergang, Messwerte und Begruendung stehen in [VorweckEntscheidung].
 */
class VorweckEntscheidungTest {

    @Test
    fun `ohne gemessene Verdraengung kein Vorlauf`() {
        assertEquals(
            "Ein Geraet ohne jede gemessene Verdraengung darf seinen Wecker nicht verzoegert bekommen",
            0L,
            VorweckEntscheidung.vorlaufMillis(
                geraetIstBetroffen = false,
                bildschirmAn = false,
                gesperrt = true
            )
        )
    }

    @Test
    fun `auf einem betroffenen Geraet wird vorgeweckt`() {
        assertEquals(
            VorweckEntscheidung.VORLAUF_MS,
            VorweckEntscheidung.vorlaufMillis(
                geraetIstBetroffen = true,
                bildschirmAn = false,
                gesperrt = true
            )
        )
    }

    @Test
    fun `bei wachem Bildschirm kein Vorlauf`() {
        // Kein Aufwecken heisst keine Gesichtsentsperrung, die verdraengen koennte.
        assertEquals(
            0L,
            VorweckEntscheidung.vorlaufMillis(
                geraetIstBetroffen = true,
                bildschirmAn = true,
                gesperrt = true
            )
        )
    }

    @Test
    fun `ohne Sperrbildschirm kein Vorlauf`() {
        // Ohne Keyguard gibt es nichts zu ueberlagern - und nichts, das die Ueberlagerung aufheben
        // koennte.
        assertEquals(
            0L,
            VorweckEntscheidung.vorlaufMillis(
                geraetIstBetroffen = true,
                bildschirmAn = false,
                gesperrt = false
            )
        )
    }

    @Test
    fun `der Vorlauf liegt ueber dem gemessenen Startabstand der Gesichtsentsperrung`() {
        // 137 ms war der gemessene Abstand Wake -> UnlockActivity am 04.09.2026; 448 ms der
        // Abstand, mit dem die Google Uhr unbehelligt blieb. Faellt der Vorlauf darunter, ist der
        // ganze Mechanismus wirkungslos - dann kaeme unser Weckbildschirm wieder zuerst.
        assert(VorweckEntscheidung.VORLAUF_MS > 448L) {
            "Vorlauf ${VorweckEntscheidung.VORLAUF_MS} ms liegt nicht ueber dem gemessenen " +
                "Abstand, mit dem die Google Uhr der Verdraengung entging (448 ms)"
        }
    }

    @Test
    fun `der Vorlauf bleibt fuer einen Wecker unspuerbar`() {
        // Obergrenze mit Absicht: das hier verzoegert die einzige Bedienoberflaeche eines
        // klingelnden Weckers. Wer den Wert hochsetzt, muss diese Zeile mit anfassen und dabei
        // erklaeren, warum das noch vertretbar ist.
        assert(VorweckEntscheidung.VORLAUF_MS <= 1_000L) {
            "Vorlauf ${VorweckEntscheidung.VORLAUF_MS} ms verzoegert den Weckbildschirm spuerbar"
        }
    }
}
