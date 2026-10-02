package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.app.Activity
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.AnmeldeWiederherstellung
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #55, Zero-Tap-Wiederherstellung im [AuthViewModel]: die Zusicherungen aus
 * ENTSCHEIDUNGEN.md, ohne Play-Dienste (die Plattform steckt hinter [AnmeldeWiederherstellung]).
 *
 * - Treffer: dieselbe Kette wie nach "Mit Google anmelden" (Auth-Daten, Kalender-Autorisierung,
 *   neuer eigener Schluessel samt Merker).
 * - Kein Schluessel, Fehler, Zeitablauf: der normale Anmeldebildschirm - der Login wird NIE
 *   blockiert, auch nicht von einer Plattform, die ihren "wirft nie"-Vertrag bricht.
 * - Abmelden loescht den Schluessel in BEIDEN Zweigen, wirft dabei nie, und kommt auch dann
 *   NACH einem gleichzeitig laufenden Anlegen (sonst bliebe ein verwaister Schluessel, der nach
 *   einer Neuinstallation still wieder anmeldet).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelWiederherstellungTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Auth-Daten im Speicher - `updateAuthData` wirkt wie im echten Store auf spaetere Reads. */
    private class FakeAuthDaten(
        var gespeichert: AuthData = AuthData(isLoggedIn = false),
        var merkerAngelegt: Result<Boolean> = Result.success(false)
    ) : IAuthDataStoreRepository {
        val geschrieben = mutableListOf<AuthData>()
        var merkerGeschrieben = 0

        // Leer: die Beobachter im init{} sollen nichts einspielen, was die Zusicherungen hier
        // ueberschreibt.
        override val authData: Flow<AuthData> = emptyFlow()
        override suspend fun updateAuthData(authData: AuthData): Result<Unit> {
            geschrieben += authData
            gespeichert = authData
            return Result.success(Unit)
        }
        override suspend fun clearAuthData(): Result<Unit> {
            gespeichert = AuthData()
            return Result.success(Unit)
        }
        override suspend fun isAuthenticated(): Result<Boolean> = Result.success(gespeichert.isLoggedIn)
        override suspend fun getCurrentAuthData(): Result<AuthData> = Result.success(gespeichert)
        override suspend fun istWiederherstellungsSchluesselAngelegt(): Result<Boolean> = merkerAngelegt
        override suspend fun merkeWiederherstellungsSchluesselAngelegt(): Result<Unit> {
            merkerGeschrieben++
            return Result.success(Unit)
        }
    }

    /** Plattform-Attrappe: protokolliert die Reihenfolge der Aufrufe. */
    private class FakeWiederherstellung(
        var gefunden: String? = null,
        var leseDauerMs: Long = 0,
        var lesenWirft: Boolean = false,
        var anlegeDauerMs: Long = 0,
        var loeschenWirft: Boolean = false
    ) : AnmeldeWiederherstellung {
        val ereignisse = mutableListOf<String>()
        var leseVersuche = 0

        override suspend fun anlegen(activityContext: Context, email: String): Boolean {
            ereignisse += "anlegen-start:$email"
            delay(anlegeDauerMs)
            ereignisse += "anlegen-ende:$email"
            return true
        }

        override suspend fun lesen(activityContext: Context): String? {
            leseVersuche++
            delay(leseDauerMs)
            if (lesenWirft) throw IllegalStateException("Vertrag gebrochen")
            return gefunden
        }

        override suspend fun loeschen() {
            ereignisse += "loeschen"
            if (loeschenWirft) throw IllegalStateException("Vertrag gebrochen")
        }
    }

    private fun authUseCase(): IAuthUseCase = mock<IAuthUseCase>().apply {
        stub {
            onBlocking { requestCalendarAuthorization(anyOrNull()) } doReturn Result.success(true)
            onBlocking { signOut() } doReturn Result.success(Unit)
            onBlocking { hasCalendarAuthorization() } doReturn Result.success(false)
        }
    }

    private fun baue(
        daten: FakeAuthDaten,
        plattform: FakeWiederherstellung,
        authUseCase: IAuthUseCase = authUseCase()
    ): AuthViewModel {
        val calendarSelectionRepository = mock<ICalendarSelectionRepository>()
        whenever(calendarSelectionRepository.selectedCalendarIds).thenReturn(MutableStateFlow(emptySet()))
        val tokenRepository = mock<TokenRepository>()
        whenever(tokenRepository.observe()).thenReturn(emptyFlow())
        val errorHandler = mock<ErrorHandler>()
        whenever(errorHandler.getErrorMessage(any())).thenReturn("Fehler")

        return AuthViewModel(
            authDataStoreRepository = daten,
            credentialAuthManager = mock<CredentialAuthManager>(),
            errorHandler = errorHandler,
            authUseCase = authUseCase,
            calendarSelectionRepository = calendarSelectionRepository,
            backgroundServiceManager = mock<BackgroundServiceManager>(),
            tokenRepository = tokenRepository,
            alarmUseCase = mock<IAlarmUseCase>(),
            shiftSpanStore = mock<ShiftSpanStore>(),
            dimSchedule = mock<DimScheduleUseCase>(),
            dndSchedule = mock<DndScheduleUseCase>(),
            hueSmartScheduler = mock<HueSmartScheduler>(),
            calendarPreAlarmRefreshScheduler = mock<CalendarPreAlarmRefreshScheduler>(),
            appContext = mock<Context>(),
            anmeldeWiederherstellung = plattform
        )
    }

    @Test
    fun `Treffer meldet an, holt die Kalender-Autorisierung und legt einen eigenen Schluessel an`() = runTest(dispatcher) {
        val daten = FakeAuthDaten()
        val plattform = FakeWiederherstellung(gefunden = "alt@example.org")
        val authUseCase = authUseCase()
        val vm = baue(daten, plattform, authUseCase)

        vm.starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()

        assertEquals(AuthData(isLoggedIn = true, email = "alt@example.org"), daten.geschrieben.single())
        assertTrue(vm.authState.value.isSignedIn)
        assertEquals("alt@example.org", vm.authState.value.userEmail)
        assertFalse(vm.authState.value.wiederherstellungLaeuft)
        verify(authUseCase).requestCalendarAuthorization("alt@example.org")
        assertEquals(listOf("anlegen-start:alt@example.org", "anlegen-ende:alt@example.org"), plattform.ereignisse)
        assertEquals(1, daten.merkerGeschrieben)
    }

    @Test
    fun `kein Schluessel - normaler Anmeldebildschirm, nichts geschrieben`() = runTest(dispatcher) {
        val daten = FakeAuthDaten()
        val plattform = FakeWiederherstellung(gefunden = null)
        val authUseCase = authUseCase()
        val vm = baue(daten, plattform, authUseCase)

        vm.starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()

        assertTrue(daten.geschrieben.isEmpty())
        assertFalse(vm.authState.value.isSignedIn)
        assertFalse(vm.authState.value.wiederherstellungLaeuft)
        verify(authUseCase, never()).requestCalendarAuthorization(anyOrNull())
        assertTrue(plattform.ereignisse.isEmpty())
    }

    @Test
    fun `waehrend der Suche steht der Ladezustand, nach dem Deckel von 5 s der Login`() = runTest(dispatcher) {
        val daten = FakeAuthDaten()
        // Wuerde nach einer Minute doch noch etwas liefern - zu spaet.
        val plattform = FakeWiederherstellung(gefunden = "alt@example.org", leseDauerMs = 60_000)
        val vm = baue(daten, plattform)

        vm.starteAnmeldeWiederherstellung(mock<Activity>())
        runCurrent()
        assertTrue("Ladezustand waehrend der Suche", vm.authState.value.wiederherstellungLaeuft)

        advanceTimeBy(AuthViewModel.LESEN_DECKEL_MS + 1)
        runCurrent()
        assertFalse("nach dem Deckel zurueck zum Login", vm.authState.value.wiederherstellungLaeuft)
        assertFalse(vm.authState.value.isSignedIn)

        advanceUntilIdle()
        assertTrue("ein verspaeteter Treffer meldet NICHT nachtraeglich an", daten.geschrieben.isEmpty())
    }

    @Test
    fun `wirft die Plattform trotz Vertrag, bleibt es beim normalen Login`() = runTest(dispatcher) {
        val daten = FakeAuthDaten()
        val vm = baue(daten, FakeWiederherstellung(lesenWirft = true))

        vm.starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()

        assertFalse(vm.authState.value.isSignedIn)
        assertFalse(vm.authState.value.wiederherstellungLaeuft)
        assertTrue(daten.geschrieben.isEmpty())
    }

    @Test
    fun `die Suche laeuft je ViewModel nur einmal, auch nach einer Drehung`() = runTest(dispatcher) {
        val plattform = FakeWiederherstellung()
        val vm = baue(FakeAuthDaten(), plattform)

        vm.starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()
        vm.starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()

        assertEquals(1, plattform.leseVersuche)
    }

    @Test
    fun `Bestandsnutzer ohne Merker bekommt einmal einen Schluessel, mit Merker nicht`() = runTest(dispatcher) {
        val ohne = FakeAuthDaten(AuthData(isLoggedIn = true, email = "da@example.org"), Result.success(false))
        val plattformOhne = FakeWiederherstellung()
        baue(ohne, plattformOhne).starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()
        assertEquals(listOf("anlegen-start:da@example.org", "anlegen-ende:da@example.org"), plattformOhne.ereignisse)
        assertEquals(1, ohne.merkerGeschrieben)
        assertEquals("angemeldet wird nichts gelesen", 0, plattformOhne.leseVersuche)

        val mit = FakeAuthDaten(AuthData(isLoggedIn = true, email = "da@example.org"), Result.success(true))
        val plattformMit = FakeWiederherstellung()
        baue(mit, plattformMit).starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()
        assertTrue(plattformMit.ereignisse.isEmpty())
    }

    @Test
    fun `unlesbarer Merker legt nichts an - aus unlesbar wird kein fehlt`() = runTest(dispatcher) {
        val daten = FakeAuthDaten(
            AuthData(isLoggedIn = true, email = "da@example.org"),
            Result.failure(IllegalStateException("auth_prefs unlesbar"))
        )
        val plattform = FakeWiederherstellung()
        baue(daten, plattform).starteAnmeldeWiederherstellung(mock<Activity>())
        advanceUntilIdle()

        assertTrue(plattform.ereignisse.isEmpty())
    }

    @Test
    fun `Abmelden loescht den Schluessel`() = runTest(dispatcher) {
        val daten = FakeAuthDaten(AuthData(isLoggedIn = true, email = "da@example.org"))
        val plattform = FakeWiederherstellung()
        val vm = baue(daten, plattform)

        vm.signOut()
        advanceUntilIdle()

        assertEquals(listOf("loeschen"), plattform.ereignisse)
        assertFalse(vm.authState.value.isSignedIn)
    }

    @Test
    fun `Abmelden loescht den Schluessel auch im halben Zweig`() = runTest(dispatcher) {
        val daten = FakeAuthDaten(AuthData(isLoggedIn = true, email = "da@example.org"))
        val plattform = FakeWiederherstellung()
        val authUseCase = authUseCase().apply {
            stub { onBlocking { signOut() } doReturn Result.failure(IllegalStateException("clearAuthData")) }
        }
        val vm = baue(daten, plattform, authUseCase)

        vm.signOut()
        advanceUntilIdle()

        assertEquals(listOf("loeschen"), plattform.ereignisse)
        // Der halbe Zweig selbst laeuft unveraendert weiter (welcher der beiden Texte, haengt am
        // Aufraeumen, das hier nicht betrachtet wird).
        assertTrue(
            vm.authState.value.error in setOf(
                AuthViewModel.FEHLER_ABMELDEN_UNVOLLSTAENDIG,
                AuthViewModel.FEHLER_ABMELDEN_UNVOLLSTAENDIG_WECKER_GEBLIEBEN
            )
        )
    }

    @Test
    fun `wirft das Loeschen, wird trotzdem abgemeldet`() = runTest(dispatcher) {
        val daten = FakeAuthDaten(AuthData(isLoggedIn = true, email = "da@example.org"))
        val plattform = FakeWiederherstellung(loeschenWirft = true)
        val authUseCase = authUseCase()
        val vm = baue(daten, plattform, authUseCase)

        vm.signOut()
        advanceUntilIdle()

        verify(authUseCase).signOut()
        assertEquals(listOf("loeschen"), plattform.ereignisse)
        assertFalse(vm.authState.value.isSignedIn)
        assertFalse(vm.authState.value.calendarOps.calendarsLoading)
    }

    @Test
    fun `Abmelden waehrend des Anlegens loescht DANACH - kein verwaister Schluessel`() = runTest(dispatcher) {
        val daten = FakeAuthDaten(AuthData(isLoggedIn = true, email = "da@example.org"), Result.success(false))
        val plattform = FakeWiederherstellung(anlegeDauerMs = 3_000)
        val vm = baue(daten, plattform)

        vm.starteAnmeldeWiederherstellung(mock<Activity>())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("anlegen-start:da@example.org"), plattform.ereignisse)

        vm.signOut()
        advanceUntilIdle()

        assertEquals(
            listOf("anlegen-start:da@example.org", "anlegen-ende:da@example.org", "loeschen"),
            plattform.ereignisse
        )
    }
}
