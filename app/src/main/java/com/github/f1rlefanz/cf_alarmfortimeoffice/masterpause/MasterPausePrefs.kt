package com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    /**
     * **Fehlerbehandlung ist hier die Richtung, in die der Wecker fällt.** Der Corruption-Handler
     * fängt nur `CorruptionException`; eine IOException reichte ohne `.catch` bis in den
     * `BootReceiver` durch und beendete den Prozess. Degradiert wird auf **`false` = NICHT
     * pausiert**: ein fälschlich klingelnder Wecker fällt auf, ein fälschlich stummer nicht. Der
     * Fehler wird geloggt, sonst gliche er im Log normalem Betrieb.
     * Hergang: Skill cfalarm-persistenz-und-auth, persistenz.md.
     */
    val paused: Flow<Boolean> = dataStore.data
        .catch { e ->
            Logger.e(
                LogTags.MASTER_PAUSE,
                "Master-Pause nicht lesbar - degradiert auf NICHT pausiert (lieber wecken als still bleiben)",
                e
            )
            emit(emptyPreferences())
        }
        .map { it[KEY_MASTER_PAUSE] ?: false }

    suspend fun pausedNow(): Boolean = paused.first()

    suspend fun setPaused(value: Boolean) = dataStore.edit { it[KEY_MASTER_PAUSE] = value }
}
