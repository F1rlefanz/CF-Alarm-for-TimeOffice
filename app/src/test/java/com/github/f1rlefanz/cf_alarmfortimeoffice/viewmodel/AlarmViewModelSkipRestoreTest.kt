package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ManualAlarmSnapshot
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.tagFreigabeUseCaseOhneFreigaben
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Haelt fest, was "Aufheben" fuer einen uebersprungenen MANUELLEN Wecker tut.
 *
 * DER FEHLER: `cancelSkip()` loeschte nur das Flag und stiess den Kalender-Sync an. Ein manueller
 * Wecker entsteht aus keinem Kalender-Event - er kam also nie zurueck, obwohl der Knopf eine
 * Umkehr verspricht. Seitdem sichert das Ueberspringen ihn als vollstaendigen Schnappschuss im
 * Skip-Zustand (geloescht wird er trotzdem, siehe `AlarmSkipManualAlarmTest`), und dieser Pfad
 * baut ihn daraus wieder auf: erst speichern, dann armieren.
 *
 * Die drei Faelle, in denen NICHTS zurueckkommt, muessen sichtbar werden statt still zu
 * verschwinden: verstrichene Weckzeit, unlesbarer Skip-Zustand, fehlgeschlagenes Speichern.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class AlarmViewModelSkipRestoreTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun manualAlarm(id: Int, triggerTime: Long) = AlarmInfo(
        id = id,
        shiftId = "manual_frueh_20260820",
        shiftName = "Früh (Manuell)",
        triggerTime = triggerTime,
        formattedTime = "20.08.2026 05:00"
    )

    private fun calendarAlarm(id: Int, triggerTime: Long) = AlarmInfo(
        id = id,
        shiftId = "shift-frueh",
        shiftName = "Früh",
        triggerTime = triggerTime,
        formattedTime = "20.08.2026 05:00"
    )

    private fun buildViewModel(
        alarmUseCase: IAlarmUseCase,
        skipUseCase: IAlarmSkipUseCase,
        masterPaused: Boolean = false,
        autoAlarmEnabled: Boolean = true
    ): AlarmViewModel {
        val shiftUseCase = mock<IShiftUseCase>()
        // Reaktive Schichtliste: AlarmViewModel sammelt diesen Flow seit Pruefrunde 8 im init{}.
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn
                Result.success(ShiftConfig(autoAlarmEnabled = autoAlarmEnabled))
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        // Pflicht, nicht Kosmetik: AlarmViewModel liest diesen Flow im
        // Property-Initializer. Ohne Stub liefert der Mock null und das Konstruieren
        // scheitert mit einer NPE - noch vor dem ersten Testschritt.
        whenever(alarmPrefs.wecktonAnstieg).thenReturn(flowOf(WecktonAnstieg.AUS))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub {
            on { pausedNow() } doReturn masterPaused
        }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            tagFreigabeUseCase = tagFreigabeUseCaseOhneFreigaben(),
            alarmRepository = mock<IAlarmRepository>().apply {
                stub {
                    on { isPersistenceBlocked() } doReturn false
                    on { istLetzterSchreibvorgangGescheitert() } doReturn false
                }
            }
        )
    }

    private fun alarmUseCaseWith(
        alarms: List<AlarmInfo> = emptyList(),
        saveResult: Result<Unit> = Result.success(Unit),
        scheduleResult: Result<Unit> = Result.success(Unit)
    ): IAlarmUseCase {
        val useCase = mock<IAlarmUseCase>()
        whenever(useCase.activeAlarms).thenReturn(flowOf(alarms))
        useCase.stub {
            on { getAllAlarms() } doReturn Result.success(alarms)
            on { saveAlarm(any()) } doReturn saveResult
            on { scheduleSystemAlarm(any()) } doReturn scheduleResult
            on { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
            on { deleteAlarm(any()) } doReturn Result.success(Unit)
        }
        return useCase
    }

    /**
     * @param snapshot roher Schnappschuss im Skip-Zustand; null = kalenderbasierter Skip.
     * @param statusFails erzwingt einen Lesefehler beim Skip-Zustand.
     */
    private fun skipUseCaseWith(
        skippedAlarmId: Int? = null,
        snapshot: String? = null,
        statusFails: Boolean = false
    ): IAlarmSkipUseCase {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        val state = if (skippedAlarmId == null) {
            AlarmSkipState()
        } else {
            AlarmSkipState(
                isNextAlarmSkipped = true,
                skippedAlarmId = skippedAlarmId,
                skippedAlarmTriggerTime = now + 60 * 60 * 1000L,
                skippedManualAlarm = snapshot
            )
        }
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(state))
        skipUseCase.stub {
            on { getSkipStatus() } doReturn if (statusFails) {
                Result.failure(IllegalStateException("DataStore nicht lesbar"))
            } else {
                Result.success(state)
            }
            on { cancelSkip() } doReturn Result.success(Unit)
        }
        return skipUseCase
    }

    @Test
    fun `cancelSkip - gesicherter manueller Wecker wird erst gespeichert, dann armiert`() = runTest {
        val manual = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(manual)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        var calendarRebuildTriggered = false
        viewModel.cancelSkip { calendarRebuildTriggered = true }
        advanceUntilIdle()

        // Reihenfolge-Invariante: ein armierter Wecker ohne Repository-Eintrag waere weder
        // sichtbar noch abbrechbar.
        val order = inOrder(alarmUseCase)
        order.verify(alarmUseCase).saveAlarm(manual)
        order.verify(alarmUseCase).scheduleSystemAlarm(manual)
        assertTrue("Der kalenderbasierte Wiederaufbau muss erhalten bleiben", calendarRebuildTriggered)
        assertNull(
            "Bei geglueckter Wiederherstellung gibt es nichts zu melden",
            viewModel.skipState.value.restoreNotice
        )
    }

    @Test
    fun `cancelSkip - manueller Wecker mit verstrichener Weckzeit wird NICHT gestellt und gemeldet`() = runTest {
        val expired = manualAlarm(id = 11, triggerTime = now - 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(expired)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
        assertNotNull(
            "Ein nicht wiederherstellbarer Wecker MUSS dem Nutzer gemeldet werden",
            viewModel.skipState.value.restoreNotice
        )
    }

    @Test
    fun `cancelSkip - unlesbarer Skip-Zustand bricht das Aufheben ab, statt den Schnappschuss zu vernichten`() = runTest {
        // Degradationsrichtung bewusst UMGEKEHRT gegenueber der ersten Fassung: cancelSkip()
        // raeumt Flag UND Schnappschuss in einem Zug ab. Wer bei einem Lesefehler trotzdem
        // aufhebt, vernichtet einen manuellen Wecker, den er nicht einmal lesen konnte - und ein
        // Lesefehler ist typischerweise voruebergehend. Andere Wecker kostet das Abbrechen
        // nichts: das Skip-Gate haengt an skippedAlarmId (AlarmUseCase.kt:279) und betrifft nur
        // den einen uebersprungenen Alarm.
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(skippedAlarmId = 11, statusFails = true)
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        var calendarRebuildTriggered = false
        viewModel.cancelSkip { calendarRebuildTriggered = true }
        advanceUntilIdle()

        verify(skipUseCase, never()).cancelSkip()
        assertFalse(
            "Ohne aufgehobenen Skip darf auch kein Kalender-Wiederaufbau angestossen werden",
            calendarRebuildTriggered
        )
        verify(alarmUseCase, never()).saveAlarm(any())
        assertNotNull(
            "Der Nutzer MUSS erfahren, dass sein Tipp nichts bewirkt hat",
            viewModel.skipState.value.restoreNotice
        )
    }

    @Test
    fun `cancelSkip - kaputter Schnappschuss wird gemeldet statt als kein Wecker zu gelten`() = runTest {
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(skippedAlarmId = 11, snapshot = "{kein json")
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        assertNotNull(viewModel.skipState.value.restoreNotice)
    }

    @Test
    fun `cancelSkip - fehlgeschlagenes Speichern armiert nichts und wird gemeldet`() = runTest {
        val manual = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith(
            saveResult = Result.failure(IllegalStateException("DataStore blockiert"))
        )
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(manual)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
        assertNotNull(viewModel.skipState.value.restoreNotice)
    }

    @Test
    fun `cancelSkip - waehrend der Master-Pause wird nichts wiederhergestellt und es wird gesagt`() = runTest {
        // Wiederherstellen heisst anlegen und stellen - genau das verweigert createManualAlarm
        // waehrend der Master-Pause. Ohne dasselbe Gate entstuende hier ein Wecker, den die
        // Pause eigentlich ausschliesst.
        val manual = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(manual)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase, masterPaused = true)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
        assertNotNull(viewModel.skipState.value.restoreNotice)
        // KERN DES FIXES: die Master-Pause ist ein BEHEBBARES Hindernis. Wuerde der Skip trotzdem
        // aufgehoben, faellt der Schnappschuss mit ihm - und der Wecker waere auch nach dem Ende
        // der Pause nicht mehr zurueckzuholen. Also bleibt beides stehen.
        verify(skipUseCase, never()).cancelSkip()
    }

    @Test
    fun `cancelSkip - fehlgeschlagenes Armieren nimmt den gespeicherten Eintrag wieder zurueck`() = runTest {
        // Ein manueller Wecker wird GENAU EINMAL armiert - syncAlarms() re-armiert nur
        // Kalenderalarme, keepManualAlarms schont ihn bloss. Bliebe der Eintrag nach einem
        // gescheiterten Armieren liegen, kuendigte die Statuszeile einen Wecker an, den niemand
        // gestellt hat und den kein spaeterer Lauf repariert.
        val manual = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith(
            scheduleResult = Result.failure(SecurityException("Exact-Alarm-Recht entzogen"))
        )
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(manual)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        // Reihenfolge wie ueberall im Projekt: erst cancelSystemAlarm(), dann deleteAlarm().
        val ordered = inOrder(alarmUseCase)
        ordered.verify(alarmUseCase).saveAlarm(any())
        ordered.verify(alarmUseCase).scheduleSystemAlarm(any())
        ordered.verify(alarmUseCase).cancelSystemAlarm(11)
        ordered.verify(alarmUseCase).deleteAlarm(11)
        assertNotNull(viewModel.skipState.value.restoreNotice)
    }

    @Test
    fun `cancelSkip - ein zweiter manueller Wecker verhindert die Wiederherstellung, ohne den Schnappschuss zu verbrennen`() = runTest {
        // Zwischen Ueberspringen und Aufheben ist der eine Manuell-Platz frei, der Nutzer kann
        // also einen neuen anlegen. Wuerde der gesicherte einfach dazugeschrieben, gaebe es ZWEI:
        // die Karte zeigt nur einen, "Loeschen" trifft nur den gezeigten, und syncAlarms() schont
        // manuelle Alarme - der andere waere ein armierter Wecker ohne Bedienoberflaeche.
        val gesichert = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val inzwischenAngelegt = manualAlarm(id = 22, triggerTime = now + 26 * 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith(alarms = listOf(inzwischenAngelegt))
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(gesichert)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
        verify(skipUseCase, never()).cancelSkip()
        assertNotNull(
            "Der Nutzer muss erfahren, WARUM sein Wecker nicht zurueckkam",
            viewModel.skipState.value.restoreNotice
        )
    }

    @Test
    fun `cancelSkip - bei ausgeschalteten Automatischen Alarmen wird nichts wiederhergestellt`() = runTest {
        // In diesem Zustand raeumt syncAlarms() ALLE Alarme ab, ausdruecklich auch manuelle. Ein
        // hier wiederhergestellter Wecker verschwaende beim naechsten Lauf ohne Rueckmeldung -
        // der schlechteste denkbare Ausgang fuer eine Wecker-App.
        val manual = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(manual)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase, autoAlarmEnabled = false)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        assertNotNull(viewModel.skipState.value.restoreNotice)
    }

    @Test
    fun `cancelSkip - ohne Schnappschuss laeuft alles ueber den Kalender-Wiederaufbau`() = runTest {
        // Ein kalenderbasierter Skip sichert nichts: der Sync des Aufrufers erledigt alles, der
        // manuelle Zweig darf nichts anfassen.
        val other = calendarAlarm(id = 22, triggerTime = now + 2 * 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith(listOf(other))
        val skipUseCase = skipUseCaseWith(skippedAlarmId = 11, snapshot = null)
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        var calendarRebuildTriggered = false
        viewModel.cancelSkip { calendarRebuildTriggered = true }
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
        assertTrue(calendarRebuildTriggered)
        assertNull(viewModel.skipState.value.restoreNotice)
    }

    @Test
    fun `Statuskarte kuendigt einen uebersprungenen Alarm nicht als naechsten Alarm an`() = runTest {
        // Normalerweise ist der uebersprungene Alarm geloescht. Bleibt er ausnahmsweise stehen -
        // `skipNextAlarm()` schluckt einen fehlgeschlagenen `deleteAlarm()` bewusst -, ist er
        // NICHT armiert: als "Nächster Alarm" angekuendigt waere er eine Anzeige, die nicht
        // eintritt.
        val stuck = calendarAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith(listOf(stuck))
        val skipUseCase = skipUseCaseWith(skippedAlarmId = 11)
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        assertNull(
            "Ein uebersprungener Wecker darf nicht als naechster Alarm angekuendigt werden",
            viewModel.uiState.value.nextAlarmTime
        )
    }
}
