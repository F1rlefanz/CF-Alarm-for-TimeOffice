package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Entscheidungstabelle des Vorweckens.
 *
 * WAS HIER GESCHUETZT WIRD: Der Vorlauf verzoegert das Posten der Wecker-Notification. Auf einem
 * betroffenen Geraet ist das der Unterschied zwischen einem bedienbaren und einem verdraengten
 * Weckbildschirm; auf jedem anderen kostet er [VorweckEntscheidung.VORLAUF_MS], waehrend der Ton
 * bereits laeuft. Diese Abwaegung ist bewusst zugunsten des Vorweckens entschieden (04.09.2026,
 * Begruendung in [VorweckEntscheidung]) - die Bedingung haengt seitdem NUR noch am Systemzustand.
 *
 * WER HIER EINE DRITTE BEDINGUNG ERGAENZEN WILL, muss vorher `reference/vorwecken.md` lesen: die
 * gestrichene dritte Bedingung war ein gespeicherter Merker, und der hat den Schutz zweimal durch
 * seinen eigenen Erfolg abgeschaltet und ausserdem den Wecker nach einem Neustart ohne
 * Entsperrung ungeschuetzt gelassen (CE-Storage, im Direct Boot nicht lesbar).
 */
class VorweckEntscheidungTest {

    @Test
    fun `bei dunklem gesperrtem Bildschirm wird vorgeweckt`() {
        assertEquals(
            VorweckEntscheidung.VORLAUF_MS,
            VorweckEntscheidung.vorlaufMillis(
                bildschirmAn = false,
                gesperrt = true
            )
        )
    }

    @Test
    fun `das Vorwecken haengt an keinem gespeicherten Zustand`() {
        // DER KERN DER AENDERUNG VON 1.39.5: Es gibt keine Eingabe mehr, die aus einer Datei
        // kommt. Genau deshalb greift das Vorwecken auch beim ersten Wecker nach einem Neustart
        // ohne Entsperrung - der Fall, in dem der frueher noetige Merker im CE-Storage nicht
        // lesbar war. Der Test haelt die Signatur klein: wer eine dritte Eingabe ergaenzt, muss
        // hier vorbei.
        // Absichtlich eine TYPZUWEISUNG und keine Reflexion: kommt eine dritte Eingabe dazu,
        // scheitert schon die Uebersetzung dieser Zeile - das ist frueher und verlaesslicher als
        // eine Laufzeitpruefung (und braucht kein kotlin-reflect).
        val nurSystemzustand: (Boolean, Boolean) -> Long = VorweckEntscheidung::vorlaufMillis
        assertEquals(
            "vorlaufMillis darf nur Systemzustand lesen (bildschirmAn, gesperrt) - kein " +
                "gespeicherter Merker, der im Direct Boot fehlen kann",
            VorweckEntscheidung.VORLAUF_MS,
            nurSystemzustand(false, true)
        )
    }

    @Test
    fun `bei wachem Bildschirm kein Vorlauf`() {
        // Kein Aufwecken heisst keine Gesichtsentsperrung, die verdraengen koennte.
        assertEquals(
            0L,
            VorweckEntscheidung.vorlaufMillis(
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
        // Obergrenze mit Absicht, und seit 1.39.5 mit mehr Gewicht: der Vorlauf trifft jetzt
        // JEDEN Wecker bei dunklem, gesperrtem Bildschirm, nicht mehr nur den auf einem
        // betroffenen Geraet. Wer den Wert hochsetzt, muss diese Zeile mit anfassen und dabei
        // erklaeren, warum das noch vertretbar ist.
        assert(VorweckEntscheidung.VORLAUF_MS <= 1_000L) {
            "Vorlauf ${VorweckEntscheidung.VORLAUF_MS} ms verzoegert den Weckbildschirm spuerbar"
        }
    }
}
