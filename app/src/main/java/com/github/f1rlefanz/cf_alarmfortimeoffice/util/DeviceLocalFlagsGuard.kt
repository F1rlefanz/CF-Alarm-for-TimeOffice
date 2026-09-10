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

    /** Nur fuer Diagnose/Tests: ist der Lauf bereits beansprucht? */
    val hasRun: Boolean get() = done.get()
}

/**
 * Setzt GERAETELOKALE Merker zurueck, wenn die App auf einem anderen Geraet aufwacht.
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
 * ZWEITE GRUPPE, gleiche Mechanik, anderer Anlass - das GEDAECHTNIS DER KALENDER-WARNUNG
 * (`CalendarUnavailablePrefs`):
 *   - `calendar_unavailable_notified`          ("ueber diese Kalender habe ich schon gewarnt")
 *   - `calendar_unavailable_last_failed`       ("diese Kalender sind beim VORIGEN Lauf gescheitert")
 *
 * Beide sind eine BEOBACHTUNG dieses Geraets, keine Einstellung - und der "schon gewarnt"-Merker
 * repariert sich nach einem Restore NICHT von selbst: `entscheideBenachrichtigung()` rechnet
 * `neuZuMelden = beharrlich - bereitsGemeldet`. Scheitert der mitgewanderte Kalender auf dem neuen
 * Geraet ebenfalls (also genau der Fall, um den es geht), ist `neuZuMelden` bei JEDEM Lauf leer, es
 * wird nichts gemeldet, und der abschliessende `intersect jetztGescheitert` haelt die ID im Merker.
 * Dauerhaft und lautlos - waehrend die Vollstaendigkeits-Sperren zwar das Loeschen von Weckern
 * verhindern, damit aber auch jedes Anlegen: die Wecker versiegen, und niemand erfaehrt davon. Dass
 * die Kalenderauswahl selbst nicht im Backup ist, entschaerft nichts - bei gleichem Google-Konto
 * sind es dieselben Kalender-IDs. Der dritte Schluessel daneben
 * (`calendar_unavailable_notification_enabled`) ist eine echte Einstellung, reist mit und darf
 * deshalb NICHT als Praefix mitgefangen werden (siehe die exakten Eintraege unten).
 * `wartung_token_stoerung_gemeldet` hat dasselbe Muster, aber nicht dasselbe Problem: `auth_prefs`
 * ist vom Backup ausgenommen, der Nutzer meldet sich neu an, und der erste gueltige Token setzt den
 * Merker ueber `WartungStoerungPrefs.zuruecksetzenFallsNoetig()` zurueck.
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
 * zurueckgesetzter Flags nichts. Fuer das Kalender-Gedaechtnis gilt dasselbe in der Richtung, die
 * `CalendarUnavailablePrefs` fuer sich beansprucht: schlimmstenfalls wird eine bereits
 * ausgesprochene Warnung ein zweites Mal gezeigt, oder die erste Stoerung braucht wieder zwei
 * Wartungslaeufe. Im Zweifel warnen.
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
     * @return true, wenn ein Geraetewechsel erkannt wurde. Der Aufrufer muss daraufhin auch die
     *         Master-Pause aufheben - und zwar ueber [com.github.f1rlefanz.cf_alarmfortimeoffice
     *         .masterpause.MasterPauseUseCase.resume], NICHT indem er hier einen Schluessel
     *         entfernt. Das war der erste, falsche Wurf dieses Waechters: eine Pause besteht aus
     *         MEHR als dem DataStore-Flag - `pause()` schreibt zusaetzlich den
     *         Device-Protected-Spiegel (den der BootReceiver VOR der ersten Entsperrung liest),
     *         loescht die Alarme und reisst 6h-Wartung, Dimmer-Tick, DND-Tick, Hue-Planung und den
     *         Pre-Alarm-Refresh ab. Wer nur `master_pause_enabled` loescht, hinterlaesst eine App,
     *         die "nicht pausiert" ANZEIGT, deren Boot-Wiederherstellung aber dauerhaft gesperrt
     *         bleibt und deren Hintergrundketten nie wieder anlaufen. Genau deshalb steht die
     *         Master-Pause NICHT in [DEVICE_LOCAL_KEY_PATTERNS].
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
