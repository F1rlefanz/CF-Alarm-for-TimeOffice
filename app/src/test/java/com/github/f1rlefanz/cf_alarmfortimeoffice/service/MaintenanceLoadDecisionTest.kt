package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import com.github.f1rlefanz.cf_alarmfortimeoffice.Kerntest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Haelt die Sync-Entscheidung der 6h-Wartung NACH dem Laden fest.
 *
 * HINTERGRUND (echte Bugs, nicht theoretisch): die Wartung lud Kalender-Events nur, wenn der
 * letzte geplante Alarm weniger als 7 Tage entfernt lag, UND synchronisierte nur, wenn es
 * mindestens eine Schicht ohne bestehenden Alarm gab. Bei einem 14 Tage gepflegten Dienstplan war
 * beides praktisch nie der Fall - eine gestrichene (Krankschreibung) oder verschobene Schicht
 * erreichte den Delta-Sync deshalb NIE, und der Wecker klingelte fuer eine Schicht, die es nicht
 * mehr gab bzw. zur alten Zeit.
 *
 * Die LADE-Entscheidung (`shouldLoadEvents`: Puffer < 7 Tage, Abfrage >= 12 h, Wecker <= 48 h)
 * gibt es seit 1.48.0 nicht mehr - geladen wird bei JEDEM Lauf. Ihre Tests sind mit ihr entfallen;
 * dass ein regulaerer Lauf auch in genau der Lage laedt, in der sie frueher uebersprang, haelt
 * `WartungLaedtBeiJedemLaufTest` am echten Dienst fest. Was hier bleibt, ist die Leerlisten-Sperre.
 */
class MaintenanceLoadDecisionTest {

    @Category(Kerntest::class)
    @Test
    fun `Sync laeuft auch ohne neue Schichten - Aenderungen und Streichungen brauchen ihn`() {
        assertTrue(MaintenanceLoadDecision.shouldSyncAfterLoad(loadedEventCount = 5))
    }

    @Category(Kerntest::class)
    @Test
    fun `schon ein einziges Event genuegt fuer den Sync`() {
        assertTrue(MaintenanceLoadDecision.shouldSyncAfterLoad(loadedEventCount = 1))
    }

    @Category(Kerntest::class)
    @Test
    fun `leere Eventliste synchronisiert NICHT - fail-safe gegen Total-Loeschung`() {
        assertFalse(MaintenanceLoadDecision.shouldSyncAfterLoad(loadedEventCount = 0))
    }
}
