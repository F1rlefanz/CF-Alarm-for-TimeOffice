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
    }

    val rules: Flow<List<DimRule>> = dataStore.data.map { prefs ->
        prefs[KEY_RULES]?.let { raw ->
            runCatching { json.decodeFromString<List<DimRule>>(raw) }
                .getOrElse {
                    Logger.w(LogTags.DIMMER, "Defektes Dimm-Regel-JSON – ignoriere")
                    emptyList()
                }
        } ?: emptyList()
    }

    suspend fun getRules(): List<DimRule> = rules.first()

    suspend fun saveRules(list: List<DimRule>) {
        dataStore.edit { it[KEY_RULES] = json.encodeToString(list) }
    }

    suspend fun upsert(rule: DimRule) {
        val current = getRules().toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) current[idx] = rule else current.add(rule)
        saveRules(current)
    }

    suspend fun delete(id: String) {
        saveRules(getRules().filterNot { it.id == id })
    }
}
