package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen der DND-Steuerung (im bestehenden [MainDataStore]). Zwei unabhängige, per OR
 * kombinierte Fenster-Quellen (siehe [DndScheduleUseCase]):
 * - `followDimmerEnabled`: DND an, waehrend der Dimmer dimmt (liest [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase.previewTimeline],
 *   keine eigene Fenster-Definition - Einbahnstrasse, der Dimmer bleibt unveraendert/unwissend).
 * - `duringShiftEnabled`: DND an von Schichtbeginn bis Schichtende (Kalender-Event-Spanne, siehe
 *   [DndShiftSpanResolver]), mit expliziten Schicht-Ausnahmen ([shiftExcludedShifts]).
 */
@Singleton
class DndPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_FOLLOW_DIMMER = booleanPreferencesKey("dnd_follow_dimmer_enabled")
        private val KEY_DURING_SHIFT = booleanPreferencesKey("dnd_during_shift_enabled")
        private val KEY_SHIFT_EXCLUDED_SHIFTS = stringSetPreferencesKey("dnd_shift_excluded_shifts")
        private val KEY_ZEN_RULE_ID = stringPreferencesKey("dnd_zen_rule_id")
    }

    /** Die zwei Fenster-Quellen-Schalter. */
    data class Toggles(val followDimmerEnabled: Boolean, val duringShiftEnabled: Boolean)

    val toggles: Flow<Toggles> = dataStore.data.map { p ->
        Toggles(
            followDimmerEnabled = p[KEY_FOLLOW_DIMMER] ?: false,
            duringShiftEnabled = p[KEY_DURING_SHIFT] ?: false
        )
    }

    /** Schichtnamen, fuer die "Waehrend der Dienstzeit" NICHT gilt (z. B. Rufbereitschaft). */
    val shiftExcludedShifts: Flow<Set<String>> = dataStore.data.map {
        it[KEY_SHIFT_EXCLUDED_SHIFTS] ?: emptySet()
    }

    /** Die einmal registrierte [android.app.AutomaticZenRule]-ID; leer = noch nicht registriert. */
    val zenRuleId: Flow<String> = dataStore.data.map { it[KEY_ZEN_RULE_ID] ?: "" }

    suspend fun togglesNow(): Toggles = toggles.first()
    suspend fun shiftExcludedShiftsNow(): Set<String> = shiftExcludedShifts.first()
    suspend fun zenRuleIdNow(): String = zenRuleId.first()

    suspend fun setFollowDimmerEnabled(v: Boolean) = dataStore.edit { it[KEY_FOLLOW_DIMMER] = v }
    suspend fun setDuringShiftEnabled(v: Boolean) = dataStore.edit { it[KEY_DURING_SHIFT] = v }
    suspend fun setShiftExcludedShifts(v: Set<String>) =
        dataStore.edit { it[KEY_SHIFT_EXCLUDED_SHIFTS] = v }
    suspend fun setZenRuleId(v: String) = dataStore.edit { it[KEY_ZEN_RULE_ID] = v }
}
