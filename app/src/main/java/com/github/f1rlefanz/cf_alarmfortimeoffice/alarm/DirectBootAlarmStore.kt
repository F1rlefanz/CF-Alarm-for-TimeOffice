package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimaler Alarm-Spiegel im DEVICE-PROTECTED STORAGE.
 *
 * Warum: Die eigentliche Alarm-Persistenz (AlarmRepository / @MainDataStore) liegt im
 * CREDENTIAL-ENCRYPTED Storage und ist vor der ersten Entsperrung nach einem Reboot NICHT
 * lesbar. Nach einem naechtlichen Reboot (OTA, Absturz, leerer Akku am Ladegeraet) kann die
 * App ihre Wecker daher nicht wiederherstellen, solange der Nutzer nicht entsperrt.
 *
 * Dieser Spiegel haelt die minimalen Trigger-Daten aller geplanten Alarme in einer
 * SharedPreferences-Datei auf dem Device-Protected-Context. Diese ist bereits im Direct-Boot-
 * Modus (vor Entsperrung) les- und schreibbar. BootReceiver nutzt sie, um bei
 * LOCKED_BOOT_COMPLETED / BOOT_COMPLETED die System-Alarme sofort und lokal (ohne Kalender,
 * Token oder CE-Storage) neu zu setzen.
 *
 * Bewusst SharedPreferences statt DataStore: synchron, ohne Coroutine-Infrastruktur, im engen
 * Boot-Zeitfenster robust - der etablierte Android-Weg fuer Direct-Boot-Daten. Beruehrt die drei
 * App-DataStores (Main/Hue/Token) NICHT.
 *
 * Geschrieben wird der Spiegel zentral in AlarmRepository.persistToDataStore(), also bei jeder
 * Aenderung des Alarm-Bestands - er bleibt damit automatisch synchron zur CE-Persistenz.
 */
@Serializable
data class DirectBootAlarmEntry(
    val id: Int,
    val shiftName: String,
    val triggerTime: Long,
    // Tatsaechlicher Schichtbeginn (formatiert "HH:mm"), NICHT die Weckzeit - siehe
    // AlarmReceiver.EXTRA_SHIFT_START_TIME. Leer = unbekannt.
    val shiftStartTimeFormatted: String = ""
)

@Singleton
class DirectBootAlarmStore @Inject constructor(
    @ApplicationContext appContext: Context
) {
    // WICHTIG: Device-Protected-Context -> im Direct-Boot lesbar. NICHT der normale Context.
    private val prefs = appContext.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Ersetzt den gespiegelten Bestand (idempotent, synchron). */
    fun saveAll(entries: List<DirectBootAlarmEntry>) {
        try {
            // Bewusst apply() (KTX-Default, commit = false) - unveraendertes Schreibverhalten.
            prefs.edit { putString(KEY_ENTRIES, json.encodeToString(entries)) }
            Logger.d(LogTags.ALARM, "🔐 DIRECT-BOOT: ${entries.size} Alarme in Device-Protected-Spiegel geschrieben")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ DIRECT-BOOT: Schreiben des Alarm-Spiegels fehlgeschlagen", e)
        }
    }

    /** Liefert alle gespiegelten Alarme, deren Weckzeit noch in der Zukunft liegt. */
    fun getFutureEntries(now: Long = System.currentTimeMillis()): List<DirectBootAlarmEntry> {
        return try {
            val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
            json.decodeFromString<List<DirectBootAlarmEntry>>(raw).filter { it.triggerTime > now }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ DIRECT-BOOT: Lesen des Alarm-Spiegels fehlgeschlagen", e)
            emptyList()
        }
    }

    /**
     * Master-Pause-Spiegel im selben Device-Protected-Storage wie die Alarm-Eintraege.
     *
     * MasterPausePrefs selbst liegt im @MainDataStore (CE-Storage) und ist vor der ersten
     * Entsperrung nach einem Reboot NICHT lesbar - siehe Klassenkommentar oben. BootReceiver
     * braucht den Pause-Zustand aber genau in diesem Fenster (LOCKED_BOOT_COMPLETED), um den
     * Direct-Boot-Restore korrekt zu unterdruecken. Geschrieben von MasterPauseUseCase.pause()/
     * resume() im selben Atemzug wie MasterPausePrefs.setPaused(), analog dazu wie
     * AlarmRepository.persistToDataStore() den Alarm-Spiegel synchron haelt.
     */
    fun savePaused(paused: Boolean) {
        try {
            // Bewusst apply() (KTX-Default, commit = false) - unveraendertes Schreibverhalten.
            prefs.edit { putBoolean(KEY_PAUSED, paused) }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ DIRECT-BOOT: Schreiben des Pause-Spiegels fehlgeschlagen", e)
        }
    }

    /** Default false (nicht pausiert) - z.B. wenn der Spiegel noch nie geschrieben wurde. */
    fun isPausedNow(): Boolean = try {
        prefs.getBoolean(KEY_PAUSED, false)
    } catch (e: Exception) {
        Logger.e(LogTags.ALARM, "❌ DIRECT-BOOT: Lesen des Pause-Spiegels fehlgeschlagen", e)
        false
    }

    companion object {
        private const val PREFS_NAME = "direct_boot_alarms"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_PAUSED = "paused"
    }
}
