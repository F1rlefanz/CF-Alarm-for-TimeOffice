package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer [ConfigBackupFilter] - der sicherheitskritische Teil des Export/Import-Features.
 * Was hier faelschlich durchgelassen wird, landet auf einem fremden Geraet.
 */
class ConfigBackupFilterTest {

    /**
     * DER WICHTIGSTE TEST: eine laufende Master-Pause darf NIE in eine Exportdatei. Importiert
     * wuerde sie den Wecker auf dem neuen Geraet stumm schalten - und niemand kommt darauf, die
     * Ursache in einer Importdatei zu suchen.
     */
    @Test
    fun `Master-Pause wird nie exportiert`() {
        assertFalse(ConfigBackupFilter.isExportable("master_pause_enabled"))
        assertEquals("Laufzeitzustand", ConfigBackupFilter.exclusionReason("master_pause_enabled"))
    }

    /** Zugangsdaten zur Hue-Bridge gehoeren nicht in eine Datei, die weitergegeben wird. */
    @Test
    fun `Hue-Zugangsdaten und Bridge-Adresse werden nie exportiert`() {
        listOf("hue_username", "username", "hue_bridge_ip", "bridge_ip").forEach { key ->
            assertFalse("'$key' darf nicht exportiert werden", ConfigBackupFilter.isExportable(key))
        }
    }

    /**
     * Die von DIESEM Geraet bei Android registrierte Zen-Regel zeigt auf nichts, was auf einem
     * anderen Geraet existiert.
     */
    @Test
    fun `registrierte Zen-Regel-ID wird nie exportiert`() {
        assertFalse(ConfigBackupFilter.isExportable("dnd_zen_rule_id"))
    }

    /**
     * Laufzeit-Renderzustand und die an ein konkretes Fenster gebundene Korrektur beschreiben einen
     * Moment, keine Einstellung.
     */
    @Test
    fun `Laufzeitzustand des Dimmers wird nie exportiert`() {
        listOf(
            "dim_overlay_on", "dim_render_strength", "dim_render_warmth",
            "dim_override_strength_delta", "dim_override_paused",
            "dim_override_window_end", "dim_override_window_strength"
        ).forEach { key ->
            assertFalse("'$key' ist Laufzeitzustand", ConfigBackupFilter.isExportable(key))
        }
    }

    /**
     * Dieselbe Liste wie beim Geraetewechsel-Waechter - eine Quelle, kein zweiter Katalog, der
     * auseinanderlaufen kann. Kaemen diese Flags mit, wuerde auf dem neuen Geraet nie nach der
     * Akku-Ausnahme gefragt.
     */
    @Test
    fun `geraetelokale Onboarding-Markierungen werden nie exportiert`() {
        listOf(
            "battery_prompt_dismissed",
            "unused_app_restrictions_dismissed",
            "timeoffice_health_prompt_dismissed",
            "oem_hint_shown_SAMSUNG",
            "device_local_flags_marker"
        ).forEach { key ->
            assertFalse("'$key' ist geraetelokal", ConfigBackupFilter.isExportable(key))
        }
    }

    /**
     * DIE GEGENPROBE, ohne die der Filter wertlos waere: alles, was echte, muehsam gepflegte
     * Konfiguration ist, MUSS mitkommen. Das ist der Grund fuer das ganze Feature - nach einer
     * Neuinstallation soll nicht wieder alles von Hand eingetragen werden.
     */
    @Test
    fun `echte Konfiguration wird exportiert`() {
        listOf(
            // Dimmer-Regeln und -Werte
            "dim_rules", "dim_strength", "dim_warmth", "dim_winddown_min",
            "dim_wellness_enabled", "dim_rules_enabled", "dim_night_default_enabled",
            "dim_night_default_start_min", "dim_night_default_free_end_min",
            "dim_night_default_strength", "dim_night_default_warmth",
            "dim_night_default_excluded_shifts", "dim_correction_notification_enabled",
            // DND
            "dnd_follow_dimmer_enabled", "dnd_during_shift_enabled",
            "dnd_policy_block_calls", "dnd_policy_block_messages",
            "dnd_policy_allow_repeat_callers", "dnd_policy_block_alarms",
            "dnd_oncall_shifts", "dnd_oncall_cutoff_min", "dnd_shift_excluded_shifts",
            // Wecker
            "snooze_minutes", "shift_change_notification_enabled",
            // Hue-Regeln - der aufwendigste Teil
            "hue_schedule_rules"
        ).forEach { key ->
            assertTrue("'$key' ist echte Konfiguration und MUSS exportiert werden", ConfigBackupFilter.isExportable(key))
            assertNull(ConfigBackupFilter.exclusionReason(key))
        }
    }

    /**
     * DER TEST, DER MEINEN EIGENEN FEHLER FESTHAELT.
     *
     * Der erste Wurf des Filters entstand aus den Schluesseln von vier Paketen und war damit
     * lueckenhaft. Der erste echte Export am Geraet enthielt genau DREI Schluessel - und alle drei
     * gehoerten nicht hinein, darunter `active_alarms`, die persistierte Alarmliste. Importiert
     * haette sie fremde Alarme mit fremden IDs in das Repository und in den Direct-Boot-Spiegel
     * geschrieben.
     *
     * Diese Liste ist aus einer vollstaendigen Inventur aller `*PreferencesKey("…")` im Baum
     * abgeleitet. Wer eine neue LAUFZEIT-Groesse einfuehrt, muss sie im Filter eintragen - dieser
     * Test ist die Erinnerung daran.
     */
    @Test
    fun `abgeleiteter Laufzeitzustand wird nie exportiert`() {
        listOf(
            "active_alarms",
            "last_maintenance_time",
            "last_event_load_time",
            "last_success_timestamp",
            "is_next_alarm_skipped",
            "skip_activated_at",
            "skip_reason",
            "skipped_alarm_id",
            "skipped_alarm_trigger_time"
        ).forEach { key ->
            assertFalse("'$key' ist abgeleiteter Laufzeitzustand", ConfigBackupFilter.isExportable(key))
        }
    }

    /**
     * Sicherungen unlesbarer Rohdaten (`shift_config_broken`, `active_alarms_broken`) gehoeren zu
     * einem Defekt auf DIESEM Geraet. Per Endung gefiltert, damit auch kuenftige Sicherungen dieser
     * Art automatisch draussen bleiben.
     */
    @Test
    fun `Sicherungen unlesbarer Rohdaten werden nie exportiert`() {
        assertFalse(ConfigBackupFilter.isExportable("shift_config_broken"))
        assertFalse(ConfigBackupFilter.isExportable("active_alarms_broken"))
        assertFalse(ConfigBackupFilter.isExportable("irgendwas_kuenftiges_broken"))
    }

    /**
     * Anmeldung, Tokens und die kontogebundene Kalenderauswahl gehoeren nie in eine Datei, die der
     * Nutzer weitergibt. Die Kalender-IDs einer Kollegin zeigen ausserdem auf Kalender, die es auf
     * diesem Geraet nicht gibt.
     */
    @Test
    fun `Anmeldung, Tokens und Kalenderauswahl werden nie exportiert`() {
        listOf(
            "access_token", "refresh_token", "token_data_v2", "token_expiry_long",
            "login_status", "user_email", "user_id",
            "selected_calendar_ids", "calendar_id",
            "connection_validated"
        ).forEach { key ->
            assertFalse("'$key' darf nicht exportiert werden", ConfigBackupFilter.isExportable(key))
        }
    }

    /**
     * Die Schichtkonfiguration geht ueber das typisierte Repository (validiert, mit Defekt-Schutz).
     * Als Rohwert ausgeschlossen, damit ein spaeteres Verschieben des Schluessels in den
     * settings-Store keinen zweiten, ungeprueften Weg oeffnet.
     */
    @Test
    fun `Schichtkonfiguration wird nicht als Rohwert exportiert`() {
        assertFalse(ConfigBackupFilter.isExportable("shift_config"))
    }

    /**
     * Die Richtung ist Absicht: unbekannte Schluessel werden EXPORTIERT. Eine neue Einstellung ist
     * damit automatisch dabei, statt beim naechsten Feature stillschweigend zu fehlen. Wer eine neue
     * LAUFZEIT-Groesse einfuehrt, muss sie in den Filter eintragen - dieser Test haelt die
     * Grundrichtung fest, damit sie nicht versehentlich umgedreht wird.
     */
    @Test
    fun `unbekannte Schluessel werden im Zweifel exportiert`() {
        assertTrue(ConfigBackupFilter.isExportable("irgendeine_kuenftige_einstellung"))
    }

    /**
     * DIE ZUSICHERUNG, DIE BISHER UNGEPRUEFT WAR: der Filter gilt in BEIDE Richtungen.
     *
     * `exclusionReason` ist der eine Ort, an dem entschieden wird - benutzt vom Export (was kommt in
     * die Datei) UND vom Import (was wird aus einer Datei uebernommen). Der Import darf sich NICHT
     * darauf verlassen, dass die Datei von diesem Filter erzeugt wurde: sie ist Text, kann von Hand
     * bearbeitet, aus einer aelteren Version stammen oder von einer anderen Person kommen. Waere die
     * Pruefung nur im Export, wuerde eine praeparierte Datei eine Master-Pause, fremde Alarme oder
     * Hue-Zugangsdaten direkt in den Store schreiben.
     *
     * Dieser Test haelt fest, dass Begruendung und Entscheidung EIN Wert sind - `isExportable` ist
     * per Definition `exclusionReason(...) == null`, es gibt also keinen Schluessel, der in einer
     * Richtung erlaubt und in der anderen verboten waere.
     */
    @Test
    fun `Filter gilt in beide Richtungen - Export und Import teilen eine Entscheidung`() {
        val verboten = listOf(
            "master_pause_enabled", "active_alarms", "hue_username", "access_token",
            "dnd_zen_rule_id", "shift_config", "battery_prompt_dismissed", "dim_overlay_on"
        )
        val erlaubt = listOf("snooze_minutes", "dim_rules", "hue_schedule_rules", "dnd_oncall_shifts")

        (verboten + erlaubt).forEach { key ->
            assertEquals(
                "'$key': isExportable und exclusionReason muessen dieselbe Entscheidung treffen - " +
                    "sonst haetten Export und Import unterschiedliche Regeln",
                ConfigBackupFilter.exclusionReason(key) == null,
                ConfigBackupFilter.isExportable(key)
            )
        }
        verboten.forEach { assertFalse(ConfigBackupFilter.isExportable(it)) }
        erlaubt.forEach { assertTrue(ConfigBackupFilter.isExportable(it)) }
    }

    /**
     * WERTEBEREICHE: der Schluessel-Filter sagt nichts darueber, ob der WERT verwertbar ist. Zwei
     * Zahlen sind gefaehrlich, wenn sie aus einer von Hand bearbeiteten Datei kommen:
     *
     *  - `snooze_minutes` = 0 oder negativ: der Schlummer-Alarm liegt in der Vergangenheit und
     *    feuert SOFORT wieder - ein Wecker, der sich nicht mehr wegdruecken laesst.
     *  - `dnd_oncall_cutoff_min` >= 1440 oder negativ: `DndOnCallCutoffResolver` rechnet
     *    `LocalTime.ofSecondOfDay(min * 60L)` und wirft dann eine `DateTimeException` - der
     *    DND-Tick stirbt bei jedem Lauf.
     */
    @Test
    fun `unsinnige Zahlenwerte werden beim Import abgelehnt`() {
        assertNull(ConfigBackupFilter.rangeRejection("snooze_minutes", 5))
        assertNull(ConfigBackupFilter.rangeRejection("snooze_minutes", 1))
        assertNull(ConfigBackupFilter.rangeRejection("snooze_minutes", 120))
        assertNotNull(ConfigBackupFilter.rangeRejection("snooze_minutes", 0))
        assertNotNull(ConfigBackupFilter.rangeRejection("snooze_minutes", -5))
        assertNotNull(ConfigBackupFilter.rangeRejection("snooze_minutes", 121))

        assertNull(ConfigBackupFilter.rangeRejection("dnd_oncall_cutoff_min", 0))
        assertNull(ConfigBackupFilter.rangeRejection("dnd_oncall_cutoff_min", 1439))
        assertNotNull(ConfigBackupFilter.rangeRejection("dnd_oncall_cutoff_min", 1440))
        assertNotNull(ConfigBackupFilter.rangeRejection("dnd_oncall_cutoff_min", -1))
    }

    /**
     * Kein Bereich hinterlegt = kein Urteil. Dieser Katalog ist bewusst KEINE zweite
     * Validierungsschicht fuer alles - fuer die Dimmer-Werte ist die Klemme im Lesepfad die
     * richtige Ebene, und die ist dort vorhanden.
     */
    @Test
    fun `Schluessel ohne hinterlegten Bereich werden nicht beurteilt`() {
        assertNull(ConfigBackupFilter.rangeRejection("dim_strength", 99999))
        assertNull(ConfigBackupFilter.rangeRejection("irgendwas", -1))
    }
}
