package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarFetchOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import java.io.IOException

/**
 * Wann erfaehrt der Nutzer im HINTERGRUND, dass sein Kalender nicht mehr abrufbar ist?
 *
 * Einziger Weg dorthin ist [CalendarUnavailableNotifier.onFetchOutcome] aus
 * [AlarmMaintenanceService.performMaintenance]. Die Entprellung selbst (zwei Laeufe in Folge,
 * nur einmal, Erholung raeumt) pruefen `CalendarUnavailableNotifierTest` und
 * `...ZustellungTest`; hier geht es darum, WAS die Wartung ihr in welcher Lage uebergibt:
 *  - Teilerfolg: die gescheiterten Kennungen, und zwar VOR der isComplete-Sperre.
 *  - voller Erfolg: die leere Menge - daran erkennt die Entprellung die Erholung.
 *  - Funkloch beim Abruf: NICHTS. Ein Aufruf mit Kennungen wuerde nach zwei Laeufen "nicht
 *    abrufbar" melden, einer mit leerer Menge das Gedaechtnis raeumen - beides waere falsch.
 *  - scheitert schon das Token: die Kalender-Warnung wird gar nicht erst befragt.
 *
 * Der Dienst laeuft dafuer echt, nur seine Abhaengigkeiten sind ersetzt (Vorbild:
 * BootReceiverRecoveryCharakterisierungTest).
 */
class WartungKalenderWarnungTest {

    private fun wartung(
        abruf: Result<CalendarFetchOutcome>,
        auswahl: Set<String> = setOf(DIENSTPLAN),
        token: Result<TokenData> = Result.success(GUELTIGES_TOKEN)
    ): AlarmMaintenanceService = AlarmMaintenanceService().apply {
        freieTageStore = mock<FreieTageStore>()
        masterPausePrefs = mock<MasterPausePrefs> {
            on { pausedNow() } doReturn false
        }
        pendingDeselectionCleanupStore = mock<PendingDeselectionCleanupStore> {
            on { pendingSince() } doReturn Result.success(null)
        }
        tokenManager = mock<OAuth2TokenManager> {
            on { getValidToken() } doReturn token
        }
        wartungStoerungPrefs = mock<WartungStoerungPrefs> {
            on { zustandNow() } doReturn WartungStoerungPrefs.Zustand()
        }
        wartungNetzNachholer = mock<WartungNetzNachholer>()
        alarmUseCase = mock<IAlarmUseCase> {
            on { getAllAlarms() } doReturn Result.success(emptyList())
        }
        mainDataStore = mock<DataStore<Preferences>> {
            on { data } doReturn flowOf(emptyPreferences())
        }
        calendarSelectionRepository = mock<CalendarSelectionRepository> {
            on { getCurrentSelectedCalendarIds() } doReturn Result.success(auswahl)
        }
        calendarUseCase = mock<ICalendarUseCase> {
            on { getCalendarEventsWithStatus(any(), any()) } doReturn abruf
        }
        calendarUnavailableNotifier = mock<CalendarUnavailableNotifier>()
    }

    @Test
    fun `Teilerfolg - die Warnung bekommt genau die gescheiterten Kennungen`() = runTest {
        val w = wartung(
            abruf = Result.success(
                CalendarFetchOutcome(
                    events = emptyList(),
                    requestedCalendars = 2,
                    failedCalendarIds = setOf(ZWEITER)
                )
            ),
            auswahl = setOf(DIENSTPLAN, ZWEITER)
        )

        w.performMaintenance(forceSync = true)

        verifyBlocking(w.calendarUnavailableNotifier) { onFetchOutcome(setOf(ZWEITER)) }
    }

    @Test
    fun `voller Erfolg - die Warnung erfaehrt die Erholung ueber die leere Menge`() = runTest {
        val w = wartung(
            abruf = Result.success(CalendarFetchOutcome(events = emptyList(), requestedCalendars = 1))
        )

        w.performMaintenance(forceSync = true)

        verifyBlocking(w.calendarUnavailableNotifier) { onFetchOutcome(emptySet()) }
    }

    /**
     * Ein Funkloch belegt nichts ueber den Kalender - und diese Warnung sagt "nicht mehr
     * abrufbar". Dauerhafte Netzstoerungen meldet der Token-Schritt ("Kalender-Synchronisation
     * gestoert"), denn ohne Netz scheitert spaetestens die stuendliche Token-Erneuerung.
     */
    @Test
    fun `Totalausfall ueber die Verbindung - keine Kalender-Warnung`() = runTest {
        for (fehler in listOf(
            AppError.NetworkError("No internet connection"),
            // Ein rohes IOException wird in SafeExecutor zum FileSystemError - die Ursache bleibt.
            AppError.FileSystemError("Software caused connection abort", IOException("abort"))
        )) {
            val w = wartung(abruf = Result.failure(fehler))

            w.performMaintenance(forceSync = true)

            verifyBlocking(w.calendarUnavailableNotifier, never()) { onFetchOutcome(any()) }
        }
    }

    @Test
    fun `scheitert schon das Token - die Kalender-Warnung wird nicht befragt`() = runTest {
        val w = wartung(
            abruf = Result.success(CalendarFetchOutcome(events = emptyList(), requestedCalendars = 1)),
            token = Result.failure(TokenException.RefreshFailed("offline", IOException("offline")))
        )

        w.performMaintenance(forceSync = true)

        verifyBlocking(w.calendarUseCase, never()) { getCalendarEventsWithStatus(any(), any()) }
        verifyBlocking(w.calendarUnavailableNotifier, never()) { onFetchOutcome(any()) }
    }

    private companion object {
        const val DIENSTPLAN = "dienstplan@group.calendar.google.com"
        const val ZWEITER = "zweiter@group.calendar.google.com"

        val GUELTIGES_TOKEN = TokenData(
            accessToken = "token",
            expiresAt = System.currentTimeMillis() + 3_600_000L,
            scope = "https://www.googleapis.com/auth/calendar.readonly"
        )
    }
}
