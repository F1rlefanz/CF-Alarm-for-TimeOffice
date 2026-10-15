package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import java.time.LocalTime
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

    // --- Kalter Prozess: Bestand kennt den klingelnden Wecker nicht mehr (Review #140) ---

    private fun definition(name: String, isSilent: Boolean) =
        ShiftDefinition(id = name, name = name, keywords = listOf(name), alarmTime = LocalTime.of(5, 0), isSilent = isSilent)

    private val definitionen = listOf(definition("AD1", isSilent = true), definition("Frühschicht", isSilent = false))

    @Test
    fun `ohne AlarmInfo entscheidet die aktuelle Definition zum Schichtnamen - still`() {
        assertTrue(AlarmReceiver.isSilentAlarm(null, "AD1", definitionen))
    }

    @Test
    fun `ohne AlarmInfo und mit lauter Definition klingelt er`() {
        assertFalse(AlarmReceiver.isSilentAlarm(null, "Frühschicht", definitionen))
    }

    @Test
    fun `manueller Wecker trifft nie eine Definition - auch nicht die stille gleichen Namens`() {
        assertFalse(AlarmReceiver.isSilentAlarm(null, "AD1 (Manuell)", definitionen))
    }

    @Test
    fun `unbekannter Name, unlesbare Definitionen oder fehlender Name - nicht still`() {
        assertFalse(AlarmReceiver.isSilentAlarm(null, "Umbenannt", definitionen))
        assertFalse(AlarmReceiver.isSilentAlarm(null, "AD1", null))
        assertFalse(AlarmReceiver.isSilentAlarm(null, null, definitionen))
    }

    @Test
    fun `liegt die AlarmInfo vor, zaehlt sie - nicht die Definition`() {
        // Schnappschuss beim Anlegen gewinnt; die Definition ist nur Rueckfall.
        assertFalse(AlarmReceiver.isSilentAlarm(alarm(isSilent = false), "AD1", definitionen))
    }
}
