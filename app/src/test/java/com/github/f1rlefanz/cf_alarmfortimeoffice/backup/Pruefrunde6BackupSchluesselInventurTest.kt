package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalFlagsGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Macht die Zusicherung, die `ConfigBackupFilter` ueber sich selbst aufschreibt, ueberpruefbar:
 * "Seither ist die Liste aus einer vollstaendigen Inventur ALLER `*PreferencesKey(...)` im ganzen
 * Baum abgeleitet."
 *
 * WARUM ALS QUELLTEXT-SCAN: Die Zusicherung war gebrochen, und niemand hat es gemerkt. Der in
 * Pruefrunde 6 eingefuehrte Laufzeitschluessel `dim_overlay_preview_until` (Ablaufzeitpunkt einer
 * Dimm-Vorschau) fiel durch den Filter und waere in Export UND Import gewandert - obwohl seine
 * drei Geschwister `dim_overlay_on`, `dim_render_strength` und `dim_render_warmth` ausdruecklich
 * als Laufzeitzustand gefuehrt sind. Der Ablauf: Geraet A exportiert waehrend einer Vorschau,
 * Geraet B importiert einen laengst vergangenen Ablaufzeitpunkt; laeuft auf B gerade ein
 * regulaeres Dimm-Fenster, liest `DimOverlayPrefs.renderState` die Kombination als "abgelaufene
 * Vorschau" und schaltet das Overlay ab. Dieselbe Luecke bestand bei `skipped_manual_alarm`.
 *
 * `ConfigBackupFilterTest` konnte das nicht sehen: es prueft handgepflegte Listen, also genau die
 * Schluessel, an die jemand gedacht hat. Dieser Test dreht die Richtung um - er geht von den
 * WIRKLICH deklarierten Schluesseln aus und verlangt fuer jeden eine Entscheidung.
 *
 * WAS ZU TUN IST, WENN DIESER TEST FAELLT: Der neue Schluessel ist entweder Laufzeitzustand /
 * geraetegebunden - dann gehoert er in `ConfigBackupFilter` -, oder er ist eine echte
 * Einstellung, die auf ein neues Geraet mitsoll - dann gehoert er in die Liste unten. Beides ist
 * eine bewusste Entscheidung; genau die soll der Test erzwingen.
 */
class Pruefrunde6BackupSchluesselInventurTest {

    /**
     * Alle heute bewusst EXPORTIERBAREN Schluessel: die vom Nutzer eingestellten Groessen
     * (Dimmer-Regeln, Nacht-Standard, DND-Politik, Hue-Zeitplan, Schlummer-Dauer, Weckton-Anstieg,
     * Hinweistexte).
     */
    private val bewusstExportierbar = setOf(
        "bridge_id",
        "calendar_unavailable_last_failed",
        "calendar_unavailable_notification_enabled",
        "calendar_unavailable_notified",
        "dim_correction_notification_enabled",
        "dim_enabled",
        "dim_night_default_enabled",
        "dim_night_default_excluded_shifts",
        "dim_night_default_free_end_min",
        "dim_night_default_start_min",
        "dim_night_default_strength",
        "dim_night_default_warmth",
        "dim_rules",
        "dim_rules_enabled",
        "dim_strength",
        "dim_warmth",
        "dim_wellness_enabled",
        "dim_winddown_min",
        "dnd_during_shift_enabled",
        "dnd_follow_dimmer_enabled",
        "dnd_oncall_cutoff_min",
        "dnd_oncall_shifts",
        "dnd_policy_allow_repeat_callers",
        "dnd_policy_block_alarms",
        "dnd_policy_block_calls",
        "dnd_policy_block_conversations",
        "dnd_policy_block_events",
        "dnd_policy_block_media",
        "dnd_policy_block_messages",
        "dnd_policy_block_reminders",
        "dnd_policy_block_system",
        "dnd_shift_excluded_shifts",
        "hue_schedule_rules",
        "shift_change_notification_enabled",
        "snooze_minutes",
        "weckton_anstieg_aktiv",
        "weckton_anstieg_sekunden",
        "weckton_anstieg_start_prozent"
    )

    @Test
    fun `jeder deklarierte Preferences-Schluessel ist entweder ausgeschlossen oder bewusst exportierbar`() {
        val schluessel = alleSchluesselImBaum()

        assertTrue(
            "Die Inventur hat fast nichts gefunden - vermutlich stimmt der Suchpfad nicht mehr " +
                "(gefunden: ${schluessel.size})",
            schluessel.size >= 60
        )

        val unentschieden = schluessel
            .filter { ConfigBackupFilter.isExportable(it) }
            .filterNot { it in bewusstExportierbar }
            .sorted()

        assertTrue(
            "Neue Preferences-Schluessel ohne Entscheidung: $unentschieden. Laufzeitzustand oder " +
                "Geraetebezug gehoert in ConfigBackupFilter, eine echte Einstellung in die Liste " +
                "`bewusstExportierbar` dieses Tests.",
            unentschieden.isEmpty()
        )
    }

    /**
     * Der Befund, der die Luecke sichtbar gemacht hat - und sein Zwilling. Beide beschreiben einen
     * Laufzeit-Moment DIESES Geraets, nicht eine Einstellung.
     */
    @Test
    fun `Vorschau-Ablauf und gesicherter Skip-Wecker sind Laufzeitzustand`() {
        listOf("dim_overlay_preview_until", "skipped_manual_alarm").forEach { key ->
            assertFalse("'$key' ist Laufzeitzustand und darf nie exportiert werden",
                ConfigBackupFilter.isExportable(key))
        }
    }

    /**
     * Die beiden Werte hinter der stillen Statuszeile "Dienstplan-Kalender zuletzt neu
     * eingelesen" (`FeedNeueinlesenStore`). Sie sind eine BEOBACHTUNG dieses Geraets an DIESEM
     * Kalenderabonnement, keine Einstellung.
     *
     * Die gefaehrliche Richtung ist der importierte FREMDE Zeitpunkt: das neue Geraet zeigte dann
     * eine ruhige Auskunft ueber einen Vorgang, den es nie beobachtet hat - und zwar so lange, bis
     * dort zufaellig selbst einmal ein Kennungswechsel auftritt. Eine erfundene Beobachtung ist
     * genau das, was diese Zeile nicht sein darf: sie soll die Frage "woran erkenne ich das?"
     * ehrlich beantworten.
     */
    @Test
    fun `der Merker 'Kalender neu eingelesen' ist Laufzeitzustand`() {
        listOf("last_feed_reread_at", "last_feed_reread_count").forEach { key ->
            assertFalse(
                "'$key' beschreibt eine Beobachtung DIESES Geraets und darf nie exportiert werden",
                ConfigBackupFilter.isExportable(key)
            )
        }
    }

    /**
     * Der einzige dynamisch zusammengesetzte Schluessel im Baum
     * (`BatteryOptimizationHelper`: "oem_hint_shown_<hersteller>") - er entzieht sich der Inventur
     * oben, weil im Quelltext kein Literal steht. Sein Praefix ist deshalb hier festgenagelt.
     */
    @Test
    fun `der zusammengesetzte OEM-Hinweis-Schluessel bleibt geraetelokal`() {
        assertTrue(DeviceLocalFlagsGuard.isDeviceLocalKey("oem_hint_shown_samsung"))
        assertFalse(ConfigBackupFilter.isExportable("oem_hint_shown_samsung"))
    }

    // ------------------------------------------------------------------
    // Helfer
    // ------------------------------------------------------------------

    /**
     * Sammelt alle Schluesselnamen aus `…PreferencesKey("name")`-Literalen. Kommentarzeilen
     * bleiben aussen vor (die KDoc von `ConfigBackupFilter` zitiert das Muster selbst), ebenso
     * Interpolationen - fuer die gibt es den Test darueber.
     */
    private fun alleSchluesselImBaum(): List<String> {
        val muster = Regex("""PreferencesKey\("([a-z0-9_]+)"\)""")
        return quellwurzel()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { datei ->
                datei.readLines()
                    .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                    .flatMap { zeile -> muster.findAll(zeile).map { it.groupValues[1] } }
            }
            .distinct()
            .toList()
    }

    /** Findet den Quellbaum unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quellwurzel(): File {
        val pfad = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice"
        return listOf(File(pfad), File("app/$pfad")).firstOrNull { it.isDirectory }
            ?: error("Quellbaum nicht gefunden: $pfad (Arbeitsverzeichnis ${File(".").absolutePath})")
    }
}
