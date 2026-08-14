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
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit-Tests fuer den Delta-Sync in [AlarmUseCase.syncAlarms].
 *
 * Genau hier lebten historisch die schwerwiegendsten Bugs der App: "alter Alarm klingelt
 * nach Event-Aenderung" und "bestehende Wecker verschwinden, sobald ein neues Event
 * auftaucht" (Audit P0-3). Diese Tests fixieren den Vertrag der Delta-Synchronisation:
 * - Event bleibt vorhanden  -> Alarm bleibt erhalten
 * - Event verschwindet      -> Alarm wird geloescht (Repository + System-Alarm)
 * - autoAlarm deaktiviert    -> bestehende Alarme werden per clearInternalAlarms() geloescht
 *                               (Repository + System-Alarm), kein neuer Alarm wird gesetzt
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
        override suspend fun isPersistenceBlocked(): Boolean = false
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
        notifier: FakeShiftChangeNotifier = FakeShiftChangeNotifier(),
        masterPaused: Boolean = false,
        spanStore: ShiftSpanStore = mock<ShiftSpanStore>()
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
            spanStore
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
    fun `autoAlarm deaktiviert - bestehende Alarme werden per clearInternalAlarms geloescht`() = runTest {
        val existing = existingAlarm(id = 1, eventId = "evB")
        val repo = FakeAlarmRepository(listOf(existing))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = false, definitions = listOf(earlyShift))

        val result = useCase(repo, manager, config)
            .syncAlarms(listOf(futureEvent("evB", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<AlarmInfo>(), result.getOrNull())
        // Deaktiviertes autoAlarm raeumt bestehende Alarme vollstaendig ab (clearInternalAlarms):
        // System-Alarm wird gecancelt UND das Repository geleert. Kein neuer Alarm wird gesetzt.
        assertEquals(emptyList<AlarmInfo>(), repo.current)
        verify(manager).cancelSystemAlarm(existing.id)
        verify(manager, never()).setAlarmFromShiftMatch(any(), any(), any())
    }

    @Test
    fun `Master-Pause aktiv - syncAlarms raeumt ab und erstellt trotz autoAlarmEnabled keine neuen Alarme`() = runTest {
        // Regression fuer einen real am Fairphone reproduzierten Bug: CalendarViewModel.
        // createAlarmsFromLoadedEvents() (ein von BootReceiver/AlarmMaintenanceService/
        // ShiftViewModel unabhaengiger fuenfter Aufrufer von syncAlarms) war beim ersten Bau
        // von Master-Pause nicht gegated und hat nach einem Reboot beim naechsten App-Start
        // trotz aktiver Pause wieder Alarme angelegt. Dieser Test haelt den zentralen Backstop
        // in syncAlarms() selbst fest, damit kuenftige Aufrufer (bekannt oder neu) nicht erneut
        // einzeln gegated werden muessen.
        val existing = existingAlarm(id = 1, eventId = "evB")
        val repo = FakeAlarmRepository(listOf(existing))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        val result = useCase(repo, manager, config, masterPaused = true)
            .syncAlarms(listOf(futureEvent("evB", "F", 1)), config)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<AlarmInfo>(), result.getOrNull())
        assertEquals(emptyList<AlarmInfo>(), repo.current)
        verify(manager).cancelSystemAlarm(existing.id)
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

    @Test
    fun `ShiftDefinition-Feld aendert sich ohne Event-Aenderung - Update wird trotzdem persistiert`() = runTest {
        // Regression fuer die urspruengliche "unchanged"-Bedingung in syncAlarms(), die nur
        // eventChecksum und triggerTime verglich: eine reine ShiftDefinition-Aenderung (hier
        // isSilent) OHNE Aenderung am zugrunde liegenden Kalender-Event (gleicher Titel/gleiche
        // Zeiten -> gleicher Checksum -> gleiche berechnete Weckzeit) wurde faelschlich als
        // "unveraendert" behandelt, und der alte (stale) Wert blieb bestehen. Dieser Test faellt
        // auf dem alten 2-Feld-Vergleich durch und haelt den vollen existingAlarm != newAlarm-
        // Vergleich fest.
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val event = futureEvent("evSilentToggle", "AD1", 9)

        // Erster Sync: Schicht noch normal (isSilent = false).
        val normalConfig = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(silentShift.copy(isSilent = false)))
        val firstResult = useCase(repo, manager, normalConfig).syncAlarms(listOf(event), normalConfig)
        val firstAlarm = firstResult.getOrNull()?.find { it.eventId == "evSilentToggle" }
        assertNotNull(firstAlarm)
        assertFalse("Vor dem Umschalten muss isSilent=false sein", firstAlarm!!.isSilent)

        // Zweiter Sync: DASSELBE Kalender-Event (identischer Titel/identische Zeiten -> identischer
        // Checksum und identische berechnete Weckzeit), aber die ShiftDefinition wurde inzwischen
        // auf isSilent=true umgestellt.
        val silentConfig = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(silentShift.copy(isSilent = true)))
        val secondResult = useCase(repo, manager, silentConfig).syncAlarms(listOf(event), silentConfig)
        val secondAlarm = secondResult.getOrNull()?.find { it.eventId == "evSilentToggle" }

        assertNotNull(secondAlarm)
        assertTrue(
            "Die aktualisierte isSilent=true-Einstellung darf NICHT als 'unveraendert' verworfen " +
                "werden - sonst bleibt der alte (nicht-stille) Alarm bestehen, obwohl die Schicht " +
                "inzwischen als still konfiguriert ist",
            secondAlarm!!.isSilent
        )
        assertTrue(
            "Repository muss ebenfalls den aktualisierten Wert zeigen",
            repo.current.find { it.eventId == "evSilentToggle" }!!.isSilent
        )
    }

    // --- scheduleSystemAlarm: Regression fuer v1.20.1 ("Deine Schicht beginnt um" zeigte die
    // Weckzeit statt des echten Schichtbeginns nach jedem Re-Arming). Die synthetische
    // CalendarEvent, die hier gebaut und via ShiftMatch an setAlarmFromShiftMatch() gereicht
    // wird, muss shiftStartTime/shiftEndTime bevorzugen und nur bei 0 (unbekannt) auf
    // triggerTime zurueckfallen. ---

    @Test
    fun `scheduleSystemAlarm - vorhandene shiftStartTime und shiftEndTime werden fuer die synthetische CalendarEvent verwendet, nicht triggerTime`() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val alarmInfo = existingAlarm(id = 1, eventId = "evX").copy(
            triggerTime = 1_000_000L,
            shiftStartTime = 2_000_000L,
            shiftEndTime = 3_000_000L
        )

        useCase(repo, manager, config).scheduleSystemAlarm(alarmInfo)

        val captor = argumentCaptor<ShiftMatch>()
        verify(manager).setAlarmFromShiftMatch(captor.capture(), any(), any())
        val calendarEvent = captor.firstValue.calendarEvent
        assertEquals(
            "startTime muss aus shiftStartTime stammen, nicht aus triggerTime",
            2_000_000L,
            calendarEvent.startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        assertEquals(
            "endTime muss aus shiftEndTime stammen, nicht aus triggerTime + DEFAULT_EVENT_DURATION_MS",
            3_000_000L,
            calendarEvent.endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
    }

    @Test
    fun `scheduleSystemAlarm - fehlende shiftStartTime und shiftEndTime fallen auf triggerTime zurueck (manueller Alarm ohne echte Schicht)`() = runTest {
        val repo = FakeAlarmRepository(emptyList())
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val alarmInfo = existingAlarm(id = 1, eventId = "evX").copy(
            triggerTime = 1_000_000L,
            shiftStartTime = 0L,
            shiftEndTime = 0L
        )

        useCase(repo, manager, config).scheduleSystemAlarm(alarmInfo)

        val captor = argumentCaptor<ShiftMatch>()
        verify(manager).setAlarmFromShiftMatch(captor.capture(), any(), any())
        val calendarEvent = captor.firstValue.calendarEvent
        assertEquals(
            "startTime muss bei shiftStartTime = 0 auf triggerTime zurueckfallen",
            1_000_000L,
            calendarEvent.startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        assertEquals(
            "endTime muss bei shiftEndTime = 0 auf triggerTime + DEFAULT_EVENT_DURATION_MS zurueckfallen",
            1_000_000L + CalendarConstants.DEFAULT_EVENT_DURATION_MS,
            calendarEvent.endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
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

    // --- Verstrichene Weckzeit ist KEINE entfernte Schicht (v1.25.2) ---

    /**
     * Ein Termin, der HEUTE laeuft, dessen Weckzeit aber schon vorbei ist - der Normalfall an
     * jedem Schichtmorgen, sobald der Wecker geklingelt hat.
     */
    private fun startedTodayEvent(id: String, title: String) = CalendarEvent(
        id = id,
        title = title,
        startTime = LocalDateTime.now().minusHours(2),
        endTime = LocalDateTime.now().plusHours(6),
        calendarId = "test"
    )

    @Test
    fun `Verstrichene Weckzeit meldet KEINE entfernte Schicht`() = runTest {
        // Realer Ablauf: der Wecker hat morgens geklingelt, die Schicht laeuft. Der naechste Sync
        // raeumt den abgelaufenen Alarm - das ist richtig, denn ein vergangener Alarm gehoert nicht
        // in den Bestand. Er darf daraus aber KEINE "Schicht entfernt"-Meldung machen: der Nutzer
        // bekam die bis v1.25.1 an jedem Schichtmorgen fuer den Dienst, den er gerade antrat.
        val ev = startedTodayEvent("evHeute", "F")
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = ev.id.hashCode(), eventId = "evHeute")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val notifier = FakeShiftChangeNotifier()

        useCase(repo, manager, config, notifier = notifier).syncAlarms(listOf(ev), config)

        assertEquals(
            "Der Termin laeuft weiter - nur die Weckzeit ist vorbei. Das ist keine Aenderung " +
                "des Dienstplans und darf nicht gemeldet werden.",
            0,
            notifier.deletedCount
        )
        assertTrue("Der abgelaufene Alarm gehoert trotzdem nicht mehr in den Bestand", repo.current.isEmpty())
    }

    @Test
    fun `Die Schichtspanne ueberlebt die verstrichene Weckzeit`() = runTest {
        // Der eigentliche Zweck des Fixes: Dimmer und "Nicht stoeren" beziehen ihre Dienstzeit-
        // Fenster aus den Spannen. Verschwaende die Spanne zusammen mit dem Alarm, waere DND
        // mitten in der Dienstzeit aus - am Emulator so gemessen (20.08. 08:00, zen_mode=0).
        val ev = startedTodayEvent("evHeute", "F")
        val repo = FakeAlarmRepository(listOf(existingAlarm(id = ev.id.hashCode(), eventId = "evHeute")))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val spanStore = mock<ShiftSpanStore>()

        useCase(repo, manager, config, spanStore = spanStore).syncAlarms(listOf(ev), config)

        val captor = argumentCaptor<List<ShiftSpan>>()
        verify(spanStore).replaceAll(captor.capture(), any())
        assertEquals(
            "Die laufende Schicht muss als Spanne erhalten bleiben, obwohl ihr Alarm geraeumt wurde",
            listOf("Frueh"),
            captor.firstValue.map { it.shiftName }
        )
    }

    @Test
    fun `Ein wirklich entferntes Event meldet weiterhin - Regressionswaechter`() = runTest {
        // Gegenprobe zum Test darueber: die echte Loeschmeldung darf dabei nicht miterschlagen
        // werden. Sie ist der Grund, warum es das Feature ueberhaupt gibt.
        val evA = futureEvent("evA", "F", 1)
        val repo = FakeAlarmRepository(
            listOf(
                existingAlarm(id = evA.id.hashCode(), eventId = "evA"),
                existingAlarm(id = 4242, eventId = "evWeg")
            )
        )
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))
        val notifier = FakeShiftChangeNotifier()

        useCase(repo, manager, config, notifier = notifier).syncAlarms(listOf(evA), config)

        assertEquals(1, notifier.deletedCount)
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
    // --- Manuelle Alarme in den datengetriebenen Raeumzweigen ---

    /**
     * DER MANUELLE ALARM UEBERLEBT "keine passende Schicht".
     *
     * Der Delta-Sync schont manuelle Alarme (leere eventId) ausdruecklich - die Loeschschleife
     * prueft `eventId.isNotEmpty()`. Die beiden Abkuerzungs-Zweige davor umgingen diese Zusicherung
     * komplett: sie riefen `clearInternalAlarms()` ohne jede Unterscheidung. Ausgerechnet der
     * manuelle Alarm ist der EINZIGE, der sich nicht aus dem Kalender rekonstruieren laesst - er kam
     * nie wieder, und im Log stand "No matching shifts found - clearing all alarms", was wie
     * Normalbetrieb klingt.
     *
     * Szenario: Urlaubswoche. Im Kalender stehen Termine, aber keiner trifft ein Schichtmuster;
     * der Nutzer hat sich fuer Mittwoch 06:00 einen Wecker fuer einen Arzttermin gestellt.
     */
    @Test
    fun `manueller Alarm bleibt erhalten wenn keine Schicht passt`() = runTest {
        val manual = existingAlarm(id = 77, eventId = "")
        val calendarBased = existingAlarm(id = 78, eventId = "evA")
        val repo = FakeAlarmRepository(listOf(manual, calendarBased))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        // Ein Event, das KEIN Schichtmuster trifft
        val result = useCase(repo, manager, config).syncAlarms(
            listOf(
                CalendarEvent(
                    id = "evUrlaub",
                    title = "Zahnarzt",
                    startTime = LocalDateTime.now().plusDays(2).withHour(9),
                    endTime = LocalDateTime.now().plusDays(2).withHour(10),
                    calendarId = "cal"
                )
            ),
            config
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "der manuelle Alarm muss im Repository bleiben",
            listOf(77),
            repo.current.map { it.id }
        )
        verify(manager).cancelSystemAlarm(78)
        verify(manager, never()).cancelSystemAlarm(77)
        assertEquals(
            "der Rueckgabewert muss den verbliebenen Bestand nennen, nicht leer sein",
            listOf(77),
            result.getOrThrow().map { it.id }
        )
    }

    /** Dasselbe fuer den zweiten Abkuerzungs-Zweig: Kalender liefert gar keine Events. */
    @Test
    fun `manueller Alarm bleibt erhalten wenn der Kalender keine Events liefert`() = runTest {
        val manual = existingAlarm(id = 77, eventId = "")
        val calendarBased = existingAlarm(id = 78, eventId = "evA")
        val repo = FakeAlarmRepository(listOf(manual, calendarBased))
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(earlyShift))

        val result = useCase(repo, manager, config).syncAlarms(emptyList(), config)

        assertEquals(listOf(77), repo.current.map { it.id })
        verify(manager, never()).cancelSystemAlarm(77)
        assertEquals(listOf(77), result.getOrThrow().map { it.id })
    }

    /**
     * GEGENPROBE: bei einer AUSDRUECKLICHEN Abschaltung ("Automatische Alarme aus") wird weiterhin
     * ALLES geraeumt - dort will der Nutzer Stille, und der Direct-Boot-Spiegel muss wirklich leer
     * werden. Die Schonung gilt nur fuer die datengetriebenen Zweige.
     */
    @Test
    fun `Automatische Alarme aus raeumt auch manuelle Alarme`() = runTest {
        val repo = FakeAlarmRepository(
            listOf(existingAlarm(id = 77, eventId = ""), existingAlarm(id = 78, eventId = "evA"))
        )
        val manager = mockManager()
        val config = ShiftConfig(autoAlarmEnabled = false, definitions = listOf(earlyShift))

        useCase(repo, manager, config).syncAlarms(emptyList(), config)

        assertTrue("bei ausdruecklicher Abschaltung bleibt nichts", repo.current.isEmpty())
        verify(manager).cancelSystemAlarm(77)
    }

}
