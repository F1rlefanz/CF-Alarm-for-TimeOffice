package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Test fuer [AlarmReceiver.isSilentAlarm] - die reine Gate-Entscheidung fuer "Stille
 * Schicht" (Feature D).
 *
 * Der umgebende `onReceive`-Fluss selbst ist wegen goAsync()/Hilt/Context Android-gebunden und
 * bleibt bewusst ungetestet (gleiche Konvention wie [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService]) -
 * aber die eigentliche Entscheidung "wird dieser Alarm stumm behandelt?" ist als reine Funktion
 * ausgelagert und hier fixiert:
 * - isSilent = true  -> AlarmReceiver startet weder AlarmSoundService noch executeHueRulesForAlarm()
 *   (beide haengen im selben onReceive-Zweig hinter demselben Gate, siehe AlarmReceiver.kt)
 * - isSilent = false / AlarmInfo fehlt (Lookup fehlgeschlagen) -> normales Wecken (fail-safe,
 *   dieselbe "im Zweifel wecken"-Haltung wie beim Skip-Check nebenan)
 */
class AlarmReceiverSilentGateTest {

    private fun alarm(isSilent: Boolean) = AlarmInfo(
        id = 1,
        shiftId = "oncall",
        shiftName = "AD1",
        triggerTime = 111L,
        formattedTime = "05:00",
        isSilent = isSilent
    )

    @Test
    fun `isSilent = true wird als stiller Alarm erkannt`() {
        assertTrue(AlarmReceiver.isSilentAlarm(alarm(isSilent = true)))
    }

    @Test
    fun `isSilent = false laesst den Alarm normal klingeln`() {
        assertFalse(AlarmReceiver.isSilentAlarm(alarm(isSilent = false)))
    }

    @Test
    fun `fehlende AlarmInfo gilt fail-safe als NICHT still - im Zweifel wecken`() {
        assertFalse(AlarmReceiver.isSilentAlarm(null))
    }
}
