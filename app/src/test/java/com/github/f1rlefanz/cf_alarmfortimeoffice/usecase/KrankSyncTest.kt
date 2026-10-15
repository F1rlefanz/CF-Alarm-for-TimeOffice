package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeKalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.KrankmeldungNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.freieTageStoreImSpeicher
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * #142 Teil B: Krankmeldung aus dem Kalender, so wie `AlarmUseCase.syncAlarms()` sie umsetzt.
 *
 * Mit einem ECHTEN [FreieTageStore] im Arbeitsspeicher ([freieTageStoreImSpeicher]): die
 * Mischsemantik des Kalender-Schluessels (vergangen behalten, ab heute ersetzen, hinter dem
 * Horizont behalten) ist genau das, was hier geprueft wird - ein Mock wuerde sie wegstubben.
 *
 * Die Tage liegen 2035, damit jede berechnete Weckzeit sicher in der Zukunft liegt.
 */
class KrankSyncTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
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

        /** #142: laesst das naechste Loeschen scheitern - Schritt 1 wirft dann den ganzen Sync. */
        var deleteSchlaegtFehl = false

        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            if (deleteSchlaegtFehl) {
                deleteSchlaegtFehl = false
                return Result.failure(IllegalStateException("Testfehler beim Loeschen"))
            }
            protokoll += "delete:$alarmId"
            state.value = state.value.filterNot { it.id == alarmId }
            return Result.success(Unit)
        }

        override suspend fun deleteAllAlarms(): Result<Unit> {
            state.value = emptyList()
            return Result.success(Unit)
        }
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
        val angelegtGemeldet = mutableListOf<AlarmInfo>()
        override suspend fun notifyCreated(new: AlarmInfo) {
            angelegtGemeldet += new
        }
        override suspend fun notifyUpdated(old: AlarmInfo, new: AlarmInfo) = Unit
        override suspend fun notifyDeleted(old: AlarmInfo) {
            geloeschtGemeldet++
        }
    }

    /**
     * Zeichnet die Meldungen auf, statt eine Benachrichtigung zu bauen. [zustellbar] = false spielt
     * blockierte Benachrichtigungen nach (der Versuch wird trotzdem aufgezeichnet).
     */
    private class FakeKrankmeldungNotifier : KrankmeldungNotifier(mock()) {
        val meldungen = mutableListOf<Pair<List<LocalDate>, List<LocalTime>>>()
        var zustellbar = true
        override fun melde(tage: List<LocalDate>, weckzeiten: List<LocalTime>): Boolean {
            meldungen += tage to weckzeiten
            return zustellbar
        }
    }

    private val frueh = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    private val nacht = ShiftDefinition(
        id = "night",
        name = "Nacht",
        keywords = listOf("N"),
        alarmTime = LocalTime.of(23, 30)
    )

    private val krank = ShiftDefinition(
        id = "sick",
        name = "Krank",
        keywords = listOf("K"),
        alarmTime = LocalTime.MIDNIGHT,
        isSickDay = true
    )

    private val config = ShiftConfig(autoAlarmEnabled = true, definitions = listOf(frueh, nacht, krank))

    private val tag: LocalDate = LocalDate.of(2035, 6, 12)

    private fun schicht(id: String, datum: LocalDate) = CalendarEvent(
        id = id,
        title = "F",
        startTime = LocalDateTime.of(datum, LocalTime.of(6, 0)),
        endTime = LocalDateTime.of(datum, LocalTime.of(14, 0)),
        calendarId = "test"
    )

    /** Ganztaegig: der Konverter laesst den Termin um 23:59 des letzten Tages enden. */
    private fun krankGanztaegig(id: String, von: LocalDate, bis: LocalDate = von) = CalendarEvent(
        id = id,
        title = "K",
        startTime = von.atStartOfDay(),
        endTime = LocalDateTime.of(bis, LocalTime.of(23, 59)),
        calendarId = "test",
        isAllDay = true
    )

    private fun millis(datum: LocalDate, uhrzeit: LocalTime): Long =
        LocalDateTime.of(datum, uhrzeit).atZone(zone).toInstant().toEpochMilli()

    private class Aufbau(
        val sut: AlarmUseCase,
        val repo: RecordingAlarmRepository,
        val manager: AlarmManagerService,
        val store: FreieTageStore,
        val armierer: ZeitkettenArmierer,
        val krankNotifier: FakeKrankmeldungNotifier,
        val schichtNotifier: FakeShiftChangeNotifier,
        val spanStore: ShiftSpanStore,
        val protokoll: MutableList<String>
    )

    private fun aufbau(
        bestand: List<AlarmInfo> = emptyList(),
        hand: Set<LocalDate> = emptySet(),
        kalender: Set<LocalDate> = emptySet(),
        pausiert: Boolean = false,
        horizont: FakeSyncHorizonStore = FakeSyncHorizonStore()
    ): Aufbau {
        val protokoll = mutableListOf<String>()
        val repo = RecordingAlarmRepository(bestand, protokoll)
        val manager = managerMit(protokoll)
        val store = freieTageStoreImSpeicher(hand = hand, kalender = kalender)
        val armierer = mock<ZeitkettenArmierer>()
        val krankNotifier = FakeKrankmeldungNotifier()
        val schichtNotifier = FakeShiftChangeNotifier()
        val spanStore = mock<ShiftSpanStore>()
        val sut = AlarmUseCase(
            repo,
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            FakeSkipUseCase(),
            schichtNotifier,
            mock<MasterPausePrefs>().also {
                kotlinx.coroutines.runBlocking { whenever(it.pausedNow()).thenReturn(pausiert) }
            },
            spanStore,
            horizont,
            FakeFeedNeueinlesenStore(),
            store,
            FakeKalenderVorausschauPrefs(FakeKalenderVorausschauPrefs.UEBER_ALLE_TESTDATEN_TAGE),
            dagger.Lazy { armierer },
            krankNotifier
        )
        return Aufbau(sut, repo, manager, store, armierer, krankNotifier, schichtNotifier, spanStore, protokoll)
    }

    private fun managerMit(protokoll: MutableList<String>): AlarmManagerService {
        val manager = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(manager.setAlarmFromShiftMatch(any(), any(), any())).thenAnswer { aufruf ->
            protokoll += "arm:${aufruf.arguments[2]}"
            status
        }
        whenever(manager.cancelSystemAlarm(any())).thenAnswer { aufruf ->
            protokoll += "cancel:${aufruf.arguments[0]}"
            status
        }
        return manager
    }

    // --- Gate: Krank-Tag erzeugt keinen Wecker ---

    @Test
    fun `Krank-Termin neben der Schicht - kein Wecker wird gestellt`() = runTest {
        val a = aufbau()

        val ergebnis = a.sut.syncAlarms(listOf(schicht("ev1", tag), krankGanztaegig("k1", tag)), config)

        assertTrue(ergebnis.isSuccess)
        assertTrue("Kein Wecker gespeichert (Protokoll: ${a.protokoll})", a.repo.current.isEmpty())
        verify(a.manager, never()).setAlarmFromShiftMatch(any(), any(), any())
        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        assertTrue("Die Hand-Freigaben gehoeren dem Nutzer", a.store.freigabeQuellenNow().hand.isEmpty())
        // Der Krank-Termin ist KEINE Schicht - er darf keine Dienstzeit-Spanne erzeugen.
        val spannen = argumentCaptor<List<ShiftSpan>>()
        verify(a.spanStore).replaceAll(spannen.capture(), any())
        assertTrue(spannen.firstValue.none { it.shiftName == krank.name })
    }

    @Test
    fun `Bestehender Wecker eines neuen Krank-Tages wird gecancelt, dann geloescht und gemeldet`() = runTest {
        val a = aufbau()
        a.sut.syncAlarms(listOf(schicht("ev1", tag)), config)
        val wecker = a.repo.current.single()
        a.protokoll.clear()

        val ergebnis = a.sut.syncAlarms(listOf(schicht("ev1", tag), krankGanztaegig("k1", tag)), config)

        assertTrue(ergebnis.isSuccess)
        assertTrue(a.repo.current.isEmpty())
        val cancel = a.protokoll.indexOf("cancel:${wecker.id}")
        val delete = a.protokoll.indexOf("delete:${wecker.id}")
        assertTrue("Erst cancel, dann delete (Protokoll: ${a.protokoll})", cancel in 0 until delete)
        assertEquals(listOf(listOf(tag) to listOf(LocalTime.of(5, 30))), a.krankNotifier.meldungen)
        assertEquals("Eine Krankmeldung ist keine entfernte Schicht", 0, a.schichtNotifier.geloeschtGemeldet)
    }

    @Test
    fun `TimeOffice ersetzt die Schicht durch K - keine Meldung Schicht entfernt`() = runTest {
        val a = aufbau()
        val anderer = tag.plusDays(2)
        a.sut.syncAlarms(listOf(schicht("ev1", tag), schicht("ev2", anderer)), config)
        val weckerAmKrankTag = a.repo.current.single { it.eventId == "ev1" }
        a.protokoll.clear()

        // Der Schichttermin verschwindet, an seiner Stelle steht "K". Eine andere Schicht bleibt,
        // damit der Sync durch den Delta-Pfad (Schritt 1) laeuft und nicht durch "keine Treffer".
        a.sut.syncAlarms(listOf(krankGanztaegig("k1", tag), schicht("ev2", anderer)), config)

        assertEquals(0, a.schichtNotifier.geloeschtGemeldet)
        assertTrue(a.repo.current.none { it.id == weckerAmKrankTag.id })
        assertTrue(a.repo.current.any { it.eventId == "ev2" })
        val cancel = a.protokoll.indexOf("cancel:${weckerAmKrankTag.id}")
        val delete = a.protokoll.indexOf("delete:${weckerAmKrankTag.id}")
        assertTrue("Erst cancel, dann delete (Protokoll: ${a.protokoll})", cancel in 0 until delete)
        assertEquals(listOf(listOf(tag) to listOf(LocalTime.of(5, 30))), a.krankNotifier.meldungen)
    }

    @Test
    fun `Termin weg - der Wecker entsteht neu und der Kalender-Tag faellt weg`() = runTest {
        val a = aufbau(kalender = setOf(tag))

        a.sut.syncAlarms(listOf(schicht("ev1", tag)), config)

        assertEquals(1, a.repo.current.size)
        assertEquals(millis(tag, LocalTime.of(5, 30)), a.repo.current.single().triggerTime)
        assertTrue(a.store.freigabeQuellenNow().kalender.isEmpty())
    }

    @Test
    fun `Mehrtaegiger ganztaegiger Krank-Termin gibt jeden Tag frei`() = runTest {
        val a = aufbau()
        val tage = (0L..2L).map { tag.plusDays(it) }
        val danach = tag.plusDays(3)

        a.sut.syncAlarms(
            tage.mapIndexed { i, d -> schicht("ev$i", d) } +
                schicht("evDanach", danach) +
                krankGanztaegig("k1", tag, tag.plusDays(2)),
            config
        )

        assertEquals(tage.toSet(), a.store.freigabeQuellenNow().kalender)
        assertEquals("Nur der Tag danach bekommt seinen Wecker", listOf("evDanach"), a.repo.current.map { it.eventId })
    }

    @Test
    fun `Zeitgebundener Krank-Termin bis Mitternacht gibt den Folgetag nicht frei`() {
        val bisMitternacht = CalendarEvent(
            id = "k1",
            title = "K",
            startTime = LocalDateTime.of(tag, LocalTime.of(8, 0)),
            endTime = LocalDateTime.of(tag.plusDays(1), LocalTime.MIDNIGHT),
            calendarId = "test"
        )
        val ueberMitternacht = bisMitternacht.copy(endTime = LocalDateTime.of(tag.plusDays(1), LocalTime.of(0, 30)))

        assertEquals(setOf(tag), AlarmUseCase.rohKrankTage(listOf(bisMitternacht)))
        assertEquals(setOf(tag, tag.plusDays(1)), AlarmUseCase.rohKrankTage(listOf(ueberMitternacht)))
        // Ganztaegig zaehlt das Ende nie zurueck - es liegt ohnehin auf 23:59 des letzten Tages.
        assertEquals(setOf(tag), AlarmUseCase.rohKrankTage(listOf(krankGanztaegig("k2", tag))))
    }

    @Test
    fun `Schicht kurz nach Mitternacht - die Weckzeit am Vortag wird mit freigegeben`() = runTest {
        val a = aufbau()
        val nachtschicht = CalendarEvent(
            id = "evN",
            title = "N",
            startTime = LocalDateTime.of(tag, LocalTime.of(0, 30)),
            endTime = LocalDateTime.of(tag, LocalTime.of(8, 30)),
            calendarId = "test"
        )

        a.sut.syncAlarms(listOf(nachtschicht, krankGanztaegig("k1", tag)), config)

        assertTrue(
            "Die Weckzeit 23:30 am Vortag darf nicht klingeln (Protokoll: ${a.protokoll})",
            a.repo.current.isEmpty()
        )
        assertEquals(setOf(tag.minusDays(1), tag), a.store.freigabeQuellenNow().kalender)
    }

    @Test
    fun `Manueller Wecker am Krank-Tag bleibt`() = runTest {
        val manuell = AlarmInfo(
            id = 4711,
            shiftId = "manual",
            shiftName = "Zahnarzt",
            triggerTime = millis(tag, LocalTime.of(7, 0)),
            formattedTime = "07:00",
            eventId = ""
        )
        val a = aufbau(bestand = listOf(manuell))

        a.sut.syncAlarms(listOf(schicht("ev1", tag), krankGanztaegig("k1", tag)), config)

        assertEquals(listOf(manuell), a.repo.current)
        assertTrue("Der manuelle Wecker wurde abgebrochen: ${a.protokoll}", "cancel:4711" !in a.protokoll)
    }

    @Test
    fun `Hand-Freigabe bleibt, wenn der Krank-Termin verschwindet`() = runTest {
        val a = aufbau(hand = setOf(tag), kalender = setOf(tag))

        a.sut.syncAlarms(listOf(schicht("ev1", tag)), config)

        val quellen = a.store.freigabeQuellenNow()
        assertTrue(quellen.kalender.isEmpty())
        assertEquals(setOf(tag), quellen.hand)
        assertTrue("Der Tag bleibt frei - kein Wecker", a.repo.current.isEmpty())
    }

    @Test
    fun `Leere Eventliste raeumt Kalender-Tage ab heute`() = runTest {
        val heute = LocalDate.now(zone)
        val a = aufbau(kalender = setOf(heute, tag))

        a.sut.syncAlarms(emptyList(), config)

        assertTrue(a.store.freigabeQuellenNow().kalender.isEmpty())
    }

    @Test
    fun `Krank-Tage hinter dem Abruf-Horizont bleiben stehen`() = runTest {
        val dahinter = tag.plusDays(10)
        val a = aufbau(kalender = setOf(dahinter))
        val horizontEnde = millis(tag.plusDays(5), LocalTime.MIDNIGHT)

        a.sut.syncAlarms(listOf(schicht("ev1", tag)), config, abrufHorizontEnde = horizontEnde)

        assertEquals("Nicht gelesen ist nicht geloescht", setOf(dahinter), a.store.freigabeQuellenNow().kalender)
        assertEquals(1, a.repo.current.size)
    }

    @Test
    fun `Neben-Ketten laufen genau einmal nach dem Sync, wenn sich die Krank-Menge aendert - sonst nicht`() = runTest {
        val a = aufbau()
        val events = listOf(schicht("ev1", tag), krankGanztaegig("k1", tag))

        a.sut.syncAlarms(events, config)
        verify(a.armierer, times(1)).armiere("KRANK", true, true)

        // Derselbe Stand noch einmal: keine Aenderung, kein Nachzug.
        a.sut.syncAlarms(events, config)
        verify(a.armierer, times(1)).armiere(any(), any(), any())
    }

    @Test
    fun `Master-Pause laesst den Kalender-Schluessel unangetastet`() = runTest {
        val a = aufbau(kalender = setOf(tag), pausiert = true)

        a.sut.syncAlarms(listOf(schicht("ev1", tag)), config)

        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        verify(a.armierer, never()).armiere(any(), any(), any())
    }

    @Test
    fun `Der allererste Sync meldet keine Krankmeldung`() = runTest {
        val a = aufbau()

        a.sut.syncAlarms(listOf(schicht("ev1", tag), krankGanztaegig("k1", tag)), config)

        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        assertTrue("Erster Sync darf nicht fluten: ${a.krankNotifier.meldungen}", a.krankNotifier.meldungen.isEmpty())
    }

    // --- #142 Review-Befunde: Anker ueber den Bestand, Meldung ueberlebt Abbruch und Fehlschlag ---

    private fun nachtschichtAm(datum: LocalDate, id: String = "evN") = CalendarEvent(
        id = id,
        title = "N",
        startTime = LocalDateTime.of(datum, LocalTime.of(0, 30)),
        endTime = LocalDateTime.of(datum, LocalTime.of(8, 30)),
        calendarId = "test"
    )

    @Test
    fun `TimeOffice ersetzt die Nachtschicht durch K - Krankmeldung statt Schicht entfernt`() = runTest {
        val a = aufbau()
        val anderer = tag.plusDays(3)
        a.sut.syncAlarms(listOf(nachtschichtAm(tag), schicht("ev2", anderer)), config)
        val nachtWecker = a.repo.current.single { it.eventId == "evN" }
        assertEquals("Weckzeit am Vortag", millis(tag.minusDays(1), LocalTime.of(23, 30)), nachtWecker.triggerTime)

        // Der Schichttermin ist weg, an seiner Stelle "K" - die Nachtschicht ist kein Treffer mehr.
        a.sut.syncAlarms(listOf(krankGanztaegig("k1", tag), schicht("ev2", anderer)), config)

        assertEquals("Eine Krankmeldung ist keine entfernte Schicht", 0, a.schichtNotifier.geloeschtGemeldet)
        assertTrue(a.repo.current.none { it.id == nachtWecker.id })
        // Der Bestand ankert den Vortag - Gate, Backstop und Nachtregeln sind sich einig.
        assertEquals(setOf(tag.minusDays(1), tag), a.store.freigabeQuellenNow().kalender)
        // Gemeldet wird der KALENDER-Krank-Tag, mitgezaehlt der Wecker vom Vortag.
        assertEquals(listOf(listOf(tag) to listOf(LocalTime.of(23, 30))), a.krankNotifier.meldungen)
    }

    @Test
    fun `Die Krankmeldung nennt nur den Kalender-Tag, nicht den Weckzeit-Anker`() = runTest {
        val a = aufbau()
        a.sut.syncAlarms(listOf(schicht("ev2", tag.plusDays(3))), config)

        a.sut.syncAlarms(listOf(nachtschichtAm(tag), krankGanztaegig("k1", tag), schicht("ev2", tag.plusDays(3))), config)

        assertEquals(setOf(tag.minusDays(1), tag), a.store.freigabeQuellenNow().kalender)
        assertEquals(listOf(listOf(tag) to emptyList<LocalTime>()), a.krankNotifier.meldungen)
    }

    @Test
    fun `Ein schon von Hand freigegebener Tag wird bei Krank nicht gemeldet`() = runTest {
        val a = aufbau(hand = setOf(tag))
        a.sut.syncAlarms(listOf(schicht("ev1", tag), schicht("ev2", tag.plusDays(3))), config)
        assertTrue("Kein Wecker am freigegebenen Tag", a.repo.current.none { it.eventId == "ev1" })

        a.sut.syncAlarms(listOf(schicht("ev1", tag), krankGanztaegig("k1", tag), schicht("ev2", tag.plusDays(3))), config)

        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        assertTrue("Ohnehin frei - keine Nachricht: ${a.krankNotifier.meldungen}", a.krankNotifier.meldungen.isEmpty())
    }

    @Test
    fun `Scheitert der Sync nach dem Schreiben, meldet der naechste die Krankmeldung nach`() = runTest {
        val a = aufbau()
        val anderer = tag.plusDays(2)
        a.sut.syncAlarms(listOf(schicht("ev1", tag), schicht("ev2", anderer)), config)
        val weckerAmKrankTag = a.repo.current.single { it.eventId == "ev1" }
        val events = listOf(krankGanztaegig("k1", tag), schicht("ev2", anderer))

        // Schritt 1 wirft beim Loeschen - der Kalender-Schluessel ist da schon geschrieben.
        a.repo.deleteSchlaegtFehl = true
        val gescheitert = a.sut.syncAlarms(events, config)

        assertTrue(gescheitert.isFailure)
        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        verify(a.armierer).armiere("KRANK", true, true)
        assertTrue("Kein Erfolg, keine Meldung", a.krankNotifier.meldungen.isEmpty())

        // Der naechste Lauf sieht keine Aenderung mehr - und meldet trotzdem.
        val ergebnis = a.sut.syncAlarms(events, config)

        assertTrue(ergebnis.isSuccess)
        assertTrue(a.repo.current.none { it.id == weckerAmKrankTag.id })
        assertEquals(listOf(listOf(tag) to listOf(LocalTime.of(5, 30))), a.krankNotifier.meldungen)
        assertEquals(0, a.schichtNotifier.geloeschtGemeldet)

        // Quittiert: ein dritter Lauf meldet nicht noch einmal.
        a.sut.syncAlarms(events, config)
        assertEquals(1, a.krankNotifier.meldungen.size)
    }

    @Test
    fun `Bricht der Sync nach dem Schreiben ab, laufen die Neben-Ketten trotzdem nach`() = runTest {
        val a = aufbau()
        a.sut.syncAlarms(listOf(schicht("ev1", tag), schicht("ev2", tag.plusDays(2))), config)
        // Die Spannen werden NACH den Krank-Tagen geschrieben - dort endet der Lauf (App verlassen).
        doAnswer { throw CancellationException("Scope beendet") }
            .whenever(a.spanStore).replaceAll(any(), any())
        val events = listOf(schicht("ev1", tag), krankGanztaegig("k1", tag), schicht("ev2", tag.plusDays(2)))

        try {
            a.sut.syncAlarms(events, config)
            fail("Der Abbruch muss weitergereicht werden")
        } catch (_: CancellationException) {
            // erwartet
        }

        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        verify(a.armierer).armiere("KRANK", true, true)
        assertTrue(a.krankNotifier.meldungen.isEmpty())

        // Der naechste vollstaendige Lauf holt die Meldung nach - der Diff ist da schon verbraucht.
        org.mockito.Mockito.reset(a.spanStore)
        val ergebnis = a.sut.syncAlarms(events, config)
        assertTrue(ergebnis.isSuccess)
        assertFalse(a.krankNotifier.meldungen.isEmpty())
        assertEquals(listOf(tag), a.krankNotifier.meldungen.single().first)
    }

    // --- #142 Fix-Runde: Anker bleiben stabil, Quittung nur nach Zustellung, Uhr, Frueh-Ausstiege ---

    @Test
    fun `Automatik aus laesst den Kalender-Schluessel unangetastet`() = runTest {
        val a = aufbau(kalender = setOf(tag))

        a.sut.syncAlarms(listOf(schicht("ev1", tag)), config.copy(autoAlarmEnabled = false))

        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        verify(a.armierer, never()).armiere(any(), any(), any())
    }

    @Test
    fun `Der Weckzeit-Anker aus dem Bestand haelt ueber den naechsten Sync hinaus`() = runTest {
        val montag = tag
        val dienstag = tag.plusDays(1)
        val anderer = tag.plusDays(4)
        val a = aufbau()
        a.sut.syncAlarms(listOf(schicht("evF", montag), nachtschichtAm(dienstag), schicht("ev2", anderer)), config)
        assertEquals("Fruehdienst und Nachtwecker am Montag", 2, a.repo.current.count { tagVon(it) == montag })

        // TimeOffice ersetzt die Nachtschicht durch "K" - nur noch der Bestand kennt den Anker.
        val events = listOf(schicht("evF", montag), krankGanztaegig("k1", dienstag), schicht("ev2", anderer))
        a.sut.syncAlarms(events, config)
        assertEquals(setOf(montag, dienstag), a.store.freigabeQuellenNow().kalender)
        assertTrue("Kein Wecker am Montag", a.repo.current.none { tagVon(it) == montag })
        a.schichtNotifier.angelegtGemeldet.clear()

        // Der naechste Lauf sieht den geloeschten Nachtwecker nicht mehr - der Montag muss bleiben.
        a.sut.syncAlarms(events, config)

        assertEquals(setOf(montag, dienstag), a.store.freigabeQuellenNow().kalender)
        assertTrue("Der Fruehdienst-Wecker darf nicht zurueckkommen", a.repo.current.none { tagVon(it) == montag })
        assertTrue(
            "Keine 'Neue Schicht erkannt': ${a.schichtNotifier.angelegtGemeldet}",
            a.schichtNotifier.angelegtGemeldet.isEmpty()
        )
        verify(a.armierer, times(1)).armiere(any(), any(), any())
    }

    @Test
    fun `Faellt die Krankmeldung weg, faellt der gemerkte Anker mit`() = runTest {
        val montag = tag
        val dienstag = tag.plusDays(1)
        val anderer = tag.plusDays(4)
        val a = aufbau()
        a.sut.syncAlarms(listOf(schicht("evF", montag), nachtschichtAm(dienstag), schicht("ev2", anderer)), config)
        a.sut.syncAlarms(listOf(schicht("evF", montag), krankGanztaegig("k1", dienstag), schicht("ev2", anderer)), config)

        a.sut.syncAlarms(listOf(schicht("evF", montag), schicht("ev2", anderer)), config)

        assertTrue("Im Zweifel klingeln", a.store.freigabeQuellenNow().kalender.isEmpty())
        assertEquals(listOf("evF"), a.repo.current.filter { tagVon(it) == montag }.map { it.eventId })
    }

    @Test
    fun `Eine nicht zugestellte Krankmeldung bleibt vorgemerkt und kommt nach`() = runTest {
        val a = aufbau()
        val anderer = tag.plusDays(2)
        a.sut.syncAlarms(listOf(schicht("ev1", tag), schicht("ev2", anderer)), config)
        val events = listOf(krankGanztaegig("k1", tag), schicht("ev2", anderer))

        a.krankNotifier.zustellbar = false
        assertTrue(a.sut.syncAlarms(events, config).isSuccess)

        assertEquals("Versucht wurde es", 1, a.krankNotifier.meldungen.size)
        assertEquals("Nicht zugestellt = nicht quittiert", setOf(tag), a.store.offeneKrankMeldungNow(LocalDate.now(zone)))

        a.krankNotifier.zustellbar = true
        a.sut.syncAlarms(events, config)

        assertEquals(2, a.krankNotifier.meldungen.size)
        assertEquals(listOf(tag), a.krankNotifier.meldungen.last().first)
        assertTrue(a.store.offeneKrankMeldungNow(LocalDate.now(zone)).isEmpty())
    }

    @Test
    fun `Leerer Bestand nach einem frueheren Sync ist kein allererster Lauf`() = runTest {
        // Eine lange Krankschreibung hat den Bestand geleert - ein frueherer Sync lief aber.
        val a = aufbau(horizont = FakeSyncHorizonStore(letzterSync = millis(tag.minusDays(30), LocalTime.NOON)))

        a.sut.syncAlarms(listOf(schicht("ev1", tag), krankGanztaegig("k1", tag), schicht("ev2", tag.plusDays(3))), config)

        assertEquals(listOf(listOf(tag) to emptyList<LocalTime>()), a.krankNotifier.meldungen)
    }

    @Test
    fun `zeitgebundeneEnden merkt nur zeitgebundene Termine mit echtem Ende`() {
        val zeitgebunden = krankZeitgebunden(tag)
        val bisMitternacht = zeitgebunden.copy(id = "k2", endTime = LocalDateTime.of(tag.plusDays(1), LocalTime.MIDNIGHT))

        assertEquals(
            setOf(LocalDateTime.of(tag, LocalTime.of(16, 0))),
            AlarmUseCase.zeitgebundeneEnden(listOf(zeitgebunden, bisMitternacht, krankGanztaegig("k3", tag)))
        )
    }

    private fun krankZeitgebunden(datum: LocalDate) = CalendarEvent(
        id = "kZ",
        title = "K",
        startTime = LocalDateTime.of(datum, LocalTime.of(8, 0)),
        endTime = LocalDateTime.of(datum, LocalTime.of(16, 0)),
        calendarId = "test"
    )

    @Test
    fun `Ein heute schon beendeter Krank-Termin haelt den Tag bis zum Abend frei`() = runTest {
        val a = aufbau()
        // Nachtschicht am Folgetag: Weckzeit heute 23:30 - genau der Wecker, der trotz Krankmeldung
        // klingeln wuerde, wenn der Tag nach dem Ende des Termins wieder Diensttag waere.
        a.sut.uhr = { tag.atTime(7, 0) }
        a.sut.syncAlarms(listOf(krankZeitgebunden(tag), nachtschichtAm(tag.plusDays(1))), config)
        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        assertTrue(a.repo.current.isEmpty())

        // 17 Uhr: der Abruf beginnt bei jetzt, der Termin ist nicht mehr darin - abgelaufen, nicht geloescht.
        a.sut.uhr = { tag.atTime(17, 0) }
        a.sut.syncAlarms(listOf(nachtschichtAm(tag.plusDays(1))), config)

        assertEquals(setOf(tag), a.store.freigabeQuellenNow().kalender)
        assertTrue("Die Nachtschicht darf heute nicht klingeln: ${a.protokoll}", a.repo.current.isEmpty())
    }

    @Test
    fun `Ein geloeschter, noch laufender Krank-Termin gibt den Tag zurueck`() = runTest {
        val a = aufbau()
        a.sut.uhr = { tag.atTime(7, 0) }
        a.sut.syncAlarms(listOf(krankZeitgebunden(tag), nachtschichtAm(tag.plusDays(1))), config)

        // 12 Uhr: der Termin liefe noch bis 16 Uhr - fehlt er, wurde er geloescht (Review M2).
        a.sut.uhr = { tag.atTime(12, 0) }
        a.sut.syncAlarms(listOf(nachtschichtAm(tag.plusDays(1))), config)

        assertTrue(a.store.freigabeQuellenNow().kalender.isEmpty())
        assertEquals(1, a.repo.current.size)
    }

    private fun tagVon(alarm: AlarmInfo): LocalDate =
        java.time.Instant.ofEpochMilli(alarm.triggerTime).atZone(zone).toLocalDate()
}
