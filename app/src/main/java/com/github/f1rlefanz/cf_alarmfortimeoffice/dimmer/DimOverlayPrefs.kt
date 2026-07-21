package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen und Render-Zustand des Schicht-Dimmers (im bestehenden [MainDataStore]).
 *
 * Zwei unabhängige Fenster-Quellen (siehe [DimScheduleUseCase]):
 * - [wellnessEnabled]: globaler „Wind-down" um den Wecker (nutzt Verdunkelung/Wärme/Wind-down).
 * - [rulesEnabled]: das schicht-gekoppelte Regelsystem ([DimRule]).
 *
 * [overlayOn] wird vom Scheduler gesetzt; der [DimAccessibilityService] beobachtet nur
 * [renderState] (an/aus + Farbe).
 */
@Singleton
class DimOverlayPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_WELLNESS = booleanPreferencesKey("dim_wellness_enabled")
        private val KEY_RULES_ON = booleanPreferencesKey("dim_rules_enabled")
        private val KEY_OVERLAY_ON = booleanPreferencesKey("dim_overlay_on")
        private val KEY_STRENGTH = intPreferencesKey("dim_strength")
        private val KEY_WARMTH = intPreferencesKey("dim_warmth")
        private val KEY_WINDDOWN_MIN = intPreferencesKey("dim_winddown_min")

        const val STRENGTH_MAX = 85
        const val WARMTH_MAX = 100
        const val DEFAULT_STRENGTH = 55
        const val DEFAULT_WARMTH = 40
        const val DEFAULT_WINDDOWN_MIN = 120
        const val WINDDOWN_MIN_LIMIT = 15
        const val WINDDOWN_MAX_LIMIT = 8 * 60

        fun overlayColor(strength: Int, warmth: Int): Int {
            val alpha = Math.round(strength / 100.0 * 255.0).toInt()
            val r = Math.round(warmth / 100.0 * 90.0).toInt()
            val g = Math.round(warmth / 100.0 * 28.0).toInt()
            return Color.argb(alpha, r, g, 0)
        }
    }

    /** Was der Service rendert. */
    data class RenderState(val overlayOn: Boolean, val strength: Int, val warmth: Int) {
        val color: Int get() = overlayColor(strength, warmth)
    }

    /** Die beiden Fenster-Quellen-Schalter. */
    data class Toggles(val wellnessEnabled: Boolean, val rulesEnabled: Boolean)

    val renderState: Flow<RenderState> = dataStore.data.map { p ->
        RenderState(
            overlayOn = p[KEY_OVERLAY_ON] ?: false,
            strength = (p[KEY_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX),
            warmth = (p[KEY_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX)
        )
    }

    val toggles: Flow<Toggles> = dataStore.data.map { p ->
        Toggles(
            wellnessEnabled = p[KEY_WELLNESS] ?: false,
            rulesEnabled = p[KEY_RULES_ON] ?: false
        )
    }

    val strength: Flow<Int> = dataStore.data.map { (it[KEY_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX) }
    val warmth: Flow<Int> = dataStore.data.map { (it[KEY_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX) }
    val windDownMinutes: Flow<Int> = dataStore.data.map {
        (it[KEY_WINDDOWN_MIN] ?: DEFAULT_WINDDOWN_MIN).coerceIn(WINDDOWN_MIN_LIMIT, WINDDOWN_MAX_LIMIT)
    }

    suspend fun togglesNow(): Toggles = toggles.first()
    suspend fun windDownMinutesNow(): Int = windDownMinutes.first()

    suspend fun setWellnessEnabled(v: Boolean) = dataStore.edit { it[KEY_WELLNESS] = v }
    suspend fun setRulesEnabled(v: Boolean) = dataStore.edit { it[KEY_RULES_ON] = v }
    suspend fun setOverlayOn(v: Boolean) = dataStore.edit { it[KEY_OVERLAY_ON] = v }
    suspend fun setStrength(v: Int) = dataStore.edit { it[KEY_STRENGTH] = v.coerceIn(0, STRENGTH_MAX) }
    suspend fun setWarmth(v: Int) = dataStore.edit { it[KEY_WARMTH] = v.coerceIn(0, WARMTH_MAX) }
    suspend fun setWindDownMinutes(v: Int) =
        dataStore.edit { it[KEY_WINDDOWN_MIN] = v.coerceIn(WINDDOWN_MIN_LIMIT, WINDDOWN_MAX_LIMIT) }
}
