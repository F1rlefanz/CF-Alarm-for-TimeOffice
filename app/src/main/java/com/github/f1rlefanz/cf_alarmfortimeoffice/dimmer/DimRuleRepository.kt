@file:Suppress("UnusedImport") // encodeToString/decodeFromString werden reified genutzt

package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistiert die Dimm-Regeln als JSON im [MainDataStore] – gleiches Muster wie
 * `AlarmRepository` (kotlinx.serialization, kein neuer DataStore-Namespace).
 */
@Singleton
class DimRuleRepository @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_RULES = stringPreferencesKey("dim_rules")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // coerceInputValues: ein Enum-Wert, den diese Version nicht kennt (Downgrade auf eine APK
        // ohne SHIFT_END, kuenftige Umbenennung) faellt auf den Feld-Default zurueck, statt die
        // GANZE Regel-Liste unlesbar zu machen. Greift, weil startAnchor/endAnchor Defaults haben.
        coerceInputValues = true
    }

    val rules: Flow<List<DimRule>> = dataStore.data.map { prefs ->
        prefs[KEY_RULES]?.let { raw ->
            runCatching { json.decodeFromString<List<DimRule>>(raw) }
                .getOrElse { e ->
                    // Ein ANZEIGE-Flow darf degradieren (leere Liste statt Crash) - der SCHREIB-Pfad
                    // darf sich darauf aber niemals stuetzen, sonst loescht das erste Speichern den
                    // gesamten Bestand. Siehe editRules().
                    Logger.e(
                        LogTags.DIMMER,
                        "Defektes Dimm-Regel-JSON (${raw.length} Zeichen) - Anzeige degradiert auf leere Liste",
                        e
                    )
                    emptyList()
                }
        } ?: emptyList()
    }

    suspend fun getRules(): List<DimRule> = rules.first()

    suspend fun saveRules(list: List<DimRule>) {
        dataStore.edit { it[KEY_RULES] = json.encodeToString(list) }
    }

    suspend fun upsert(rule: DimRule) = editRules("upsert(${rule.id})") { current ->
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) current[idx] = rule else current.add(rule)
    }

    suspend fun delete(id: String) = editRules("delete($id)") { current ->
        current.removeAll { it.id == id }
    }

    /**
     * Read-Modify-Write INNERHALB einer einzigen `dataStore.edit{}`-Transaktion – Vorbild
     * `HueConfigRepository.saveScheduleRule()`.
     *
     * Zwei Gründe, warum Lesen und Schreiben hier nicht getrennt sein dürfen:
     * 1. Atomizität: Jede Nutzeraktion läuft in einer eigenen, unabgewarteten Coroutine
     *    (`DimmerRulesViewModel`). Zwei getrennte Transaktionen (`getRules()` + `saveRules()`)
     *    verlieren bei einem Doppel-Tap eine Änderung; DataStore serialisiert `edit{}`-Blöcke selbst.
     * 2. Datenverlust: Ein Dekodier-Fehler darf NICHT als leere Liste in den Schreibvorgang laufen –
     *    sonst löscht das nächste Speichern/Löschen alle übrigen Regeln endgültig (inklusive der
     *    bedeutungstragenden Nachtdienst-Ausnahme mit leerer Fensterliste). Deshalb bricht der Edit
     *    hier ohne Änderung ab: nicht speichern ist besser als alles verlieren, die Rohdaten bleiben
     *    liegen und sind nach einem Downgrade/Fix wieder lesbar. Bewusst KEINE Exception nach oben:
     *    der Aufrufer (`viewModelScope.launch` ohne try/catch) würde daran abstürzen.
     */
    private suspend fun editRules(op: String, transform: (MutableList<DimRule>) -> Unit) {
        dataStore.edit { prefs ->
            val raw = prefs[KEY_RULES]
            val current = try {
                raw?.let { json.decodeFromString<List<DimRule>>(it) } ?: emptyList()
            } catch (e: Exception) {
                Logger.e(
                    LogTags.DIMMER,
                    "Defektes Dimm-Regel-JSON (${raw?.length ?: 0} Zeichen): $op abgebrochen, " +
                        "bestehende Regeln bleiben unveraendert",
                    e
                )
                return@edit
            }
            val updated = current.toMutableList()
            transform(updated)
            prefs[KEY_RULES] = json.encodeToString(updated)
        }
    }
}
