package com.github.f1rlefanz.cf_alarmfortimeoffice

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.GeklingelteWeckzeiten
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Welche Weckzeit der `AlarmReceiver` der Tageslicht-Bedingung der Hue-Regeln mitgibt (#140):
 * beim Schlummern die ERSTE Weckzeit aus dem Snooze-Merker (derselbe Wecktag, dieselbe
 * Entscheidung wie beim ersten Klingeln), sonst die Weckzeit des Weckers. `null` heisst
 * "kein Tageslicht-Filter" - im Zweifel Licht.
 */
class AlarmReceiverWeckzeitFuerHueTest {

    @Test
    fun `normaler Wecker nimmt seine Weckzeit`() {
        assertEquals(1_000L, AlarmReceiver.weckzeitFuerHue(istSnooze = false, snoozeWeckzeit = 5L, alarmTrigger = 1_000L))
    }

    @Test
    fun `Schlummern nimmt die Weckzeit aus dem Merker`() {
        assertEquals(500L, AlarmReceiver.weckzeitFuerHue(istSnooze = true, snoozeWeckzeit = 500L, alarmTrigger = 1_000L))
    }

    @Test
    fun `Merker ohne Weckzeit faellt NICHT auf den Wecker zurueck - kein Filter`() {
        assertNull(AlarmReceiver.weckzeitFuerHue(istSnooze = true, snoozeWeckzeit = null, alarmTrigger = 1_000L))
    }

    @Test
    fun `ohne AlarmInfo kein Filter`() {
        assertNull(AlarmReceiver.weckzeitFuerHue(istSnooze = false, snoozeWeckzeit = null, alarmTrigger = null))
    }

    // Review #140: im kalt gestarteten Prozess wirft der Alarm-Bestand den gerade klingelnden
    // (verstrichenen) Wecker beim Laden weg - die Weckzeit muss dann aus dem Intent kommen.

    @Test
    fun `kalter Prozess - leerer Bestand, Weckzeit aus dem Intent-Extra`() {
        assertEquals(1_000L, AlarmReceiver.geplanteWeckzeit(extraWert = 1_000L, ausBestand = null))
        assertEquals(
            1_000L,
            AlarmReceiver.weckzeitFuerHue(
                istSnooze = false,
                snoozeWeckzeit = null,
                alarmTrigger = AlarmReceiver.geplanteWeckzeit(extraWert = 1_000L, ausBestand = null)
            )
        )
    }

    @Test
    fun `Extra geht dem Bestand vor`() {
        assertEquals(1_000L, AlarmReceiver.geplanteWeckzeit(extraWert = 1_000L, ausBestand = 2_000L))
    }

    @Test
    fun `alter PendingIntent ohne Extra faellt auf den Bestand zurueck`() {
        assertEquals(2_000L, AlarmReceiver.geplanteWeckzeit(extraWert = -1L, ausBestand = 2_000L))
        assertEquals(2_000L, AlarmReceiver.geplanteWeckzeit(extraWert = 0L, ausBestand = 2_000L))
    }

    @Test
    fun `weder Extra noch Bestand - kein Filter`() {
        assertNull(AlarmReceiver.geplanteWeckzeit(extraWert = -1L, ausBestand = null))
    }

    // Review #140: der Snooze-Eintrag erbt seine Weckzeit aus GeklingelteWeckzeiten. Merkte der
    // Receiver dort die KLINGELZEIT, entschiede ein verspaeteter Wecker beim Schlummern anders.

    @Test
    fun `verspaetet zugestellter Wecker - Schlummern sieht dieselbe Weckzeit wie das erste Klingeln`() {
        val geplant = 1_760_000_400_000L // ganze Minute
        val geklingelt = geplant + 125_000L // 2 min 5 s zu spaet (inexakter Fallback/Doze)
        val geschlummert = geklingelt + 60_000L

        val erstesKlingeln = AlarmReceiver.weckzeitFuerHue(
            istSnooze = false,
            snoozeWeckzeit = null,
            alarmTrigger = AlarmReceiver.geplanteWeckzeit(extraWert = geplant, ausBestand = null)
        )

        // Was der Receiver beim ersten Klingeln merkt (GeklingelteWeckzeiten.merke rundet ab) ...
        val gemerkt = GeklingelteWeckzeiten.aufMinute(AlarmReceiver.zuMerkendeWeckzeit(extraWert = geplant, jetzt = geklingelt))
        // ... und was der Snooze-Eintrag daraus erbt.
        val snoozeWeckzeit = AlarmManagerService.weckzeitFuerEintrag(
            vorgaenger = null,
            geklingelt = listOf(gemerkt),
            jetzt = geschlummert
        )
        val schlummerKlingeln = AlarmReceiver.weckzeitFuerHue(
            istSnooze = true,
            snoozeWeckzeit = snoozeWeckzeit,
            alarmTrigger = null
        )

        assertEquals(geplant, erstesKlingeln)
        assertEquals(erstesKlingeln, schlummerKlingeln)
    }

    @Test
    fun `ohne Extra - Schlummer-Broadcast oder alter PendingIntent - wird jetzt gemerkt`() {
        assertEquals(5_000L, AlarmReceiver.zuMerkendeWeckzeit(extraWert = -1L, jetzt = 5_000L))
        assertEquals(5_000L, AlarmReceiver.zuMerkendeWeckzeit(extraWert = 0L, jetzt = 5_000L))
    }

    @Test
    fun `Weckzeit in der Zukunft wird nicht gemerkt - der Merker zaehlt nur bis jetzt`() {
        assertEquals(5_000L, AlarmReceiver.zuMerkendeWeckzeit(extraWert = 9_000L, jetzt = 5_000L))
    }
}
