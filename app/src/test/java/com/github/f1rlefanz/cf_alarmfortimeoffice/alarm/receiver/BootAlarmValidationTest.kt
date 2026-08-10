package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boot-Recovery darf nicht auf einem veralteten Alarm-Snapshot loeschen.
 *
 * SZENARIO (Grund fuer diese Absicherung): Beim Boot laufen seit dem Wartungslauf-Sicherheitsnetz
 * ZWEI unabhaengige Sync-Pipelines. `performAlarmRecovery()` liest seinen Alarmbestand einmal oben,
 * holt danach Kalender-Events (Sekunden) und entscheidet erst dann pro Alarm. Korrigiert der
 * parallele Wartungslauf in diesem Fenster eine verschobene Schicht (gleiche `id`, neuer
 * `eventChecksum`) und stellt den System-Alarm korrekt, sah die Recovery danach ihren ALTEN
 * Checksum gegen den frischen Event-Checksum, erkannte einen Mismatch und loeschte den gerade
 * korrigierten Alarm - ohne ihn neu anzulegen. Der Wecker war bis zur naechsten 6h-Wartung weg.
 */
class BootAlarmValidationTest {

    @Test
    fun `unveraenderter Alarm darf normal validiert werden`() {
        assertTrue(BootAlarmValidation.snapshotStillCurrent("chk-1", "chk-1"))
    }

    @Test
    fun `parallel neu geschriebener Alarm blockiert die Entscheidung auf altem Stand`() {
        // Der Wartungslauf hat denselben Alarm korrigiert - der Snapshot ist wertlos.
        assertFalse(BootAlarmValidation.snapshotStillCurrent("chk-alt", "chk-neu"))
    }

    @Test
    fun `parallel geloeschter Alarm wird nicht mehr angefasst`() {
        // null = nicht mehr im Repository. Wuerde hier weiter entschieden, koennte der Alarm als
        // verwaister System-Alarm wieder auferstehen (Re-Arming eines geloeschten Eintrags).
        assertFalse(BootAlarmValidation.snapshotStillCurrent("chk-1", null))
    }

    @Test
    fun `leerer Checksum ist kein Freifahrtschein`() {
        // Alte Alarme ohne Checksum: gleich bleibt gleich, verschieden bleibt verschieden -
        // die Funktion darf hier keinen Sonderfall erfinden.
        assertTrue(BootAlarmValidation.snapshotStillCurrent("", ""))
        assertFalse(BootAlarmValidation.snapshotStillCurrent("", "chk-neu"))
        assertFalse(BootAlarmValidation.snapshotStillCurrent("", null))
    }
}
