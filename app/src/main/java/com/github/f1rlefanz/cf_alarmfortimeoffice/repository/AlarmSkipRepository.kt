package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.AlarmSkipPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository implementation for alarm skip functionality.
 * Handles persistence of alarm skip state using DataStore.
 */
@Singleton
class AlarmSkipRepository @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) : IAlarmSkipRepository {
    
    /**
     * ROH, absichtlich OHNE `.catch`: die punktuellen Abfragen ([getSkipStatus], [isAlarmSkipped])
     * MUESSEN einen Lesefehler als Fehler sehen.
     *
     * Sie tragen den gesicherten manuellen Wecker: `AlarmViewModel.cancelSkip()` bricht bei
     * `Result.failure` ausdruecklich ab, statt Flag UND Schnappschuss abzuraeumen, die es gerade
     * nicht lesen konnte. Wuerde hier auf "nicht uebersprungen" degradiert, waere dieser Schutz
     * still ausgehebelt - der Wecker verschwaende wortlos.
     */
    private val rohSkipStatusFlow: Flow<AlarmSkipState> = dataStore.data.map { preferences ->
        AlarmSkipState(
            isNextAlarmSkipped = preferences[AlarmSkipPreferences.IS_NEXT_ALARM_SKIPPED] ?: false,
            skippedAlarmId = preferences[AlarmSkipPreferences.SKIPPED_ALARM_ID],
            skipActivatedAt = preferences[AlarmSkipPreferences.SKIP_ACTIVATED_AT] ?: 0L,
            skipReason = preferences[AlarmSkipPreferences.SKIP_REASON] ?: "Manuell übersprungen",
            skippedAlarmTriggerTime = preferences[AlarmSkipPreferences.SKIPPED_ALARM_TRIGGER_TIME] ?: 0L,
            skippedManualAlarm = preferences[AlarmSkipPreferences.SKIPPED_MANUAL_ALARM]
        )
    }

    /**
     * Der beobachtete Zustand fuer die Oberflaeche - `retryWhen` und `.catch` HINTER dem `.map`
     * (die Reihenfolge ist tragend, siehe CLAUDE.md "Persistenz"; so faengt der Retry auch ein
     * Scheitern der Abbildung und abonniert den Store wirklich neu).
     *
     * WELCHER ABLAUF GING KAPUTT: Der `ReplaceFileCorruptionHandler` des `settings`-Stores faengt
     * nur Korruption; eine IOException (voller Speicher, EACCES, transienter Lesefehler) reicht
     * DataStore in den Flow durch, und dieser Flow ENDETE daran dauerhaft - die Skip-Karte fror
     * bis zum Prozessneustart auf ihrem letzten Stand ein.
     *
     * WARUM `retryWhen` UND NICHT `.catch { emit(...) }`: `Flow.catch` faengt, emittiert einmal
     * und laesst den Flow danach NORMAL ABSCHLIESSEN - es abonniert nichts neu. Die Terminierung
     * blieb damit bestehen, nur der eingefrorene WERT waere ein anderer geworden. Genau das steht
     * im Haus schon zweimal aufgeschrieben (`DataStoreTokenRepository.observe()`,
     * `CalendarSelectionRepository`): ein transienter Fehler heilt sich selbst, wenn man erneut
     * abonniert.
     *
     * WARUM DER LETZTE FANG NICHTS EMITTIERT: Die Skip-Karte samt "Aufheben"-Knopf zeigt
     * `WeckerTabContent` nur bei `hasActiveAlarms || isNextAlarmSkipped` - und beim Ueberspringen
     * eines MANUELLEN Weckers ist der Alarm vorher aus dem Repository geloescht worden, es gibt
     * also typischerweise keinen aktiven Alarm mehr. Eine Degradierung auf "nicht uebersprungen"
     * nimmt damit den Knopf weg, der als einziger an den gesicherten `skippedManualAlarm`
     * heranfuehrt; das Flag laeuft erst NACH der Weckzeit ab, ein Nachholer existiert nicht. Kein
     * Signal ist hier also richtiger als ein falsches (gleiche Ueberlegung wie beim
     * Token-Verlust-Waechter) - die Ausloesung selbst haengt ohnehin am ROHEN Flow.
     *
     * DASS DER FLOW DANACH ENDET, DARF DIE BEDIENUNG ABER NICHT SPERREN. Genau das tat es bis
     * v1.28.0, und zwar andersherum als der Absatz zuvor verspricht: `AlarmViewModel` setzte
     * `isLoading` ausschliesslich in diesem Collector zurueck, `skipNextAlarm()` schaltete es vor
     * dem Schreiben ein - blieb die Emission aus, waren "Ueberspringen" UND "Aufheben" fuer den
     * Rest der Prozesslaufzeit ausgegraut (beide haengen an `!isLoading`). Der Collector loest
     * den Ladezustand deshalb jetzt auch beim Ende des Flows, abonniert begrenzt neu
     * (WIEDERAUFNAHME statt endgueltigem Aus, wie in `CalendarSelectionRepository`), und
     * `skipNextAlarm()`/`cancelSkip()` uebernehmen den gerade BESTAETIGT geschriebenen Zustand
     * selbst, statt auf die naechste Emission zu warten.
     */
    override val skipStatusFlow: Flow<AlarmSkipState> = rohSkipStatusFlow
        .retryWhen { cause, attempt ->
            if (attempt >= OBSERVE_RETRY_ATTEMPTS) {
                Logger.e(
                    LogTags.ALARM_SKIP,
                    "Skip-Zustand nach $attempt Versuchen nicht lesbar - die Anzeige bleibt auf " +
                        "ihrem letzten Stand stehen (der 'Aufheben'-Knopf bleibt damit erreichbar)",
                    cause
                )
                false
            } else {
                Logger.w(
                    LogTags.ALARM_SKIP,
                    "Skip-Zustand nicht lesbar (Versuch ${attempt + 1}/$OBSERVE_RETRY_ATTEMPTS) - neuer Versuch",
                    cause
                )
                delay(OBSERVE_RETRY_BASE_DELAY_MS * (attempt + 1))
                true
            }
        }
        .catch { e ->
            // Letzte Verteidigungslinie gegen den Absturz im Collector - bewusst OHNE emit.
            Logger.e(LogTags.ALARM_SKIP, "Skip-Zustand endgueltig nicht lesbar", e)
        }

    override suspend fun setNextAlarmSkipped(
        alarmId: Int,
        triggerTime: Long,
        reason: String,
        manualAlarmSnapshot: String?
    ): Result<Unit> =
        SafeExecutor.safeExecute("AlarmSkipRepository.setNextAlarmSkipped") {
            dataStore.edit { preferences ->
                preferences[AlarmSkipPreferences.IS_NEXT_ALARM_SKIPPED] = true
                preferences[AlarmSkipPreferences.SKIPPED_ALARM_ID] = alarmId
                preferences[AlarmSkipPreferences.SKIP_ACTIVATED_AT] = System.currentTimeMillis()
                preferences[AlarmSkipPreferences.SKIP_REASON] = reason
                preferences[AlarmSkipPreferences.SKIPPED_ALARM_TRIGGER_TIME] = triggerTime
                // Ohne das `remove` im else-Zweig ueberlebte der Schnappschuss eines FRUEHEREN
                // manuellen Skips einen neuen Skip auf einen kalenderbasierten Alarm - und
                // "Aufheben" haette einen laengst erledigten Wecker wieder gestellt.
                if (manualAlarmSnapshot != null) {
                    preferences[AlarmSkipPreferences.SKIPPED_MANUAL_ALARM] = manualAlarmSnapshot
                } else {
                    preferences.remove(AlarmSkipPreferences.SKIPPED_MANUAL_ALARM)
                }
            }
            Logger.business(
                LogTags.ALARM_SKIP,
                "Skip activated for alarm $alarmId (manueller Schnappschuss gesichert: ${manualAlarmSnapshot != null})"
            )
        }

    override suspend fun clearSkipStatus(): Result<Unit> =
        SafeExecutor.safeExecute("AlarmSkipRepository.clearSkipStatus") {
            dataStore.edit { preferences ->
                preferences.remove(AlarmSkipPreferences.IS_NEXT_ALARM_SKIPPED)
                preferences.remove(AlarmSkipPreferences.SKIPPED_ALARM_ID)
                preferences.remove(AlarmSkipPreferences.SKIP_ACTIVATED_AT)
                preferences.remove(AlarmSkipPreferences.SKIP_REASON)
                preferences.remove(AlarmSkipPreferences.SKIPPED_ALARM_TRIGGER_TIME)
                preferences.remove(AlarmSkipPreferences.SKIPPED_MANUAL_ALARM)
            }
            Logger.business(LogTags.ALARM_SKIP, "Skip status cleared")
        }
    
    override suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean> =
        SafeExecutor.safeExecute("AlarmSkipRepository.isAlarmSkipped") {
            // Bewusst der ROHE Flow: ein Lesefehler muss hier ein Result.failure werden, siehe
            // [rohSkipStatusFlow].
            val skipState = rohSkipStatusFlow.first()
            skipState.isNextAlarmSkipped && skipState.skippedAlarmId == alarmId
        }

    override suspend fun getSkipStatus(): Result<AlarmSkipState> =
        SafeExecutor.safeExecute("AlarmSkipRepository.getSkipStatus") {
            rohSkipStatusFlow.first()
        }

    companion object {
        /** Wie in `DataStoreTokenRepository` und `CalendarSelectionRepository` - ein Muster, eine Zahl. */
        internal const val OBSERVE_RETRY_ATTEMPTS = 5L
        internal const val OBSERVE_RETRY_BASE_DELAY_MS = 500L
    }
}
