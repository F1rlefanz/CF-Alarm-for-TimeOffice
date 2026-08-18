package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen der Schlummer-Dauer (im bestehenden [MainDataStore]).
 *
 * Uebernimmt die frühere Rolle von `AlarmManagerService.SNOOZE_MINUTES` als EINE Quelle fuer
 * Vollbild-Button UND Notification-Button - aber NICHT indem beide Ausloeser diesen DataStore
 * direkt lesen. `AlarmSoundService.onStartCommand`s `ACTION_SNOOZE_ALARM`-Zweig ist bewusst ein
 * synchroner, schneller Notausgang (siehe Klassenkommentar dort) und
 * `AlarmFullScreenActivity.snoozeAlarm()` ist ebenfalls synchron - beide duerfen keine
 * DataStore-Reads bekommen. Stattdessen liest [AlarmReceiver] den Wert EINMAL pro Alarm-Feuern
 * (schon in einer Coroutine, `receiverScope.launch`) und reicht ihn als Intent-Extra
 * (`AlarmSoundService.EXTRA_SNOOZE_MINUTES`) an beide Ausloeser durch - bis dahin ist der Wert
 * synchron aus dem Intent verfuegbar.
 */
@Singleton
class AlarmPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")

        /** Default-Schlummer-Dauer in Minuten - identisch zum frueheren SNOOZE_MINUTES-Default. */
        const val DEFAULT_SNOOZE_MINUTES = 5

        /**
         * Sinnvolle Grenzen, geklemmt beim LESEN und beim SCHREIBEN.
         *
         * `0` oder negativ waere kein "kurzer Schlummer", sondern ein Schlummer-Alarm in der
         * Vergangenheit: er feuert sofort wieder, und der Wecker laesst sich nicht mehr
         * wegdruecken. Der Wert kommt nicht nur aus der eigenen UI - er liegt im `settings`-Store,
         * also auch im Android-Backup und in der Konfigurationsdatei (Export/Import). Beides sind
         * Wege, auf denen ein Wert von einem anderen Geraet, einer aelteren Version oder aus einer
         * von Hand bearbeiteten Datei hereinkommt. Der Import prueft den Bereich zwar selbst
         * (`ConfigBackupFilter.rangeRejection`), aber der Lesepfad ist die letzte Linie und die
         * einzige, die auch fuer das Android-Backup gilt - genauso wie `DimOverlayPrefs` jeden
         * seiner Werte beim Lesen klemmt.
         */
        const val MIN_SNOOZE_MINUTES = 1
        const val MAX_SNOOZE_MINUTES = 120
    }

    /**
     * Das `.catch` steht HINTER dem `.map` (die Reihenfolge ist tragend, siehe CLAUDE.md,
     * "Persistenz") und ist der einzige Grund, warum ein Lesefehler hier keinen stummen Wecker
     * mehr erzeugt.
     *
     * Der Hergang, gefunden in der Pruefrunde vom 18.08.2026: [snoozeMinutesNow] ist ein
     * `first()` auf diesem Flow, und `AlarmReceiver.startAlarmSoundService()` liest den Wert als
     * ERSTE Anweisung in demselben `try`, das auch `startForegroundService()` umschliesst. Der
     * `ReplaceFileCorruptionHandler` des `settings`-Stores faengt nur Korruption; eine
     * IOException (voller Speicher, EACCES, transienter Lesefehler) reicht DataStore in den Flow
     * durch. Sie flog also aus dem Read heraus, das `catch` darunter loggte sie - und
     * `startForegroundService()` wurde nie erreicht: kein Ton, keine Vibration, keine
     * Notification, kein Vollbild. Bei jedem Alarm, bis "App-Daten loeschen".
     *
     * Die Direct-Boot-Ursache derselben Stelle war bereits per `userUnlocked`-Gate geschlossen
     * (Kommentar in `AlarmReceiver`), die IO-Ursache blieb offen - dasselbe Ergebnis, anderer
     * Weg.
     *
     * Degradiert wird auf [DEFAULT_SNOOZE_MINUTES], nicht auf "gar nicht wecken". Die Richtung
     * ist bewusst gewaehlt: ein Wecker mit der Standard-Schlummerdauer ist ein winziger Fehler,
     * ein Wecker, der nicht klingelt, der schwerste denkbare. Im Zweifel klingeln.
     */
    val snoozeMinutes: Flow<Int> = dataStore.data
        .map {
            (it[KEY_SNOOZE_MINUTES] ?: DEFAULT_SNOOZE_MINUTES)
                .coerceIn(MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES)
        }
        .catch { e ->
            Logger.e(
                LogTags.ALARM,
                "Schlummerdauer nicht lesbar - degradiert auf $DEFAULT_SNOOZE_MINUTES Minuten, " +
                    "der Wecker klingelt trotzdem",
                e
            )
            emit(DEFAULT_SNOOZE_MINUTES)
        }

    suspend fun snoozeMinutesNow(): Int = snoozeMinutes.first()

    suspend fun setSnoozeMinutes(v: Int) = dataStore.edit {
        it[KEY_SNOOZE_MINUTES] = v.coerceIn(MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES)
    }
}
