package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeKalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.KrankmeldungNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.MANUELLER_ALARM_SUFFIX
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
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
import java.time.ZoneId

/**
 * Umbau E2 „Wecker an/aus“ am Schichttyp (#168): ein Typ mit `weckerAn = false` wird weiter
 * ERKANNT - seine Schichtspanne bleibt fuer Dimmer, „Nicht stoeren“ und die Ruf-Abfrage -, stellt
 * aber keinen Wecker. Das ist bewusst NICHT [ShiftDefinition.isEnabled]: `isEnabled = false`
 * schaltet die Erkennung selbst ab, und die Spanne waere weg.
 *
 * Schaltet man den Wecker aus, verschwinden die gestellten Wecker ueber denselben Weg wie heute
 * (erst `cancelSystemAlarm()`, dann `deleteAlarm()`), aber OHNE „Schicht entfernt“: die Schicht
 * steht ja noch im Kalender. Manuelle Wecker bleiben, sie sind ausdruecklich gestellt.
 *
 * Getestet gegen die ECHTE [ShiftRecognitionEngine] und den ECHTEN [ShiftSpanStore]; nur der
 * Android-gebundene [AlarmManagerService] ist gemockt. Die Engine liest ihre eigene Konfiguration,
 * nicht den `shiftConfig`-Parameter von `syncAlarms()` - deshalb bekommt jeder Schaltzustand einen
 * eigenen UseCase ueber DENSELBEN Bestand.
 */
class WeckerAusAmTypSyncTest {

    // --- Fakes ---

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
    }

    /** [protokoll] haelt die Reihenfolge fest - fuer „erst cancelSystemAlarm, dann deleteAlarm“. */
    private class FakeAlarmRepository(
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
    }

    private class FakeSkipUseCase : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("not used in these tests"))

        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)

        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(AlarmSkipState())

        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)

        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
    }

    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        var deletedCount = 0

        override suspend fun notifyCreated(new: AlarmInfo) = Unit

        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) = Unit

        override suspend fun notifyDeleted(old: AlarmInfo) {
            deletedCount++
        }
    }

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    // --- Fixtures ---

    private val frueh = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    private val spaet = ShiftDefinition(
        id = "late",
        name = "Spaet",
        keywords = listOf("S"),
        alarmTime = LocalTime.of(12, 0)
    )

    private fun konfig(fruehWeckerAn: Boolean) = ShiftConfig(
        autoAlarmEnabled = true,
        definitions = listOf(frueh.copy(weckerAn = fruehWeckerAn), spaet)
    )

    /** Weit in der Zukunft, damit die berechnete Weckzeit garantiert > now ist. */
    private fun event(id: String, title: String, day: Int, startStunde: Int = 6) = CalendarEvent(
        id = id,
        title = title,
        startTime = LocalDateTime.of(2035, 6, day, startStunde, 0),
        endTime = LocalDateTime.of(2035, 6, day, startStunde + 8, 0),
        calendarId = "test"
    )

    private fun millis(zeit: LocalDateTime): Long =
        zeit.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun fruehWeckzeit(day: Int): Long = millis(LocalDateTime.of(2035, 6, day, 5, 30))

    private fun mockManager(protokoll: MutableList<String>): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(m.setAlarmFromShiftMatch(any(), any(), any())).thenAnswer { aufruf ->
            protokoll += "arm:${aufruf.arguments[2]}"
            status
        }
        whenever(m.cancelSystemAlarm(any())).thenAnswer { aufruf ->
            protokoll += "cancel:${aufruf.arguments[0]}"
            status
        }
        return m
    }

    private fun useCase(
        config: ShiftConfig,
        repo: FakeAlarmRepository,
        manager: AlarmManagerService,
        notifier: FakeShiftChangeNotifier,
        spanStore: ShiftSpanStore
    ): AlarmUseCase = AlarmUseCase(
        repo,
        manager,
        FakeShiftConfigRepository(config),
        ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
        FakeSkipUseCase(),
        notifier,
        mock<MasterPausePrefs>().also {
            kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(false) }
        },
        spanStore,
        FakeSyncHorizonStore(),
        FakeFeedNeueinlesenStore(),
        keineFreienTage(),
        FakeKalenderVorausschauPrefs(FakeKalenderVorausschauPrefs.UEBER_ALLE_TESTDATEN_TAGE),
        dagger.Lazy { mock<ZeitkettenArmierer>() },
        mock<KrankmeldungNotifier>()
    )

    private suspend fun spannen(store: ShiftSpanStore): List<ShiftSpan> =
        store.letzterStand().getOrThrow()?.spans.orEmpty()

    // --- Tests ---

    @Test
    fun `Typ mit Wecker aus - keine AlarmInfo, aber die Schichtspanne bleibt`() = runTest {
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(emptyList(), protokoll)
        val notifier = FakeShiftChangeNotifier()
        val spanStore = ShiftSpanStore(FakePreferencesDataStore())
        val events = listOf(event("f-1", "F", 1), event("s-2", "S", 2, startStunde = 13))

        val ergebnis = useCase(konfig(fruehWeckerAn = false), repo, mockManager(protokoll), notifier, spanStore)
            .syncAlarms(events, konfig(fruehWeckerAn = false), null)

        assertTrue("Sync muss gelingen: $ergebnis", ergebnis.isSuccess)
        // Nur der Typ mit Wecker an stellt einen Wecker - der Filter wirkt je Typ.
        assertEquals(listOf("Spaet"), repo.current.map { it.shiftName })
        assertEquals(listOf("s-2"), repo.current.map { it.eventId })
        assertTrue("Fuer Frueh darf nichts gestellt werden: $protokoll",
            protokoll.none { it == "arm:${"f-1".hashCode()}" || it == "save:${"f-1".hashCode()}" })
        // ...aber beide Schichten sind erkannt und als Spanne da (Dimmer, DND, Ruf-Abfrage).
        val spans = spannen(spanStore)
        assertEquals(listOf("Frueh", "Spaet"), spans.map { it.shiftName }.sorted())
        val fruehSpanne = spans.single { it.shiftName == "Frueh" }
        assertEquals(millis(LocalDateTime.of(2035, 6, 1, 6, 0)), fruehSpanne.startTime)
        assertEquals(millis(LocalDateTime.of(2035, 6, 1, 14, 0)), fruehSpanne.endTime)
        assertEquals(0, notifier.deletedCount)
    }

    @Test
    fun `Wecker aus raeumt den gestellten Wecker ab ohne Entfernt-Hinweis, Wecker an stellt ihn wieder`() = runTest {
        val protokoll = mutableListOf<String>()
        val manuell = AlarmInfo(
            id = 777,
            shiftId = frueh.id,
            shiftName = frueh.name + MANUELLER_ALARM_SUFFIX,
            triggerTime = fruehWeckzeit(3),
            formattedTime = "manuell"
        )
        val repo = FakeAlarmRepository(listOf(manuell), protokoll)
        val notifier = FakeShiftChangeNotifier()
        val spanStore = ShiftSpanStore(FakePreferencesDataStore())
        val manager = mockManager(protokoll)
        val events = listOf(event("f-1", "F", 1))
        val alarmId = "f-1".hashCode()

        // 1. Wecker an: der Wecker steht.
        useCase(konfig(fruehWeckerAn = true), repo, manager, notifier, spanStore)
            .syncAlarms(events, konfig(fruehWeckerAn = true), null).getOrThrow()
        assertEquals(setOf(alarmId, manuell.id), repo.current.map { it.id }.toSet())

        // 2. Wecker aus: abgeraeumt - erst Cancel, dann Delete -, ohne „Schicht entfernt“.
        protokoll.clear()
        useCase(konfig(fruehWeckerAn = false), repo, manager, notifier, spanStore)
            .syncAlarms(events, konfig(fruehWeckerAn = false), null).getOrThrow()
        assertEquals("Nur der manuelle Wecker bleibt", listOf(manuell.id), repo.current.map { it.id })
        val cancel = protokoll.indexOf("cancel:$alarmId")
        val delete = protokoll.indexOf("delete:$alarmId")
        assertTrue("Cancel UND Delete erwartet: $protokoll", cancel >= 0 && delete >= 0)
        assertTrue("Erst cancelSystemAlarm, dann deleteAlarm: $protokoll", cancel < delete)
        assertTrue("Der manuelle Wecker wird nicht angefasst: $protokoll",
            protokoll.none { it.endsWith(":${manuell.id}") })
        assertEquals("Kein „Schicht entfernt“ - der Termin steht noch im Kalender", 0, notifier.deletedCount)
        assertEquals("Die Schichtspanne bleibt", listOf("Frueh"), spannen(spanStore).map { it.shiftName })

        // 3. Wecker wieder an: der Wecker wird neu gestellt.
        protokoll.clear()
        useCase(konfig(fruehWeckerAn = true), repo, manager, notifier, spanStore)
            .syncAlarms(events, konfig(fruehWeckerAn = true), null).getOrThrow()
        val wieder = repo.current.single { it.eventId == "f-1" }
        assertEquals(fruehWeckzeit(1), wieder.triggerTime)
        assertTrue("Der Systemwecker wird wieder gestellt: $protokoll", "arm:${wieder.id}" in protokoll)
        assertEquals(0, notifier.deletedCount)
    }

    @Test
    fun `Wecker aus bei gleichzeitig neuer Kalender-Kennung - abgeraeumt, trotzdem kein Entfernt-Hinweis`() = runTest {
        // Der Feed vergibt beim Neueinlesen allen Terminen neue Kennungen. Faellt das auf den Lauf,
        // der den Wecker ausschaltet, paart erst Runde (b) (Weckzeit + Schicht) den alten Wecker -
        // ohne diese Paarung galte er als entfernte Schicht.
        val protokoll = mutableListOf<String>()
        val alt = AlarmInfo(
            id = 4711,
            shiftId = frueh.id,
            shiftName = frueh.name,
            triggerTime = fruehWeckzeit(1),
            formattedTime = "alt",
            eventId = "alt-1",
            shiftStartTime = millis(LocalDateTime.of(2035, 6, 1, 6, 0)),
            shiftEndTime = millis(LocalDateTime.of(2035, 6, 1, 14, 0))
        )
        val repo = FakeAlarmRepository(listOf(alt), protokoll)
        val notifier = FakeShiftChangeNotifier()

        useCase(konfig(fruehWeckerAn = false), repo, mockManager(protokoll), notifier,
            ShiftSpanStore(FakePreferencesDataStore()))
            .syncAlarms(listOf(event("neu-1", "F", 1)), konfig(fruehWeckerAn = false), null).getOrThrow()

        assertTrue("Abgeraeumt: ${repo.current}", repo.current.isEmpty())
        assertTrue("Erst Cancel, dann Delete: $protokoll",
            protokoll.indexOf("cancel:4711") in 0 until protokoll.indexOf("delete:4711"))
        assertEquals(0, notifier.deletedCount)
    }
}
