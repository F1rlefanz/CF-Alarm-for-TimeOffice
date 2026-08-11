package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt die Lade-/Sync-Entscheidung der 6h-Wartung fest.
 *
 * HINTERGRUND (echte Bugs, nicht theoretisch): die Wartung lud Kalender-Events nur, wenn der
 * letzte geplante Alarm weniger als 7 Tage entfernt lag, UND synchronisierte nur, wenn es
 * mindestens eine Schicht ohne bestehenden Alarm gab. Bei einem 14 Tage gepflegten Dienstplan war
 * beides praktisch nie der Fall - eine gestrichene (Krankschreibung) oder verschobene Schicht
 * erreichte den Delta-Sync deshalb NIE, und der Wecker klingelte fuer eine Schicht, die es nicht
 * mehr gab bzw. zur alten Zeit.
 */
class MaintenanceLoadDecisionTest {

    private val now = 1_800_000_000_000L

    private fun hours(h: Long) = TimeUnit.HOURS.toMillis(h)
    private fun days(d: Long) = TimeUnit.DAYS.toMillis(d)

    @Test
    fun `ohne zukuenftige Alarme wird immer geladen`() {
        assertTrue(
            MaintenanceLoadDecision.shouldLoadEvents(
                futureAlarmTriggerTimes = emptyList(),
                now = now,
                lastEventLoad = now - hours(1),
                forceSync = false
            )
        )
    }

    @Test
    fun `Puffer unter 7 Tagen laedt wie bisher`() {
        assertTrue(
            MaintenanceLoadDecision.shouldLoadEvents(
                futureAlarmTriggerTimes = listOf(now + days(3), now + days(6)),
                now = now,
                lastEventLoad = now - hours(1),
                forceSync = false
            )
        )
    }

    @Test
    fun `voller Puffer mit frischen Daten und weit entferntem naechsten Alarm laedt NICHT`() {
        // Sparzweck des alten Gates bleibt erhalten: kein unnoetiger Kalender-Abruf.
        assertFalse(
            MaintenanceLoadDecision.shouldLoadEvents(
                futureAlarmTriggerTimes = listOf(now + days(9), now + days(13)),
                now = now,
                lastEventLoad = now - hours(2),
                forceSync = false
            )
        )
    }

    @Test
    fun `voller Puffer aber Daten aelter als 12h laedt trotzdem`() {
        // REGRESSION: vorher blieb die Wartung im eingeschwungenen Zustand dauerhaft blind -
        // eine gestrichene Schicht in 5 Tagen wurde nie erkannt.
        assertTrue(
            MaintenanceLoadDecision.shouldLoadEvents(
                futureAlarmTriggerTimes = listOf(now + days(9), now + days(13)),
                now = now,
                lastEventLoad = now - hours(13),
                forceSync = false
            )
        )
    }

    @Test
    fun `voller Puffer und frische Daten laden trotzdem wenn der naechste Alarm nah ist`() {
        // REGRESSION: eine kurzfristige Verschiebung des morgigen Weckers muss gefunden werden,
        // auch wenn vor 2h schon geladen wurde.
        assertTrue(
            MaintenanceLoadDecision.shouldLoadEvents(
                futureAlarmTriggerTimes = listOf(now + hours(20), now + days(13)),
                now = now,
                lastEventLoad = now - hours(2),
                forceSync = false
            )
        )
    }

    @Test
    fun `noch nie geladen laedt immer`() {
        assertTrue(
            MaintenanceLoadDecision.shouldLoadEvents(
                futureAlarmTriggerTimes = listOf(now + days(13)),
                now = now,
                lastEventLoad = 0L,
                forceSync = false
            )
        )
    }

    @Test
    fun `forceSync ueberspringt jedes Gate`() {
        // Zeitzonen-Wechsel und Boot: die Weckzeiten MUESSEN neu berechnet werden.
        assertTrue(
            MaintenanceLoadDecision.shouldLoadEvents(
                futureAlarmTriggerTimes = listOf(now + days(9), now + days(13)),
                now = now,
                lastEventLoad = now - hours(1),
                forceSync = true
            )
        )
    }

    @Test
    fun `Sync laeuft auch ohne neue Schichten - Aenderungen und Streichungen brauchen ihn`() {
        assertTrue(MaintenanceLoadDecision.shouldSyncAfterLoad(loadedEventCount = 5))
    }

    @Test
    fun `leere Eventliste synchronisiert NICHT - fail-safe gegen Total-Loeschung`() {
        assertFalse(MaintenanceLoadDecision.shouldSyncAfterLoad(loadedEventCount = 0))
    }
}
