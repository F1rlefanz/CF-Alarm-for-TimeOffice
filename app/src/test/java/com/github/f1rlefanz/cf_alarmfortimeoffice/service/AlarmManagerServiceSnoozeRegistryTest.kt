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
 * Seit v1.23.0 dient derselbe Merker einem ZWEITEN Zweck: `restorePendingSnoozes()` setzt einen
 * schwebenden Snooze nach einem Reboot neu. Deshalb tragen die Eintraege jetzt auch die
 * Anzeigedaten - und deshalb muss der zweiteilige Altbestand weiter lesbar bleiben.
 *
 * Hier getestet ist nur der reine Teil (Kodierung/Parsen/Selbstreinigung) - das
 * SharedPreferences-Schreiben und der AlarmManager-Cancel sind Android-Raender.
 */
class AlarmManagerServiceSnoozeRegistryTest {

    @Test
    fun `Eintrag kodiert und parst alle vier Felder verlustfrei`() {
        val entry = AlarmManagerService.encodeSnoozeEntry(
            alarmId = -629498222,
            triggerTime = 1_775_000_000_000L,
            shiftName = "Fruehdienst",
            shiftStartTimeFormatted = "05:48"
        )

        val parsed = AlarmManagerService.parseSnoozeEntry(entry)

        assertEquals(-629498222, parsed?.id)
        assertEquals(1_775_000_000_000L, parsed?.triggerTime)
        assertEquals("Fruehdienst", parsed?.shiftName)
        assertEquals("05:48", parsed?.shiftStartTimeFormatted)
    }

    /**
     * Der Merker ist die EINZIGE Spur eines schwebenden Snooze. Wuerde ein Eintrag aus einer
     * Version vor v1.23.0 (nur "id|triggerTime") jetzt als kaputt gelten, verloere genau der
     * Nutzer, der ueber die Aktualisierung hinweg schlummert, sowohl die Wiederherstellung als
     * auch die Moeglichkeit, diesen Snooze noch abzubrechen.
     */
    @Test
    fun `zweiteiliger Altbestand bleibt lesbar - ohne Anzeigedaten`() {
        val parsed = AlarmManagerService.parseSnoozeEntry("4711|1775000000000")

        assertEquals(4711, parsed?.id)
        assertEquals(1_775_000_000_000L, parsed?.triggerTime)
        assertEquals("", parsed?.shiftName)
        assertEquals("", parsed?.shiftStartTimeFormatted)
    }

    /**
     * Das Trennzeichen wird aus den Anzeigetexten ENTFERNT statt escaped: ein ersetztes Zeichen in
     * einem Anzeigetext ist harmlos, ein zerbrochener Eintrag nicht - er wuerde den Snooze
     * unauffindbar machen.
     */
    @Test
    fun `Trennzeichen im Schichtnamen zerbricht den Eintrag nicht`() {
        val entry = AlarmManagerService.encodeSnoozeEntry(
            alarmId = 5,
            triggerTime = 9_000L,
            shiftName = "Frueh|dienst",
            shiftStartTimeFormatted = "05:48"
        )

        val parsed = AlarmManagerService.parseSnoozeEntry(entry)

        assertEquals(5, parsed?.id)
        assertEquals(9_000L, parsed?.triggerTime)
        assertEquals("Frueh/dienst", parsed?.shiftName)
        assertEquals("05:48", parsed?.shiftStartTimeFormatted)
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
        assertNull(AlarmManagerService.parseSnoozeEntry("1|2|3|4|5"))
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
