package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRUEFRUNDE 8, WELLE 5 - BEFUND A, Hintergrundseite.
 *
 * Der offene Raeumauftrag nach einer Kalender-Abwahl ueberlebt jetzt den Prozesstod (siehe
 * `PendingDeselectionCleanupStore`). Damit das etwas nuetzt, muss ihn jemand abarbeiten, ohne dass
 * der Nutzer die App je wieder oeffnet - das tut die 6h-Wartung. [AbwahlRaeumauftrag] ist die
 * Android-freie Entscheidung dahinter.
 *
 * Die Zusicherungen, die hier haengen, sind dieselben wie im Vordergrund: nur bei nachweislich
 * leerer Auswahl raeumen, bei unlesbarer Konfiguration NICHT raeumen (der Auftrag bleibt), bei
 * abgeschalteter Automatik gar nicht erst anlaufen (dort loescht `syncAlarms()` auch manuelle
 * Wecker), und einen erledigten Auftrag wirklich loeschen.
 */
class Pruefrunde8RaeumauftragWartungTest {

    private val config = ShiftConfig()

    @Test
    fun `ohne offenen Auftrag passiert nichts`() = runTest {
        var configGelesen = false
        val ergebnis = AbwahlRaeumauftrag.abarbeiten(
            auftragOffen = false,
            auswahlIstLeer = true,
            shiftConfigLesen = { configGelesen = true; config },
            raeumen = { error("darf nicht geraeumt werden") }
        )

        assertFalse(ergebnis.geraeumt)
        assertEquals(AbwahlRaeumauftrag.Merker.BEHALTEN, ergebnis.merker)
        assertFalse("Ohne Auftrag wird nicht einmal die Konfiguration gelesen", configGelesen)
    }

    /**
     * DER KERNFALL: Der Nutzer hat abgewaehlt, der Prozess starb vor dem Raeumen - die Wartung
     * holt es nach.
     */
    @Test
    fun `offener Auftrag bei leerer Auswahl wird geraeumt und faellt danach`() = runTest {
        var geraeumtMit: ShiftConfig? = null
        val ergebnis = AbwahlRaeumauftrag.abarbeiten(
            auftragOffen = true,
            auswahlIstLeer = true,
            shiftConfigLesen = { config },
            raeumen = { c -> geraeumtMit = c; true }
        )

        assertTrue(ergebnis.geraeumt)
        assertEquals(config, geraeumtMit)
        assertEquals(AbwahlRaeumauftrag.Merker.LOESCHEN, ergebnis.merker)
    }

    /** Scheitert das Raeumen, bleibt der Auftrag offen - sonst gibt es keinen dritten Anlauf. */
    @Test
    fun `ein gescheitertes Raeumen laesst den Auftrag offen`() = runTest {
        val ergebnis = AbwahlRaeumauftrag.abarbeiten(
            auftragOffen = true,
            auswahlIstLeer = true,
            shiftConfigLesen = { config },
            raeumen = { false }
        )

        assertFalse(ergebnis.geraeumt)
        assertEquals(AbwahlRaeumauftrag.Merker.BEHALTEN, ergebnis.merker)
    }

    /**
     * Ist wieder ein Kalender ausgewaehlt, ist der Auftrag hinfaellig - und Raeumen waere hier
     * sogar schaedlich: es traefe die Wecker des neu gewaehlten Dienstplans.
     */
    @Test
    fun `bei wieder ausgewaehltem Kalender wird nicht geraeumt und der Auftrag verworfen`() = runTest {
        val ergebnis = AbwahlRaeumauftrag.abarbeiten(
            auftragOffen = true,
            auswahlIstLeer = false,
            shiftConfigLesen = { error("darf gar nicht gelesen werden") },
            raeumen = { error("darf nicht geraeumt werden") }
        )

        assertFalse(ergebnis.geraeumt)
        assertEquals(AbwahlRaeumauftrag.Merker.LOESCHEN, ergebnis.merker)
    }

    /**
     * Fail-safe wie ueberall sonst: ein Defekt im Konfigurations-Store darf keine Wecker kosten.
     * Der Auftrag bleibt fuer den naechsten Lauf offen.
     */
    @Test
    fun `bei unlesbarer Schicht-Konfiguration wird nicht geraeumt und der Auftrag bleibt`() = runTest {
        val ergebnis = AbwahlRaeumauftrag.abarbeiten(
            auftragOffen = true,
            auswahlIstLeer = true,
            shiftConfigLesen = { null },
            raeumen = { error("darf nicht geraeumt werden") }
        )

        assertFalse(ergebnis.geraeumt)
        assertEquals(AbwahlRaeumauftrag.Merker.BEHALTEN, ergebnis.merker)
    }

    /**
     * MANUELLE WECKER: Bei abgeschalteter Automatik nimmt `syncAlarms()` einen anderen Zweig, der
     * auch manuelle Wecker loescht. Die stammen nicht aus dem Kalender und ueberleben eine
     * Abwahl - deshalb laeuft der Raeumlauf hier gar nicht erst an. Kalenderbasierte Wecker gibt
     * es dort ohnehin keine, der Auftrag ist also erledigt.
     */
    @Test
    fun `bei abgeschalteter Automatik wird nicht geraeumt und der Auftrag verworfen`() = runTest {
        val ergebnis = AbwahlRaeumauftrag.abarbeiten(
            auftragOffen = true,
            auswahlIstLeer = true,
            shiftConfigLesen = { ShiftConfig(autoAlarmEnabled = false) },
            raeumen = { error("manuelle Wecker duerfen nicht angefasst werden") }
        )

        assertFalse(ergebnis.geraeumt)
        assertEquals(AbwahlRaeumauftrag.Merker.LOESCHEN, ergebnis.merker)
    }
}
