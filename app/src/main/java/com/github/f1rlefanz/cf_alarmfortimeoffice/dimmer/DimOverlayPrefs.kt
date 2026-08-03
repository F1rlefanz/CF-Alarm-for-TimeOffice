package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen und Render-Zustand des Schicht-Dimmers (im bestehenden [MainDataStore]).
 *
 * Drei unabhängige Fenster-Quellen (siehe [DimScheduleUseCase], [Toggles]):
 * - `wellnessEnabled`: globaler „Wind-down" um den Wecker (nutzt Verdunkelung/Wärme/Wind-down).
 * - `rulesEnabled`: das schicht-gekoppelte Regelsystem ([DimRule]).
 * - `nightDefaultEnabled` (seit v1.17.0): eingebauter Nacht-Standard ohne eigene Regel, mit
 *   eigener Verdunkelung/Wärme ([nightDefaultStrength]/[nightDefaultWarmth]) und expliziten
 *   Schicht-Ausnahmen ([nightDefaultExcludedShifts]) statt einer leeren [DimRule].
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
        private val KEY_NIGHT_DEFAULT_ON = booleanPreferencesKey("dim_night_default_enabled")
        private val KEY_OVERLAY_ON = booleanPreferencesKey("dim_overlay_on")
        private val KEY_STRENGTH = intPreferencesKey("dim_strength")
        private val KEY_WARMTH = intPreferencesKey("dim_warmth")
        private val KEY_WINDDOWN_MIN = intPreferencesKey("dim_winddown_min")
        private val KEY_NIGHT_DEFAULT_START_MIN = intPreferencesKey("dim_night_default_start_min")
        private val KEY_NIGHT_DEFAULT_FREE_END_MIN = intPreferencesKey("dim_night_default_free_end_min")
        private val KEY_NIGHT_DEFAULT_EXCLUDED_SHIFTS = stringSetPreferencesKey("dim_night_default_excluded_shifts")
        private val KEY_NIGHT_DEFAULT_STRENGTH = intPreferencesKey("dim_night_default_strength")
        private val KEY_NIGHT_DEFAULT_WARMTH = intPreferencesKey("dim_night_default_warmth")

        // Dimmer-Korrektur-Notification (Feature C) - Override-Zustand + Settings-Toggle. Der
        // Toggle-Key ist bereits hier angelegt, aber noch NICHT an einen Settings-Screen
        // angebunden (Default AUS deckt das ab - siehe CLAUDE.md "Dimmer-Korrektur-Notification").
        private val KEY_OVERRIDE_STRENGTH_DELTA = intPreferencesKey("dim_override_strength_delta")
        private val KEY_OVERRIDE_PAUSED = booleanPreferencesKey("dim_override_paused")
        private val KEY_OVERRIDE_WINDOW_END = longPreferencesKey("dim_override_window_end")
        private val KEY_OVERRIDE_WINDOW_STRENGTH = intPreferencesKey("dim_override_window_strength")
        private val KEY_CORRECTION_NOTIFICATION_ENABLED = booleanPreferencesKey("dim_correction_notification_enabled")

        /** Schrittweite von Heller/Dunkler in der Korrektur-Notification. Wirkt NUR auf strength. */
        const val OVERRIDE_STEP = 10

        // Farbe, die der Service gerade rendert (Intensität des AKTIVEN Fensters). Getrennt von
        // KEY_STRENGTH/KEY_WARMTH (= globale Nutzer-Slider), damit der Scheduler-Schreibzugriff und
        // die „Darstellung"-Slider sich nicht überschreiben. Fällt zurück auf die globalen Werte.
        private val KEY_RENDER_STRENGTH = intPreferencesKey("dim_render_strength")
        private val KEY_RENDER_WARMTH = intPreferencesKey("dim_render_warmth")

        const val STRENGTH_MAX = 85
        const val WARMTH_MAX = 100
        const val DEFAULT_STRENGTH = 55
        const val DEFAULT_WARMTH = 40
        const val DEFAULT_WINDDOWN_MIN = 120
        const val WINDDOWN_MIN_LIMIT = 15
        const val WINDDOWN_MAX_LIMIT = 8 * 60
        const val DEFAULT_NIGHT_DEFAULT_START_MIN = 22 * 60
        const val DEFAULT_NIGHT_DEFAULT_FREE_END_MIN = 7 * 60

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

    /** Die drei Fenster-Quellen-Schalter. */
    data class Toggles(val wellnessEnabled: Boolean, val rulesEnabled: Boolean, val nightDefaultEnabled: Boolean)

    /**
     * Temporärer Nutzer-Override für die Dimmer-Korrektur-Notification (Feature C). Persistiert im
     * DataStore, nicht in-memory - übersteht damit einen Prozess-Neustart von
     * [DimAccessibilityService]/[DimScheduleReceiver], die beide keine garantierte Lebensdauer
     * haben. [windowEnd] + [windowStrength] (= `range.last`/`strength` der aktiven Spanne) sind der
     * Gültigkeits-Schlüssel: gilt nur für dieselbe aktive Fenster-Spanne wie beim Setzen - `windowEnd`
     * allein reicht nicht, weil Wellness/Regeln/Nacht-Standard sich denselben Anker (oft die Weckzeit)
     * teilen können, siehe [DimWindowResolver.isOverrideStale].
     */
    data class Override(val strengthDelta: Int, val paused: Boolean, val windowEnd: Long, val windowStrength: Int)

    val renderState: Flow<RenderState> = dataStore.data.map { p ->
        RenderState(
            overlayOn = p[KEY_OVERLAY_ON] ?: false,
            strength = (p[KEY_RENDER_STRENGTH] ?: p[KEY_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX),
            warmth = (p[KEY_RENDER_WARMTH] ?: p[KEY_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX)
        )
    }

    val toggles: Flow<Toggles> = dataStore.data.map { p ->
        Toggles(
            wellnessEnabled = p[KEY_WELLNESS] ?: false,
            rulesEnabled = p[KEY_RULES_ON] ?: false,
            nightDefaultEnabled = p[KEY_NIGHT_DEFAULT_ON] ?: false
        )
    }

    val strength: Flow<Int> = dataStore.data.map { (it[KEY_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX) }
    val warmth: Flow<Int> = dataStore.data.map { (it[KEY_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX) }
    val windDownMinutes: Flow<Int> = dataStore.data.map {
        (it[KEY_WINDDOWN_MIN] ?: DEFAULT_WINDDOWN_MIN).coerceIn(WINDDOWN_MIN_LIMIT, WINDDOWN_MAX_LIMIT)
    }
    val nightDefaultStartMinutes: Flow<Int> = dataStore.data.map {
        (it[KEY_NIGHT_DEFAULT_START_MIN] ?: DEFAULT_NIGHT_DEFAULT_START_MIN).coerceIn(0, 24 * 60 - 1)
    }
    val nightDefaultFreeEndMinutes: Flow<Int> = dataStore.data.map {
        (it[KEY_NIGHT_DEFAULT_FREE_END_MIN] ?: DEFAULT_NIGHT_DEFAULT_FREE_END_MIN).coerceIn(0, 24 * 60 - 1)
    }
    /** Schichtnamen, deren Nacht der Nacht-Standard NICHT dimmt (z. B. Nachtdienst). */
    val nightDefaultExcludedShifts: Flow<Set<String>> = dataStore.data.map {
        it[KEY_NIGHT_DEFAULT_EXCLUDED_SHIFTS] ?: emptySet()
    }
    /** Eigene Verdunkelung/Wärme des Nacht-Standards - unabhängig von der globalen Wellness-Darstellung. */
    val nightDefaultStrength: Flow<Int> = dataStore.data.map {
        (it[KEY_NIGHT_DEFAULT_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX)
    }
    val nightDefaultWarmth: Flow<Int> = dataStore.data.map {
        (it[KEY_NIGHT_DEFAULT_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX)
    }

    val override: Flow<Override> = dataStore.data.map { p ->
        Override(
            strengthDelta = p[KEY_OVERRIDE_STRENGTH_DELTA] ?: 0,
            paused = p[KEY_OVERRIDE_PAUSED] ?: false,
            windowEnd = p[KEY_OVERRIDE_WINDOW_END] ?: 0L,
            windowStrength = p[KEY_OVERRIDE_WINDOW_STRENGTH] ?: 0
        )
    }

    /** Settings-Toggle fuer die Dimmer-Korrektur-Notification. Default AUS - noch nicht an einen
     * Settings-Screen angebunden, siehe Klassen-/Feld-Kommentar oben. */
    val correctionNotificationEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_CORRECTION_NOTIFICATION_ENABLED] ?: false
    }

    suspend fun togglesNow(): Toggles = toggles.first()
    suspend fun windDownMinutesNow(): Int = windDownMinutes.first()
    suspend fun strengthNow(): Int = strength.first()
    suspend fun warmthNow(): Int = warmth.first()
    suspend fun nightDefaultStartMinutesNow(): Int = nightDefaultStartMinutes.first()
    suspend fun nightDefaultFreeEndMinutesNow(): Int = nightDefaultFreeEndMinutes.first()
    suspend fun nightDefaultExcludedShiftsNow(): Set<String> = nightDefaultExcludedShifts.first()
    suspend fun nightDefaultStrengthNow(): Int = nightDefaultStrength.first()
    suspend fun nightDefaultWarmthNow(): Int = nightDefaultWarmth.first()
    suspend fun overrideNow(): Override = override.first()
    suspend fun correctionNotificationEnabledNow(): Boolean = correctionNotificationEnabled.first()

    suspend fun setWellnessEnabled(v: Boolean) = dataStore.edit { it[KEY_WELLNESS] = v }
    suspend fun setRulesEnabled(v: Boolean) = dataStore.edit { it[KEY_RULES_ON] = v }
    suspend fun setNightDefaultEnabled(v: Boolean) = dataStore.edit { it[KEY_NIGHT_DEFAULT_ON] = v }
    suspend fun setNightDefaultStartMinutes(v: Int) =
        dataStore.edit { it[KEY_NIGHT_DEFAULT_START_MIN] = v.coerceIn(0, 24 * 60 - 1) }
    suspend fun setNightDefaultFreeEndMinutes(v: Int) =
        dataStore.edit { it[KEY_NIGHT_DEFAULT_FREE_END_MIN] = v.coerceIn(0, 24 * 60 - 1) }
    suspend fun setNightDefaultExcludedShifts(v: Set<String>) =
        dataStore.edit { it[KEY_NIGHT_DEFAULT_EXCLUDED_SHIFTS] = v }
    suspend fun setNightDefaultStrength(v: Int) =
        dataStore.edit { it[KEY_NIGHT_DEFAULT_STRENGTH] = v.coerceIn(0, STRENGTH_MAX) }
    suspend fun setNightDefaultWarmth(v: Int) =
        dataStore.edit { it[KEY_NIGHT_DEFAULT_WARMTH] = v.coerceIn(0, WARMTH_MAX) }

    /**
     * Setzt An/Aus UND die Render-Farbe (Intensität/Wärme des gerade aktiven Fensters). Der Scheduler
     * ruft das mit den Werten der aktiven Spanne; die globalen [strength]/[warmth] bleiben unberührt
     * (sie sind Wellness- + Editor-Default). Für Vorschau: mit den globalen Werten aufrufen.
     */
    suspend fun setActiveOverlay(on: Boolean, strength: Int, warmth: Int) = dataStore.edit {
        it[KEY_OVERLAY_ON] = on
        it[KEY_RENDER_STRENGTH] = strength.coerceIn(0, STRENGTH_MAX)
        it[KEY_RENDER_WARMTH] = warmth.coerceIn(0, WARMTH_MAX)
    }
    suspend fun setStrength(v: Int) = dataStore.edit { it[KEY_STRENGTH] = v.coerceIn(0, STRENGTH_MAX) }
    suspend fun setWarmth(v: Int) = dataStore.edit { it[KEY_WARMTH] = v.coerceIn(0, WARMTH_MAX) }
    suspend fun setWindDownMinutes(v: Int) =
        dataStore.edit { it[KEY_WINDDOWN_MIN] = v.coerceIn(WINDDOWN_MIN_LIMIT, WINDDOWN_MAX_LIMIT) }

    /**
     * Schreibt Delta/Pause/Fenster-Schlüssel ATOMAR zusammen. Ein Teil-Update (z. B. nur `paused`
     * ändern) würde sonst einen alten, eigentlich schon veralteten `strengthDelta`-Wert unter einem
     * neuen [windowEnd] unbeabsichtigt "wiederbeleben" - der Aufrufer ([DimNotificationService])
     * liest daher vorher IMMER den effektiven (nicht-stale) Zustand und schreibt ihn hier komplett
     * zurück.
     */
    suspend fun setOverride(strengthDelta: Int, paused: Boolean, windowEnd: Long, windowStrength: Int) = dataStore.edit {
        it[KEY_OVERRIDE_STRENGTH_DELTA] = strengthDelta
        it[KEY_OVERRIDE_PAUSED] = paused
        it[KEY_OVERRIDE_WINDOW_END] = windowEnd
        it[KEY_OVERRIDE_WINDOW_STRENGTH] = windowStrength
    }

    suspend fun clearOverride() = dataStore.edit {
        it.remove(KEY_OVERRIDE_STRENGTH_DELTA)
        it.remove(KEY_OVERRIDE_PAUSED)
        it.remove(KEY_OVERRIDE_WINDOW_END)
        it.remove(KEY_OVERRIDE_WINDOW_STRENGTH)
    }

    suspend fun setCorrectionNotificationEnabled(v: Boolean) =
        dataStore.edit { it[KEY_CORRECTION_NOTIFICATION_ENABLED] = v }
}
