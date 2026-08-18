package com.github.f1rlefanz.cf_alarmfortimeoffice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Befund 12 (Pruefrunde 6): Ein inexakt gestellter Wecker (`setAndAllowWhileIdle`, der Fallback
 * bei fehlender Exact-Alarm-Berechtigung) traegt beim Feuern NICHT die
 * Vordergrunddienst-Startfreigabe, die exakte Alarme mitbringen. `startForegroundService()` wirft
 * dann. Vorher wurde das nur geloggt: der Wecker war damit nicht "um Minuten verzoegert", sondern
 * komplett stumm - kein Ton, keine Vibration, keine Benachrichtigung, kein Vollbild.
 *
 * Diese Tests fixieren die Verzweigung in [AlarmReceiver.starteWeckerMitNotausgang]. Ohne den Fix
 * fallen 2 und 3 um: dort gab es gar keinen zweiten Weg.
 */
class Pruefrunde6ExactAlarmNotausgangTest {

    private class Protokoll {
        val meldungen = mutableListOf<String>()
        var notausgaenge = 0
        var dienstStarts = 0
    }

    @Test
    fun `startet der Dienst, bleibt der Notausgang unangetastet`() {
        val p = Protokoll()

        val gestartet = AlarmReceiver.starteWeckerMitNotausgang(
            starteDienst = { p.dienstStarts++ },
            notausgang = { p.notausgaenge++ },
            melde = { text, _ -> p.meldungen.add(text) }
        )

        assertTrue(gestartet)
        assertEquals(1, p.dienstStarts)
        assertEquals(
            "Kein zweiter Weckweg, solange der AlarmSoundService laeuft - sonst gaebe es zwei " +
                "Wecker-Besitzer und einen zweiten Klingelton",
            0,
            p.notausgaenge
        )
        assertTrue(p.meldungen.isEmpty())
    }

    @Test
    fun `lehnt das System den Vordergrund-Start ab, greift der Notausgang`() {
        val p = Protokoll()

        val gestartet = AlarmReceiver.starteWeckerMitNotausgang(
            starteDienst = { throw IllegalStateException("ForegroundServiceStartNotAllowedException") },
            notausgang = { p.notausgaenge++ },
            melde = { text, _ -> p.meldungen.add(text) }
        )

        assertFalse(
            "Der Rueckgabewert traegt die Erfolgsmeldung des Aufrufers - er darf nicht luegen",
            gestartet
        )
        assertEquals(
            "Ohne diesen Aufruf bleibt der Wecker vollstaendig stumm",
            1,
            p.notausgaenge
        )
        assertEquals(listOf(AlarmReceiver.MELDUNG_DIENST_ABGELEHNT), p.meldungen)
    }

    @Test
    fun `scheitert auch der Notausgang, laeuft keine Exception aus onReceive heraus`() {
        val p = Protokoll()

        val gestartet = AlarmReceiver.starteWeckerMitNotausgang(
            starteDienst = { throw IllegalStateException("FGS abgelehnt") },
            notausgang = { throw SecurityException("Benachrichtigung abgelehnt") },
            melde = { text, _ -> p.meldungen.add(text) }
        )

        // Wuerde die Exception herauslaufen, riss sie den Prozess mit UND verhinderte
        // pendingResult.finish() - der Broadcast bliebe offen haengen.
        assertFalse(gestartet)
        assertEquals(
            listOf(
                AlarmReceiver.MELDUNG_DIENST_ABGELEHNT,
                AlarmReceiver.MELDUNG_NOTAUSGANG_GESCHEITERT
            ),
            p.meldungen
        )
    }

    @Test
    fun `der Fehlschlag wird mit dem Wurf gemeldet, nicht nur als Text`() {
        val wurf = IllegalStateException("ForegroundServiceStartNotAllowedException")
        var gemeldet: Throwable? = null

        AlarmReceiver.starteWeckerMitNotausgang(
            starteDienst = { throw wurf },
            notausgang = { },
            melde = { _, fehler -> gemeldet = fehler }
        )

        assertEquals(wurf, gemeldet)
    }
}
