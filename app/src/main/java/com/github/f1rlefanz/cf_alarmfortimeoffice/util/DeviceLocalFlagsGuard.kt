package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EINWEG-SPERRE fuer den Startblock, der [DeviceLocalFlagsGuard.resetIfDeviceChanged] und den
 * Abgleich des Pausen-Spiegels ausfuehrt.
 *
 * Gebraucht, weil dieser Block seit dem Direct-Boot-Fix ZWEI Anlaesse hat: den regulaeren
 * Prozessstart (Nutzer bereits entsperrt) und das Nachholen nach `ACTION_USER_UNLOCKED`, wenn der
 * Prozess vor der ersten Entsperrung hochkam. Beide duerfen sich ueberschneiden, ohne dass der
 * Block zweimal laeuft: ein zweiter Lauf wuerde erneut `resume()` und
 * `reconcileDirectBootMirror()` ausloesen, also einen gerade hergestellten Zustand nochmals
 * anfassen.
 *
 * `compareAndSet` statt `if (!done) { done = true }`: die beiden Anlaesse laufen auf
 * verschiedenen Threads (Application-Scope auf Dispatchers.IO bzw. Receiver-Thread).
 */
internal class DeviceLocalStartupGate {

    private val done = AtomicBoolean(false)

    /** Liefert GENAU EINMAL je Instanz `true`; jeder weitere Aufruf `false`. */
    fun claimRun(): Boolean = done.compareAndSet(false, true)

    /** Ist der Lauf bereits beansprucht? Liest auch der Startsequenzer in `CFAlarmApplication`. */
    val hasRun: Boolean get() = done.get()
}

/**
 * Setzt GERAETELOKALE Merker zurueck, wenn die App auf einem anderen Geraet aufwacht.
 *
 * Zwei Gruppen im @MainDataStore beschreiben den Zustand EINES Geraets, keine Einstellung: die
 * "Hinweis schon weggetippt"-Flags (Akku-Ausnahme, "Pause bei Nichtnutzung", TimeOffice, OEM) und
 * das Gedaechtnis der Kalender-Warnung (`CalendarUnavailablePrefs` - mitgereist heilt es nicht, es
 * haelt sich selbst am Leben, und die Wecker versiegen lautlos). Der `settings`-Store ist EINE Datei
 * im Backup, einzelne Schluessel lassen sich nicht ausnehmen - deshalb ein Waechter statt einer
 * Backup-Regel. `wartung_token_stoerung_gemeldet` braucht ihn nicht (`auth_prefs` ist vom Backup
 * ausgenommen).
 *
 * BEWUSSTE GRENZE: fehlt der Marker (Erstinstallation, Bestand von vor diesem Waechter), wird nur
 * der Marker geschrieben, nichts zurueckgesetzt. Zuruecksetzen ist harmlos: die Hinweise erscheinen
 * nur, wenn die Einstellung wirklich fehlt; im Zweifel wird einmal zu viel gewarnt.
 * Hergang: Skill cfalarm-persistenz-und-auth, reference/geraetewechsel-und-export.md.
 */
object DeviceLocalFlagsGuard {

    private val KEY_DEVICE_MARKER = stringPreferencesKey("device_local_flags_marker")

    /**
     * Die Merker, die zum Geraet gehoeren und beim Geraetewechsel wieder offen sein muessen.
     * Praefix-Eintraege (mit `*` am Ende) treffen jeden Schluessel mit diesem Anfang.
     *
     * Die beiden Kalender-Eintraege stehen BEWUSST exakt und nicht als `calendar_unavailable*`:
     * ein Praefix faenge `calendar_unavailable_notification_enabled` mit, und das ist die echte
     * Einstellung "will ich diese Meldung ueberhaupt?" - sie gehoert auf das neue Geraet.
     */
    private val DEVICE_LOCAL_KEY_PATTERNS = listOf(
        "battery_prompt_dismissed",
        "unused_app_restrictions_dismissed",
        "timeoffice_health_prompt_dismissed",
        "oem_hint_shown*",
        "calendar_unavailable_notified",
        "calendar_unavailable_last_failed"
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
     *
     * NUR BEI ENTSPERRTEM NUTZER AUFRUFEN. Der uebergebene Store ist der CE-`settings`-Store; ein
     * Read daraus VOR der ersten Entsperrung wirft nicht, sondern liefert still leere Preferences -
     * und DataStore legt genau dieses leere Ergebnis fuer die restliche PROZESSLAUFZEIT in seinen
     * In-Memory-Cache (die Version steigt nur bei einem erfolgreichen Write, der im gesperrten
     * CE-Storage scheitert). Der Prozess saehe den Store danach dauerhaft leer. Deshalb fragt
     * [com.github.f1rlefanz.cf_alarmfortimeoffice.CFAlarmApplication] vorher den `UserManager` und
     * holt den Aufruf nach `ACTION_USER_UNLOCKED` nach - abgesichert gegen Doppellauf ueber
     * [DeviceLocalStartupGate].
     *
     * @return true, wenn ein Geraetewechsel erkannt wurde. Der Aufrufer hebt dann die Master-Pause
     *         ueber `MasterPauseUseCase.resume()` auf, nie per Schluessel (eine Pause ist mehr als
     *         das Flag - Hergang im Skill); deshalb steht sie NICHT in [DEVICE_LOCAL_KEY_PATTERNS].
     */
    suspend fun resetIfDeviceChanged(dataStore: DataStore<Preferences>): Boolean {
        val current = currentDeviceMarker()
        val stored = dataStore.data.first()[KEY_DEVICE_MARKER]

        if (!shouldResetFlags(stored, current)) {
            if (stored == null) {
                dataStore.edit { it[KEY_DEVICE_MARKER] = current }
                Logger.d(LogTags.APP, "🔖 GERAETE-MARKER gesetzt (Erstinstallation oder Bestandsinstall)")
            }
            return false
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
            "🔄 GERAETEWECHSEL erkannt - geraetelokale Merker zurueckgesetzt " +
                "(${removed.size}: ${removed.joinToString()}). Akku-Ausnahme und " +
                "\"Pause bei Nichtnutzung\" muessen auf diesem Geraet neu erteilt werden; das " +
                "Gedaechtnis der Kalender-Warnung faengt hier neu an (im Zweifel warnen)."
        )
        return true
    }
}
