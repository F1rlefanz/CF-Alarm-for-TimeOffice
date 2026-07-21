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
 * Einstellungen und aktueller Zustand des Schicht-Dimmers.
 *
 * Liegt bewusst im bestehenden [MainDataStore] (kein neuer Namespace – CFAlarm-Invariante).
 * Der [DimAccessibilityService] beobachtet [state] reaktiv; der [DimScheduleUseCase] setzt
 * [setOverlayOn] passend zum Schicht-Zeitfenster.
 *
 * Aufteilung wie beim NachtDimmer-Vorbild:
 * - [DimState.featureEnabled]: Der Nutzer hat den Schicht-Dimmer eingeschaltet (Master).
 * - [DimState.overlayOn]: Ob JETZT tatsaechlich gedimmt wird (vom Zeitplan gesetzt).
 */
@Singleton
class DimOverlayPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_FEATURE_ENABLED = booleanPreferencesKey("dim_feature_enabled")
        private val KEY_OVERLAY_ON = booleanPreferencesKey("dim_overlay_on")
        private val KEY_STRENGTH = intPreferencesKey("dim_strength")
        private val KEY_WARMTH = intPreferencesKey("dim_warmth")
        private val KEY_WINDDOWN_MIN = intPreferencesKey("dim_winddown_min")

        /** Nie 100 % – der Bildschirm muss ablesbar bleiben. */
        const val STRENGTH_MAX = 85
        const val WARMTH_MAX = 100
        const val DEFAULT_STRENGTH = 55
        const val DEFAULT_WARMTH = 40
        const val DEFAULT_WINDDOWN_MIN = 120   // 2 h Wind-down vor der Weckzeit
        const val WINDDOWN_MIN_LIMIT = 15
        const val WINDDOWN_MAX_LIMIT = 8 * 60

        /**
         * ARGB-Overlay-Farbe aus Dim- und Waermegrad – identisch zum NachtDimmer:
         * reines Schwarz bei Waerme 0, dunkles Amber bei hoher Waerme.
         */
        fun overlayColor(strength: Int, warmth: Int): Int {
            val alpha = Math.round(strength / 100.0 * 255.0).toInt()
            val r = Math.round(warmth / 100.0 * 90.0).toInt()
            val g = Math.round(warmth / 100.0 * 28.0).toInt()
            return Color.argb(alpha, r, g, 0)
        }
    }

    data class DimState(
        val featureEnabled: Boolean,
        val overlayOn: Boolean,
        val strength: Int,
        val warmth: Int
    ) {
        val color: Int get() = overlayColor(strength, warmth)
    }

    val state: Flow<DimState> = dataStore.data.map { p ->
        DimState(
            featureEnabled = p[KEY_FEATURE_ENABLED] ?: false,
            overlayOn = p[KEY_OVERLAY_ON] ?: false,
            strength = (p[KEY_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX),
            warmth = (p[KEY_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX)
        )
    }

    val windDownMinutes: Flow<Int> = dataStore.data.map {
        (it[KEY_WINDDOWN_MIN] ?: DEFAULT_WINDDOWN_MIN).coerceIn(WINDDOWN_MIN_LIMIT, WINDDOWN_MAX_LIMIT)
    }

    suspend fun snapshot(): DimState = state.first()
    suspend fun windDownMinutesNow(): Int = windDownMinutes.first()

    suspend fun setFeatureEnabled(v: Boolean) = dataStore.edit { it[KEY_FEATURE_ENABLED] = v }
    suspend fun setOverlayOn(v: Boolean) = dataStore.edit { it[KEY_OVERLAY_ON] = v }
    suspend fun setStrength(v: Int) =
        dataStore.edit { it[KEY_STRENGTH] = v.coerceIn(0, STRENGTH_MAX) }
    suspend fun setWarmth(v: Int) =
        dataStore.edit { it[KEY_WARMTH] = v.coerceIn(0, WARMTH_MAX) }
    suspend fun setWindDownMinutes(v: Int) =
        dataStore.edit { it[KEY_WINDDOWN_MIN] = v.coerceIn(WINDDOWN_MIN_LIMIT, WINDDOWN_MAX_LIMIT) }
}
