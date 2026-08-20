package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pruefrunde 6, Befund 9: Der AENDERUNGS-Zweig von [AlarmUseCase.syncAlarms] loeschte VOR dem
 * Cancellen - genau umgekehrt zum Loeschzweig zwoelf Zeilen darueber und entgegen der
 * CLAUDE.md-Invariante "Loeschen heisst IMMER: erst cancelSystemAlarm(), dann deleteAlarm()".
 *
 * WARUM DAS ZAEHLT: `deleteAlarm()` schreibt DataStore UND Direct-Boot-Spiegel neu. Zwischen den
 * beiden Zeilen kennt also niemand mehr den Alarm, waehrend er im AlarmManager noch scharf steht -
 * und ALLE Cancel-Wege der App iterieren ueber den Repository-Bestand. Stirbt der Prozess dort
 * (Low-Memory-Kill des kurzlebigen Wartungs-Service, Force-Stop, leerer Akku) und verschwindet der
 * Termin danach aus dem Kalender, klingelt der Waise an einem freien Tag - unsichtbar und bis zum
 * naechsten Geraete-Neustart nicht abbrechbar.
 */
class Pruefrunde6SkipSyncOrderTest {

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> =
            Result.success(config.definitions.isNotEmpty())
    }

    /** Schreibt jeden Repository-Schritt in [protokoll] - so wird die REIHENFOLGE pruefbar. */
    private class RecordingAlarmRepository(
        initial: List<AlarmInfo>,
        private val protokoll: MutableList<String>
    ) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = false
        // Schreibfehler-Merker: fuer diese Tests immer heil - die Trennung der beiden
        // Signale wird in Pruefrunde8SignaltrennungTest geprueft.
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        private val state = MutableStateFlow(initial)
        override val activeAlarms: Flow<List<AlarmInfo>> = state

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            protokoll += "save:${alarmInfo.id}"
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(state.value.find { it.id == alarmId })

        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            protokoll += "delete:$alarmId"
            state.value = state.value.filterNot { it.id == alarmId }
            return Result.success(Unit)
        }

        override suspend fun deleteAllAlarms(): Result<Unit> {
            protokoll += "deleteAll"
            state.value = emptyList()
            return Result.success(Unit)
        }

        override suspend fun alarmExists(alarmId: Int): Result<Boolean> =
            Result.success(state.value.any { it.id == alarmId })
    }

    private class FakeSkipUseCase : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used in these tests"))

        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)

        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> =
            Result.success(AlarmSkipState())

        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
    }

    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        override suspend fun notifyCreated(new: AlarmInfo) = Unit
        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) = Unit
        override suspend fun notifyDeleted(old: AlarmInfo) = Unit
    }

    private val earlyShift = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    /** Weit in der Zukunft, damit die berechnete Weckzeit garantiert > now ist. */
    private fun futureEvent(id: String, title: String, day: Int) = CalendarEvent(
        id = id,
        title = title,
        startTime = LocalDateTime.of(2035, 6, day, 6, 0),
        endTime = LocalDateTime.of(2035, 6, day, 14, 0),
        calendarId = "test"
    )

    @Test
    fun `syncAlarms - der Aenderungs-Zweig cancelt VOR dem Loeschen`() = runTest {
        val protokoll = mutableListOf<String>()

        // Bestehender Alarm mit demselben Event, aber anderem Inhalt -> Aenderungs-Zweig.
        val bestehend = AlarmInfo(
            id = 4242,
            shiftId = "early",
            shiftName = "Frueh",
            triggerTime = 111L,
            formattedTime = "alt",
            eventId = "evChanged",
            eventChecksum = "old"
        )
        val repo = RecordingAlarmRepository(listOf(bestehend), protokoll)

        val manager = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(manager.setAlarmFromShiftMatch(any(), any(), any())).thenReturn(status)
        whenever(manager.cancelSystemAlarm(any())).thenAnswer { aufruf ->
            protokoll += "cancel:${aufruf.arguments[0]}"
            status
        }

        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val useCase = AlarmUseCase(
            repo,
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            FakeSkipUseCase(),
            FakeShiftChangeNotifier(),
            mock<MasterPausePrefs>().also {
                kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(false) }
            },
            mock<ShiftSpanStore>(),
            FakeSyncHorizonStore()
        )

        val result = useCase.syncAlarms(listOf(futureEvent("evChanged", "F", 1)), config)

        assertTrue(result.isSuccess)
        val cancelIndex = protokoll.indexOf("cancel:4242")
        val deleteIndex = protokoll.indexOf("delete:4242")
        assertTrue("Der alte System-Alarm muss gecancelt worden sein", cancelIndex >= 0)
        assertTrue("Der alte Eintrag muss geloescht worden sein", deleteIndex >= 0)
        assertTrue(
            "ERST cancelSystemAlarm(), DANN deleteAlarm() - sonst bleibt bei Prozess-Tod ein " +
                "armierter Wecker zurueck, den kein Cancel-Weg der App mehr erreicht " +
                "(Protokoll: $protokoll)",
            cancelIndex < deleteIndex
        )
    }
}
