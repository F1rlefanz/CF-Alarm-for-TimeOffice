package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests der reinen Entscheidungsfunktionen von [DeviceLocalFlagsGuard].
 *
 * Der Fall, den das absichert (Review-Befund, 10.08.2026): der `settings`-DataStore liegt im
 * Android-Backup - richtigerweise, denn er enthaelt Wecker-, Dimmer- und DND-Einstellungen. Er
 * enthaelt aber AUCH die "schon abgelehnt"-Flags der Onboarding-Hinweise. Nach einem Restore auf
 * ein neues Geraet kamen die mit, und die App fragte dort nie wieder nach Akku-Ausnahme und
 * "Pause bei Nichtnutzung" - den zwei Einstellungen, die in diesem Projekt nachweislich Wecker
 * verschluckt haben. Ein selektiver Backup-Ausschluss einzelner Schluessel ist nicht moeglich
 * (ein Preferences-Store ist EINE Datei), deshalb der Marker-Waechter.
 */
class DeviceLocalFlagsGuardTest {

    @Test
    fun `abweichender Marker bedeutet Geraetewechsel und setzt zurueck`() {
        assertTrue(DeviceLocalFlagsGuard.shouldResetFlags("Fairphone/FP6/…", "google/sdk_gphone64/…"))
    }

    @Test
    fun `gleicher Marker setzt nicht zurueck`() {
        assertFalse(DeviceLocalFlagsGuard.shouldResetFlags("Fairphone/FP6/…", "Fairphone/FP6/…"))
    }

    /**
     * Fehlender Marker heisst Erstinstallation ODER Bestandsinstall aus der Zeit vor dem Waechter.
     * In beiden Faellen darf NICHT zurueckgesetzt werden - sonst verliert ein laufender
     * Bestandsinstall seine bereits weggetippten Hinweise. Die bewusst akzeptierte Grenze: ein
     * Restore aus einem Backup, das vor dieser Version entstand, faellt noch in die alte Falle.
     */
    @Test
    fun `fehlender Marker setzt nicht zurueck`() {
        assertFalse(DeviceLocalFlagsGuard.shouldResetFlags(null, "google/sdk_gphone64/…"))
    }

    @Test
    fun `alle vier geraetelokalen Flags werden erkannt`() {
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("battery_prompt_dismissed"))
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("unused_app_restrictions_dismissed"))
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("timeoffice_health_prompt_dismissed"))
        // Praefix-Muster: die OEM-Flags tragen den Herstellernamen im Schluessel.
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("oem_hint_shown_SAMSUNG"))
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("oem_hint_shown_XIAOMI"))
    }

    /**
     * Die Gegenprobe, die den Waechter ueberhaupt sicher macht: er darf KEINE echten
     * Nutzereinstellungen anfassen. Alles im selben Store, was Konfiguration ist, muss unberuehrt
     * bleiben.
     */
    @Test
    fun `echte Nutzereinstellungen werden nicht angefasst`() {
        listOf(
            "shift_config", "snooze_minutes", "master_pause_until", "dim_rules",
            "dim_overlay_strength", "dnd_toggles", "dnd_policy", "selected_calendar_ids",
            "alarm_skip_state", "device_local_flags_marker"
        ).forEach { key ->
            assertFalse(
                "'$key' ist keine geraetelokale Onboarding-Markierung und darf nicht zurueckgesetzt werden",
                DeviceLocalFlagsGuard.isDeviceLocalKey(key)
            )
        }
    }

    /** Ein Schluessel, der nur AEHNLICH heisst, darf nicht mitgeloescht werden. */
    @Test
    fun `aehnlich benannte Schluessel treffen nicht`() {
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey("battery_prompt_dismissed_at"))
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey("my_oem_hint_shown_SAMSUNG"))
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey(""))
    }
}
