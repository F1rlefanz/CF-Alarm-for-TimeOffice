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
        private val skippedAlarmId: Int? = null,
        private var state: AlarmSkipState = AlarmSkipState()
    ) : IAlarmSkipRepository {
        var setSkippedFor: Int? = null
        var setSkippedTriggerTime: Long? = null
        var clearCalled = false

        override suspend fun setNextAlarmSkipped(alarmId: Int, triggerTime: Long, reason: String): Result<Unit> {
            setSkippedFor = alarmId
            setSkippedTriggerTime = triggerTime
            state = state.copy(
                isNextAlarmSkipped = true,
                skippedAlarmId = alarmId,
                skippedAlarmTriggerTime = triggerTime,
                skipReason = reason
            )
            return Result.success(Unit)
        }

        override suspend fun clearSkipStatus(): Result<Unit> {
            clearCalled = true
            state = AlarmSkipState()
            return Result.success(Unit)
        }

        override suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean> =
            Result.success(skippedAlarmId != null && skippedAlarmId == alarmId)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> =
            Result.success(state)

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(state)
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

    @Test
    fun `skipNextAlarm - persistiert die triggerTime des uebersprungenen Alarms`() = runTest {
        val alarm = futureAlarm(id = 3, offsetMs = 60 * 60 * 1000L)
        val repo = FakeAlarmRepository(listOf(alarm))
        val skipRepo = FakeSkipRepository()
        val manager = mockManager()

        AlarmSkipUseCase(skipRepo, repo, manager).skipNextAlarm()

        assertEquals(
            "Die urspruengliche triggerTime muss fuer clearExpiredSkip gespeichert werden",
            alarm.triggerTime,
            skipRepo.setSkippedTriggerTime
        )
    }

    @Test
    fun `clearExpiredSkip - loescht ein Flag dessen Ziel-Alarmzeit laengst verstrichen ist`() = runTest {
        // Regression: skipNextAlarm() cancelt+loescht den System-Alarm SOFORT (SKIP-IMMEDIATE),
        // wodurch der einzige urspruenglich vorgesehene Rueckmeldepfad (checkAndProcessSkip via
        // AlarmReceiver) fuer genau diesen Alarm nie erreicht wird - das Flag blieb dadurch bisher
        // dauerhaft haengen (real beobachtet: 26.07.-30.07., ~4 Tage). clearExpiredSkip() ist der
        // Ersatzmechanismus, der ueber syncAlarms() (Vordergrund + 6h-Wartung) periodisch greift.
        val pastTriggerTime = now - 60_000L
        val skipRepo = FakeSkipRepository(
            state = AlarmSkipState(
                isNextAlarmSkipped = true,
                skippedAlarmId = 42,
                skippedAlarmTriggerTime = pastTriggerTime
            )
        )
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).clearExpiredSkip()

        assertTrue(result.isSuccess)
        assertTrue("Ein abgelaufener Skip muss als geloescht gemeldet werden", result.getOrNull() == true)
        assertTrue("clearSkipStatus muss aufgerufen werden", skipRepo.clearCalled)
    }

    @Test
    fun `clearExpiredSkip - laesst ein noch aktuelles Flag unangetastet`() = runTest {
        val futureTriggerTime = now + 60 * 60 * 1000L
        val skipRepo = FakeSkipRepository(
            state = AlarmSkipState(
                isNextAlarmSkipped = true,
                skippedAlarmId = 42,
                skippedAlarmTriggerTime = futureTriggerTime
            )
        )
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).clearExpiredSkip()

        assertTrue(result.isSuccess)
        assertFalse("Ein noch nicht faelliges Flag darf nicht geloescht werden", result.getOrNull() == true)
        assertFalse("clearSkipStatus darf nicht aufgerufen werden", skipRepo.clearCalled)
    }

    @Test
    fun `clearExpiredSkip - kein Flag aktiv, nichts zu tun`() = runTest {
        val skipRepo = FakeSkipRepository(state = AlarmSkipState())
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).clearExpiredSkip()

        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull() == true)
        assertFalse(skipRepo.clearCalled)
    }

    @Test
    fun `clearExpiredSkip - Legacy-Flag ohne triggerTime (0) bleibt fuer immer unangetastet`() = runTest {
        // Ungetestete Kombination: isNextAlarmSkipped=true mit skippedAlarmTriggerTime=0 (Legacy-
        // Flag aus einer Version vor v1.18.2, oder ein kuenftiger Pfad, der isNextAlarmSkipped
        // setzt ohne triggerTime zu persistieren). Die Bedingung "skippedAlarmTriggerTime > 0"
        // haelt dieses Flag bewusst fuer immer stehen - wuerde eine kuenftige "Vereinfachung"
        // diesen Teil der Bedingung entfernen (z.B. nur noch "now > skippedAlarmTriggerTime"
        // pruefen), wuerde "now > 0" sofort zutreffen und ein legitimes Flag faelschlich sofort
        // loeschen.
        val skipRepo = FakeSkipRepository(
            state = AlarmSkipState(
                isNextAlarmSkipped = true,
                skippedAlarmId = 42,
                skippedAlarmTriggerTime = 0L
            )
        )
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).clearExpiredSkip()

        assertTrue(result.isSuccess)
        assertFalse("Ein Flag ohne triggerTime darf NIE als abgelaufen gelten", result.getOrNull() == true)
        assertFalse("clearSkipStatus darf nicht aufgerufen werden", skipRepo.clearCalled)
    }

    @Test
    fun `checkAndProcessSkip - Fehltreffer (andere Alarm-ID) laesst ein bestehendes Flag stehen`() = runTest {
        // Dokumentiert bewusst die Luecke, die den urspruenglichen Bug ausgemacht hat: feuert ein
        // ANDERER Alarm als der uebersprungene, raeumt checkAndProcessSkip nichts auf (ALARM_EXECUTED
        // -> kein clearSkipStatus). Real beobachtet am 30.07. 05:30:01 (Alarm 629498222 "not skipped",
        // Flag blieb trotzdem auf true). Das ist erwuenscht fuer den ID-Treffer-Fall, macht aber
        // clearExpiredSkip() in syncAlarms() zum einzig verlaesslichen Rueckfangnetz fuer ein
        // veraltetes Flag.
        val alarm = futureAlarm(id = 7, offsetMs = 60 * 60 * 1000L)
        val repo = FakeAlarmRepository(listOf(alarm))
        val skipRepo = FakeSkipRepository(
            skippedAlarmId = 999, // uebersprungener Alarm ist NICHT der hier feuernde (7)
            state = AlarmSkipState(isNextAlarmSkipped = true, skippedAlarmId = 999)
        )
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).checkAndProcessSkip(7)

        assertTrue(result.isSuccess)
        assertEquals(SkipProcessResult.ALARM_EXECUTED, result.getOrNull())
        assertFalse("Bei ID-Mismatch bleibt das Flag stehen - genau die Luecke, die clearExpiredSkip schliesst", skipRepo.clearCalled)
    }
}
