package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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
        private val persistenzGesperrt: Boolean = false,
        /** Die ersten N deleteAlarm()-Aufrufe scheitern (voruebergehender Schreibfehler). */
        private val ersteLoeschFehler: Int = 0,
        /** Die Persistenz sperrt erst NACH dem ersten deleteAlarm() (nach der Vorpruefung). */
        private val gesperrtNachLoeschen: Boolean = false,
        /** #142: nur DIESE Wecker lassen sich nicht loeschen - ein Tag mitten im Zeitraum scheitert. */
        private val loeschenScheitertFuer: Set<Int> = emptySet()
    ) : IAlarmRepository {
        private val state = MutableStateFlow(initial)
        private var loeschAufrufe = 0
        val current: List<AlarmInfo> get() = state.value
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        override suspend fun isPersistenceBlocked(): Boolean =
            persistenzGesperrt || (gesperrtNachLoeschen && loeschAufrufe > 0)
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }
        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            protokoll += "delete:$alarmId"
            loeschAufrufe++
            if (loeschenScheitert || loeschAufrufe <= ersteLoeschFehler || alarmId in loeschenScheitertFuer) {
                return Result.failure(IllegalStateException("Schreibfehler"))
            }
            state.value = state.value.filterNot { it.id == alarmId }
            return Result.success(Unit)
        }
        override suspend fun deleteAllAlarms(): Result<Unit> {
            state.value = emptyList()
            return Result.success(Unit)
        }
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
            whenever(store.zuruecknehmenAlle(any())).thenAnswer { inv ->
                val zurueck = inv.getArgument<Set<LocalDate>>(0)
                protokoll += "ruecknahme:${zurueck.sorted()}"
                tage.removeAll(zurueck)
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
        whenever(service.cancelSnoozesFuerFreigabe(any(), any())).thenAnswer { inv ->
            protokoll += "snooze:${inv.getArgument<LocalDate>(0)}"
            0
        }
        return service
    }

    private fun sut(
        repo: IAlarmRepository,
        store: FreieTageStore,
        skip: IAlarmSkipUseCase,
        alarmManager: AlarmManagerService,
        armierer: ZeitkettenArmierer = mock()
    ) = TagFreigabeUseCase(
        store = store,
        alarmRepository = repo,
        alarmManagerService = alarmManager,
        alarmSkipUseCase = skip,
        armierer = armierer,
    )

    /**
     * Ein Tag = der Zeitraum mit von == bis, als `Result` wie frueher die (entfernte) Huelle
     * `freigeben(datum)` - die Faelle unten pruefen die Einzeltag-Schrittfolge.
     */
    private suspend fun TagFreigabeUseCase.einTagFreigeben(datum: LocalDate, zone: ZoneId): Result<TagFreigabeResult> {
        val ergebnis = freigebenZeitraum(datum, datum, zone)
        ergebnis.fehler?.let { return Result.failure(it) }
        return Result.success(TagFreigabeResult(datum, ergebnis.geloeschteWecker, ergebnis.ueberspringenAufgehoben))
    }

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
            .einTagFreigeben(tag, zone)

        assertTrue(ergebnis.isSuccess)
        assertEquals(1, ergebnis.getOrThrow().geloeschteWecker)
        // Die Markierung steht ZUERST: faellt der Prozess danach, ist der schlimmste Zustand ein
        // freigegebener Tag mit noch stehendem Wecker - den raeumt das Gate im naechsten Sync weg.
        // Umgekehrt waere derselbe Absturz ein spurlos verschwundener Wecker.
        // Der schwebende Snooze eines schon geklingelten Weckers faellt ERST NACH der Schleife -
        // scheitert dort ein Loeschen, wird die Freigabe zurueckgenommen, und der Snooze muss bleiben.
        assertEquals(listOf("markiere:$tag", "cancel:1", "delete:1", "snooze:$tag"), protokoll)
        assertEquals(listOf(2), repo.current.map { it.id })
    }

    @Test
    fun `BEIDE Schichten eines Tages verlieren ihren Wecker`() = runTest {
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag, 5), weckerAm(2, tag, 13)))
        val ergebnis = sut(repo, storeMit(mutableSetOf()), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .einTagFreigeben(tag, zone)

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
            .einTagFreigeben(tag, zone)

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
            .einTagFreigeben(tag, zone)

        val fehler = ergebnis.exceptionOrNull()
        assertTrue("Der Aufrufer muss den Typ sehen", fehler is FreigabeZurueckgenommenException)
        fehler as FreigabeZurueckgenommenException
        // Beides braucht das ViewModel, um die stumm gewordenen Wecker wieder zu stellen: die IDs,
        // und die Auskunft, dass die Markierung wirklich weg ist (sonst weist der Backstop in
        // scheduleSystemAlarm() jedes Re-Arming ab).
        assertEquals(listOf(1), fehler.alarmIds)
        assertTrue(fehler.freigabeZurueckgenommen)
        assertTrue("Markierung muss weg sein", tage.isEmpty())
        // Der Tag ist NICHT freigegeben - ein schon geschlummerter Wecker muss weiter klingeln.
        assertFalse("Snooze darf nicht abgebrochen sein", protokoll.any { it.startsWith("snooze:") })
    }

    @Test
    fun `Ein voruebergehender Loeschfehler wird genau einmal nachgefasst`() = runTest {
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag)), ersteLoeschFehler = 1)
        val tage = mutableSetOf<LocalDate>()
        val ergebnis = sut(repo, storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .einTagFreigeben(tag, zone)

        assertTrue(ergebnis.isSuccess)
        assertEquals(setOf(tag), tage)
        assertEquals(2, protokoll.count { it == "delete:1" })
        assertTrue(repo.current.isEmpty())
    }

    @Test
    fun `Loeschen nur im Arbeitsspeicher zaehlt als Fehlschlag`() = runTest {
        // deleteAlarm() meldet Erfolg, aber die Persistenz ist inzwischen gesperrt: Bestand und
        // Direct-Boot-Spiegel behalten den Wecker. Die Sperre entsteht erst NACH der Vorpruefung.
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag)), gesperrtNachLoeschen = true)
        val tage = mutableSetOf<LocalDate>()
        val ergebnis = sut(repo, storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .einTagFreigeben(tag, zone)

        assertTrue(ergebnis.exceptionOrNull() is FreigabeZurueckgenommenException)
        assertTrue("Markierung muss weg sein", tage.isEmpty())
        assertEquals(2, protokoll.count { it == "delete:1" })
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
            .einTagFreigeben(tag, zone)

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
        ).einTagFreigeben(tag, zone)

        assertFalse(skip.cancelAufgerufen)
        assertFalse(ergebnis.getOrThrow().ueberspringenAufgehoben)
    }

    @Test
    fun `Ein Tag ohne Wecker laesst sich freigeben - die Schicht kann spaeter kommen`() = runTest {
        // Der Chef gibt Tage im Voraus frei, bevor der Feed die Schicht ueberhaupt liefert. Das
        // Gate im Sync sorgt dafuer, dass daraus nie ein Wecker entsteht.
        val tage = mutableSetOf<LocalDate>()
        val ergebnis = sut(FakeAlarmRepository(emptyList()), storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .einTagFreigeben(tag.plusDays(10), zone)

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
            .einTagFreigeben(tag, zone)

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
            .zuruecknehmenTage(setOf(tag))

        assertTrue(ergebnis.isSuccess)
        assertTrue(tage.isEmpty())
    }

    // --- #142: Zeitraum ---

    @Test
    fun `Zeitraum gibt alle Tage aufsteigend frei - je Tag Markierung vor dem Loeschen`() = runTest {
        val repo = FakeAlarmRepository(listOf(weckerAm(3, tag.plusDays(2)), weckerAm(1, tag), weckerAm(2, tag.plusDays(1))))
        val tage = mutableSetOf<LocalDate>()
        val armierer = mock<ZeitkettenArmierer>()

        val ergebnis = sut(repo, storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit(), armierer)
            .freigebenZeitraum(tag, tag.plusDays(2), zone)

        assertNull(ergebnis.fehler)
        assertEquals(tag.plusDays(2), ergebnis.freigegebenBis)
        assertEquals(3, ergebnis.geloeschteWecker)
        assertEquals(setOf(tag, tag.plusDays(1), tag.plusDays(2)), tage)
        assertTrue(repo.current.isEmpty())
        // Je Tag die volle Einzeltag-Schrittfolge, Tage aufsteigend - auch wenn der Bestand
        // ungeordnet liegt. Die Markierung steht vor jedem Loeschen dieses Tages.
        assertEquals(
            listOf(
                "markiere:$tag", "cancel:1", "delete:1", "snooze:$tag",
                "markiere:${tag.plusDays(1)}", "cancel:2", "delete:2", "snooze:${tag.plusDays(1)}",
                "markiere:${tag.plusDays(2)}", "cancel:3", "delete:3", "snooze:${tag.plusDays(2)}"
            ),
            protokoll
        )
        verify(armierer, times(1)).armiere(eq("FREIGABE"), any(), any())
    }

    @Test
    fun `Abbruch mitten im Zeitraum - vorherige Tage bleiben frei, spaetere bleiben unberuehrt`() = runTest {
        // Eigentuemer-Entscheidung #142: was schon freigegeben ist, BLEIBT. Der gescheiterte Tag
        // wird wie beim Einzeltag zurueckgerollt, die Tage danach werden gar nicht erst angefasst.
        val repo = FakeAlarmRepository(
            listOf(weckerAm(1, tag), weckerAm(2, tag.plusDays(1)), weckerAm(3, tag.plusDays(2))),
            loeschenScheitertFuer = setOf(2)
        )
        val tage = mutableSetOf<LocalDate>()

        val ergebnis = sut(repo, storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit())
            .freigebenZeitraum(tag, tag.plusDays(2), zone)

        assertEquals(tag, ergebnis.freigegebenBis)
        assertEquals(1, ergebnis.geloeschteWecker)
        val fehler = ergebnis.fehler
        assertTrue("Identitaet muss erhalten bleiben: $fehler", fehler is FreigabeZurueckgenommenException)
        fehler as FreigabeZurueckgenommenException
        assertEquals(tag.plusDays(1), fehler.datum)
        assertTrue(fehler.freigabeZurueckgenommen)
        assertEquals("Nur der erste Tag bleibt frei", setOf(tag), tage)
        assertEquals(listOf(2, 3), repo.current.map { it.id }.sorted())
        assertFalse(
            "Der Tag danach darf nicht angefasst sein (Protokoll: $protokoll)",
            protokoll.any { it == "markiere:${tag.plusDays(2)}" || it == "cancel:3" }
        )
    }

    @Test
    fun `Neben-Ketten laufen genau einmal - auch wenn ein Tag scheitert`() = runTest {
        val repo = FakeAlarmRepository(
            listOf(weckerAm(1, tag), weckerAm(2, tag.plusDays(1))),
            loeschenScheitertFuer = setOf(2)
        )
        val armierer = mock<ZeitkettenArmierer>()

        val ergebnis = sut(repo, storeMit(mutableSetOf()), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit(), armierer)
            .freigebenZeitraum(tag, tag.plusDays(3), zone)

        assertEquals(tag, ergebnis.freigegebenBis)
        // Der erste Tag STEHT - ohne Neu-Armieren liefe "Nicht stoeren" waehrend seiner Schicht.
        verify(armierer, times(1)).armiere(eq("FREIGABE"), any(), any())
    }

    @Test
    fun `Mehr als 31 Tage werden abgewiesen, ohne etwas anzufassen`() = runTest {
        val tage = mutableSetOf<LocalDate>()
        val armierer = mock<ZeitkettenArmierer>()
        val repo = FakeAlarmRepository(listOf(weckerAm(1, tag)))
        val useCase = sut(repo, storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit(), armierer)

        // 32 Tage einschliesslich beider Enden.
        val ergebnis = useCase.freigebenZeitraum(tag, tag.plusDays(TagFreigabeUseCase.MAX_ZEITRAUM_TAGE.toLong()), zone)

        assertTrue(ergebnis.fehler is IllegalArgumentException)
        assertNull(ergebnis.freigegebenBis)
        assertTrue("Nichts angefasst", protokoll.isEmpty())
        assertTrue(tage.isEmpty())
        assertEquals(listOf(1), repo.current.map { it.id })
        verify(armierer, never()).armiere(any(), any(), any())

        // Die Grenze selbst ist erlaubt: genau 31 Tage.
        val grenze = useCase.freigebenZeitraum(
            tag.plusDays(40),
            tag.plusDays(40L + TagFreigabeUseCase.MAX_ZEITRAUM_TAGE - 1),
            zone
        )
        assertNull(grenze.fehler)
        assertEquals(TagFreigabeUseCase.MAX_ZEITRAUM_TAGE, tage.size)
    }

    @Test
    fun `Ende vor Beginn wird abgewiesen`() = runTest {
        val tage = mutableSetOf<LocalDate>()
        val armierer = mock<ZeitkettenArmierer>()

        val ergebnis = sut(FakeAlarmRepository(listOf(weckerAm(1, tag))), storeMit(tage), FakeSkipUseCase(AlarmSkipState()), alarmManagerMit(), armierer)
            .freigebenZeitraum(tag.plusDays(1), tag, zone)

        assertTrue(ergebnis.fehler is IllegalArgumentException)
        assertNull(ergebnis.freigegebenBis)
        assertTrue("Nichts angefasst", protokoll.isEmpty())
        assertTrue(tage.isEmpty())
        verify(armierer, never()).armiere(any(), any(), any())
    }

    @Test
    fun `zuruecknehmenTage nimmt nur Hand-Freigaben zurueck und armiert einmal`() = runTest {
        val tage = mutableSetOf(tag, tag.plusDays(1), tag.plusDays(2))
        val store = storeMit(tage)
        val armierer = mock<ZeitkettenArmierer>()

        val ergebnis = sut(FakeAlarmRepository(emptyList()), store, FakeSkipUseCase(AlarmSkipState()), alarmManagerMit(), armierer)
            .zuruecknehmenTage(setOf(tag, tag.plusDays(1)))

        assertTrue(ergebnis.isSuccess)
        assertEquals(setOf(tag.plusDays(2)), tage)
        // EIN Schreibvorgang fuer alle Tage, EIN Neu-Armieren.
        assertEquals(listOf("ruecknahme:${listOf(tag, tag.plusDays(1))}"), protokoll)
        verify(armierer, times(1)).armiere(eq("FREIGABE"), any(), any())
        // Der Kalender-Schluessel gehoert dem Sync - die Ruecknahme fasst ihn nicht an.
        verify(store, never()).setzeKalenderKrankTage(any(), any(), anyOrNull(), any(), any(), any())
    }
}
