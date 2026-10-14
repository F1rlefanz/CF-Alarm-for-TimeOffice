package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
        // Fuenf Teile sind seit 1.50.3 gueltig (Weckzeit), sechs nicht.
        assertNull(AlarmManagerService.parseSnoozeEntry("1|2|3|4|5|6"))
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

    // --- Weckzeit-Anker fuer "Dienst faellt aus" (1.50.3) ---------------------------------------

    private val zone = ZoneId.of("Europe/Berlin")
    private fun ms(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `fuenftes Feld Weckzeit ueberlebt Kodieren und Parsen`() {
        val weckzeit = ms("2026-10-15T05:30")
        val entry = AlarmManagerService.encodeSnoozeEntry(7, ms("2026-10-15T05:39"), "Fruehschicht", "06:00", weckzeit)

        assertEquals(weckzeit, AlarmManagerService.parseSnoozeEntry(entry)?.weckzeit)
    }

    @Test
    fun `ohne Weckzeit bleibt der Eintrag vierteilig und der Anker unbekannt`() {
        val entry = AlarmManagerService.encodeSnoozeEntry(7, 1L, "Fruehschicht", "06:00")

        assertEquals(4, entry.split("|").size)
        assertNull(AlarmManagerService.parseSnoozeEntry(entry)?.weckzeit)
    }

    @Test
    fun `erstes Schlummern nimmt die juengste geklingelte Weckzeit`() {
        val anker = AlarmManagerService.weckzeitFuerEintrag(
            vorgaenger = null,
            geklingelt = listOf(ms("2026-10-14T05:30"), ms("2026-10-15T05:30")),
            jetzt = ms("2026-10-15T05:31")
        )

        assertEquals(ms("2026-10-15T05:30"), anker)
    }

    @Test
    fun `erneutes Schlummern erbt die Weckzeit, auch ueber Mitternacht`() {
        // 23:50 geweckt, um 00:05 zum zweiten Mal geschlummert: der Anker bleibt am ALTEN Tag.
        // Die juengste geklingelte Zeit (das Schlummer-Klingeln 00:05) darf ihn nicht ersetzen.
        val vorgaenger = AlarmManagerService.Companion.SnoozeEntry(7, ms("2026-10-16T00:05"), "Spaet", "", ms("2026-10-15T23:50"))

        val anker = AlarmManagerService.weckzeitFuerEintrag(
            vorgaenger = vorgaenger,
            geklingelt = listOf(ms("2026-10-15T23:50"), ms("2026-10-16T00:05")),
            jetzt = ms("2026-10-16T00:05")
        )

        assertEquals(ms("2026-10-15T23:50"), anker)
    }

    @Test
    fun `Vorgaenger aus dem Altbestand ohne Weckzeit vererbt null statt zu raten`() {
        val vorgaenger = AlarmManagerService.Companion.SnoozeEntry(7, ms("2026-10-15T05:39"), "Frueh", "")

        val anker = AlarmManagerService.weckzeitFuerEintrag(vorgaenger, listOf(ms("2026-10-15T05:30")), ms("2026-10-15T05:40"))

        assertNull(anker)
    }

    @Test
    fun `alter Eintrag derselben ID zaehlt nicht als Vorgaenger`() {
        val gestern = AlarmManagerService.Companion.SnoozeEntry(7, ms("2026-10-14T05:39"), "Frueh", "", ms("2026-10-14T05:30"))

        val anker = AlarmManagerService.weckzeitFuerEintrag(gestern, listOf(ms("2026-10-15T05:30")), ms("2026-10-15T05:31"))

        assertEquals(ms("2026-10-15T05:30"), anker)
    }

    @Test
    fun `ohne gemerkte Weckzeit kein Anker - nie jetzt als Ersatz`() {
        assertNull(AlarmManagerService.weckzeitFuerEintrag(null, emptyList(), ms("2026-10-15T05:31")))
        // Eine Weckzeit von vor drei Stunden gehoert nicht zu diesem Schlummern.
        assertNull(AlarmManagerService.weckzeitFuerEintrag(null, listOf(ms("2026-10-15T02:00")), ms("2026-10-15T05:31")))
    }

    @Test
    fun `Freigabe trifft den Snooze ihres Tages, nicht den des Nachbartags`() {
        val heute = AlarmManagerService.encodeSnoozeEntry(1, ms("2026-10-15T05:39"), "Frueh", "06:00", ms("2026-10-15T05:30"))
        val morgen = AlarmManagerService.encodeSnoozeEntry(2, ms("2026-10-16T00:09"), "Nacht", "", ms("2026-10-16T00:00"))
        val gesternSpaet = AlarmManagerService.encodeSnoozeEntry(3, ms("2026-10-15T00:04"), "Spaet", "", ms("2026-10-14T23:55"))

        val ids = AlarmManagerService.snoozesFuerFreigabe(setOf(heute, morgen, gesternSpaet), LocalDate.parse("2026-10-15"), zone)

        assertEquals(listOf(1), ids)
    }

    @Test
    fun `Freigabe laesst manuelle Wecker und Eintraege ohne Anker klingeln`() {
        val manuell = AlarmManagerService.encodeSnoozeEntry(1, ms("2026-10-15T05:39"), "Frueh (Manuell)", "", ms("2026-10-15T05:30"))
        val ohneAnker = AlarmManagerService.encodeSnoozeEntry(2, ms("2026-10-15T05:39"), "Frueh", "06:00")
        val altZweiteilig = "3|${ms("2026-10-15T05:39")}"

        val ids = AlarmManagerService.snoozesFuerFreigabe(setOf(manuell, ohneAnker, altZweiteilig), LocalDate.parse("2026-10-15"), zone)

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `Backstop-Pruefung - leere Freigaben heissen klingeln`() {
        val eintrag = AlarmManagerService.Companion.SnoozeEntry(1, ms("2026-10-15T05:39"), "Frueh", "", ms("2026-10-15T05:30"))

        assertFalse(AlarmManagerService.gehoertSnoozeZuFreiemTag(eintrag, emptySet(), zone))
        assertTrue(AlarmManagerService.gehoertSnoozeZuFreiemTag(eintrag, setOf(LocalDate.parse("2026-10-15")), zone))
    }

    @Test
    fun `Zurueckschreiben behaelt den gerade gefeuerten Eintrag als Anker-Spur, Neu-Armieren nicht`() {
        val jetzt = ms("2026-10-16T00:06")
        val gefeuert = AlarmManagerService.encodeSnoozeEntry(7, ms("2026-10-16T00:05"), "Spaet", "", ms("2026-10-15T23:50"))
        val uralt = AlarmManagerService.encodeSnoozeEntry(8, ms("2026-10-15T20:00"), "Spaet", "", ms("2026-10-15T19:50"))

        // Ein fremdes Schreiben (anderer Abbruch, Boot-Restore) raeumt den frisch gefeuterten
        // Eintrag NICHT weg - sonst fiele der Anker beim erneuten Schlummern auf 00:05, den Folgetag.
        assertEquals(setOf(gefeuert), AlarmManagerService.kuerzeZumSchreiben(setOf(gefeuert, uralt), jetzt))
        // Neu armiert wird trotzdem nur Zukuenftiges.
        assertTrue(AlarmManagerService.pruneSnoozeEntries(setOf(gefeuert, uralt), jetzt).isEmpty())
    }
}
