package com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellung der Master-Pause (im bestehenden [MainDataStore]): EIN Schalter, der ALLE
 * autonomen Hintergrunddienste (Wecker, Dimmer, DND, Hue-SmartScheduler, Pre-Alarm-Refresh)
 * gemeinsam pausiert - siehe [MasterPauseUseCase].
 */
@Singleton
class MasterPausePrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_MASTER_PAUSE = booleanPreferencesKey("master_pause_enabled")
    }

    val paused: Flow<Boolean> = dataStore.data.map { it[KEY_MASTER_PAUSE] ?: false }

    suspend fun pausedNow(): Boolean = paused.first()

    suspend fun setPaused(value: Boolean) = dataStore.edit { it[KEY_MASTER_PAUSE] = value }
}
