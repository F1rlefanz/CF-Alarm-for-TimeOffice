package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.HueDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Was ein Import tatsaechlich geschrieben hat - fuer eine ehrliche Rueckmeldung an den Nutzer. */
data class ImportSummary(
    val shiftDefinitions: Int,
    val settingsKeys: Int,
    val hueKeys: Int,
    /** Schluessel aus der Datei, die der Filter abgelehnt hat - benannt statt verschwiegen. */
    val rejectedKeys: List<String>
)

/**
 * Exportiert und importiert die Konfiguration als Datei - der Weg, der die Handarbeit nach jeder
 * Neuinstallation erspart.
 *
 * BEWUSST OHNE ZUGANGSDATEN. Keine Tokens, kein Tink-Keyset, kein Hue-Bridge-Username, keine
 * Anmeldedaten. Der Token-Store wird von dieser Klasse gar nicht angefasst; was aus den beiden
 * gelesenen Stores nicht mit darf, entscheidet [ConfigBackupFilter].
 *
 * UNABHAENGIG VON GOOGLES BACKUP. Die Auto-Backup-Regeln sind eine zweite, andere Sache (sie
 * greifen bei einem Geraetewechsel automatisch, aber nur wenn der Nutzer sie hat und Google sie
 * ausfuehrt). Diese Datei funktioniert immer, auch bei Neuinstallation auf demselben Geraet, und
 * laesst sich an eine Kollegin weitergeben.
 *
 * DER FILTER GILT IN BEIDE RICHTUNGEN. Beim Import wird jeder Schluessel erneut geprueft, nicht nur
 * beim Export. Eine handbearbeitete Datei, eine Datei aus einer aelteren Version mit anderem Filter
 * oder eine fremde Datei kann so keinen Laufzeitzustand und keine Zugangsdaten einschleusen -
 * insbesondere keine aktive Master-Pause, die den Wecker stumm schalten wuerde.
 */
@Singleton
class ConfigBackupUseCase @Inject constructor(
    @param:MainDataStore private val mainDataStore: DataStore<Preferences>,
    @param:HueDataStore private val hueDataStore: DataStore<Preferences>,
    private val shiftConfigRepository: IShiftConfigRepository
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * @param appVersion nur zur Nachvollziehbarkeit in der Datei
     * @param createdAt Zeitstempel als Text, vom Aufrufer - haelt diese Klasse frei von
     *        Uhrzeit-Abhaengigkeiten
     */
    suspend fun export(appVersion: String, createdAt: String): Result<String> = runCatching {
        val shiftConfig = shiftConfigRepository.getCurrentShiftConfig().getOrNull()
        if (shiftConfig == null) {
            // Kein Abbruch: die restliche Konfiguration ist trotzdem wertvoll. Aber sichtbar machen.
            Logger.w(
                LogTags.SHIFT_CONFIG,
                "⚠️ EXPORT: Schicht-Konfiguration nicht lesbar - Datei enthaelt sie NICHT"
            )
        }

        val backup = ConfigBackup(
            formatVersion = ConfigBackup.CURRENT_FORMAT_VERSION,
            appVersion = appVersion,
            createdAt = createdAt,
            shiftConfig = shiftConfig,
            settings = exportableValues(mainDataStore),
            hue = exportableValues(hueDataStore)
        )

        Logger.business(
            LogTags.APP,
            "📤 EXPORT: ${backup.shiftConfig?.definitions?.size ?: 0} Schichtdefinitionen, " +
                "${backup.settings.size} Einstellungen, ${backup.hue.size} Hue-Werte"
        )
        json.encodeToString(ConfigBackup.serializer(), backup)
    }

    suspend fun import(fileContent: String): Result<ImportSummary> = runCatching {
        val backup = json.decodeFromString(ConfigBackup.serializer(), fileContent)

        require(backup.formatVersion <= ConfigBackup.CURRENT_FORMAT_VERSION) {
            "Die Datei stammt aus einer neueren App-Version (Format ${backup.formatVersion}, " +
                "diese App kann bis ${ConfigBackup.CURRENT_FORMAT_VERSION}). Bitte die App aktualisieren."
        }

        val rejected = mutableListOf<String>()

        // Schichtdefinitionen ueber das typisierte Repository - dort greifen Validierung und
        // Serialisierung, und der Defekt-Schutz bleibt zustaendig.
        var definitions = 0
        backup.shiftConfig?.let { config ->
            shiftConfigRepository.saveShiftConfig(config).getOrThrow()
            definitions = config.definitions.size
        }

        val settingsWritten = writeValues(mainDataStore, backup.settings, rejected)
        val hueWritten = writeValues(hueDataStore, backup.hue, rejected)

        Logger.business(
            LogTags.APP,
            "📥 IMPORT: $definitions Schichtdefinitionen, $settingsWritten Einstellungen, " +
                "$hueWritten Hue-Werte" +
                if (rejected.isEmpty()) "" else ", ${rejected.size} Schluessel abgelehnt: ${rejected.joinToString()}"
        )
        ImportSummary(definitions, settingsWritten, hueWritten, rejected)
    }

    private suspend fun exportableValues(store: DataStore<Preferences>): Map<String, StoredValue> =
        store.data.first().asMap()
            .asSequence()
            .filter { (key, _) -> ConfigBackupFilter.isExportable(key.name) }
            .mapNotNull { (key, value) -> toStoredValue(value)?.let { key.name to it } }
            .toMap()

    private suspend fun writeValues(
        store: DataStore<Preferences>,
        values: Map<String, StoredValue>,
        rejected: MutableList<String>
    ): Int {
        var written = 0
        store.edit { prefs ->
            values.forEach { (name, stored) ->
                val reason = ConfigBackupFilter.exclusionReason(name)
                if (reason != null) {
                    rejected += "$name ($reason)"
                    return@forEach
                }
                if (applyValue(prefs, name, stored)) written++ else rejected += "$name (unbekannter Typ '${stored.type}')"
            }
        }
        return written
    }

    companion object {
        /**
         * REIN UND TESTBAR: DataStore-Wert -> Dateiformat. `null` fuer Typen, die es in
         * Preferences nicht gibt - dann fehlt der Wert in der Datei statt sie zu zerstoeren.
         */
        fun toStoredValue(value: Any?): StoredValue? = when (value) {
            is Boolean -> StoredValue("boolean", value.toString())
            is Int -> StoredValue("int", value.toString())
            is Long -> StoredValue("long", value.toString())
            is Float -> StoredValue("float", value.toString())
            is Double -> StoredValue("double", value.toString())
            is String -> StoredValue("string", value)
            // Reihenfolge irrelevant, aber stabil sortiert, damit zwei Exporte desselben Zustands
            // dieselbe Datei ergeben und ein Diff aussagekraeftig bleibt.
            is Set<*> -> StoredValue("stringSet", setValue = value.map { it.toString() }.sorted())
            else -> null
        }

        /**
         * REIN UND TESTBAR: Dateiformat -> DataStore-Wert, typerhaltend.
         *
         * @return false, wenn Typ oder Wert nicht verwertbar sind. Dann wird NICHTS geschrieben -
         *         ein falsch typisierter Wert waere schlimmer als ein fehlender, weil der naechste
         *         Lesezugriff mit einer ClassCastException scheitert.
         */
        fun applyValue(prefs: MutablePreferences, name: String, stored: StoredValue): Boolean {
            return when (stored.type) {
                "boolean" -> stored.value?.toBooleanStrictOrNull()
                    ?.let { prefs[booleanPreferencesKey(name)] = it; true } ?: false
                "int" -> stored.value?.toIntOrNull()
                    ?.let { prefs[intPreferencesKey(name)] = it; true } ?: false
                "long" -> stored.value?.toLongOrNull()
                    ?.let { prefs[longPreferencesKey(name)] = it; true } ?: false
                "float" -> stored.value?.toFloatOrNull()
                    ?.let { prefs[floatPreferencesKey(name)] = it; true } ?: false
                "double" -> stored.value?.toDoubleOrNull()
                    ?.let { prefs[doublePreferencesKey(name)] = it; true } ?: false
                "string" -> stored.value?.let { prefs[stringPreferencesKey(name)] = it; true } ?: false
                "stringSet" -> stored.setValue?.let { prefs[stringSetPreferencesKey(name)] = it.toSet(); true } ?: false
                else -> false
            }
        }
    }
}
