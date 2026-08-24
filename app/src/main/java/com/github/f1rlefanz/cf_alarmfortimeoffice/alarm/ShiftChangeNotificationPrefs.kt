package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
 * Einstellungen der Schicht-Aenderungs-Notification (Feature B, im bestehenden [MainDataStore]).
 *
 * Der Toggle-Key ist bereits hier angelegt, aber noch NICHT an einen Settings-Screen angebunden -
 * das uebernimmt das separate Integrations-Paket danach (siehe Plan "Gemeinsame UI-Anbindung
 * (Feature B + C)"). Default AN: anders als die Dimmer-Korrektur (Default AUS, ein reines
 * Komfort-Werkzeug) soll die Schicht-Aenderungs-Notification von Anfang an ohne Zutun des Nutzers
 * warnen - genau das TimeOffice-Sync-Problem, das diesen Feature-Bereich ausgeloest hat.
 */
@Singleton
class ShiftChangeNotificationPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("shift_change_notification_enabled")
    }

    /**
     * Das `.catch` umschliesst den Store-Read selbst - es war bis v1.26.2 die einzige der drei
     * Notification-Prefs OHNE eines (CalendarUnavailablePrefs und DimOverlayPrefs haben es).
     *
     * Der Weg des Wurfs: [enabledNow] ist ein `first()` hierauf, `ShiftChangeNotifier.notifyX()`
     * ruft es als erste Anweisung, und die drei notify-Methoden laufen MITTEN IN
     * `AlarmUseCase.syncAlarms()`. Eine IOException (der corruptionHandler faengt nur Korruption)
     * haette also nicht nur die Benachrichtigung verhindert, sondern den laufenden Alarm-Sync
     * abgebrochen - mit halb angelegten Alarmen.
     *
     * Degradiert wird auf `true`, also "im Zweifel benachrichtigen": eine ueberfluessige Meldung
     * ist harmlos, eine verschwiegene Dienstplan-Aenderung nicht.
     */
    val enabled: Flow<Boolean> = dataStore.data
        .map { it[KEY_ENABLED] ?: true }
        .catch { e ->
            Logger.e(
                LogTags.ALARM,
                "Schicht-Aenderungs-Einstellung nicht lesbar - degradiert auf AN (im Zweifel melden)",
                e
            )
            emit(true)
        }

    suspend fun enabledNow(): Boolean = enabled.first()

    suspend fun setEnabled(v: Boolean) = dataStore.edit { it[KEY_ENABLED] = v }
}
