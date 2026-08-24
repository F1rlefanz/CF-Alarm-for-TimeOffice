package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * Vertrag von "Tag freigeben".
 *
 * Der schaerfste Punkt ist derselbe wie beim Ueberspringen: **erst `cancelSystemAlarm()`, dann
 * `deleteAlarm()`**, und wenn das Loeschen nicht dauerhaft gelingt, wird die Freigabe
 * ZURUECKGENOMMEN statt eine Luege stehenzulassen. Ein Eintrag, der liegen bleibt, waehrend die
 * Oberflaeche "freigegeben" sagt, wird vom Direct-Boot-Spiegel nach dem naechsten Neustart
 * ungefiltert wieder armiert - der freigegebene Morgen klingelt dann doch.
 */
class TagFreigabeUseCaseTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val tag: LocalDate = LocalDate.of(2026, 8, 24)

    /** Ein KALENDER-Wecker: `eventId` gesetzt. Nur solche gehoeren zum Dienst. */
    private fun weckerAm(id: Int, datum: LocalDate, stunde: Int = 5): AlarmInfo = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Schicht$id",
        triggerTime = datum.atTime(stunde, 30).atZone(zone).toInstant().toEpochMilli(),
        formattedTime = "%02d:30".format(stunde),
        eventId = "ev$id"
    )

    /** Ein MANUELLER Wecker: leere `eventId`, wie ihn `AlarmViewModel.createManualAlarm` anlegt. */
    private fun manuellerWeckerAm(id: Int, datum: LocalDate, stunde: Int = 9): AlarmInfo = AlarmInfo(
        id = id,
        shiftId = "manual_$id",
        shiftName = "Zahnarzt",
        triggerTime = datum.atTime(stunde, 30).atZone(zone).toInstant().toEpochMilli(),
        formattedTime = "%02d:30".format(stunde)
    )

    /** Sammelt die Eingriffe in ihrer REIHENFOLGE - genau darum geht es hier. */
    private val protokoll = mutableListOf<String>()

    private inner class FakeAlarmRepository(
        initial: List<AlarmInfo>,
        private val loeschenScheitert: Boolean = false,
        private val persistenzGesperrt: Boolean = false
    ) : IAlarmRepository {
        private val state = MutableStateFlow(initial)
        val current: List<AlarmInfo> get() = state.value
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        override suspend fun isPersistenceBlocked(): Boolean = persistenzGesperrt
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }
        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(state.value.find { it.id == alarmId })
        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            protokoll += "delete:$alarmId"
            if (loeschenScheitert) return Result.failure(IllegalStateException("Schreibfehler"))
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

    private class FakeSkipUseCase(private val zustand: AlarmSkipState) : IAlarmSkipUseCase {
        var cancelAufgerufen = false
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> = error("nicht benutzt")
        override suspend fun cancelSkip(): Result<Unit> {
            cancelAufgerufen = true
            return Result.success(Unit)
        }
        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)
        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(zustand)
        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)
        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(zustand)
    }

    /** Freigaben im Arbeitsspeicher - genug, um Setzen und Ruecknahme zu pruefen. */
    private fun storeMit(tage: MutableSet<LocalDate>): FreieTageStore {
        val store = mock<FreieTageStore>()
        runBlocking {
            whenever(store.freieTageNow()).thenAnswer { tage.toSet() }
            whenever(store.freigeben(any())).thenAnswer { inv ->
                protokoll += "markiere:${inv.getArgument<LocalDate>(0)}"
                tage.add(inv.getArgument(0))
                Unit
            }
            whenever(store.zuruecknehmen(any())).thenAnswer { inv ->
                protokoll += "ruecknahme:${inv.getArgument<LocalDate>(0)}"
                tage.remove(inv.getArgument<LocalDate>(0))
                Unit
            }
        }
        return store
    }

    private fun alarmManagerMit(): AlarmManagerService {
        val service = mock<AlarmManagerService>()
        whenever(service.cancelSystemAlarm(any())).thenAnswer { inv ->
            protokoll += "cancel:${inv.getArgument<Int>(0)}"
            Unit
        }
        return service
    }

    private fun sut(
        repo: IAlarmRepository,
        store: FreieTageStore,
        skip: IAlarmSkipUseCase,
        alarmManager: AlarmManagerService
    ) = TagFreigabeUseCase(
        store = store,
        alarmRepository = repo,
        alarmManagerService = alarmManager,
        alarmSkipUseCase = skip,
        dimSchedule = mock<DimScheduleUseCase>(),
        dndSchedule = mock<DndScheduleUseCase>()
    )

    // --- Tages-Anker ---

    @Test
    fun `gehoertZuTag ankert an der WECKZEIT, nicht am Schichtbeginn`() {
        // Spaetdienst: Wecker am 24. um 23,30 Uhr, Dienst beginnt am 25. Der Wecker gehoert zum
        // 24. - derselbe Anker wie FreieTageStore.tagVon und AlarmUseCase.istTagFreigegeben.
        val spaet = weckerAm(1, tag, stunde = 23)
        assertTrue(TagFreigabeUseCase.gehoertZuTag(spaet, tag, zone))
        assertFalse(TagFreigabeUseCase.gehoertZuTag(spaet, tag.plusDays(1), zone))
    }

    @Test
    fun `gehoertZuTag beruecksichtigt die Zeitzone`() {
        val frueh = weckerAm(1, tag, stunde = 0)
        assertTrue(TagFreigabeUseCase.gehoertZuTag(frueh, tag, zone))
        // 00,30 Uhr Berliner Zeit ist in UTC noch der Vortag.
        assertTrue(TagFreigabeUseCase.gehoertZuTag(frueh, tag.minusDays(1), ZoneId.of("UTC")))
    }

    // --- Der eigentliche Vorgang ---

    @Test
    fun `Freigeben cancelt VOR dem Loeschen - und nur die Wecker dieses Tages`() = runTest {
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag), weckerAm(2, tag.plusDays(1))))
        val store = storeMit(mutableSetOf())
        val ergebnis = sut(repo, store, FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .freigeben(tag, zone)

        assertTrue(ergebnis.isSuccess)
        assertEquals(1, ergebnis.getOrThrow().geloeschteWecker)
        // Die Markierung steht ZUERST: faellt der Prozess danach, ist der schlimmste Zustand ein
        // freigegebener Tag mit noch stehendem Wecker - den raeumt das Gate im naechsten Sync weg.
        // Umgekehrt waere derselbe Absturz ein spurlos verschwundener Wecker.
        assertEquals(listOf("markiere:$tag", "cancel:1", "delete:1"), protokoll)
        assertEquals(listOf(2), repo.current.map { it.id })
    }

    @Test
    fun `BEIDE Schichten eines Tages verlieren ihren Wecker`() = runTest {
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag, 5), weckerAm(2, tag, 13)))
        val ergebnis = sut(repo, storeMit(mutableSetOf()), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .freigeben(tag, zone)

        assertEquals(2, ergebnis.getOrThrow().geloeschteWecker)
        assertTrue(repo.current.isEmpty())
    }

    @Test
    fun `Gesperrte Persistenz bricht ab, BEVOR irgendetwas angefasst ist`() = runTest {
        // Bei gesperrter Persistenz meldet deleteAlarm() Erfolg, ohne dauerhaft zu loeschen -
        // die Freigabe waere eine Luege, und der Wecker klingelte am freien Morgen doch.
        // Also gar nicht erst anfangen: der Wecker bleibt stehen und klingelt. Sichere Richtung.
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag)), persistenzGesperrt = true)
        val tage = mutableSetOf<LocalDate>()
        val ergebnis = sut(repo, storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .freigeben(tag, zone)

        assertTrue(ergebnis.isFailure)
        assertTrue("Nichts angefasst", protokoll.isEmpty())
        assertTrue(tage.isEmpty())
        assertEquals(listOf(1), repo.current.map { it.id })
    }

    @Test
    fun `Scheitert das Loeschen, wird die Freigabe zurueckgenommen und laut gemeldet`() = runTest {
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag)), loeschenScheitert = true)
        val tage = mutableSetOf<LocalDate>()
        val ergebnis = sut(repo, storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .freigeben(tag, zone)

        val fehler = ergebnis.exceptionOrNull()
        assertTrue("Der Aufrufer muss den Typ sehen", fehler is FreigabeZurueckgenommenException)
        fehler as FreigabeZurueckgenommenException
        // Beides braucht das ViewModel, um die stumm gewordenen Wecker wieder zu stellen: die IDs,
        // und die Auskunft, dass die Markierung wirklich weg ist (sonst weist der Backstop in
        // scheduleSystemAlarm() jedes Re-Arming ab).
        assertEquals(listOf(1), fehler.alarmIds)
        assertTrue(fehler.freigabeZurueckgenommen)
        assertTrue("Markierung muss weg sein", tage.isEmpty())
    }

    @Test
    fun `Ein Ueberspringen desselben Tages wird mit aufgehoben`() = runTest {
        // Die Freigabe ist die staerkere Aussage. Bliebe der Merker stehen, muesste der Nutzer
        // zwei Zustaende zuruecknehmen, um einen Wecker wiederzubekommen - und der zweite waere
        // unsichtbar, sobald der erste weg ist.
        val wecker = weckerAm(1, tag)
        val skip = FakeSkipUseCase(
            AlarmSkipState(
                isNextAlarmSkipped = true,
                skippedAlarmId = 1,
                skippedAlarmTriggerTime = wecker.triggerTime
            )
        )
        val ergebnis = sut(FakeAlarmRepository(listOf(wecker)), storeMit(mutableSetOf()), skip, alarmManagerMit())
            .freigeben(tag, zone)

        assertTrue(skip.cancelAufgerufen)
        assertTrue(ergebnis.getOrThrow().ueberspringenAufgehoben)
    }

    @Test
    fun `Ein Ueberspringen an einem ANDEREN Tag bleibt unangetastet`() = runTest {
        val fremderWecker = weckerAm(9, tag.plusDays(2))
        val skip = FakeSkipUseCase(
            AlarmSkipState(
                isNextAlarmSkipped = true,
                skippedAlarmId = 9,
                skippedAlarmTriggerTime = fremderWecker.triggerTime
            )
        )
        val ergebnis = sut(
            FakeAlarmRepository(listOf(weckerAm(1, tag), fremderWecker)),
            storeMit(mutableSetOf()), skip, alarmManagerMit()
        ).freigeben(tag, zone)

        assertFalse(skip.cancelAufgerufen)
        assertFalse(ergebnis.getOrThrow().ueberspringenAufgehoben)
    }

    @Test
    fun `Ein Tag ohne Wecker laesst sich freigeben - die Schicht kann spaeter kommen`() = runTest {
        // Der Chef gibt Tage im Voraus frei, bevor der Feed die Schicht ueberhaupt liefert. Das
        // Gate im Sync sorgt dafuer, dass daraus nie ein Wecker entsteht.
        val tage = mutableSetOf<LocalDate>()
        val ergebnis = sut(FakeAlarmRepository(emptyList()), storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .freigeben(tag.plusDays(10), zone)

        assertTrue(ergebnis.isSuccess)
        assertEquals(0, ergebnis.getOrThrow().geloeschteWecker)
        assertEquals(setOf(tag.plusDays(10)), tage)
    }

    @Test
    fun `Ein MANUELLER Wecker des Tages bleibt stehen`() = runTest {
        // Eine Freigabe streicht den DIENST, nicht die eigenen Wecker des Nutzers. Und sie waere
        // hier unumkehrbar: `zuruecknehmen()` baut ueber den Kalender wieder auf, in dem ein
        // manueller Wecker nicht steht - `syncAlarms` schont ihn nur, es legt ihn nie neu an.
        val protokoll_vorher = protokoll.size
        val manueller = manuellerWeckerAm(99, tag)
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag), manueller))

        val ergebnis = sut(repo, storeMit(mutableSetOf()), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .freigeben(tag, zone)

        assertEquals(1, ergebnis.getOrThrow().geloeschteWecker)
        assertEquals(listOf(99), repo.current.map { it.id })
        assertFalse(
            "Der manuelle Wecker darf nicht einmal gecancelt werden (Protokoll: $protokoll)",
            protokoll.drop(protokoll_vorher).contains("cancel:99")
        )
    }

    @Test
    fun `gehoertZuTag nimmt manuelle Wecker aus`() {
        assertFalse(TagFreigabeUseCase.gehoertZuTag(manuellerWeckerAm(99, tag), tag, zone))
        assertTrue(TagFreigabeUseCase.gehoertZuTag(weckerAm(1, tag), tag, zone))
    }

    @Test
    fun `Ruecknahme entfernt die Markierung`() = runTest {
        val tage = mutableSetOf(tag)
        val ergebnis = sut(FakeAlarmRepository(emptyList()), storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .zuruecknehmen(tag)

        assertTrue(ergebnis.isSuccess)
        assertTrue(tage.isEmpty())
    }
}
