package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

/**
 * Haelt das Gate in [ShiftViewModel.updateShiftConfig] fest: synchronisiert wird nur auf einer
 * nachweislich VOLLSTAENDIGEN Eventliste aus dem [CalendarStateHolder].
 *
 * DER FEHLER: Die Stelle las `calendarStateHolder.events.value` und nannte das im Kommentar den
 * "vollstaendigen Soll-Zustand fuer den Delta-Sync". Das stimmte nicht - `CalendarViewModel` legt
 * dort im Normalfall das LAZY-PRAEFIX ab (pro Kalender die ersten 10 Events), und ein
 * ausgefallener Kalender fehlt darin ebenfalls. `AlarmUseCase.syncAlarms()` loescht jeden Alarm,
 * dessen eventId in der uebergebenen Liste fehlt: jede Aenderung an der Schicht-Konfiguration hat
 * damit bei mehr als zehn Terminen in 14 Tagen die spaetesten Wecker entfernt.
 *
 * Der Weg ueber `CalendarViewModel` war bereits gefixt - dieser zweite Weg in dieselbe Loeschlogik
 * blieb offen. Genau die Zwillings-Fehlerklasse, vor der CLAUDE.md warnt.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class ShiftViewModelSyncGateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun event(id: String) = CalendarEvent(
        id = id,
        title = "Schicht $id",
        startTime = LocalDateTime.of(2026, 8, 20, 6, 0),
        endTime = LocalDateTime.of(2026, 8, 20, 14, 0),
        calendarId = "cal-a"
    )

    private fun buildViewModel(
        holder: CalendarStateHolder,
        alarmUseCase: IAlarmUseCase
    ): ShiftViewModel {
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig())
            onBlocking { saveShiftConfig(any()) } doReturn Result.success(Unit)
            onBlocking { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        return ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            calendarStateHolder = holder,
            errorHandler = mock<ErrorHandler>(),
            // Seit dem Muster-Nachzug beim Umbenennen (Pruefrunde 8, Befund 2) Teil des
            // Konstruktors. Fuer diesen Test ohne Belang: die Konfiguration aendert hier keinen
            // Schichtnamen, es wird also nichts nachgezogen.
            dimRuleUseCase = dagger.Lazy { mock<DimRuleUseCase>() },
            hueRuleUseCase = dagger.Lazy { mock<HueRuleUseCase>() },
            dimScheduleUseCase = dagger.Lazy { mock<DimScheduleUseCase>() },
            dndScheduleUseCase = dagger.Lazy { mock<DndScheduleUseCase>() },
            dndPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs>() },
            dimOverlayPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs>() }
        )
    }

    @Test
    fun `unvollstaendige Eventliste loest KEINEN Alarm-Sync aus`() = runTest(dispatcher) {
        val holder = CalendarStateHolder()
        // Genau der Normalfall: das Lazy-Praefix aus CalendarViewModel.
        holder.updateEvents(listOf(event("A"), event("B")), complete = false)
        val alarmUseCase = mock<IAlarmUseCase>()
        val vm = buildViewModel(holder, alarmUseCase)
        advanceUntilIdle()

        vm.updateShiftConfig(ShiftConfig(autoAlarmEnabled = true))
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(any(), any())
    }

    @Test
    fun `vollstaendige Eventliste loest den Alarm-Sync aus`() = runTest(dispatcher) {
        val holder = CalendarStateHolder()
        val events = listOf(event("A"), event("B"))
        holder.updateEvents(events, complete = true)
        val alarmUseCase = mock<IAlarmUseCase>()
        // PFLICHT: der Erfolgszweig liest `alarms.size` - ein ungestubbtes `null` wirft dort eine
        // NPE, die wie ein Fehler im Gate aussieht, obwohl das Gate gerade korrekt durchgelassen hat.
        alarmUseCase.stub {
            onBlocking { syncAlarms(any(), any()) } doReturn Result.success(emptyList())
        }
        val vm = buildViewModel(holder, alarmUseCase)
        advanceUntilIdle()

        vm.updateShiftConfig(ShiftConfig(autoAlarmEnabled = true))
        advanceUntilIdle()

        verify(alarmUseCase).syncAlarms(eq(events), any())
    }

    @Test
    fun `Automatische Alarme AUS raeumt weiterhin, unabhaengig von der Vollstaendigkeit`() = runTest(dispatcher) {
        // Das Gate darf den Pausen-Pfad NICHT blockieren: "Automatische Alarme aus" ist eine
        // ECHTE, sofortige Pause und muss auch dann raeumen, wenn gerade nur ein Ausschnitt
        // geladen ist (er steht im Code bewusst VOR dem Vollstaendigkeits-Gate).
        val holder = CalendarStateHolder()
        holder.updateEvents(listOf(event("A")), complete = false)
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            onBlocking { deleteAllAlarms() } doReturn Result.success(Unit)
        }
        val vm = buildViewModel(holder, alarmUseCase)
        advanceUntilIdle()

        vm.updateShiftConfig(ShiftConfig(autoAlarmEnabled = false))
        advanceUntilIdle()

        verify(alarmUseCase).deleteAllAlarms()
        verify(alarmUseCase, never()).syncAlarms(any(), any())
    }
}
