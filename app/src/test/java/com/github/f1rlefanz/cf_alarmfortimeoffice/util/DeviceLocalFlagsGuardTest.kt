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
 *
 * Seit Issue #84 haelt der Waechter eine ZWEITE Gruppe: das Gedaechtnis der Kalender-Warnung
 * (`CalendarUnavailablePrefs`). Gleiche Mechanik, anderer Anlass - und anders als die reinen
 * Laufzeit-Spiegel im selben Store heilt es sich nach einem Restore nicht selbst. Die beiden Tests
 * dazu stehen unten; die Folge fuer den Nutzer rechnet `KalenderWarnungMerkerBackupTest` aus.
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

    /**
     * DIE MASTER-PAUSE GEHOERT AUSDRUECKLICH NICHT HIERHER - und das ist die Korrektur eines
     * eigenen Fehlers, nicht eine Luecke.
     *
     * Sie MUSS beim Geraetewechsel aufgehoben werden (sie liegt im "settings"-Store, der im
     * Android-Backup ist; nach einem Restore waere der Wecker auf dem neuen Geraet STILL). Aber
     * nicht durch Loeschen dieses Schluessels: eine Pause besteht aus mehr als dem
     * DataStore-Flag - `MasterPauseUseCase.pause()` schreibt zusaetzlich den
     * Device-Protected-Spiegel, den der BootReceiver VOR der ersten Entsperrung liest, loescht die
     * Alarme und reisst 6h-Wartung, Dimmer-Tick, DND-Tick, Hue-Planung und Pre-Alarm-Refresh ab.
     * Wer nur den Schluessel entfernt, hinterlaesst eine App, die "nicht pausiert" ANZEIGT, deren
     * Boot-Wiederherstellung aber dauerhaft gesperrt bleibt und deren Hintergrundketten nie wieder
     * anlaufen - die GEFAEHRLICHERE Variante desselben Bugs.
     *
     * Deshalb gibt `resetIfDeviceChanged()` ein `Boolean` zurueck und `CFAlarmApplication.
     * initializeApp()` ruft bei einem erkannten Wechsel `MasterPauseUseCase.resume()`.
     */
    @Test
    fun `Master-Pause ist kein geraetelokaler Schluessel - sie wird ueber resume aufgehoben`() {
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey("master_pause_enabled"))
    }

    @Test
    fun `alle vier geraetelokalen Onboarding-Flags werden erkannt`() {
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("battery_prompt_dismissed"))
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("unused_app_restrictions_dismissed"))
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("timeoffice_health_prompt_dismissed"))
        // Praefix-Muster: die OEM-Flags tragen den Herstellernamen im Schluessel.
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("oem_hint_shown_SAMSUNG"))
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("oem_hint_shown_XIAOMI"))
    }

    /**
     * DER ZWEITE WEG ZUM SELBEN SCHADEN (Issue #84). Mit v1.40.4 nahm `ConfigBackupFilter` das
     * Gedaechtnis der Kalender-Warnung aus der Exportdatei - Googles Auto-Backup und der
     * Geraetetransfer sehen diesen Filter aber per Konstruktion nie: sie sichern den kompletten
     * `settings`-Store als Datei.
     *
     * Und dieser Merker heilt nicht von selbst. `entscheideBenachrichtigung()` rechnet
     * `neuZuMelden = beharrlich - bereitsGemeldet`; scheitert der mitgewanderte Kalender auf dem
     * neuen Geraet weiter, ist das bei JEDEM Lauf leer, es wird nichts gemeldet, und der
     * abschliessende `intersect jetztGescheitert` haelt die ID fest. Dauerhaft und lautlos -
     * waehrend die Vollstaendigkeits-Sperren zwar das Loeschen von Weckern verhindern, damit aber
     * auch jedes Anlegen. Bei gleichem Google-Konto sind es dieselben Kalender-IDs.
     */
    @Test
    fun `das Gedaechtnis der Kalender-Warnung ist geraetelokal`() {
        assertTrue(
            "Der 'schon gewarnt'-Merker heilt nach einem Restore NICHT von selbst",
            DeviceLocalFlagsGuard.isDeviceLocalKey("calendar_unavailable_notified")
        )
        assertTrue(
            "Der Beharrlichkeits-Merker gehoert zum selben Gedaechtnis - ganz oder gar nicht",
            DeviceLocalFlagsGuard.isDeviceLocalKey("calendar_unavailable_last_failed")
        )
    }

    /**
     * DIE GEGENPROBE ZUM TEST DARUEBER, und der Grund, warum die beiden Eintraege exakt und nicht
     * als Praefix `calendar_unavailable*` gefuehrt werden: der dritte Schluessel derselben Klasse
     * ist die echte Einstellung "will ich diese Meldung ueberhaupt?". Ein Praefix-Muster wuerde sie
     * bei jedem Geraetewechsel auf den Default zuruecksetzen - eine abgeschaltete Meldung kaeme
     * dann ungefragt zurueck.
     */
    @Test
    fun `der Schalter der Kalender-Warnung ist eine echte Einstellung und bleibt`() {
        assertFalse(
            DeviceLocalFlagsGuard.isDeviceLocalKey("calendar_unavailable_notification_enabled")
        )
    }

    /**
     * Die Gegenprobe, die den Waechter ueberhaupt sicher macht: er darf KEINE echten
     * Nutzereinstellungen anfassen. Alles im selben Store, was Konfiguration ist, muss unberuehrt
     * bleiben.
     */
    @Test
    fun `echte Nutzereinstellungen werden nicht angefasst`() {
        listOf(
            "shift_config", "snooze_minutes", "dim_rules",
            "dim_overlay_strength", "dnd_toggles", "dnd_policy", "selected_calendar_ids",
            "alarm_skip_state", "device_local_flags_marker",
            "calendar_unavailable_notification_enabled"
        ).forEach { key ->
            assertFalse(
                "'$key' ist kein geraetelokaler Merker und darf nicht zurueckgesetzt werden",
                DeviceLocalFlagsGuard.isDeviceLocalKey(key)
            )
        }
    }

    /** Ein Schluessel, der nur AEHNLICH heisst, darf nicht mitgeloescht werden. */
    @Test
    fun `aehnlich benannte Schluessel treffen nicht`() {
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey("battery_prompt_dismissed_at"))
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey("my_oem_hint_shown_SAMSUNG"))
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey("calendar_unavailable_notified_at"))
        assertFalse(DeviceLocalFlagsGuard.isDeviceLocalKey(""))
    }
}
