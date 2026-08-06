package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer den Merker schwebender Snooze-Alarme in [AlarmManagerService].
 *
 * WARUM es diesen Merker gibt: Der Snooze liegt bewusst in einem EIGENEN PendingIntent-Slot
 * (`snoozeAlarmAction`), damit ihn der Maintenance-Sync beim Loeschen des gefeuerten
 * Ursprungsalarms nicht mit abraeumt. Genau daraus folgte aber ein Loch - `cancelSystemAlarm()`
 * baut ausschliesslich `enhancedAlarmAction` und trifft den Snooze-Slot damit NIE. Ein bereits
 * gestellter Snooze lief deshalb durch jede App-seitige Abschaltung hindurch (Master-Pause,
 * "Automatische Alarme aus") und klingelte mitten in einer gerade eingeschalteten Pause. Die
 * Snooze-ID ist sonst nirgends persistiert (der Ursprungsalarm ist zu dem Zeitpunkt schon aus dem
 * Repository geraeumt), also braucht der Cancel-Weg diese eigene Spur.
 *
 * Hier getestet ist nur der reine Teil (Kodierung/Parsen/Selbstreinigung) - das
 * SharedPreferences-Schreiben und der AlarmManager-Cancel sind Android-Raender.
 */
class AlarmManagerServiceSnoozeRegistryTest {

    @Test
    fun `Eintrag kodiert und parst id und triggerTime verlustfrei`() {
        val entry = AlarmManagerService.encodeSnoozeEntry(alarmId = -629498222, triggerTime = 1_775_000_000_000L)

        val parsed = AlarmManagerService.parseSnoozeEntry(entry)

        assertEquals(-629498222, parsed?.first)
        assertEquals(1_775_000_000_000L, parsed?.second)
    }

    @Test
    fun `kaputte oder fremde Eintraege liefern null statt zu werfen`() {
        // Ein Lesefehler darf den Cancel-Weg niemals sprengen: sonst bleibt der schwebende Snooze
        // erst recht stehen.
        assertNull(AlarmManagerService.parseSnoozeEntry(""))
        assertNull(AlarmManagerService.parseSnoozeEntry("42"))
        assertNull(AlarmManagerService.parseSnoozeEntry("42|nicht-numerisch"))
        assertNull(AlarmManagerService.parseSnoozeEntry("keine-zahl|1000"))
        assertNull(AlarmManagerService.parseSnoozeEntry("1|2|3"))
    }

    @Test
    fun `Selbstreinigung entfernt verstrichene Snooze-Zeiten und behaelt kuenftige`() {
        val now = 1_000_000L
        val entries = setOf(
            AlarmManagerService.encodeSnoozeEntry(1, now - 1),      // gefeuert/gestoppt -> erledigt
            AlarmManagerService.encodeSnoozeEntry(2, now),           // genau jetzt -> erledigt
            AlarmManagerService.encodeSnoozeEntry(3, now + 60_000L), // steht noch aus
            "muell"
        )

        val kept = AlarmManagerService.pruneSnoozeEntries(entries, now)

        assertEquals(setOf(AlarmManagerService.encodeSnoozeEntry(3, now + 60_000L)), kept)
    }

    @Test
    fun `snoozeIdsOf liefert jede ID genau einmal und ignoriert Muell`() {
        val entries = setOf(
            AlarmManagerService.encodeSnoozeEntry(7, 2_000L),
            AlarmManagerService.encodeSnoozeEntry(7, 3_000L), // derselbe Alarm, neuer Snooze
            AlarmManagerService.encodeSnoozeEntry(8, 4_000L),
            "kaputt"
        )

        val ids = AlarmManagerService.snoozeIdsOf(entries)

        assertEquals(2, ids.size)
        assertTrue(ids.containsAll(listOf(7, 8)))
    }
}
