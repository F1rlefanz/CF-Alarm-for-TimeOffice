package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreigabeQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreigabeZurueckgenommenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.TagFreigabeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.TagFreigabeZeitraumResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * #142, Review S6 im ViewModel: scheitert "Dienst faellt aus" an einem Tag, der ZUGLEICH laut
 * Kalender krankgemeldet ist, darf der Hinweis nicht "klingeln wie geplant" versprechen - der
 * Backstop in `scheduleSystemAlarm()` weist jedes Wiederstellen ab. Geprueft wird, dass
 * `stelleNachAbgebrochenerFreigabeWiederHer` den Fall ERKENNT (die Texte selbst prueft
 * `FreigabeHinweiseTest`), dass kein Re-Arming versucht wird und welcher Tag als gescheitert gilt.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class FreigabeKalenderKrankAbbruchTest {

    private val dispatcher = StandardTestDispatcher()
    private val tag: LocalDate = LocalDate.now().plusDays(10)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun wecker(id: Int, datum: LocalDate) = AlarmInfo(
        id = id,
        shiftId = "early",
        shiftName = "Frueh",
        triggerTime = datum.atTime(5, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        formattedTime = "05:30",
        eventId = "ev$id"
    )

    private fun freigabeUseCase(ergebnis: TagFreigabeZeitraumResult, kalender: Set<LocalDate>): TagFreigabeUseCase {
        val useCase = mock<TagFreigabeUseCase>()
        whenever(useCase.freigabeQuellen).thenReturn(flowOf(FreigabeQuellen.LEER))
        runBlocking {
            whenever(useCase.freigabeQuellenNow()).thenReturn(FreigabeQuellen(emptySet(), kalender))
            whenever(useCase.freigebenZeitraum(any(), any(), any())).thenReturn(ergebnis)
        }
        return useCase
    }

    private fun abbruch(datum: LocalDate) = FreigabeZurueckgenommenException(
        datum = datum,
        alarmIds = listOf(7),
        freigabeZurueckgenommen = true,
        cause = IOException("kaputt")
    )

    @Test
    fun `Einzeltag laut Kalender krank - kein Re-Arming und kein Versprechen`() = runTest(dispatcher) {
        val alarmUseCase = alarmUseCase(listOf(wecker(7, tag)))
        val ergebnis = TagFreigabeZeitraumResult(
            von = tag, bis = tag, freigegebenBis = null, geloeschteWecker = 0,
            ueberspringenAufgehoben = false, fehler = abbruch(tag)
        )
        val vm = buildViewModel(alarmUseCase, freigabeUseCase(ergebnis, kalender = setOf(tag)))
        advanceUntilIdle()

        vm.tagFreigeben(tag)
        advanceUntilIdle()

        val stand = vm.tagFreigabeState.value
        assertEquals(
            freigabeHinweis(tag, tag, null, 0, false, tag, Wiederherstellung.KalenderKrank),
            stand.hinweis
        )
        assertNull("Ein Fehlschlag ist keine Erfolgsmeldung", stand.hinweisErfolgBis)
        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
    }

    @Test
    fun `Teilerfolg - der gescheiterte Tag ist laut Kalender krank`() = runTest(dispatcher) {
        val bis = tag.plusDays(2)
        val gescheitert = tag.plusDays(1)
        val alarmUseCase = alarmUseCase(listOf(wecker(7, gescheitert)))
        val ergebnis = TagFreigabeZeitraumResult(
            von = tag, bis = bis, freigegebenBis = tag, geloeschteWecker = 1,
            ueberspringenAufgehoben = false, fehler = abbruch(gescheitert)
        )
        val vm = buildViewModel(alarmUseCase, freigabeUseCase(ergebnis, kalender = setOf(gescheitert)))
        advanceUntilIdle()

        vm.zeitraumFreigeben(tag, bis)
        advanceUntilIdle()

        val stand = vm.tagFreigabeState.value
        assertEquals(
            freigabeHinweis(tag, bis, tag, 1, false, gescheitert, Wiederherstellung.KalenderKrank),
            stand.hinweis
        )
        assertNull(stand.hinweisErfolgBis)
        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
    }

    @Test
    fun `Gegenprobe - nicht krank, dann wird wieder gestellt`() = runTest(dispatcher) {
        val alarmUseCase = alarmUseCase(listOf(wecker(7, tag)))
        val ergebnis = TagFreigabeZeitraumResult(
            von = tag, bis = tag, freigegebenBis = null, geloeschteWecker = 0,
            ueberspringenAufgehoben = false, fehler = abbruch(tag)
        )
        val vm = buildViewModel(alarmUseCase, freigabeUseCase(ergebnis, kalender = emptySet()))
        advanceUntilIdle()

        vm.tagFreigeben(tag)
        advanceUntilIdle()

        assertEquals(
            freigabeHinweis(tag, tag, null, 0, false, tag, Wiederherstellung.KlingelnWieGeplant),
            vm.tagFreigabeState.value.hinweis
        )
        verify(alarmUseCase).scheduleSystemAlarm(any())
    }

    private fun alarmUseCase(bestand: List<AlarmInfo>): IAlarmUseCase {
        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(bestand))
        alarmUseCase.stub {
            on { getAllAlarms() } doReturn Result.success(bestand)
            on { scheduleSystemAlarm(any()) } doReturn Result.success(Unit)
        }
        return alarmUseCase
    }

    private fun buildViewModel(alarmUseCase: IAlarmUseCase, tagFreigabeUseCase: TagFreigabeUseCase): AlarmViewModel {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(MutableStateFlow(AlarmSkipState()))
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig(autoAlarmEnabled = true))
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        whenever(alarmPrefs.wecktonAnstieg).thenReturn(flowOf(WecktonAnstieg.AUS))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub { on { pausedNow() } doReturn false }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            tagFreigabeUseCase = tagFreigabeUseCase,
            zeitkettenArmierer = mock(),
            wecktonProbe = mock(),
            alarmRepository = mock<IAlarmRepository>().apply {
                stub {
                    on { isPersistenceBlocked() } doReturn false
                    on { istLetzterSchreibvorgangGescheitert() } doReturn false
                }
            }
        )
    }
}
