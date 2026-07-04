package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Unit-Tests fuer den Delta-Sync in [AlarmUseCase.syncAlarms].
 *
 * Genau hier lebten historisch die schwerwiegendsten Bugs der App: "alter Alarm klingelt
 * nach Event-Aenderung" und "bestehende Wecker verschwinden, sobald ein neues Event
 * auftaucht" (Audit P0-3). Diese Tests fixieren den Vertrag der Delta-Synchronisation:
 * - Event bleibt vorhanden  -> Alarm bleibt erhalten
 * - Event verschwindet      -> Alarm wird geloescht (Repository + System-Alarm)
 * - autoAlarm deaktiviert    -> keine Alarme werden angefasst
 *
 * Getestet gegen die ECHTE [ShiftRecognitionEngine] (Keyword-Matching + Weckzeit) mit
 * handgeschriebenen Fakes; nur der Android-gebundene [AlarmManagerService] ist gemockt.
 */
class AlarmUseCaseDeltaSyncTest {

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> =
            Result.success(config.definitions.isNotEmpty())
    }

    private class FakeAlarmRepository(initial: List<AlarmInfo> = emptyList()) : IAlarmRepository {
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

    // --- Fixtures ---

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

    private fun existingAlarm(id: Int, eventId: String) = AlarmInfo(
        id = id,
        shiftId = "early",
        shiftName = "Frueh",
        triggerTime = 111L,
        formattedTime = "x",
        eventId = eventId,
        eventChecksum = "old"
    )

    private fun useCase(repo: FakeAlarmRepository, manager: AlarmManagerService, config: ShiftConfig): AlarmUseCase =
        AlarmUseCase(repo, manager, FakeShiftConfigRepository(config), ShiftRecognitionEngine(FakeShiftConfigRepository(config)))

    private fun mockManager(): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(m.setAlarmFromShiftMatch(any(), any(), any())).thenReturn(status)
        whenever(m.cancelSystemAlarm(any())).thenReturn(status)
        return m
    }

    // --- Tests ---

    @Test
    fun `autoAlarm deaktiviert - keine Alarme werden angefasst`() = runTest {
        val existing = existingAlarm(id = 1, eventId = "evB")
        val repo = FakeAlarmRepository(listOf(existing))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = false, definitions = listOf(earlyShift))

        val result = useCase(repo, manager, config)
            .syncAlarms(listOf(futureEvent("evB", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<AlarmInfo>(), result.getOrNull())
        // Bestehender Alarm unangetastet, kein System-Alarm gesetzt oder gecancelt.
        assertEquals(listOf(existing), repo.current)
        verify(manager, never()).cancelSystemAlarm(any())
        verify(manager, never()).setAlarmFromShiftMatch(any(), any(), any())
    }

    @Test
    fun `neues Event - bestehender Alarm bleibt erhalten und neuer wird erstellt`() = runTest {
        val evB = futureEvent("evB", "F", 1)
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = evB.id.hashCode(), eventId = "evB")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        // evB bleibt im Kalender, evC kommt neu hinzu (beide matchen Keyword "F").
        val result = useCase(repo, manager, config)
            .syncAlarms(listOf(evB, futureEvent("evC", "F", 2)), config)

        assertTrue(result.isSuccess)
        assertNotNull("Alarm fuer weiterhin vorhandenes evB darf NICHT geloescht werden",
            repo.current.find { it.eventId == "evB" })
        assertNotNull("Alarm fuer neues evC muss erstellt werden",
            repo.current.find { it.eventId == "evC" })
        verify(manager, atLeastOnce()).setAlarmFromShiftMatch(any(), any(), any())
    }

    @Test
    fun `entferntes Event - dessen Alarm wird geloescht, anderes bleibt`() = runTest {
        val evA = futureEvent("evA", "F", 1)
        val bAlarmId = 4242
        val repo = FakeAlarmRepository(
            listOf(
                existingAlarm(id = evA.id.hashCode(), eventId = "evA"),
                existingAlarm(id = bAlarmId, eventId = "evB")
            )
        )
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        // Nur evA wird uebergeben -> evB gilt als aus dem Kalender entfernt.
        val result = useCase(repo, manager, config).syncAlarms(listOf(evA), config)

        assertTrue(result.isSuccess)
        assertNull("Alarm fuer entferntes evB muss aus dem Repository verschwinden",
            repo.current.find { it.eventId == "evB" })
        verify(manager).cancelSystemAlarm(bAlarmId)
        assertNotNull("Alarm fuer weiterhin vorhandenes evA muss bleiben",
            repo.current.find { it.eventId == "evA" })
    }
}
