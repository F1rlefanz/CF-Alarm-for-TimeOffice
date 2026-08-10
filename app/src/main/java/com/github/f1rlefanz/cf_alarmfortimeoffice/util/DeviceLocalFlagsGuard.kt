package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Setzt GERAETELOKALE Onboarding-Flags zurueck, wenn die App auf einem anderen Geraet aufwacht.
 *
 * WARUM DAS NOETIG IST:
 * Vier Flags im @MainDataStore ("settings") merken sich, dass der Nutzer einen Hinweis bereits
 * weggetippt hat:
 *   - `battery_prompt_dismissed`               (Akku-Optimierung ausnehmen)
 *   - `unused_app_restrictions_dismissed`      ("Pause bei Nichtnutzung")
 *   - `timeoffice_health_prompt_dismissed`     (TimeOffice-Hintergrundsync)
 *   - `oem_hint_shown_<OEM>`                   (herstellerspezifischer Hinweis)
 *
 * Diese Flags beschreiben KEINE Nutzerkonfiguration, sondern den Zustand EINES Geraets: welche
 * Ausnahme dort erteilt ist, welcher Hersteller es ist, ob TimeOffice dort installiert ist. Der
 * `settings`-Store liegt aber - richtigerweise, denn er enthaelt auch Wecker-, Dimmer- und
 * DND-Einstellungen - im Android-Backup. Nach einem Restore auf ein NEUES Geraet kamen damit die
 * "schon abgelehnt"-Flags mit, und die App fragte nie wieder nach Akku-Ausnahme und "Pause bei
 * Nichtnutzung" - genau die zwei Einstellungen, die in diesem Projekt nachweislich Wecker
 * verschluckt haben (siehe CLAUDE.md und die Status-Karten dazu). Auf dem neuen Geraet ist die
 * Ausnahme naturgemaess NICHT erteilt, der Hinweis waere also faellig gewesen.
 *
 * Ein selektiver Backup-Ausschluss ist technisch nicht moeglich: ein DataStore-Preferences-Store
 * ist EINE Datei, einzelne Schluessel lassen sich nicht ausnehmen. Deshalb dieser Waechter.
 *
 * BEWUSSTE GRENZE: Fehlt der Marker (Erstinstallation, oder ein Bestandsinstall aus der Zeit vor
 * diesem Waechter), wird NICHT zurueckgesetzt - nur der Marker geschrieben. Sonst wuerde ein
 * laufender Bestandsinstall seine Abweisungen einmalig verlieren. Der Preis: ein Restore aus einem
 * Backup, das VOR dieser Version entstanden ist, faellt noch in die alte Falle. Ab dem ersten
 * Start mit dieser Version ist der Marker gesetzt und jeder weitere Geraetewechsel greift.
 *
 * Ein Zurechtsetzen ist harmlos: die Hinweise erscheinen nur, wenn die jeweilige Einstellung
 * tatsaechlich fehlt (`BatteryOptimizationHelper.isExempted`, `UnusedAppRestrictionsHelper.
 * isRestricted`). Ist auf dem neuen Geraet alles in Ordnung, sieht der Nutzer trotz
 * zurueckgesetzter Flags nichts.
 */
object DeviceLocalFlagsGuard {

    private val KEY_DEVICE_MARKER = stringPreferencesKey("device_local_flags_marker")

    /**
     * Die Flags, die zum Geraet gehoeren und beim Geraetewechsel wieder offen sein muessen.
     * Praefix-Eintraege (mit `*` am Ende) treffen jeden Schluessel mit diesem Anfang.
     */
    private val DEVICE_LOCAL_KEY_PATTERNS = listOf(
        "battery_prompt_dismissed",
        "unused_app_restrictions_dismissed",
        "timeoffice_health_prompt_dismissed",
        "oem_hint_shown*"
    )

    /**
     * Kennung des aktuellen Geraets. `Build.FINGERPRINT` aendert sich beim Geraetewechsel - und
     * ausserdem bei einem OS-Update, was hier unschaedlich ist: ein Zuruecksetzen zeigt einen
     * Hinweis nur dann, wenn die Einstellung real fehlt.
     */
    fun currentDeviceMarker(): String = Build.FINGERPRINT ?: "unknown"

    /**
     * REINE FUNKTION, damit die Entscheidung testbar ist, ohne Android anzufassen.
     *
     * @return true nur dann, wenn ein Marker gespeichert war UND er von diesem Geraet abweicht.
     */
    fun shouldResetFlags(storedMarker: String?, currentMarker: String): Boolean =
        storedMarker != null && storedMarker != currentMarker

    /** REINE FUNKTION: gehoert dieser Schluesselname zu den geraetelokalen Flags? */
    fun isDeviceLocalKey(keyName: String): Boolean = DEVICE_LOCAL_KEY_PATTERNS.any { pattern ->
        if (pattern.endsWith("*")) {
            keyName.startsWith(pattern.dropLast(1))
        } else {
            keyName == pattern
        }
    }

    /**
     * Wird beim App-Start aufgerufen. Best-effort: ein Fehler hier darf den Start nicht
     * beeintraechtigen, deshalb faengt der Aufrufer.
     */
    suspend fun resetIfDeviceChanged(dataStore: DataStore<Preferences>) {
        val current = currentDeviceMarker()
        val stored = dataStore.data.first()[KEY_DEVICE_MARKER]

        if (!shouldResetFlags(stored, current)) {
            if (stored == null) {
                dataStore.edit { it[KEY_DEVICE_MARKER] = current }
                Logger.d(LogTags.APP, "🔖 GERAETE-MARKER gesetzt (Erstinstallation oder Bestandsinstall)")
            }
            return
        }

        val removed = mutableListOf<String>()
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { isDeviceLocalKey(it.name) }
                .forEach { key ->
                    prefs.remove(key)
                    removed += key.name
                }
            prefs[KEY_DEVICE_MARKER] = current
        }

        Logger.w(
            LogTags.APP,
            "🔄 GERAETEWECHSEL erkannt - geraetelokale Onboarding-Flags zurueckgesetzt " +
                "(${removed.size}: ${removed.joinToString()}). Akku-Ausnahme und " +
                "\"Pause bei Nichtnutzung\" muessen auf diesem Geraet neu erteilt werden."
        )
    }
}
