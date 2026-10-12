package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarFetchOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import java.util.concurrent.TimeUnit

/**
 * Die 6h-Wartung holt die Termine bei JEDEM Lauf (seit 1.48.0) - auch ohne `forceSync`.
 *
 * WAS VORHER GALT: `MaintenanceLoadDecision.shouldLoadEvents` lud nur bei Puffer < 7 Tage,
 * letzter Abfrage >= 12 h oder naechstem Wecker <= 48 h. Im eingeschwungenen Zustand (Dienstplan
 * zwei Wochen gepflegt, frisch abgefragt, naechster Dienst in ein paar Tagen) kehrte ein
 * regulaerer Lauf mit "buffer sufficient" zurueck, ohne in den Kalender zu sehen - in freien Wochen
 * bis zu 12 h lang. Gespart hat das nur die Abfrage selbst; das Netz war fuer den Token-Schritt
 * davor ohnehin schon da. Und "alle 6 Stunden", wie es die App jetzt im Status-Tab sagt, haette
 * nicht gestimmt.
 *
 * Darum spielen die Tests genau diesen Zustand nach: alle Wecker weit weg, die letzte Abfrage zwei
 * Stunden alt. Fuehrt jemand ein Lade-Gate wieder ein, faellt es hier auf. (Die uebrigen Tests am
 * Dienst, z. B. `WartungKalenderWarnungTest`, laufen alle mit `forceSync = true` und saehen es
 * nicht.)
 *
 * Geprueft wird nur, DASS abgefragt wird; die Terminliste ist leer, der Lauf endet also an der
 * Leerlisten-Sperre, die unveraendert bleibt.
 */
class WartungLaedtBeiJedemLaufTest {

    private val jetzt = System.currentTimeMillis()

    private fun wecker(id: Int, inTagen: Long) = AlarmInfo(
        id = id,
        shiftId = "s$id",
        shiftName = "Frueh",
        triggerTime = jetzt + TimeUnit.DAYS.toMillis(inTagen),
        formattedTime = "05:30",
        eventId = "e$id"
    )

    /** Der Zustand, in dem das alte Lade-Gate uebersprang: Puffer 13 Tage, naechster in 9, Daten 2 h alt. */
    private fun wartung(): AlarmMaintenanceService = AlarmMaintenanceService().apply {
        freieTageStore = mock<FreieTageStore>()
        masterPausePrefs = mock<MasterPausePrefs> {
            on { pausedNow() } doReturn false
        }
        pendingDeselectionCleanupStore = mock<PendingDeselectionCleanupStore> {
            on { pendingSince() } doReturn Result.success(null)
        }
        tokenManager = mock<OAuth2TokenManager> {
            on { getValidToken() } doReturn Result.success(GUELTIGES_TOKEN)
        }
        wartungStoerungPrefs = mock<WartungStoerungPrefs> {
            on { zustandNow() } doReturn WartungStoerungPrefs.Zustand()
        }
        wartungNetzNachholer = mock<WartungNetzNachholer>()
        alarmUseCase = mock<IAlarmUseCase> {
            on { getAllAlarms() } doReturn Result.success(listOf(wecker(1, inTagen = 9), wecker(2, inTagen = 13)))
        }
        mainDataStore = mock<DataStore<Preferences>> {
            on { data } doReturn flowOf(
                preferencesOf(longPreferencesKey("last_event_load_time") to jetzt - TimeUnit.HOURS.toMillis(2))
            )
        }
        calendarSelectionRepository = mock<CalendarSelectionRepository> {
            on { getCurrentSelectedCalendarIds() } doReturn Result.success(setOf(DIENSTPLAN))
        }
        calendarUseCase = mock<ICalendarUseCase> {
            on { getCalendarEventsWithStatus(any(), any()) } doReturn
                Result.success(CalendarFetchOutcome(events = emptyList(), requestedCalendars = 1))
        }
        calendarUnavailableNotifier = mock<CalendarUnavailableNotifier>()
    }

    @Test
    fun `ein regulaerer Lauf fragt den Kalender ab, auch wenn Puffer und Daten frisch sind`() = runTest {
        val w = wartung()

        w.performMaintenance(forceSync = false)

        // forceRefresh = false: der regulaere Lauf darf einen Treffer der letzten 15 Minuten nehmen.
        verifyBlocking(w.calendarUseCase) { getCalendarEventsWithStatus(setOf(DIENSTPLAN), false) }
    }

    @Test
    fun `der erzwungene Lauf fragt am Zwischenspeicher vorbei ab - das ist alles, was forceSync noch tut`() = runTest {
        val w = wartung()

        w.performMaintenance(forceSync = true)

        verifyBlocking(w.calendarUseCase) { getCalendarEventsWithStatus(setOf(DIENSTPLAN), true) }
        verifyBlocking(w.calendarUseCase, never()) { getCalendarEventsWithStatus(any(), eq(false)) }
    }

    /**
     * Der Satz in der Karte "Hintergrunddienst" nennt Zahlen, die im Code stehen. Aendert jemand
     * das Intervall, muss der Satz mitwandern - sonst behauptet die App einen Rhythmus, den sie
     * nicht hat. (Die "3 Stunden vor jedem Wecker" stehen privat in
     * `CalendarPreAlarmRefreshScheduler` und sind von hier nicht erreichbar.)
     */
    @Test
    fun `der Satz zum Abgleich-Rhythmus passt zu den Intervallen im Code`() {
        val satz = com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.ABGLEICH_RHYTHMUS_TEXT

        assertTrue(satz, satz.contains("alle ${WartungsKettenPlanung.INTERVALL_STUNDEN} Stunden"))
        assertEquals(
            "\"stündlich\" im Satz, aber die Rufbereitschafts-Abfrage hat ein anderes Intervall",
            TimeUnit.HOURS.toMillis(1),
            RufbereitschaftAbfrage.INTERVALL_MS
        )
        assertTrue(satz, satz.contains("stündlich"))
    }

    private companion object {
        const val DIENSTPLAN = "dienstplan@group.calendar.google.com"

        val GUELTIGES_TOKEN = TokenData(
            accessToken = "token",
            expiresAt = System.currentTimeMillis() + 3_600_000L,
            scope = "https://www.googleapis.com/auth/calendar.readonly"
        )
    }
}
