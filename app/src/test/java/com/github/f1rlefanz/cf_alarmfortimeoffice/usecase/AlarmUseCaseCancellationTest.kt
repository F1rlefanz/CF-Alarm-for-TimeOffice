package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

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
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.CancellationException

/**
 * Die per-Event-Resilienz des Delta-Syncs darf Cancellation NICHT als Event-Fehler verbuchen.
 *
 * Die Resilienz-Schleife in [AlarmUseCase.syncAlarms] faengt pro Event `Exception` - und
 * `CancellationException` IST eine. Wird der Sync gecancelt (zwei ueberlappende Wartungslaeufe
 * teilen sich `serviceScope`; `AlarmMaintenanceService.onDestroy()` cancelt ihn mitten im Sync des
 * zweiten), wirft ab diesem Moment jeder suspendierende Aufruf im Schleifenkoerper sofort. Ohne
 * kooperativen Abbruch arbeitete die Schleife alle Events ab, loggte je einen
 * "uebersprungen"-Fehler und meldete danach `Result.success` mit "complete" - woraufhin der
 * Aufrufer `saveMaintenanceTime()` stempelte und die Statusanzeige einen erfolgreichen Sync
 * behauptete, obwohl gar nichts geschrieben wurde.
 */
class AlarmUseCaseCancellationTest {

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> =
            Result.success(config.definitions.isNotEmpty())
    }

    /**
     * [throwOnSave] bildet den gecancelten Scope nach: der erste suspendierende Aufruf im
     * Schleifenkoerper wirft. `alwaysCancel = true` wirft `CancellationException`, sonst eine
     * gewoehnliche Exception (der Fall, fuer den die Resilienz-Schleife gebaut wurde).
     */
    private class FakeAlarmRepository(
        private val alwaysCancel: Boolean
    ) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = false
        private val state = MutableStateFlow<List<AlarmInfo>>(emptyList())
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        var saveAttempts = 0

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            saveAttempts++
            if (alwaysCancel) throw CancellationException("Scope gecancelt")
            return Result.failure(IllegalStateException("Alarm time is in the past"))
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(state.value.find { it.id == alarmId })
        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAllAlarms(): Result<Unit> {
            state.value = emptyList()
            return Result.success(Unit)
        }
        override suspend fun alarmExists(alarmId: Int): Result<Boolean> =
            Result.success(state.value.any { it.id == alarmId })
    }

    private class FakeSkipUseCase : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used"))
        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)
        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)
        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(AlarmSkipState())
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
    private fun futureEvent(id: String, day: Int) = CalendarEvent(
        id = id,
        title = "F",
        startTime = LocalDateTime.of(2035, 6, day, 6, 0),
        endTime = LocalDateTime.of(2035, 6, day, 14, 0),
        calendarId = "test"
    )

    private fun useCase(repo: IAlarmRepository, config: ShiftConfig): AlarmUseCase {
        val manager = mock<AlarmManagerService>().also {
            val status = AlarmManagerService.AlarmStatus(
                systemAlarmSet = true,
                canScheduleExactAlarms = true,
                alarmStatusMessage = null
            )
            whenever(it.setAlarmFromShiftMatch(any(), any(), any())).thenReturn(status)
            whenever(it.cancelSystemAlarm(any())).thenReturn(status)
        }
        return AlarmUseCase(
            repo,
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            FakeSkipUseCase(),
            FakeShiftChangeNotifier(),
            mock<MasterPausePrefs>().also {
                kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(false) }
            }
        )
    }

    @Test
    fun `Cancellation bricht den Delta-Sync ab und meldet keinen Erfolg`() = runTest {
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val repo = FakeAlarmRepository(alwaysCancel = true)

        // Die Cancellation kommt jetzt als EXCEPTION heraus, nicht als Result.failure - und das ist
        // die staerkere Zusicherung, nicht die schwaechere: `SafeExecutor.safeExecute()` wirft
        // CancellationException seit dieser Runde weiter, statt sie in einen AppError zu verpacken.
        // Verpackt verlor sie ihre Identitaet, und genau dadurch lief der ausdrueckliche
        // `catch (e: CancellationException) { throw e }` der Delta-Sync-Schleife ins Leere:
        // `scheduleSystemAlarm()` ist ueber safeExecute gewrappt, die Cancellation kam dort als
        // gewoehnliches Failure an und wurde als Fehler EINES Events verbucht - die Schleife lief
        // ueber alle restlichen Events weiter, ohne einen einzigen zu re-armieren.
        //
        // Unveraendert bleibt der Kern: nach der Cancellation wird KEIN weiteres Event angefasst,
        // und der Aufrufer bekommt auf keinen Fall einen Erfolg (er darf keinen
        // saveMaintenanceTime()-Stempel fuer einen Lauf setzen, der nichts geschrieben hat).
        var thrown: Throwable? = null
        try {
            useCase(repo, config)
                .syncAlarms(listOf(futureEvent("evA", 1), futureEvent("evB", 2)), config)
        } catch (e: Throwable) {
            thrown = e
        }

        assertEquals(
            "Nach der Cancellation darf KEIN weiteres Event mehr angefasst werden",
            1,
            repo.saveAttempts
        )
        assertTrue(
            "Eine Cancellation MUSS die Aufrufkette hochlaufen (kein Result.failure) - der Scope " +
                "des Aufrufers ist in diesem Moment ohnehin nicht mehr am Leben. Bekommen: $thrown",
            thrown is kotlinx.coroutines.CancellationException
        )
    }

    @Test
    fun `ein gewoehnlicher Event-Fehler bricht den Sync weiterhin NICHT ab`() = runTest {
        // Gegenprobe: die Resilienz-Schleife bleibt erhalten - nur Cancellation ist die Ausnahme.
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val repo = FakeAlarmRepository(alwaysCancel = false)

        val result = useCase(repo, config)
            .syncAlarms(listOf(futureEvent("evA", 1), futureEvent("evB", 2)), config)

        assertEquals("Beide Events muessen versucht worden sein", 2, repo.saveAttempts)
        assertTrue("Einzelne abgelehnte Alarme lassen den Sync nicht scheitern", result.isSuccess)
    }
}
