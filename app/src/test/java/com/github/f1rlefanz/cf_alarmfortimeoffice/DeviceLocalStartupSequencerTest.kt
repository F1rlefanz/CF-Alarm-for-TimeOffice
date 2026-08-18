package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalStartupGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prueft [ensureDeviceLocalStartupRuns] - die Entscheidung, ob der geraetelokale Startblock
 * (Geraetewechsel-Check, Aufheben einer mitgesicherten Master-Pause, Abgleich des
 * Direct-Boot-Spiegels) sofort laeuft oder auf das Entsperren wartet.
 *
 * DER REGRESSIONSFALL: zwischen der Abfrage "ist entsperrt?" und dem tatsaechlichen
 * `registerReceiver()` liegt ein Fenster, denn der ganze Startblock laeuft asynchron im
 * Application-Scope. Entsperrt der Nutzer genau darin, ist `ACTION_USER_UNLOCKED` schon
 * verschickt - der Broadcast ist NICHT sticky und wird nicht nachgeliefert. Ohne die
 * Nachpruefung nach dem Aufsetzen laeuft der Block in diesem Prozess NIE, waehrend der Prozess
 * als gewoehnlicher App-Prozess weiterlebt.
 */
class DeviceLocalStartupSequencerTest {

    @Test
    fun `bei entsperrtem Nutzer laeuft der Block sofort und es wird nichts aufgesetzt`() = runTest {
        var armed = 0
        var runs = 0

        ensureDeviceLocalStartupRuns(
            isUserUnlocked = { true },
            armUnlockWatchers = { armed++ },
            runChecks = { runs++ }
        )

        assertEquals("Der Block muss sofort laufen", 1, runs)
        assertEquals("Ohne Sperre braucht es keine Wartekonstruktion", 0, armed)
    }

    @Test
    fun `bleibt der Nutzer gesperrt, wird nur gewartet`() = runTest {
        var armed = 0
        var runs = 0

        ensureDeviceLocalStartupRuns(
            isUserUnlocked = { false },
            armUnlockWatchers = { armed++ },
            runChecks = { runs++ }
        )

        assertEquals("Es muss auf das Entsperren gewartet werden", 1, armed)
        assertEquals("Im gesperrten Zustand darf der CE-Block NICHT laufen", 0, runs)
    }

    /**
     * (a) Entsperren genau im Fenster zwischen Abfrage und `registerReceiver()`.
     * Dreht man die Nachpruefung zurueck, faellt dieser Test um.
     */
    @Test
    fun `entsperrt der Nutzer waehrend des Aufsetzens, wird der Block nachgezogen`() = runTest {
        var unlocked = false
        var runs = 0

        ensureDeviceLocalStartupRuns(
            isUserUnlocked = { unlocked },
            // Der Broadcast faellt genau hier - waehrend registriert wird - und ist damit weg.
            armUnlockWatchers = { unlocked = true },
            runChecks = { runs++ }
        )

        assertEquals("Der verpasste Broadcast muss nachgezogen werden", 1, runs)
    }

    /**
     * (b) Scheitert das Aufsetzen selbst, ist Nichtstun die schlechteste Antwort: die
     * Nachpruefung muss trotzdem stattfinden.
     */
    @Test
    fun `scheitert das Aufsetzen, wird bei entsperrtem Nutzer trotzdem nachgezogen`() = runTest {
        var unlocked = false
        var runs = 0
        var geworfen: IllegalStateException? = null

        try {
            ensureDeviceLocalStartupRuns(
                isUserUnlocked = { unlocked },
                armUnlockWatchers = {
                    unlocked = true
                    throw IllegalStateException("registerReceiver abgelehnt")
                },
                runChecks = { runs++ }
            )
        } catch (e: IllegalStateException) {
            geworfen = e
        }

        assertEquals("Auch bei gescheiterter Registrierung muss nachgezogen werden", 1, runs)
        assertTrue("Der Fehlschlag darf nicht verschluckt werden", geworfen != null)
    }

    /**
     * Scheitert das Aufsetzen, WAEHREND der Nutzer weiterhin gesperrt ist, darf kein CE-Zugriff
     * erzwungen werden - ein Lauf im gesperrten Zustand liest den `settings`-Store still leer und
     * vergiftet den DataStore-Cache fuer die restliche Prozesslaufzeit.
     */
    @Test
    fun `gescheitertes Aufsetzen erzwingt bei gesperrtem Nutzer keinen Lauf`() = runTest {
        var runs = 0

        try {
            ensureDeviceLocalStartupRuns(
                isUserUnlocked = { false },
                armUnlockWatchers = { throw IllegalStateException("registerReceiver abgelehnt") },
                runChecks = { runs++ }
            )
        } catch (_: IllegalStateException) {
            // erwartet
        }

        assertEquals("Im gesperrten Zustand darf der CE-Block NICHT laufen", 0, runs)
    }

    /**
     * Der Nachzug aus dem Fenster und ein spaeter doch noch eintreffender Entsperr-Anlass duerfen
     * den Block zusammen nur EINMAL ausfuehren. Geschuetzt ist das allein durch das Gate.
     */
    @Test
    fun `Nachzug und spaeterer Entsperr-Anlass laufen zusammen nur einmal`() = runTest {
        val gate = DeviceLocalStartupGate()
        val echteLaeufe = AtomicInteger(0)
        var unlocked = false
        val block: suspend () -> Unit = {
            if (gate.claimRun()) echteLaeufe.incrementAndGet()
        }

        ensureDeviceLocalStartupRuns(
            isUserUnlocked = { unlocked },
            armUnlockWatchers = { unlocked = true },
            runChecks = block
        )
        // Der Receiver war doch noch rechtzeitig scharf und feuert zusaetzlich.
        block()

        assertEquals("Der Startblock darf hoechstens einmal wirklich laufen", 1, echteLaeufe.get())
        assertTrue(gate.hasRun)
        assertFalse("Das Gate ist verbraucht", gate.claimRun())
    }
}
