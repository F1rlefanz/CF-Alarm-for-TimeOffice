package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import org.junit.Assert.assertEquals
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

/**
 * Ein Kennungswechsel im abonnierten Dienstplan-Feed ist KEINE geloeschte Schicht.
 *
 * SZENARIO (am Geraet belegt, 20.08.2026): Google liest den abonnierten TimeOffice-ICS-Feed alle
 * paar Tage neu ein und vergibt dabei ALLEN Terminen neue Event-IDs - bei voellig unveraenderten
 * Schichten und Weckzeiten (11 Wecker geloescht, 11 neu angelegt, Schnittmenge der Event-IDs leer).
 * Zwischen so einem Neueinlesen und dem naechsten vollstaendigen Sync (bis zu 6 h) tragen ALLE
 * gespeicherten Alarme Kennungen, die es im Kalender nicht mehr gibt. `performAlarmRecovery()`
 * kannte bisher nur "Kennung nicht gefunden = Termin geloescht" und raeumte bei einem Neustart in
 * diesem Fenster den gesamten Bestand samt Systemweckern ab - ohne Neuanlage. Kam der Sync im
 * selben Boot-Ablauf nicht durch (kein Netz nach dem Neustart, unvollstaendige Kalenderliste),
 * war der Nutzer ohne Wecker.
 */
class BootAlarmKennungswechselTest {

    private val fruehdienst = BootAlarmValidation.Weckpunkt(triggerTime = 4_000L, shiftId = "frueh")
    private val spaetdienst = BootAlarmValidation.Weckpunkt(triggerTime = 9_000L, shiftId = "spaet")
    private val lesung = setOf(fruehdienst, spaetdienst)

    @Test
    fun `neue Kennung bei gleicher Weckzeit und Schicht loescht den Wecker nicht`() {
        // Genau der belegte Fall: der Termin ist da, nur unter neuer Event-ID.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.NUR_NEUE_KENNUNG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `der ganze Bestand ueberlebt ein Feed-Neueinlesen`() {
        // 11 Alarme, 11 neue Kennungen - kein einziger darf geloescht werden.
        val bestand = (1..11).map { i -> BootAlarmValidation.Weckpunkt(i * 1_000L, "schicht-$i") }
        val urteile = bestand.map { alarm ->
            BootAlarmValidation.beurteile(
                alarmTriggerTime = alarm.triggerTime,
                alarmShiftId = alarm.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = bestand.toSet()
            )
        }
        assertTrue(urteile.all { it == BootAlarmValidation.AlarmUrteil.NUR_NEUE_KENNUNG })
    }

    @Test
    fun `wirklich gestrichene Schicht wird weiterhin geloescht`() {
        // Der Kennungs-Freibrief darf keine echte Streichung verdecken.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 20_000L,
                alarmShiftId = "nacht",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `gleiche Weckzeit aber andere Schicht ist kein Kennungswechsel`() {
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = "nacht",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `gleiche Schicht aber verschobene Weckzeit ist kein Kennungswechsel`() {
        // Eine verschobene Schicht ist eine echte Aenderung - der Sync legt sie neu an.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime + 1,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `ohne auswertbare Schichterkennung wird nie wegen fehlender Kennung geloescht`() {
        // weckpunkte == null: die Schichterkennung ist gescheitert. "Im Zweifel wecken" - lieber
        // ein Wecker zu viel (er klingelt hoerbar) als ein geloeschter Bestand (der ist still).
        assertEquals(
            BootAlarmValidation.AlarmUrteil.NUR_NEUE_KENNUNG,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = 20_000L,
                alarmShiftId = "nacht",
                alarmChecksum = "chk-alt",
                terminChecksum = null,
                weckpunkte = null
            )
        )
    }

    @Test
    fun `bestehende Kennung mit geaendertem Termin wird weiterhin geloescht`() {
        // Unveraendertes Verhalten: gleiche Kennung, anderer Inhalt.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_GEAENDERT,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = "chk-neu",
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `unveraenderter Termin wird ganz normal wiederhergestellt`() {
        assertEquals(
            BootAlarmValidation.AlarmUrteil.WIEDERHERSTELLEN,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-1",
                terminChecksum = "chk-1",
                weckpunkte = lesung
            )
        )
    }

    @Test
    fun `der Checksum-Vergleich braucht die Weckpunkte nicht`() {
        // Die Kennung ist da - dann entscheidet allein der Inhalt, auch wenn die Schichterkennung
        // gescheitert ist (weckpunkte == null). Sonst wuerde ein Fehler der Schichterkennung das
        // Erkennen echter Terminaenderungen mit abschalten.
        assertEquals(
            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_GEAENDERT,
            BootAlarmValidation.beurteile(
                alarmTriggerTime = fruehdienst.triggerTime,
                alarmShiftId = fruehdienst.shiftId,
                alarmChecksum = "chk-alt",
                terminChecksum = "chk-neu",
                weckpunkte = null
            )
        )
    }
}
