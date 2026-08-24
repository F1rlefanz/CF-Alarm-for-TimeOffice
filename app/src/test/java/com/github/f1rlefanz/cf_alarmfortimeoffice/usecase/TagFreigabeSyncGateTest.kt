package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.freieTageStoreMit
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Gate und Backstop fuer "Tag freigeben" in [AlarmUseCase].
 *
 * Zwei Stellen, aus demselben Grund wie beim Ueberspringen:
 *  - das Gate in `syncAlarms()` verhindert, dass der Wecker ueberhaupt entsteht,
 *  - der Backstop in `scheduleSystemAlarm()` faengt jeden anderen Weg in den AlarmManager ab -
 *    insbesondere das ungefilterte Re-Arming des `BootReceiver` nach einem Neustart und den
 *    `TimezoneChangeReceiver`.
 *
 * Halten nur beide, haelt es ueberhaupt: das Gate allein liesse einen Neustart den Wecker
 * zurueckbringen, der Backstop allein liesse den Eintrag im Bestand liegen - und der
 * Direct-Boot-Spiegel und die Hue-Tagesplanung lesen den ungefiltert.
 */
class TagFreigabeSyncGateTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> =
            Result.success(config.definitions.isNotEmpty())
    }

    private class RecordingAlarmRepository(
        initial: List<AlarmInfo>,
        private val protokoll: MutableList<String>
    ) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = false
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        private val state = MutableStateFlow(initial)
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        val current: List<AlarmInfo> get() = state.value

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
        var geloeschtGemeldet = 0
        override suspend fun notifyCreated(new: AlarmInfo) = Unit
        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) = Unit
        override suspend fun notifyDeleted(old: AlarmInfo) {
            geloeschtGemeldet++
        }
    }

    private val frueh = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    /** Weit in der Zukunft, damit die berechnete Weckzeit garantiert > now ist. */
    private val eventTag: LocalDate = LocalDate.of(2035, 6, 12)

    private fun futureEvent(id: String, tag: LocalDate) = CalendarEvent(
        id = id,
        title = "F",
        startTime = LocalDateTime.of(tag, LocalTime.of(6, 0)),
        endTime = LocalDateTime.of(tag, LocalTime.of(14, 0)),
        calendarId = "test"
    )

    private fun sut(
        repo: IAlarmRepository,
        manager: AlarmManagerService,
        config: ShiftConfig,
        freieTage: Set<LocalDate>,
        notifier: FakeShiftChangeNotifier = FakeShiftChangeNotifier()
    ) = AlarmUseCase(
        repo,
        manager,
        FakeShiftConfigRepository(config),
        ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
        FakeSkipUseCase(),
        notifier,
        mock<MasterPausePrefs>().also {
            kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(false) }
        },
        mock<ShiftSpanStore>(),
        FakeSyncHorizonStore(),
        FakeFeedNeueinlesenStore(),
        freieTageStoreMit(freieTage)
    )

    private fun managerMit(protokoll: MutableList<String>): AlarmManagerService {
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
        return manager
    }

    // --- Der reine Helfer ---

    @Test
    fun `istTagFreigegeben ankert an der Weckzeit und faellt fail-safe auf NICHT freigegeben`() {
        val wecker = AlarmInfo(
            id = 1,
            shiftId = "early",
            shiftName = "Frueh",
            triggerTime = eventTag.atTime(5, 30).atZone(zone).toInstant().toEpochMilli(),
            formattedTime = "05:30",
            eventId = "ev1"
        )
        assertTrue(AlarmUseCase.istTagFreigegeben(setOf(eventTag), wecker, zone))
        assertFalse(AlarmUseCase.istTagFreigegeben(setOf(eventTag.plusDays(1)), wecker, zone))
        // Leere Menge heisst NICHT freigegeben - im Zweifel wecken.
        assertFalse(AlarmUseCase.istTagFreigegeben(emptySet(), wecker, zone))
        // MANUELLER Wecker (leere eventId): nimmt nicht teil. Sonst liesse sich an einem
        // freigegebenen Tag kein eigener Wecker mehr stellen - der Backstop wiese ihn ab.
        assertFalse(AlarmUseCase.istTagFreigegeben(setOf(eventTag), wecker.copy(eventId = ""), zone))
    }

    // --- Gate in syncAlarms() ---

    @Test
    fun `Fuer einen freigegebenen Tag entsteht kein Wecker`() = runTest {
        val protokoll = mutableListOf<String>()
        val repo = RecordingAlarmRepository(emptyList(), protokoll)
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh))

        val ergebnis = sut(repo, managerMit(protokoll), config, setOf(eventTag))
            .syncAlarms(listOf(futureEvent("ev1", eventTag)), config)

        assertTrue(ergebnis.isSuccess)
        assertTrue("Kein Wecker gespeichert (Protokoll: $protokoll)", repo.current.isEmpty())
        assertTrue(protokoll.none { it.startsWith("save:") })
    }

    @Test
    fun `Ein BESTEHENDER Wecker des Tages wird gecancelt UND geloescht - in dieser Reihenfolge`() = runTest {
        // Der Eintrag darf nicht liegen bleiben: der Direct-Boot-Spiegel armiert jeden
        // Zukunfts-Eintrag nach einem Neustart ungefiltert wieder, und die Hue-Tagesplanung liest
        // den Bestand ebenfalls ohne Filter - der freigegebene Morgen bekaeme Wecker UND Licht.
        val protokoll = mutableListOf<String>()
        val weckzeit = eventTag.atTime(5, 30).atZone(zone).toInstant().toEpochMilli()
        val bestehend = AlarmInfo(
            id = 77,
            shiftId = "early",
            shiftName = "Frueh",
            triggerTime = weckzeit,
            formattedTime = "05:30",
            eventId = "ev1",
            eventChecksum = "alt"
        )
        val repo = RecordingAlarmRepository(listOf(bestehend), protokoll)
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh))

        val ergebnis = sut(repo, managerMit(protokoll), config, setOf(eventTag))
            .syncAlarms(listOf(futureEvent("ev1", eventTag)), config)

        assertTrue(ergebnis.isSuccess)
        assertTrue("Der Eintrag muss weg sein", repo.current.isEmpty())
        val cancelIndex = protokoll.indexOf("cancel:77")
        val deleteIndex = protokoll.indexOf("delete:77")
        assertTrue("gecancelt (Protokoll: $protokoll)", cancelIndex >= 0)
        assertTrue("geloescht (Protokoll: $protokoll)", deleteIndex >= 0)
        assertTrue(
            "ERST cancelSystemAlarm(), DANN deleteAlarm() (Protokoll: $protokoll)",
            cancelIndex < deleteIndex
        )
    }

    @Test
    fun `Eine Freigabe meldet KEINE entfernte Schicht`() = runTest {
        // Der Dienstplan hat sich nicht geaendert - der Nutzer hat den Tag selbst freigegeben.
        // Eine "Schicht entfernt"-Benachrichtigung waere dieselbe Fehlklasse wie die verstrichene
        // Weckzeit im Loeschzweig.
        val protokoll = mutableListOf<String>()
        val weckzeit = eventTag.atTime(5, 30).atZone(zone).toInstant().toEpochMilli()
        val bestehend = AlarmInfo(
            id = 77, shiftId = "early", shiftName = "Frueh", triggerTime = weckzeit,
            formattedTime = "05:30", eventId = "ev1", eventChecksum = "alt"
        )
        val notifier = FakeShiftChangeNotifier()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh))

        sut(RecordingAlarmRepository(listOf(bestehend), protokoll), managerMit(protokoll), config, setOf(eventTag), notifier)
            .syncAlarms(listOf(futureEvent("ev1", eventTag)), config)

        assertEquals(0, notifier.geloeschtGemeldet)
    }

    @Test
    fun `Ein NICHT freigegebener Tag bekommt seinen Wecker wie immer`() = runTest {
        val protokoll = mutableListOf<String>()
        val repo = RecordingAlarmRepository(emptyList(), protokoll)
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh))

        sut(repo, managerMit(protokoll), config, setOf(eventTag.plusDays(3)))
            .syncAlarms(listOf(futureEvent("ev1", eventTag)), config)

        assertEquals(1, repo.current.size)
    }

    // --- Backstop in scheduleSystemAlarm() ---

    @Test
    fun `scheduleSystemAlarm weist einen Wecker des freigegebenen Tages ab - und meldet es`() = runTest {
        // "Abgewiesen" darf fuer den Aufrufer nicht wie "armiert" aussehen: ein Erfolgs-Result
        // haette hier einen stummen Wecker MIT Anzeige erzeugt.
        val protokoll = mutableListOf<String>()
        val weckzeit = eventTag.atTime(5, 30).atZone(zone).toInstant().toEpochMilli()
        val wecker = AlarmInfo(
            id = 5, shiftId = "early", shiftName = "Frueh", triggerTime = weckzeit,
            formattedTime = "05:30", eventId = "ev5"
        )
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh))

        val ergebnis = sut(
            RecordingAlarmRepository(listOf(wecker), protokoll),
            managerMit(protokoll), config, setOf(eventTag)
        ).scheduleSystemAlarm(wecker)

        assertTrue(ergebnis.isFailure)
        assertTrue(
            "Eigener Typ, damit die Oberflaeche ihn von einem Ueberspringen unterscheiden kann",
            ergebnis.exceptionOrNull() is FreigegebenerTagNichtArmiertException
        )
    }

    @Test
    fun `scheduleSystemAlarm stellt einen MANUELLEN Wecker am freigegebenen Tag trotzdem`() = runTest {
        // Der Nutzer hat frei - und will fuer diesen Tag einen eigenen Wecker stellen (Zahnarzt,
        // Zug). Wuerde der Backstop ihn abweisen, waere der freigegebene Tag ein Tag, an dem sich
        // ueberhaupt kein Wecker mehr stellen laesst.
        val protokoll = mutableListOf<String>()
        val weckzeit = eventTag.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val manueller = AlarmInfo(
            id = 6, shiftId = "manual_6", shiftName = "Zahnarzt", triggerTime = weckzeit,
            formattedTime = "09:30"
        )
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh))

        val ergebnis = sut(
            RecordingAlarmRepository(listOf(manueller), protokoll),
            managerMit(protokoll), config, setOf(eventTag)
        ).scheduleSystemAlarm(manueller)

        assertTrue(ergebnis.isSuccess)
    }

    @Test
    fun `scheduleSystemAlarm stellt einen Wecker an einem freien anderen Tag normal`() = runTest {
        val protokoll = mutableListOf<String>()
        val weckzeit = eventTag.atTime(5, 30).atZone(zone).toInstant().toEpochMilli()
        val wecker = AlarmInfo(
            id = 5, shiftId = "early", shiftName = "Frueh", triggerTime = weckzeit,
            formattedTime = "05:30", eventId = "ev5"
        )
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh))

        val ergebnis = sut(
            RecordingAlarmRepository(listOf(wecker), protokoll),
            managerMit(protokoll), config, setOf(eventTag.minusDays(1))
        ).scheduleSystemAlarm(wecker)

        assertTrue(ergebnis.isSuccess)
    }
}
