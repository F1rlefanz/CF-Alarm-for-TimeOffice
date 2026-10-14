package com.github.f1rlefanz.cf_alarmfortimeoffice

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
}
