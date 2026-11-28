package com.github.f1rlefanz.cf_alarmfortimeoffice

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stummer Wecker = Lichtwecker (#251, SPEC E8 und 3.3 Punkt 5): [AlarmReceiver.fuehreWeckvorgangAus]
 * ist die Verzweigung, die `onReceive` nach Skip-, Freigabe- und Stille-Pruefung ausfuehrt.
 *
 * Bei einem stillen Wecker bleibt der Ton-Dienst aus (kein Ton, keine Vibration, kein Vollbild, keine
 * Notification 2002), die Hue-Regeln laufen aber wie im lauten Zweig: hinter der Entsperrung und unter
 * demselben Budget. Bis #251 stieg `onReceive` bei `isSilent` vor beidem aus - das Licht blieb aus.
 *
 * Mutationsprobe: mit `if (still) return true` am Anfang (dem alten Ausstieg) sind der erste und der
 * letzte Test rot.
 */
class AlarmReceiverStilleHueTest {

    private val schritte = mutableListOf<String>()

    private suspend fun weckvorgang(
        still: Boolean,
        userUnlocked: Boolean = true,
        hue: suspend () -> Unit = { schritte += "hue" }
    ): Boolean = AlarmReceiver.fuehreWeckvorgangAus(
        still = still,
        userUnlocked = userUnlocked,
        starteTon = { schritte += "ton" },
        armiereNebenketten = { schritte += "nebenketten" },
        fuehreHueAus = hue
    )

    @Test
    fun `ein stiller Wecker fuehrt die Hue-Regeln aus und startet keinen Ton-Dienst`() = runTest {
        assertTrue(weckvorgang(still = true))
        // Auch die Neubewertung von Dimmer/DND bleibt beim stillen Wecker aus - wie vor #251: seine
        // Schichtspanne beendet das Dimm-Fenster ohnehin (Kommentar an `GeklingelteWeckzeiten.merke`).
        assertEquals(listOf("hue"), schritte)
    }

    @Test
    fun `der laute Wecker bleibt - erst der Ton, dann Dimmer und DND, dann Hue`() = runTest {
        assertTrue(weckvorgang(still = false))
        assertEquals(listOf("ton", "nebenketten", "hue"), schritte)
    }

    @Test
    fun `vor der Entsperrung klingelt der Wecker, Hue laeuft nicht`() = runTest {
        assertTrue(weckvorgang(still = false, userUnlocked = false))
        assertEquals(listOf("ton"), schritte)
    }

    @Test
    fun `auch im stillen Zweig ist Hue auf dasselbe Budget gedeckelt`() = runTest {
        val imBudget = weckvorgang(still = true, hue = {
            schritte += "hue"
            delay(AlarmReceiver.HUE_EXECUTION_BUDGET_MS + 10_000)
            schritte += "hue fertig"
        })
        assertFalse("das Budget ist abgelaufen", imBudget)
        assertEquals(AlarmReceiver.HUE_EXECUTION_BUDGET_MS, currentTime)
        assertEquals(listOf("hue"), schritte)
    }
}
