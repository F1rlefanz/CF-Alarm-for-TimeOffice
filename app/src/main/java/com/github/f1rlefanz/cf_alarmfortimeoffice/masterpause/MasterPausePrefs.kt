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
     * **Fehlerbehandlung ist hier keine Kür, sondern die Richtung, in die der Wecker fällt.**
     *
     * Der `ReplaceFileCorruptionHandler` des [MainDataStore] fängt NUR eine `CorruptionException`;
     * eine IOException reicht DataStore unverändert durch. Ohne `.catch` schlug sie damit bei
     * jedem Leser durch — u. a. beim Master-Pause-Backstop in `AlarmUseCase.syncAlarms()`, den
     * Gates von `DimScheduleUseCase`/`DndScheduleUseCase` und im `BootReceiver`, wo der Read
     * außerhalb eines try/catch in einem Scope ohne `CoroutineExceptionHandler` lag und den
     * PROZESS beendete (damit fiel die gesamte Boot-Wiederherstellung aus). Dasselbe Muster hat
     * `auth_prefs` bereits: `corruptionHandler` UND `.catch{}` am Flow.
     *
     * Degradiert wird auf **`false` = NICHT pausiert**, und diese Richtung ist der eigentliche
     * Punkt: Ein fälschlich wiederhergestellter Wecker klingelt hörbar und der Nutzer stellt ihn
     * ab. Ein fälschlich unterdrückter ist STILL, und niemand merkt es, bis er verschlafen hat.
     * Dieselbe Abwägung trifft `DeviceLocalFlagsGuard` ausdrücklich.
     *
     * Der Fehler wird als solcher **geloggt** — sonst ist er im Log von einem normalen,
     * nicht pausierten Betrieb nicht zu unterscheiden.
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
