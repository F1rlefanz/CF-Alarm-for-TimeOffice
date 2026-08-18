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
import kotlinx.coroutines.flow.catch
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

    /**
     * NUR fuer den ANZEIGE-Flow. `coerceInputValues`: ein Enum-Wert, den diese Version nicht kennt
     * (Downgrade auf eine APK ohne SHIFT_END, kuenftige Umbenennung) faellt auf den Feld-Default
     * zurueck, statt die GANZE Regel-Liste unlesbar zu machen. Greift, weil startAnchor/endAnchor
     * Defaults haben.
     *
     * Diese Toleranz darf NIEMALS in einen Schreibpfad geraten - siehe [strictJson].
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /**
     * SCHREIB-Pfad: bewusst OHNE `coerceInputValues`.
     *
     * `editRules()` serialisiert den dekodierten Bestand komplett neu. Ein still auf den
     * Feld-Default gefallener Anker wuerde dabei beim naechsten `upsert()`/`delete()` - auch beim
     * Bearbeiten einer voellig anderen Regel - dauerhaft festgeschrieben: das Fenster dimmt danach
     * zur falschen Zeit, und der Rueckweg ueber ein Update/Downgrade-Fix ist zu, weil der
     * Originalwert nicht mehr in den Rohdaten steht. Deshalb behandelt der Schreibpfad einen
     * unbekannten Wert wie einen Dekodier-Fehler und bricht ab (Rohdaten bleiben liegen), waehrend
     * die Anzeige weiter tolerant liest.
     *
     * `ignoreUnknownKeys` bleibt auch hier an: ein unbekanntes FELD (Downgrade auf eine APK ohne
     * dieses Feld) wuerde sonst jedes Bearbeiten blockieren. Es faellt beim Zurueckschreiben
     * weiterhin weg - bewusst in Kauf genommen, anders als beim bedeutungstragenden Anker.
     */
    private val strictJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Das `runCatching` weiter unten umfasst NUR `decodeFromString`, nicht den Store-Read davor -
     * das war bis zum 18.08.2026 die Luecke. Eine IOException aus `dataStore.data` (voller
     * Speicher, EACCES, transienter Lesefehler; der `ReplaceFileCorruptionHandler` faengt nur
     * Korruption) flog ungebremst durch `getRules()` und damit durch `DimRuleUseCase
     * .getAllRules()` in `DimScheduleUseCase.computeWindows()` - also mitten in den Dimm-Tick.
     *
     * Deshalb hier zusaetzlich ein `.catch` auf dem LESE-Flow, gleiche Richtung wie der
     * Dekodier-Fehler darunter: leere Regelliste. Fuer den Dimmer heisst das "diese Nacht kein
     * Dimmen" - dieselbe fail-safe Richtung, die [DimOverlayPrefs.safeData] mit ausfuehrlicher
     * Begruendung waehlt (ein unerwartet dunkler Bildschirm ist schlimmer als ein heller).
     *
     * Der SCHREIB-Pfad bleibt davon unberuehrt: `editRules()` liest in seinem eigenen
     * `dataStore.edit{}` erneut und stuetzt sich nie auf diesen Flow. Genau deshalb darf er
     * degradieren, ohne dass die Notlage-Leere zur Schreibwahrheit wird.
     */
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
        .catch { e ->
            Logger.e(
                LogTags.DIMMER,
                "Dimm-Regeln nicht lesbar - degradiert auf leere Liste (kein Dimmen)",
                e
            )
            emit(emptyList())
        }

    suspend fun getRules(): List<DimRule> = rules.first()

    // Es gibt bewusst KEIN oeffentliches saveRules(list): das war das Muster "getRules() +
    // Ganzliste zurueckschreiben", das genau die zwei Fehler mitbringt, die editRules() unten
    // ausschliesst (verlorene Aenderung bei Doppel-Tap, degradierte leere Liste loescht alles).
    // Bis v1.22.x stand es hier ohne Aufrufer herum - ein Nebeneingang, der nur darauf wartete,
    // wieder benutzt zu werden. Wer einen Massen-Schreibpfad braucht, baut ihn auf editRules() auf.

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
     *    (`DimmerRulesViewModel`). Zwei getrennte Transaktionen (`getRules()` + Zurückschreiben der
     *    Ganzliste) verlieren bei einem Doppel-Tap eine Änderung; DataStore serialisiert
     *    `edit{}`-Blöcke selbst.
     * 2. Datenverlust: Ein Dekodier-Fehler darf NICHT als leere Liste in den Schreibvorgang laufen –
     *    sonst löscht das nächste Speichern/Löschen alle übrigen Regeln endgültig (inklusive der
     *    bedeutungstragenden Nachtdienst-Ausnahme mit leerer Fensterliste). Deshalb bricht der Edit
     *    hier ohne Änderung ab: nicht speichern ist besser als alles verlieren, die Rohdaten bleiben
     *    liegen und sind nach einem Downgrade/Fix wieder lesbar. Bewusst KEINE Exception nach oben:
     *    der Aufrufer (`viewModelScope.launch` ohne try/catch) würde daran abstürzen.
     *
     * Gelesen wird mit [strictJson], NICHT mit dem toleranten Anzeige-[json]: ein unbekannter
     * Enum-Wert ist hier derselbe Abbruchgrund wie defektes JSON, damit das Zurückschreiben ihn
     * nicht stillschweigend auf den Feld-Default festschreibt.
     */
    private suspend fun editRules(op: String, transform: (MutableList<DimRule>) -> Unit) {
        dataStore.edit { prefs ->
            val raw = prefs[KEY_RULES]
            val current = try {
                raw?.let { strictJson.decodeFromString<List<DimRule>>(it) } ?: emptyList()
            } catch (e: Exception) {
                Logger.e(
                    LogTags.DIMMER,
                    "Dimm-Regel-JSON (${raw?.length ?: 0} Zeichen) defekt oder enthaelt einen " +
                        "unbekannten Wert: $op abgebrochen, bestehende Regeln bleiben unveraendert",
                    e
                )
                return@edit
            }
            val updated = current.toMutableList()
            transform(updated)
            prefs[KEY_RULES] = strictJson.encodeToString(updated)
        }
    }
}
