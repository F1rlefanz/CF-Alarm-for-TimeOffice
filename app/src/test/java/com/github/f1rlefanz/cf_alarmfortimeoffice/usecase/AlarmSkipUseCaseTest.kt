package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit-Tests fuer [AlarmSkipUseCase].
 *
 * Der schaerfste Vertrag hier ist die "Geister-Wecker"-Praevention: Wird ein Alarm
 * uebersprungen (bewusst per Knopf oder beim Ausloesen erkannt), MUSS der System-Alarm im
 * Android AlarmManager gecancelt werden - sonst klingelt er trotz "uebersprungen". Diese
 * Tests fixieren:
 * - skipNextAlarm  -> frueheste ZUKUNFT wird gewaehlt, Skip gesetzt, System-Alarm gecancelt, Repo bereinigt
 * - skipNextAlarm  -> ohne zukuenftigen Alarm: Fehler, nichts wird angefasst
 * - checkAndProcessSkip(skipped)  -> System-Alarm gecancelt + Repo bereinigt + Skip-Status geleert
 * - checkAndProcessSkip(nicht skipped) -> ALARM_EXECUTED, kein Cancel, kein Loeschen, kein Clear
 *
 * Nur der Android-gebundene [AlarmManagerService] ist gemockt; Repositories sind handgeschriebene Fakes.
 */
class AlarmSkipUseCaseTest {

    private val now = System.currentTimeMillis()

    private fun futureAlarm(id: Int, offsetMs: Long) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + offsetMs,
        formattedTime = "t$id"
    )

    private class FakeAlarmRepository(initial: List<AlarmInfo>) : IAlarmRepository {
        private val state = MutableStateFlow(initial)
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        val current: List<AlarmInfo> get() = state.value

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(state.value.find { it.id == alarmId })

        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmId }
            return Result.success(Unit)
        }

        override suspend fun deleteAllAlarms(): Result<Unit> {
            state.value = emptyList()
            return Result.success(Unit)
        }

        override suspend fun alarmExists(alarmId: Int): Result<Boolean> =
            Result.success(state.value.any { it.id == alarmId })
    }

    /** Fake-Skip-Repository mit konfigurierbarem "ist uebersprungen"-Zustand und Aufruf-Tracking. */
    private class FakeSkipRepository(
        private val skippedAlarmId: Int? = null
    ) : IAlarmSkipRepository {
        var setSkippedFor: Int? = null
        var clearCalled = false

        override suspend fun setNextAlarmSkipped(alarmId: Int, reason: String): Result<Unit> {
            setSkippedFor = alarmId
            return Result.success(Unit)
        }

        override suspend fun clearSkipStatus(): Result<Unit> {
            clearCalled = true
            return Result.success(Unit)
        }

        override suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean> =
            Result.success(skippedAlarmId != null && skippedAlarmId == alarmId)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> =
            Result.success(AlarmSkipState())

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
    }

    private fun mockManager(): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(m.cancelSystemAlarm(any())).thenReturn(status)
        return m
    }

    @Test
    fun `skipNextAlarm - waehlt fruehesten Zukunfts-Alarm, cancelt System-Alarm und bereinigt Repo`() = runTest {
        val early = futureAlarm(id = 1, offsetMs = 60 * 60 * 1000L)   // +1h
        val late = futureAlarm(id = 2, offsetMs = 2 * 60 * 60 * 1000L) // +2h
        val repo = FakeAlarmRepository(listOf(late, early))
        val skipRepo = FakeSkipRepository()
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).skipNextAlarm()

        assertTrue(result.isSuccess)
        assertEquals("Frueheste Zukunft muss gewaehlt werden", 1, result.getOrNull()?.alarmId)
        assertEquals("Skip muss fuer genau diesen Alarm gesetzt werden", 1, skipRepo.setSkippedFor)
        verify(manager).cancelSystemAlarm(1)
        assertNull("Uebersprungener Alarm muss aus dem Repo verschwinden", repo.current.find { it.id == 1 })
        assertNotNull("Der spaetere Alarm bleibt unberuehrt", repo.current.find { it.id == 2 })
    }

    @Test
    fun `skipNextAlarm - ohne zukuenftigen Alarm schlaegt fehl und fasst nichts an`() = runTest {
        // Nur ein bereits vergangener Alarm -> findNextAlarm liefert null.
        val past = futureAlarm(id = 9, offsetMs = -60 * 60 * 1000L)
        val repo = FakeAlarmRepository(listOf(past))
        val skipRepo = FakeSkipRepository()
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).skipNextAlarm()

        assertTrue("Ohne aktiven Alarm muss der Skip fehlschlagen", result.isFailure)
        assertNull("Es darf kein Skip gesetzt werden", skipRepo.setSkippedFor)
        verify(manager, never()).cancelSystemAlarm(any())
        assertNotNull("Der vergangene Alarm bleibt unangetastet", repo.current.find { it.id == 9 })
    }

    @Test
    fun `checkAndProcessSkip - uebersprungener Alarm wird gecancelt, geloescht und Skip geleert`() = runTest {
        val alarm = futureAlarm(id = 5, offsetMs = 60 * 60 * 1000L)
        val repo = FakeAlarmRepository(listOf(alarm))
        val skipRepo = FakeSkipRepository(skippedAlarmId = 5)
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).checkAndProcessSkip(5)

        assertTrue(result.isSuccess)
        assertEquals(SkipProcessResult.ALARM_SKIPPED, result.getOrNull())
        verify(manager).cancelSystemAlarm(5)
        assertNull("Uebersprungener Alarm muss aus dem Repo entfernt werden", repo.current.find { it.id == 5 })
        assertTrue("Skip-Status muss nach Verarbeitung geleert werden", skipRepo.clearCalled)
    }

    @Test
    fun `checkAndProcessSkip - nicht uebersprungener Alarm laeuft normal, nichts wird angefasst`() = runTest {
        val alarm = futureAlarm(id = 7, offsetMs = 60 * 60 * 1000L)
        val repo = FakeAlarmRepository(listOf(alarm))
        val skipRepo = FakeSkipRepository(skippedAlarmId = null) // nichts uebersprungen
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).checkAndProcessSkip(7)

        assertTrue(result.isSuccess)
        assertEquals(SkipProcessResult.ALARM_EXECUTED, result.getOrNull())
        verify(manager, never()).cancelSystemAlarm(any())
        assertNotNull("Ein normal laufender Alarm bleibt im Repo", repo.current.find { it.id == 7 })
        assertFalse("Ohne Skip darf der Skip-Status nicht geleert werden", skipRepo.clearCalled)
    }
}
