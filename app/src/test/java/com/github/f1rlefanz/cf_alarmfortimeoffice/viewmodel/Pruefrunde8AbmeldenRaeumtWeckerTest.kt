package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Pruefrunde 8, Befund 3: "Abmelden laesst alle Wecker armiert zurueck".
 *
 * DER ZUSTAND, DEN DIESE TESTS AUSSCHLIESSEN: `signOut()` verwarf nur Token und Auth-Daten. Die
 * gestellten Wecker blieben im AlarmManager armiert, im Repository und im Direct-Boot-Spiegel
 * stehen; Dimmer und "Nicht stoeren" liefen auf den Schichtspannen des abgemeldeten Kontos
 * weiter. Gleichzeitig zeigt die App nach dem Abmelden ausschliesslich den Anmeldebildschirm -
 * kein Wecker-Tab, keine Master-Pause, kein Schalter "Automatische Alarme". Bis zu 14 Tage lang
 * klingelten also Wecker eines Kontos, das die App nicht mehr kennt, ohne dass der Nutzer sie
 * noch abstellen konnte. Ein Neustart machte es schlimmer: der `BootReceiver` armiert den
 * Bestand aus dem Direct-Boot-Spiegel ungegatet erneut.
 *
 * WARUM DIE TESTS HIER UND NICHT AM AuthUseCase HAENGEN: Das Aufraeumen orchestriert das
 * ViewModel, weil sein Ergebnis den Nutzer erreichen muss, ohne die Bedeutung von
 * `IAuthUseCase.signOut(): Result<Unit>` zu verbiegen (Begruendung im KDoc dort).
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde8AbmeldenRaeumtWeckerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        val viewModel: AuthViewModel,
        val authUseCase: IAuthUseCase,
        val alarmUseCase: IAlarmUseCase,
        val shiftSpanStore: ShiftSpanStore,
        val dimSchedule: DimScheduleUseCase,
        val dndSchedule: DndScheduleUseCase,
        val hueSmartScheduler: HueSmartScheduler,
        val calendarPreAlarmRefreshScheduler: CalendarPreAlarmRefreshScheduler,
        val backgroundServiceManager: BackgroundServiceManager,
        val alarmManager: AlarmManager
    )

    private fun buildFixture(
        abmeldenErgebnis: Result<Unit> = Result.success(Unit),
        weckerLoeschErgebnis: Result<Unit> = Result.success(Unit)
    ): Fixture {
        val authDataStoreRepository = mock<IAuthDataStoreRepository>()
        // Leerer Flow: die drei Beobachter im init{} sollen keine Zustaende einspielen, die die
        // Zusicherungen dieser Tests ueberschreiben.
        whenever(authDataStoreRepository.authData).thenReturn(emptyFlow<AuthData>())

        val calendarSelectionRepository = mock<ICalendarSelectionRepository>()
        whenever(calendarSelectionRepository.selectedCalendarIds)
            .thenReturn(MutableStateFlow(emptySet()))

        val tokenRepository = mock<TokenRepository>()
        whenever(tokenRepository.observe()).thenReturn(emptyFlow())

        val authUseCase = mock<IAuthUseCase>()
        authUseCase.stub { onBlocking { signOut() } doReturn abmeldenErgebnis }

        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub { onBlocking { deleteAllAlarms() } doReturn weckerLoeschErgebnis }

        val credentialAuthManager = mock<CredentialAuthManager>()
        val errorHandler = mock<ErrorHandler>()
        whenever(errorHandler.getErrorMessage(any())).thenReturn("Fehler")
        val backgroundServiceManager = mock<BackgroundServiceManager>()
        val shiftSpanStore = mock<ShiftSpanStore>()
        val dimSchedule = mock<DimScheduleUseCase>()
        // Kein Uri-Sondermock noetig: DndScheduleUseCase.CONDITION_ID ist `by lazy` (siehe
        // MasterPauseUseCaseTest).
        val dndSchedule = mock<DndScheduleUseCase>()
        val hueSmartScheduler = mock<HueSmartScheduler>()
        val calendarPreAlarmRefreshScheduler = mock<CalendarPreAlarmRefreshScheduler>()

        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        // AlarmMaintenanceService.cancelNext() castet das Ergebnis auf AlarmManager - ohne diesen
        // Stub crasht der echte (durchlaufende) Android-Code mit einer ClassCastException.
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)

        val viewModel = AuthViewModel(
            authDataStoreRepository = authDataStoreRepository,
            credentialAuthManager = credentialAuthManager,
            errorHandler = errorHandler,
            authUseCase = authUseCase,
            calendarSelectionRepository = calendarSelectionRepository,
            backgroundServiceManager = backgroundServiceManager,
            tokenRepository = tokenRepository,
            alarmUseCase = alarmUseCase,
            shiftSpanStore = shiftSpanStore,
            dimSchedule = dimSchedule,
            dndSchedule = dndSchedule,
            hueSmartScheduler = hueSmartScheduler,
            calendarPreAlarmRefreshScheduler = calendarPreAlarmRefreshScheduler,
            appContext = context
        )

        return Fixture(
            viewModel = viewModel,
            authUseCase = authUseCase,
            alarmUseCase = alarmUseCase,
            shiftSpanStore = shiftSpanStore,
            dimSchedule = dimSchedule,
            dndSchedule = dndSchedule,
            hueSmartScheduler = hueSmartScheduler,
            calendarPreAlarmRefreshScheduler = calendarPreAlarmRefreshScheduler,
            backgroundServiceManager = backgroundServiceManager,
            alarmManager = alarmManager
        )
    }

    @Test
    fun `abmelden bricht die Wecker ab, loescht sie und stoppt jede Hintergrundkette`() = runTest {
        val f = buildFixture()

        f.viewModel.signOut()
        advanceUntilIdle()

        // Der zentrale Weg: deleteAllAlarms() cancelt ueber clearInternalAlarms() ERST die
        // System-Alarme (und schwebende Snoozes) und loescht DANACH den Bestand - inklusive
        // Direct-Boot-Spiegel, sonst armiert der BootReceiver alles wieder.
        verify(f.alarmUseCase, times(1)).deleteAllAlarms()
        // Dimmer und "Nicht stoeren" ziehen ihre Fenster aus dem ShiftSpanStore, nicht aus dem
        // Alarm-Bestand - ohne dieses Leeren dimmen sie fuer das abgemeldete Konto weiter.
        verify(f.shiftSpanStore, times(1)).replaceAll(any(), any())
        verify(f.dimSchedule, times(1)).disable()
        verify(f.dndSchedule, times(1)).disable()
        verify(f.hueSmartScheduler, times(1)).cleanup()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).cancelAll()
        // Stellvertretend fuer AlarmMaintenanceService.cancelNext(context): der regulaere 6h-Slot
        // UND der Wiederanlauf-Wachhund, der die Kette sonst aus sich heraus neu aufzieht.
        verify(f.alarmManager, times(2)).cancel(anyOrNull<PendingIntent>())

        // Und das Abmelden selbst ist trotzdem passiert.
        verify(f.authUseCase, times(1)).signOut()
        assertFalse("nach dem Abmelden darf niemand mehr angemeldet sein", f.viewModel.authState.value.isSignedIn)
        assertNull("ohne Fehler beim Aufraeumen gibt es keinen Hinweis", f.viewModel.authState.value.error)
    }

    @Test
    fun `abmelden raeumt VOR dem Verwerfen der Anmeldedaten auf`() = runTest {
        val f = buildFixture()

        f.viewModel.signOut()
        advanceUntilIdle()

        // Die Reihenfolge ist tragend: nach dem Verwerfen zeigt die App nur noch den
        // Anmeldebildschirm - ein erst dann gescheitertes Aufraeumen waere von keiner
        // Oberflaeche mehr aus nachholbar.
        inOrder(f.alarmUseCase, f.authUseCase) {
            verify(f.alarmUseCase).deleteAllAlarms()
            verify(f.authUseCase).signOut()
        }
    }

    @Test
    fun `ein gescheitertes Aufraeumen meldet sich beim Nutzer, haelt das Abmelden aber nicht auf`() = runTest {
        val f = buildFixture(weckerLoeschErgebnis = Result.failure(IOException("Bestand nicht lesbar")))

        f.viewModel.signOut()
        advanceUntilIdle()

        // Abgemeldet wird trotzdem - wer auf "Abmelden" tippt, darf nicht angemeldet bleiben.
        verify(f.authUseCase, times(1)).signOut()
        assertFalse(f.viewModel.authState.value.isSignedIn)
        // Aber der Nutzer MUSS erfahren, dass moeglicherweise noch Wecker gestellt sind. Der Text
        // erscheint auf dem Anmeldebildschirm, also genau dort, wo er danach landet.
        assertEquals(
            AuthViewModel.FEHLER_ABMELDEN_WECKER_GEBLIEBEN,
            f.viewModel.authState.value.error
        )
    }

    @Test
    fun `ein gescheiterter Schritt stoppt die nachfolgenden nicht`() = runTest {
        val f = buildFixture()
        whenever(f.shiftSpanStore.replaceAll(any(), any())).thenThrow(RuntimeException("boom"))

        f.viewModel.signOut()
        advanceUntilIdle()

        verify(f.dimSchedule, times(1)).disable()
        verify(f.dndSchedule, times(1)).disable()
        verify(f.hueSmartScheduler, times(1)).cleanup()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).cancelAll()
        assertEquals(
            AuthViewModel.FEHLER_ABMELDEN_WECKER_GEBLIEBEN,
            f.viewModel.authState.value.error
        )
    }

    @Test
    fun `das Aufraeumen laeuft auch bei abgeraeumtem ViewModel zu Ende`() = runTest {
        val f = buildFixture()
        // Der Nutzer verlaesst die App unmittelbar nach dem Antippen von "Abmelden" - genau das
        // macht onCleared() mit dem viewModelScope. Ohne withContext(NonCancellable) braeche die
        // Sequenz hier ab, und zurueck bliebe der halb geraeumte Zustand: Wecker geloescht,
        // Dimmer/DND/Hue/Wartung aber weiter aktiv - oder umgekehrt.
        f.alarmUseCase.stub {
            onBlocking { deleteAllAlarms() } doSuspendableAnswer {
                f.viewModel.viewModelScope.cancel()
                Result.success(Unit)
            }
        }
        // Echter Suspensionspunkt NACH dem Abbruch: nur daran kann sich eine Cancellation
        // ueberhaupt bemerkbar machen (gemockte suspend-Aufrufe suspendieren nicht).
        f.shiftSpanStore.stub {
            onBlocking { replaceAll(any(), any()) } doSuspendableAnswer {
                delay(1)
                Unit
            }
        }

        f.viewModel.signOut()
        advanceUntilIdle()

        verify(f.dimSchedule, times(1)).disable()
        verify(f.dndSchedule, times(1)).disable()
        verify(f.hueSmartScheduler, times(1)).cleanup()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).cancelAll()
    }

    @Test
    fun `scheitert das Abmelden selbst, faehrt die gestoppte Automatik wieder hoch`() = runTest {
        val f = buildFixture(abmeldenErgebnis = Result.failure(IOException("Auth-Store kaputt")))

        f.viewModel.signOut()
        advanceUntilIdle()

        // Der Nutzer BLEIBT angemeldet - dann darf er nicht ohne Wecker und ohne die Kette
        // dastehen, die sie neu anlegt. Der Wartungslauf plant sich selbst wieder ein und baut
        // die Wecker aus dem Kalender neu auf.
        verify(f.backgroundServiceManager, times(1)).initializeMaintenanceService()
        verify(f.dimSchedule, times(1)).enable()
        verify(f.dndSchedule, times(1)).enable()
        verify(f.hueSmartScheduler, times(1)).initializeSmartScheduling()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).reschedule()
    }
}
