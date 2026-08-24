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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
 * DIE REIHENFOLGE IST DER EIGENTLICHE GEGENSTAND DIESER TESTS - und sie ist einmal umgedreht
 * worden. Die erste Fassung raeumte VOR dem Abmelden und erfand damit einen Zustand, den es
 * sonst nirgends gibt: "angemeldet, aber saemtliche Wecker geloescht". Ihn wieder aufzuloesen
 * verlangte einen Rueckbau, und der scheiterte in drei aufeinanderfolgenden Reviews an je einer
 * neuen Luecke - der manuelle Wecker steht in keiner Terminliste und kam nie zurueck, der
 * ShiftSpanStore blieb leer (Dimmer und DND liefen ohne Dienstzeiten weiter), und der Knopf
 * "Erneut abmelden" auf der Warnkarte loeschte die Warnung selbst. Jetzt gilt: erst abmelden,
 * dann raeumen - und damit entfallen Rueckbau, Verlustpruefung, Merker und Warnkarte.
 *
 * DER PUNKT OHNE WIEDERKEHR IST DAS VERWERFEN DES TOKENS (Welle 5, Befunde A und B). Die erste
 * Fassung dieser Datei behauptete hier noch, ein gescheitertes Abmelden habe NICHTS angefasst.
 * Das stimmte nie: `AuthUseCase.signOut()` verwirft ZUERST das Kalender-Token und loescht erst
 * danach die Auth-Daten - nur Letzteres kann scheitern. Zwei Tests halten die Folgerungen fest:
 * geraeumt wird in BEIDEN Zweigen, und der ganze Block (Abmelden UND Aufraeumen) laeuft
 * unabbrechbar, weil das Token-Verwerfen ueber `GoogleAuthUtil.clearToken()` einen Netzaufruf
 * enthaelt, in dessen Timeout-Fenster der Nutzer die App typischerweise verlaesst.
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
        weckerLoeschErgebnis: Result<Unit> = Result.success(Unit),
        /** Der einzige Wurf VOR dem Punkt ohne Wiederkehr - danach wurde wirklich nichts angefasst. */
        signOutLocallyWirft: Boolean = false
    ): Fixture {
        val authDataStoreRepository = mock<IAuthDataStoreRepository>()
        // Leerer Flow: die Beobachter im init{} sollen keine Zustaende einspielen, die die
        // Zusicherungen dieser Tests ueberschreiben.
        whenever(authDataStoreRepository.authData).thenReturn(emptyFlow<AuthData>())

        val calendarSelectionRepository = mock<ICalendarSelectionRepository>()
        whenever(calendarSelectionRepository.selectedCalendarIds)
            .thenReturn(MutableStateFlow(emptySet()))

        val tokenRepository = mock<TokenRepository>()
        whenever(tokenRepository.observe()).thenReturn(emptyFlow())

        val authUseCase = mock<IAuthUseCase>()
        authUseCase.stub { on { signOut() } doReturn abmeldenErgebnis }

        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub { on { deleteAllAlarms() } doReturn weckerLoeschErgebnis }

        val credentialAuthManager = mock<CredentialAuthManager>()
        if (signOutLocallyWirft) {
            // doThrow-Form und nicht whenever(...): signOutLocally() liefert Unit und damit im
            // Bytecode void - darauf laesst sich kein when() setzen.
            doThrow(RuntimeException("boom")).whenever(credentialAuthManager).signOutLocally()
        }
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
        assertFalse(
            "nach dem Abmelden darf niemand mehr angemeldet sein",
            f.viewModel.authState.value.isSignedIn
        )
        assertNull(
            "ohne Fehler beim Aufraeumen gibt es keinen Hinweis",
            f.viewModel.authState.value.error
        )
    }

    @Test
    fun `abmelden verwirft ZUERST die Anmeldung und raeumt erst danach auf`() = runTest {
        val f = buildFixture()

        f.viewModel.signOut()
        advanceUntilIdle()

        // WIDERLEGTE ANNAHME (die Reihenfolge dieses Tests war einmal genau umgekehrt): Die
        // erste Fassung raeumte VOR dem Abmelden, um armierte Wecker ohne Bedienoberflaeche zu
        // vermeiden. Das erkaufte sich den Zustand "angemeldet, aber alle Wecker geloescht" -
        // einen Zustand, den die App vollstaendig selbst wieder aufloesen muss und an dem drei
        // Reviews hintereinander je eine neue Luecke gefunden haben (manueller Wecker, leerer
        // ShiftSpanStore, sich selbst loeschende Warnkarte). Diese Richtung kennt ihn nicht:
        // scheitert das Abmelden, ist nichts geraeumt.
        inOrder(f.authUseCase, f.alarmUseCase) {
            verify(f.authUseCase).signOut()
            verify(f.alarmUseCase).deleteAllAlarms()
        }
    }

    @Test
    fun `scheitert das Abmelden, wird trotzdem geraeumt - das Token ist dann schon weg`() = runTest {
        val f = buildFixture(abmeldenErgebnis = Result.failure(IOException("Auth-Store kaputt")))

        f.viewModel.signOut()
        advanceUntilIdle()

        // WIDERLEGTE ANNAHME (Pruefrunde 8 / Welle 5, Befund B): Dieser Test verlangte frueher
        // das Gegenteil - "scheitert das Abmelden, wurde NICHTS angefasst". Das stimmte nie.
        // `AuthUseCase.signOut()` hat genau eine Fehlerquelle, `clearAuthData()`, und die liegt
        // NACH `oauth2TokenManager.invalidate()`. Ein Failure heisst also nicht "nichts
        // passiert", sondern "Token weg, Auth-Daten noch da": der Nutzer gilt weiter als
        // angemeldet, kommt aber an keinen Kalender mehr, die 6h-Wartung faellt in ihre
        // fail-safe-Zweige, und fuer neue Schichten entstehen keine Wecker. Ihn mit einem vollen
        // Weckbestand fuer ein totes Konto stehen zu lassen waere die schlechtere Haelfte.
        verify(f.alarmUseCase, times(1)).deleteAllAlarms()
        verify(f.shiftSpanStore, times(1)).replaceAll(any(), any())
        verify(f.dimSchedule, times(1)).disable()
        verify(f.dndSchedule, times(1)).disable()
        verify(f.hueSmartScheduler, times(1)).cleanup()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).cancelAll()

        // Kein Rueckbau: die Ketten bleiben gestoppt, bis sich der Nutzer neu anmeldet. Genau
        // der Rueckbau war es, an dem die umgekehrte Reihenfolge dreimal gescheitert ist.
        verify(f.backgroundServiceManager, never()).initializeMaintenanceService()
        verify(f.dimSchedule, never()).enable()
        verify(f.dndSchedule, never()).enable()

        // Und der Nutzer erfaehrt, dass er nur halb abgemeldet ist - nicht die generische
        // Fehlermeldung, die ihn als "angemeldet und alles in Ordnung" zuruecklassen wuerde.
        assertEquals(
            AuthViewModel.FEHLER_ABMELDEN_UNVOLLSTAENDIG,
            f.viewModel.authState.value.error
        )
    }

    @Test
    fun `ein Abbruch WAEHREND des Abmeldens raeumt trotzdem zu Ende`() = runTest {
        // BEFUND A (Pruefrunde 8 / Welle 5): Der Punkt ohne Wiederkehr ist das Verwerfen des
        // Tokens, nicht das Ende von `authUseCase.signOut()`. `invalidate()` ruft
        // GoogleAuthUtil.clearToken() - einen Netzaufruf, der ohne Netz bis zum Timeout haengt.
        // Solange die Sperre erst um `stopScheduledWorkForSignOut()` lag, war die Coroutine in
        // genau diesem Fenster voll abbrechbar; wischt der Nutzer die App dort weg (das
        // wahrscheinlichste Verhalten direkt nach "Abmelden"), war das Token verworfen und kein
        // einziger Wecker abgeraeumt - Befund 3 ueber den Abbruchweg.
        val f = buildFixture()
        f.authUseCase.stub {
            on { signOut() } doSuspendableAnswer {
                f.viewModel.viewModelScope.cancel()
                // Echter Suspensionspunkt NACH dem Abbruch, stellvertretend fuer den
                // Netzaufruf: nur daran kann sich eine Cancellation bemerkbar machen
                // (gemockte suspend-Aufrufe suspendieren nicht).
                delay(1)
                Result.success(Unit)
            }
        }

        f.viewModel.signOut()
        advanceUntilIdle()

        verify(f.alarmUseCase, times(1)).deleteAllAlarms()
        verify(f.shiftSpanStore, times(1)).replaceAll(any(), any())
        verify(f.dimSchedule, times(1)).disable()
        verify(f.dndSchedule, times(1)).disable()
        verify(f.hueSmartScheduler, times(1)).cleanup()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).cancelAll()
    }

    @Test
    fun `scheitern Abmelden UND Aufraeumen, nennt der Text beides`() = runTest {
        val f = buildFixture(
            abmeldenErgebnis = Result.failure(IOException("Auth-Store kaputt")),
            weckerLoeschErgebnis = Result.failure(IOException("Bestand nicht lesbar"))
        )

        f.viewModel.signOut()
        advanceUntilIdle()

        // Der doppelte Fehlschlag ist der einzige Fall, in dem Wecker zurueckbleiben KOENNEN,
        // waehrend der Nutzer noch als angemeldet gilt. Er ist damit nicht ohne Oberflaeche -
        // deshalb verweist der Text auf den Schalter, der in dieser Lage erreichbar ist.
        assertEquals(
            AuthViewModel.FEHLER_ABMELDEN_UNVOLLSTAENDIG_WECKER_GEBLIEBEN,
            f.viewModel.authState.value.error
        )
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
            on { deleteAllAlarms() } doSuspendableAnswer {
                f.viewModel.viewModelScope.cancel()
                Result.success(Unit)
            }
        }
        // Echter Suspensionspunkt NACH dem Abbruch: nur daran kann sich eine Cancellation
        // ueberhaupt bemerkbar machen (gemockte suspend-Aufrufe suspendieren nicht).
        f.shiftSpanStore.stub {
            on { replaceAll(any(), any()) } doSuspendableAnswer {
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
    fun `der Hinweistext nennt die Folge und einen Ausweg`() = runTest {
        val text = AuthViewModel.FEHLER_ABMELDEN_WECKER_GEBLIEBEN

        // Die verbleibende Fehlerklasse der gewaehlten Reihenfolge: abgemeldet, aber es koennen
        // Wecker stehengeblieben sein. Sie darf nicht stumm sein - und der genannte Ausweg muss
        // in dieser App wirklich existieren (der Schalter in den Einstellungen raeumt ueber
        // dieselbe zentrale Operation).
        assertTrue("Die Folge fuer die Wecker fehlt", text.contains("klingeln"))
        assertTrue(
            "Ohne Ausweg ist die Meldung eine Sackgasse",
            text.contains("Hintergrunddienste pausieren")
        )
    }

    @Test
    fun `die Texte der halben Abmeldung nennen die Folge und einen erreichbaren Ausweg`() = runTest {
        // Halbe Abmeldung, Wecker geraeumt: Der Nutzer sieht sich als angemeldet, hat aber
        // keinen Kalender-Zugriff mehr. Der Text darf das nicht verschweigen - "angemeldet und
        // alles in Ordnung" ist genau die Luege, die Befund B beschreibt.
        val halb = AuthViewModel.FEHLER_ABMELDEN_UNVOLLSTAENDIG
        assertTrue(
            "Der entzogene Kalender-Zugriff fehlt",
            halb.contains("Kalender-Zugriff")
        )
        assertTrue(
            // KORRIGIERT (Welle 6, Befund B): Frueher stand hier "Abmelden" - der Knopf aus den
            // Einstellungen. Seit dieser Zweig `hasValidToken = false` setzt, landet der Nutzer
            // auf dem Kalender-Autorisierungsbildschirm, und dort heisst der Knopf, der genau
            // dasselbe tut, "Mit anderem Konto anmelden". Die Einstellungen sind von dort NICHT
            // erreichbar - der alte Text waere ab da eine Sackgasse.
            "Ohne Ausweg ist die Meldung eine Sackgasse - hier: die Abmeldung abschliessen",
            halb.contains("Mit anderem Konto anmelden")
        )
        assertTrue(
            "Der einzige unwiederbringliche Verlust ist der von Hand gestellte Wecker",
            halb.contains("von Hand gestellten")
        )

        // Doppelter Fehlschlag: hier koennen Wecker zurueckbleiben. Der Ausweg ist derselbe
        // Knopf und leistet beides auf einmal - ein zweiter signOut() schliesst die Abmeldung ab
        // UND laesst das Aufraeumen noch einmal laufen. Der frueher hier genannte Schalter
        // "Hintergrunddienste pausieren" liegt in den Einstellungen und ist vom
        // Autorisierungsbildschirm aus nicht erreichbar.
        val doppelt = AuthViewModel.FEHLER_ABMELDEN_UNVOLLSTAENDIG_WECKER_GEBLIEBEN
        assertTrue("Die Folge fuer die Wecker fehlt", doppelt.contains("klingeln"))
        assertTrue(
            "Ohne Ausweg ist die Meldung eine Sackgasse",
            doppelt.contains("Mit anderem Konto anmelden")
        )
    }

    /**
     * DER EINZIGE WURF VOR DEM PUNKT OHNE WIEDERKEHR: `signOutLocally()`. Danach ist wirklich
     * nichts angefasst - weder das Kalender-Token noch die Auth-Daten -, also darf hier auch
     * nicht geraeumt werden. Sonst stuende ein weiterhin angemeldeter Nutzer ohne Wecker da.
     */
    @Test
    fun `wirft schon signOutLocally, wird weder abgemeldet noch geraeumt`() = runTest {
        val f = buildFixture(signOutLocallyWirft = true)

        f.viewModel.signOut()
        advanceUntilIdle()

        verify(f.authUseCase, never()).signOut()
        verify(f.alarmUseCase, never()).deleteAllAlarms()
    }

    // ---- WELLE 6, BEFUND B: Oberflaeche und Weckbestand muessen zusammenpassen ----------------

    /**
     * DER ZUSTAND, DEN DIESER TEST AUSSCHLIESST: `authUseCase.signOut()` scheitert (nur
     * `clearAuthData()` kann werfen, das Token ist da schon verworfen). Der Nutzer gilt weiter
     * als angemeldet - aber `stopScheduledWorkForSignOut()` ist trotzdem gelaufen: alle Wecker
     * weg, Schichtspannen leer, 6h-Kette abbestellt. Neu angestossen wird die Kette nur bei
     * Anmeldung, Boot oder Master-Pause-resume, NICHT beim naechsten App-Start.
     *
     * Ohne diese Korrektur blieb `hasValidToken` auf `true`, und die `MainActivity` zeigte die
     * normale Haupt-Oberflaeche: eine App, die normal aussieht und nie wieder einen Wecker
     * stellt. Mit `hasValidToken = false` fuehrt dasselbe Gate auf den
     * Kalender-Autorisierungsbildschirm - was schlicht die Wahrheit ist, denn das Token IST weg -
     * und der bietet beide Auswege an: neu autorisieren (das startet ueber
     * `requestCalendarAuthorization()` auch die Wartung wieder) oder die Abmeldung abschliessen.
     */
    @Test
    fun `scheitert das Abmelden, verlangt die Oberflaeche eine neue Autorisierung`() = runTest {
        val f = buildFixture(abmeldenErgebnis = Result.failure(IOException("Auth-Store kaputt")))

        f.viewModel.signOut()
        advanceUntilIdle()

        // Der Ausgangszustand dieser Fixture ist tokenChecked=false (die init-Beobachter spielen
        // ueber die leeren Flows nichts ein) - die beiden unteren Zusicherungen sind deshalb die
        // unterscheidenden: ohne die Korrektur bleibt tokenChecked false, und das Gate faende
        // gar keine Aussage vor.
        val calendarOps = f.viewModel.authState.value.calendarOps
        assertFalse(
            "Das Token ist verworfen - die Oberflaeche darf nichts anderes behaupten",
            calendarOps.hasValidToken
        )
        assertTrue(
            "Ohne tokenChecked haengt das Gate im Ladebildschirm",
            calendarOps.tokenChecked
        )
        assertTrue(
            "Der Nutzer muss auf den Autorisierungsbildschirm - dort stehen beide Auswege",
            calendarOps.needsCalendarAuthorization
        )
    }
}
