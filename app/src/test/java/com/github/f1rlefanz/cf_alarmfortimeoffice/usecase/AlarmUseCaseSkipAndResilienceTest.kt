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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Zwei Vertraege rund um [AlarmUseCase.syncAlarms], die beide aus real gemeldeten Fehlern stammen:
 *
 * 1. "Naechsten Alarm ueberspringen" darf vom naechsten Sync NICHT teilweise zurueckgedreht werden.
 *    `AlarmSkipUseCase.skipNextAlarm()` cancelt den System-Alarm sofort (SKIP-IMMEDIATE-UX) UND
 *    loescht den Alarm aus dem Repository - fuer den naechsten Sync sah dessen Kalender-Event damit
 *    wie ein NEUES Event aus: System-Alarm wieder scharf plus eine falsche "Neue Schicht
 *    erkannt"-Notification, obwohl der Nutzer den Wecker gerade abgeschaltet hatte.
 *
 * 2. Ein einzelner abgelehnter Alarm darf den restlichen Delta-Sync nicht abbrechen (`getOrThrow()`
 *    mitten in der Schleife liess bisher alle noch nicht abgearbeiteten Alarme ungesetzt).
 *
 * Dazu der eigene Cancel-Weg fuer schwebende Snooze-Alarme: er darf NUR bei ausdruecklichem
 * Nutzer-Willen greifen, niemals in datengetriebenen Aufraeumzweigen.
 */
class AlarmUseCaseSkipAndResilienceTest {

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> =
            Result.success(config.definitions.isNotEmpty())
    }

    /** [rejectEventIds] simuliert AlarmRepository.saveAlarm()s "Alarm time is in the past". */
    private class FakeAlarmRepository(
        initial: List<AlarmInfo> = emptyList(),
        private val rejectEventIds: Set<String> = emptySet()
    ) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = false
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        private val state = MutableStateFlow(initial)
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        val current: List<AlarmInfo> get() = state.value

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            if (alarmInfo.eventId in rejectEventIds) {
                return Result.failure(IllegalArgumentException("Alarm time is in the past"))
            }
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

    /**
     * Fake-Skip-UseCase mit festem Zustand. [failStatusRead] bildet den Fall ab, dass der
     * Skip-Zustand gerade nicht lesbar ist (z.B. CE-DataStore vor der ersten Entsperrung) - dann
     * MUSS der Alarm trotzdem gestellt werden (im Zweifel wecken).
     */
    private class FakeSkipUseCase(
        private val state: AlarmSkipState = AlarmSkipState(),
        private val failStatusRead: Boolean = false
    ) : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used in these tests"))

        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)

        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> =
            if (failStatusRead) Result.failure(IllegalStateException("Skip-Status nicht lesbar")) else Result.success(state)

        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(state)
    }

    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        var createdCount = 0
        var updatedCount = 0
        var deletedCount = 0

        override suspend fun notifyCreated(new: AlarmInfo) { createdCount++ }
        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) { updatedCount++ }
        override suspend fun notifyDeleted(old: AlarmInfo) { deletedCount++ }
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
        notifier: FakeShiftChangeNotifier = FakeShiftChangeNotifier(),
        masterPaused: Boolean = false
    ): AlarmUseCase =
        AlarmUseCase(
            repo,
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            skipUseCase,
            notifier,
            mock<MasterPausePrefs>().also {
                kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(masterPaused) }
            },
            mock<ShiftSpanStore>(),
            FakeSyncHorizonStore()
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

    private fun skippedState(alarmId: Int) = AlarmSkipState(
        isNextAlarmSkipped = true,
        skippedAlarmId = alarmId,
        skippedAlarmTriggerTime = Long.MAX_VALUE
    )

    // --- 1. Uebersprungener Alarm ---

    @Test
    fun `uebersprungener Alarm wird von syncAlarms nicht neu gestellt und nicht gemeldet`() = runTest {
        val skippedEvent = futureEvent("evSkip", "F", 1)
        val skippedId = skippedEvent.id.hashCode()
        val keptEvent = futureEvent("evKeep", "F", 2)

        // Repository nicht leer -> isFirstSync = false, eine falsche "Neue Schicht erkannt"-
        // Notification wuerde also tatsaechlich rausgehen.
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = 4242, eventId = "evKeep")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val notifier = FakeShiftChangeNotifier()

        val result = useCase(
            repo, manager, config,
            skipUseCase = FakeSkipUseCase(skippedState(skippedId)),
            notifier = notifier
        ).syncAlarms(listOf(skippedEvent, keptEvent), config)

        assertTrue(result.isSuccess)
        assertNull(
            "Der uebersprungene Alarm darf NICHT wieder im Repository auftauchen",
            repo.current.find { it.id == skippedId }
        )
        verify(manager, never()).setAlarmFromShiftMatch(any(), any(), eq(skippedId))
        assertEquals(
            "Fuer einen uebersprungenen Alarm darf keine 'Neue Schicht erkannt'-Notification kommen",
            0,
            notifier.createdCount
        )
        assertNotNull(
            "Der unbeteiligte Alarm muss ganz normal weiterverarbeitet werden",
            repo.current.find { it.eventId == "evKeep" }
        )
    }

    @Test
    fun `scheduleSystemAlarm - uebersprungener Alarm wird als Backstop nicht armiert`() = runTest {
        // Zentraler Backstop: auch Aufrufer ausserhalb von syncAlarms (BootReceiver re-armt alle
        // gespeicherten Alarme direkt hierueber) duerfen einen uebersprungenen Alarm nicht scharf
        // machen.
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val alarm = existingAlarm(id = 77, eventId = "evX").copy(triggerTime = 9_000_000_000L)

        val result = useCase(repo, manager, config, skipUseCase = FakeSkipUseCase(skippedState(77)))
            .scheduleSystemAlarm(alarm)

        // GEAENDERTE ZUSICHERUNG (Pruefrunde 6): frueher stand hier `assertTrue(result.isSuccess)`.
        // Genau das hat einen Wecker gekostet - "abgewiesen" war fuer den Aufrufer nicht von
        // "armiert" zu unterscheiden, und createManualAlarm() meldete deshalb Erfolg fuer einen
        // Wecker, den es im AlarmManager nie gab.
        assertTrue(result.isFailure)
        assertTrue(
            "Der Aufrufer muss 'wegen Ueberspringen abgewiesen' von einem echten Fehler " +
                "unterscheiden koennen",
            result.exceptionOrNull() is SkippedAlarmNotArmedException
        )
        verify(manager, never()).setAlarmFromShiftMatch(any(), any(), any())
    }

    @Test
    fun `scheduleSystemAlarm - nicht lesbarer Skip-Status stellt den Alarm trotzdem (im Zweifel wecken)`() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val alarm = existingAlarm(id = 77, eventId = "evX").copy(triggerTime = 9_000_000_000L)

        useCase(repo, manager, config, skipUseCase = FakeSkipUseCase(failStatusRead = true))
            .scheduleSystemAlarm(alarm)

        verify(manager).setAlarmFromShiftMatch(any(), any(), eq(77))
    }

    @Test
    fun `scheduleSystemAlarm - ein ANDERER uebersprungener Alarm blockiert diesen hier nicht`() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val alarm = existingAlarm(id = 77, eventId = "evX").copy(triggerTime = 9_000_000_000L)

        useCase(repo, manager, config, skipUseCase = FakeSkipUseCase(skippedState(999)))
            .scheduleSystemAlarm(alarm)

        verify(manager).setAlarmFromShiftMatch(any(), any(), eq(77))
    }

    // --- 2. Ein gescheiterter Alarm bricht den Sync nicht ab ---

    @Test
    fun `ein abgelehnter Alarm bricht den restlichen Delta-Sync nicht ab`() = runTest {
        // AlarmRepository.saveAlarm lehnt eine inzwischen verstrichene Weckzeit ab. Vorher warf
        // getOrThrow() aus der Schleife heraus: der Rest der (unsortierten) Map wurde weder erstellt
        // noch re-armed, und der Aufrufer sah nur ein Result.failure.
        val badEvent = futureEvent("evBad", "F", 1)
        val goodEvent = futureEvent("evGood", "F", 2)
        val repo = FakeAlarmRepository(rejectEventIds = setOf("evBad"))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        val result = useCase(repo, manager, config).syncAlarms(listOf(badEvent, goodEvent), config)

        assertTrue("Ein einzelner abgelehnter Alarm darf den Sync nicht scheitern lassen", result.isSuccess)
        assertNotNull(
            "Der unbeteiligte Alarm muss unabhaengig von der Map-Reihenfolge erstellt werden",
            repo.current.find { it.eventId == "evGood" }
        )
        assertNull(
            "Der abgelehnte Alarm darf nicht im Repository landen",
            repo.current.find { it.eventId == "evBad" }
        )
        assertEquals(
            "Nur der erfolgreiche Alarm darf im Ergebnis stehen",
            listOf("evGood"),
            result.getOrThrow().map { it.eventId }
        )
    }

    // --- 3. Schwebende Snooze-Alarme: nur bei Nutzer-Willen abbrechen ---

    @Test
    fun `Master-Pause bricht schwebende Snooze-Alarme mit ab`() = runTest {
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = 1, eventId = "evB")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        useCase(repo, manager, config, masterPaused = true)
            .syncAlarms(listOf(futureEvent("evB", "F", 1)), config)

        verify(manager).cancelAllSnoozes()
    }

    @Test
    fun `Automatische Alarme aus bricht schwebende Snooze-Alarme mit ab`() = runTest {
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = 1, eventId = "evB")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = false, definitions = listOf(earlyShift))

        useCase(repo, manager, config).syncAlarms(listOf(futureEvent("evB", "F", 1)), config)

        verify(manager).cancelAllSnoozes()
    }

    @Test
    fun `leere Eventliste raeumt Alarme ab, laesst einen schwebenden Snooze aber stehen`() = runTest {
        // Der Kern der Snooze-Invariante: "Kalender liefert gerade keine Events" ist direkt nach
        // dem Boot oder ohne Netz der Normalfall und KEIN Nutzer-Wille. Wuerde dieser Zweig den
        // Snooze mitloeschen, haette der Nutzer "5 Minuten schlummern" gedrueckt und wuerde nie
        // wieder geweckt - genau die Fehlerklasse, wegen der der Snooze einen eigenen
        // PendingIntent-Slot hat.
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = 1, eventId = "evB")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        useCase(repo, manager, config).syncAlarms(emptyList(), config)

        verify(manager).cancelSystemAlarm(1)
        verify(manager, never()).cancelAllSnoozes()
    }

    // --- 4. Aufraeumen darf nicht am noch leeren In-Memory-Cache vorbeilaufen ---

    /**
     * Bildet das Prozess-Startfenster des echten [com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmRepository]
     * nach: `activeAlarms` ist ein StateFlow, dessen Startwert `emptyList()` ist, bis der asynchrone
     * Init-Load zurueckkommt - `first()` liefert genau diesen leeren Wert SOFORT. `getAllAlarms()`
     * wartet dagegen auf den Load (awaitInitialLoad) und kennt den echten Bestand.
     */
    private class FakeNotYetLoadedRepository(private val persisted: List<AlarmInfo>) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = false
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        private val cache = MutableStateFlow<List<AlarmInfo>>(emptyList()) // Init-Load noch unterwegs
        override val activeAlarms: Flow<List<AlarmInfo>> = cache
        var deletedAll = false

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(persisted)
        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> = Result.success(Unit)
        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(persisted.find { it.id == alarmId })
        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAllAlarms(): Result<Unit> {
            deletedAll = true
            return Result.success(Unit)
        }
        override suspend fun alarmExists(alarmId: Int): Result<Boolean> =
            Result.success(persisted.any { it.id == alarmId })
    }

    @Test
    fun `Master-Pause cancelt System-Alarme auch wenn der Repository-Cache noch nicht geladen ist`() = runTest {
        // Vorher las clearInternalAlarms() `activeAlarms.first()`: in einem frisch gestarteten
        // Prozess (Wartungs-Service/Worker/Boot) war das die noch leere Startliste - es wurde KEIN
        // System-Alarm gecancelt, waehrend Repository und Direct-Boot-Spiegel gleich danach geleert
        // wurden. Der verwaiste AlarmManager-Eintrag feuerte spaeter trotzdem: der Wecker klingelte
        // trotz aktiver Master-Pause.
        val repo = FakeNotYetLoadedRepository(
            listOf(existingAlarm(id = 11, eventId = "evA"), existingAlarm(id = 12, eventId = "evB"))
        )
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        val useCase = AlarmUseCase(
            repo,
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            FakeSkipUseCase(),
            FakeShiftChangeNotifier(),
            mock<MasterPausePrefs>().also {
                kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(true) }
            },
            mock<ShiftSpanStore>(),
            FakeSyncHorizonStore()
        )

        useCase.syncAlarms(listOf(futureEvent("evA", "F", 1)), config)

        verify(manager).cancelSystemAlarm(11)
        verify(manager).cancelSystemAlarm(12)
        assertTrue("Das Repository muss trotzdem geleert werden", repo.deletedAll)
    }

    @Test
    fun `deleteAllAlarms bricht schwebende Snooze-Alarme mit ab`() = runTest {
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = 1, eventId = "evB")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        useCase(repo, manager, config).deleteAllAlarms()

        verify(manager).cancelAllSnoozes()
    }
}
