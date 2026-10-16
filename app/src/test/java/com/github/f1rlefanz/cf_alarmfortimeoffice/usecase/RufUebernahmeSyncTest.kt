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
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Umbau E3, Sonderfall Rufbereitschaft (#172): steht am selben Tag wie eine Rufbereitschaft ein
 * Dienst im Kalender (ein nachgetragener Einsatz), gilt fuer den Tag der Wecker des Dienstes - der
 * Ruf-Wecker entfaellt. Die Ruf-SPANNE bleibt, denn aus ihr lesen die stuendliche Ruf-Abfrage und
 * das DND-Ende 05:00.
 *
 * Ein schon gestellter Ruf-Wecker wird ueber den vorhandenen Weg abgeraeumt (erst
 * `cancelSystemAlarm()`, dann `deleteAlarm()`), OHNE „Schicht entfernt“: der Ruftermin steht ja
 * noch im Kalender. Faellt der Einsatz weg, kommt der Ruf-Wecker zurueck.
 *
 * Geruest wie [WeckerAusAmTypSyncTest]: echte [ShiftRecognitionEngine], echter [ShiftSpanStore],
 * nur der Android-gebundene [AlarmManagerService] ist gemockt.
 */
class RufUebernahmeSyncTest {

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

    /** Haelt die Namen fest, fuer die „Schicht entfernt“ gemeldet wurde. */
    private class FakeShiftChangeNotifier : ShiftChangeNotifier(mock(), mock()) {
        val geloeschtGemeldet = mutableListOf<String>()

        override suspend fun notifyCreated(new: AlarmInfo) = Unit

        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) = Unit

        override suspend fun notifyDeleted(old: AlarmInfo) {
            geloeschtGemeldet += old.shiftName
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

    /** Rufbereitschaft AD1, Weckzeit 06:00. */
    private val ruf = ShiftDefinition(
        id = "oncall",
        name = "Ruf",
        keywords = listOf("AD1"),
        alarmTime = LocalTime.of(6, 0)
    ).mitKategorie(ShiftKategorie.RUFBEREITSCHAFT)

    /** Der Einsatz: Fruehdienst F, Weckzeit 05:30. */
    private val frueh = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    private val konfig = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(ruf, frueh))

    /** Ganztaegiger Ruftermin wie aus dem Repository (00:00 bis 23:59), weit in der Zukunft. */
    private fun rufTermin(id: String, day: Int) = CalendarEvent(
        id = id,
        title = "AD1",
        startTime = LocalDateTime.of(2035, 6, day, 0, 0),
        endTime = LocalDateTime.of(2035, 6, day, 23, 59),
        calendarId = "test",
        isAllDay = true
    )

    private fun fruehTermin(id: String, day: Int) = CalendarEvent(
        id = id,
        title = "F",
        startTime = LocalDateTime.of(2035, 6, day, 6, 0),
        endTime = LocalDateTime.of(2035, 6, day, 14, 0),
        calendarId = "test"
    )

    private fun millis(zeit: LocalDateTime): Long =
        zeit.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun fruehWeckzeit(day: Int): Long = millis(LocalDateTime.of(2035, 6, day, 5, 30))

    private fun rufWeckzeit(day: Int): Long = millis(LocalDateTime.of(2035, 6, day, 6, 0))

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

    private suspend fun sync(
        events: List<CalendarEvent>,
        repo: FakeAlarmRepository,
        manager: AlarmManagerService,
        notifier: FakeShiftChangeNotifier,
        spanStore: ShiftSpanStore
    ): List<AlarmInfo> = AlarmUseCase(
        repo,
        manager,
        FakeShiftConfigRepository(konfig),
        ShiftRecognitionEngine(FakeShiftConfigRepository(konfig)),
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
    ).syncAlarms(events, konfig, null).getOrThrow()

    private suspend fun spannen(store: ShiftSpanStore): List<ShiftSpan> =
        store.letzterStand().getOrThrow()?.spans.orEmpty()

    // --- Tests ---

    @Test
    fun `Ruf 06_00 und F 05_30 am selben Tag - nur der F-Wecker, die Ruf-Spanne ist da`() = runTest {
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(emptyList(), protokoll)
        val notifier = FakeShiftChangeNotifier()
        val spanStore = ShiftSpanStore(FakePreferencesDataStore())
        // Tag 1: Ruf + Einsatz F. Tag 2: nur Ruf - dort bleibt der Ruf-Wecker.
        val events = listOf(rufTermin("ruf-1", 1), fruehTermin("f-1", 1), rufTermin("ruf-2", 2))

        sync(events, repo, mockManager(protokoll), notifier, spanStore)

        assertEquals(
            "Tag 1 weckt der Dienst, Tag 2 die Rufbereitschaft",
            setOf("f-1" to fruehWeckzeit(1), "ruf-2" to rufWeckzeit(2)),
            repo.current.map { it.eventId to it.triggerTime }.toSet()
        )
        assertTrue("Fuer den uebernommenen Ruf darf nichts gestellt werden: $protokoll",
            protokoll.none { it == "arm:${"ruf-1".hashCode()}" || it == "save:${"ruf-1".hashCode()}" })
        // Die Ruf-Spanne von Tag 1 bleibt - Ruf-Abfrage und DND-Ende lesen aus ihr.
        val spans = spannen(spanStore)
        val rufSpanne1 = spans.singleOrNull {
            it.shiftName == "Ruf" && it.startTime == millis(LocalDateTime.of(2035, 6, 1, 0, 0))
        }
        assertNotNull("Ruf-Spanne von Tag 1 fehlt: $spans", rufSpanne1)
        assertEquals(rufWeckzeit(1), rufSpanne1!!.alarmTriggerTime)
        assertEquals(listOf("Frueh", "Ruf", "Ruf"), spans.map { it.shiftName }.sorted())
        assertEquals(emptyList<String>(), notifier.geloeschtGemeldet)
    }

    @Test
    fun `nachgetragener Einsatz raeumt den Ruf-Wecker ohne Entfernt-Hinweis ab, gestrichen kommt er zurueck`() = runTest {
        val protokoll = mutableListOf<String>()
        val repo = FakeAlarmRepository(emptyList(), protokoll)
        val notifier = FakeShiftChangeNotifier()
        val spanStore = ShiftSpanStore(FakePreferencesDataStore())
        val manager = mockManager(protokoll)
        val rufId = "ruf-1".hashCode()

        // 1. Nur die Rufbereitschaft: ihr Wecker steht.
        sync(listOf(rufTermin("ruf-1", 1)), repo, manager, notifier, spanStore)
        assertEquals(listOf(rufId), repo.current.map { it.id })

        // 2. Der Chef traegt einen Einsatz F ein: Ruf-Wecker weg (erst Cancel, dann Delete), F steht.
        protokoll.clear()
        sync(listOf(rufTermin("ruf-1", 1), fruehTermin("f-1", 1)), repo, manager, notifier, spanStore)
        assertEquals(listOf("f-1"), repo.current.map { it.eventId })
        val cancel = protokoll.indexOf("cancel:$rufId")
        val delete = protokoll.indexOf("delete:$rufId")
        assertTrue("Cancel UND Delete erwartet: $protokoll", cancel >= 0 && delete >= 0)
        assertTrue("Erst cancelSystemAlarm, dann deleteAlarm: $protokoll", cancel < delete)
        assertEquals("Kein „Schicht entfernt“ - der Ruftermin steht noch im Kalender",
            emptyList<String>(), notifier.geloeschtGemeldet)
        assertTrue("Die Ruf-Spanne bleibt", spannen(spanStore).any { it.shiftName == "Ruf" })

        // 3. Der Einsatz wird gestrichen: der Ruf-Wecker ist wieder da. Den F-Wecker meldet der Sync
        // zu Recht als entfernt - der Dienst IST aus dem Kalender verschwunden.
        protokoll.clear()
        sync(listOf(rufTermin("ruf-1", 1)), repo, manager, notifier, spanStore)
        val wieder = repo.current.single()
        assertEquals("ruf-1", wieder.eventId)
        assertEquals(rufWeckzeit(1), wieder.triggerTime)
        assertTrue("Der Systemwecker wird wieder gestellt: $protokoll", "arm:${wieder.id}" in protokoll)
        assertEquals(listOf("Frueh"), notifier.geloeschtGemeldet)
    }

    @Test
    fun `Einsatz bei gleichzeitig neuer Kalender-Kennung - Ruf-Wecker abgeraeumt, kein Entfernt-Hinweis`() = runTest {
        // Liest der Feed beim Eintragen des Einsatzes alles neu ein, hat der Ruftermin eine neue
        // Kennung. Erst Paarungsrunde (b) (Weckzeit + Schicht) findet den alten Ruf-Wecker - ohne
        // sie galte er als entfernte Schicht.
        val protokoll = mutableListOf<String>()
        val alt = AlarmInfo(
            id = 4711,
            shiftId = ruf.id,
            shiftName = ruf.name,
            triggerTime = rufWeckzeit(1),
            formattedTime = "alt",
            eventId = "alt-ruf-1",
            shiftStartTime = millis(LocalDateTime.of(2035, 6, 1, 0, 0)),
            shiftEndTime = millis(LocalDateTime.of(2035, 6, 1, 23, 59))
        )
        val repo = FakeAlarmRepository(listOf(alt), protokoll)
        val notifier = FakeShiftChangeNotifier()

        sync(
            listOf(rufTermin("neu-ruf-1", 1), fruehTermin("neu-f-1", 1)),
            repo, mockManager(protokoll), notifier, ShiftSpanStore(FakePreferencesDataStore())
        )

        assertEquals(listOf("neu-f-1"), repo.current.map { it.eventId })
        assertTrue("Erst Cancel, dann Delete: $protokoll",
            protokoll.indexOf("cancel:4711") in 0 until protokoll.indexOf("delete:4711"))
        assertEquals(emptyList<String>(), notifier.geloeschtGemeldet)
    }
}
