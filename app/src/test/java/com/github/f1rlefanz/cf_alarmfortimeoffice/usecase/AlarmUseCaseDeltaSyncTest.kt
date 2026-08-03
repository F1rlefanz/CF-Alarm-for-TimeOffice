package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
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
import org.junit.Assert.assertFalse
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

    /** Fake-Skip-UseCase; zaehlt lediglich, wie oft clearExpiredSkip() aufgerufen wurde. */
    private class FakeSkipUseCase : IAlarmSkipUseCase {
        var clearExpiredSkipCallCount = 0

        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used in these tests"))

        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)

        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(AlarmSkipState())

        override suspend fun clearExpiredSkip(): Result<Boolean> {
            clearExpiredSkipCallCount++
            return Result.success(false)
        }

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
    }

    /**
     * Fake-Notifier: zaehlt nur Aufrufe, dupliziert KEINE Schwellenwert-Logik (die lebt als reine
     * Funktion in [ShiftChangeNotifier.exceedsThreshold] und wird unten direkt getestet). Der
     * Context/die Prefs im Super-Konstruktor werden nie tatsaechlich benutzt, da alle drei
     * Notify-Methoden ueberschrieben sind und den Super-Aufruf nie erreichen.
     */
    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        var createdCount = 0
        var updatedCount = 0
        var deletedCount = 0

        override suspend fun notifyCreated(new: AlarmInfo) {
            createdCount++
        }

        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) {
            updatedCount++
        }

        override suspend fun notifyDeleted(old: AlarmInfo) {
            deletedCount++
        }
    }

    // --- Fixtures ---

    private val earlyShift = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    /** Rufbereitschaft (AD1): still, aber die Zeit bleibt Pflicht-Anker fuer DND/Dimmer/Hue. */
    private val silentShift = ShiftDefinition(
        id = "oncall",
        name = "AD1",
        keywords = listOf("AD1"),
        alarmTime = LocalTime.of(5, 0),
        isSilent = true
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

    private fun useCase(
        repo: FakeAlarmRepository,
        manager: AlarmManagerService,
        config: ShiftConfig,
        skipUseCase: IAlarmSkipUseCase = FakeSkipUseCase(),
        notifier: FakeShiftChangeNotifier = FakeShiftChangeNotifier()
    ): AlarmUseCase =
        AlarmUseCase(
            repo,
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            skipUseCase,
            notifier
        )

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

    @Test
    fun `syncAlarms - stoesst bei jedem Durchlauf die Skip-Ablauf-Pruefung an`() = runTest {
        // syncAlarms ist der einzige Einstiegspunkt der Event-Alarm-Pipeline (Vordergrund-Sync +
        // 6h-Wartung) - genau deshalb der richtige Ort fuer die Selbstheilung eines haengen
        // gebliebenen "Naechsten Alarm ueberspringen"-Flags. Siehe AlarmSkipUseCaseTest fuer die
        // eigentliche clearExpiredSkip-Logik.
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val skipUseCase = FakeSkipUseCase()

        useCase(repo, manager, config, skipUseCase).syncAlarms(listOf(futureEvent("evD", "F", 3)), config)

        assertEquals(1, skipUseCase.clearExpiredSkipCallCount)
    }

    @Test
    fun `Stille Schicht - isSilent wird aus der ShiftDefinition uebernommen und bleibt in getAllAlarms sichtbar`() = runTest {
        // Feature D: ShiftDefinition.isSilent -> AlarmInfo.isSilent (createAlarmFromShiftMatch).
        // Der Alarm muss trotzdem ganz normal im Bestand erscheinen - Voraussetzung fuer Feature A
        // (On-Call-DND liest AlarmInfo.shiftStartTime ueber genau denselben Weg).
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift, silentShift))
        val useCase = useCase(repo, manager, config)

        val result = useCase.syncAlarms(listOf(futureEvent("evOnCall", "AD1", 5)), config)

        assertTrue(result.isSuccess)
        val created = result.getOrNull()?.find { it.eventId == "evOnCall" }
        assertNotNull("Alarm fuer die stille Schicht muss erstellt werden", created)
        assertTrue("AlarmInfo.isSilent muss aus ShiftDefinition.isSilent uebernommen werden",
            created!!.isSilent)

        // Auch ueber den regulaeren Lesepfad (getAllAlarms) weiterhin sichtbar und als still markiert.
        val allAlarms = useCase.getAllAlarms().getOrThrow()
        val fromGetAll = allAlarms.find { it.eventId == "evOnCall" }
        assertNotNull("Stille Schicht darf nicht aus getAllAlarms() verschwinden", fromGetAll)
        assertTrue(fromGetAll!!.isSilent)
    }

    @Test
    fun `Normale Schicht bleibt isSilent = false`() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        val result = useCase(repo, manager, config).syncAlarms(listOf(futureEvent("evNormal", "F", 6)), config)

        val created = result.getOrNull()?.find { it.eventId == "evNormal" }
        assertNotNull(created)
        assertTrue("Regulaere Schicht darf nicht als still markiert werden", created!!.isSilent == false)
    }

    // --- Feature B: Schicht-Aenderungs-Notification (Wiring in AlarmUseCase.syncAlarms) ---

    @Test
    fun `Notifier - erster Sync ueberhaupt erstellt Alarme aber meldet keine 'Neue Schicht erkannt'`() = runTest {
        // Sonst wuerde jede Neuinstallation/jedes Zuruecksetzen sofort eine Notification-Flut
        // ausloesen - siehe ShiftChangeNotifier-Klassenkommentar.
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val notifier = FakeShiftChangeNotifier()

        useCase(repo, manager, config, notifier = notifier)
            .syncAlarms(listOf(futureEvent("evFirst", "F", 7)), config)

        assertEquals(0, notifier.createdCount)
        assertEquals(0, notifier.updatedCount)
        assertEquals(0, notifier.deletedCount)
    }

    @Test
    fun `Notifier - neues Event NACH dem ersten Sync wird als erstellt gemeldet`() = runTest {
        val evB = futureEvent("evB", "F", 1)
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = evB.id.hashCode(), eventId = "evB")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val notifier = FakeShiftChangeNotifier()

        // evB bleibt (Dummy-Fixture weicht vom real berechneten Alarm ab -> Update-Zweig),
        // evC kommt neu hinzu -> Create-Zweig.
        useCase(repo, manager, config, notifier = notifier)
            .syncAlarms(listOf(evB, futureEvent("evC", "F", 2)), config)

        assertEquals("Nur das tatsaechlich neue Event darf gemeldet werden", 1, notifier.createdCount)
        assertEquals(1, notifier.updatedCount)
        assertEquals(0, notifier.deletedCount)
    }

    @Test
    fun `Notifier - entferntes Event wird als geloescht gemeldet`() = runTest {
        val evA = futureEvent("evA", "F", 1)
        val repo = FakeAlarmRepository(
            listOf(
                existingAlarm(id = evA.id.hashCode(), eventId = "evA"),
                existingAlarm(id = 4242, eventId = "evB")
            )
        )
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val notifier = FakeShiftChangeNotifier()

        // Nur evA wird uebergeben -> evB gilt als aus dem Kalender entfernt.
        useCase(repo, manager, config, notifier = notifier).syncAlarms(listOf(evA), config)

        assertEquals(1, notifier.deletedCount)
        assertEquals(0, notifier.createdCount)
    }

    @Test
    fun `Notifier - autoAlarm deaktiviert - keine Notifier-Aufrufe`() = runTest {
        val existing = existingAlarm(id = 1, eventId = "evB")
        val repo = FakeAlarmRepository(listOf(existing))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = false, definitions = listOf(earlyShift))
        val notifier = FakeShiftChangeNotifier()

        useCase(repo, manager, config, notifier = notifier)
            .syncAlarms(listOf(futureEvent("evB", "F", 1)), config)

        assertEquals(0, notifier.createdCount)
        assertEquals(0, notifier.updatedCount)
        assertEquals(0, notifier.deletedCount)
    }

    // --- ShiftChangeNotifier.exceedsThreshold: reine Funktion, unabhaengig von Context/Prefs ---

    @Test
    fun `exceedsThreshold - Zeitverschiebung unter 10 Minuten ohne Namensaenderung ist Rundungsrauschen`() {
        val old = existingAlarm(id = 1, eventId = "e").copy(shiftName = "Frueh", triggerTime = 1_000_000L)
        val new = old.copy(triggerTime = old.triggerTime + 5 * 60_000L)

        assertFalse(ShiftChangeNotifier.exceedsThreshold(old, new))
    }

    @Test
    fun `exceedsThreshold - Zeitverschiebung ab 10 Minuten ist meldenswert`() {
        val old = existingAlarm(id = 1, eventId = "e").copy(shiftName = "Frueh", triggerTime = 1_000_000L)
        val new = old.copy(triggerTime = old.triggerTime + ShiftChangeNotifier.CHANGE_THRESHOLD_MINUTES * 60_000L)

        assertTrue(ShiftChangeNotifier.exceedsThreshold(old, new))
    }

    @Test
    fun `exceedsThreshold - Namensaenderung ist immer meldenswert, auch ohne Zeitverschiebung`() {
        val old = existingAlarm(id = 1, eventId = "e").copy(shiftName = "Frueh", triggerTime = 1_000_000L)
        val new = old.copy(shiftName = "AD1")

        assertTrue(ShiftChangeNotifier.exceedsThreshold(old, new))
    }
}
