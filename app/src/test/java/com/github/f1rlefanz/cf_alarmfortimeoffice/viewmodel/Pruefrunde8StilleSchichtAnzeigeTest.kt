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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

/**
 * BEFUND 9 der Pruefrunde 8: "Nächster Alarm: X" verschwieg die stille Schicht.
 *
 * DER FEHLER: `computeNextAlarmTime()` filterte nur nach `triggerTime` und Skip-Merker. Eine
 * STILLE Schicht (Rufbereitschaft, `AlarmInfo.isSilent`) liegt aber ganz normal im Bestand und
 * gewinnt damit das `minByOrNull` - waehrend der `AlarmReceiver` zur Weckzeit bei `isSilent`
 * aussteigt, bevor Ton, Vibration, Vollbild oder Hue angestossen werden. Die Karte sagte also
 * einen Weckzeitpunkt zu, an dem die App per Konstruktion stumm bleibt, faerbte das Icon gruen
 * und zaehlte den stillen Eintrag unter "N aktive Alarme" mit. Der naechste WIRKLICH klingelnde
 * Wecker war aus der Oberflaeche gar nicht ablesbar, weil der stille ihn verdeckte.
 *
 * SEIT #139 (1.50.0) nennt die Oberflaeche nur noch den naechsten KLINGELNDEN Wecker
 * ([AlarmUiState.nextRingingAlarmTime]/[AlarmUiState.nextRingingAlarmTriggerTime]); die frueheren
 * Felder fuer den naechsten EINTRAG samt Stumm-Kennzeichen und Zaehler sind mit der Karte
 * "Alarm-Status" entfallen. Stille Eintraege bleiben trotzdem sichtbar, nur an anderer Stelle:
 * die Karte "Nächster Wecker" zaehlt sie ("nur 1 stiller Eintrag", `NaechsterWeckerKarteTest`),
 * die Tagesliste zeigt sie als "Still" (`WeckerTageTest`) - gekennzeichnet, nicht versteckt.
 *
 * SEIT 1.48.0: „Nicht wecken“ waehlt ueber dieselbe Funktion
 * (`AlarmSkipUseCase.naechsterKlingelnderWecker`), mit der die Anzeige den naechsten klingelnden
 * Wecker errechnet. Bis 1.47.1 traf der Knopf den stillen Eintrag, waehrend die Karte den
 * klingelnden nannte. Die Tests zum Ziel stehen in `UeberspringenKlingelnderWeckerTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde8StilleSchichtAnzeigeTest {

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

    private fun alarm(
        id: Int,
        inStunden: Long,
        stumm: Boolean,
        anzeige: String
    ) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = if (stumm) "Rufbereitschaft" else "Frueh",
        triggerTime = now + inStunden * 60 * 60 * 1000L,
        formattedTime = anzeige,
        isSilent = stumm
    )

    private fun buildViewModel(alarms: List<AlarmInfo>): AlarmViewModel {
        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(alarms))
        alarmUseCase.stub {
            on { getAllAlarms() } doReturn Result.success(alarms)
            on { deleteAlarm(any()) } doReturn Result.success(Unit)
        }
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig())
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        // Pflicht, nicht Kosmetik: AlarmViewModel liest diesen Flow im
        // Property-Initializer. Ohne Stub liefert der Mock null und das Konstruieren
        // scheitert mit einer NPE - noch vor dem ersten Testschritt.
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
            tagFreigabeUseCase = tagFreigabeUseCaseOhneFreigaben(),
            zeitkettenArmierer = org.mockito.kotlin.mock(),
            wecktonProbe = org.mockito.kotlin.mock(),
            alarmRepository = mock<IAlarmRepository>().apply {
                stub { on { isPersistenceBlocked() } doReturn false }
            }
        )
    }

    @Test
    fun `ein stiller naechster Eintrag verdeckt den echten Wecker nicht`() =
        runTest {
            // OHNE DEN FIX behauptete die Karte "Nächster Alarm: 20.08.2026 05:30" - ein
            // Zeitpunkt, an dem nichts klingelt.
            val viewModel = buildViewModel(
                listOf(
                    alarm(1, inStunden = 12, stumm = true, anzeige = "20.08.2026 05:30"),
                    alarm(2, inStunden = 36, stumm = false, anzeige = "21.08.2026 05:00")
                )
            )
            advanceUntilIdle()

            val stand = viewModel.uiState.value
            assertEquals(
                "Der naechste wirklich klingelnde Wecker muss zusaetzlich ablesbar sein",
                "21.08.2026 05:00",
                stand.nextRingingAlarmTime
            )
            assertEquals(
                "Der Zeitpunkt fuer die Knopfzeile gehoert zu DEMSELBEN Wecker",
                now + 36 * 60 * 60 * 1000L,
                stand.nextRingingAlarmTriggerTime
            )
        }

    @Test
    fun `ein klingelnder naechster Eintrag ist selbst der naechste klingelnde`() = runTest {
        val viewModel = buildViewModel(
            listOf(
                alarm(1, inStunden = 12, stumm = false, anzeige = "20.08.2026 05:00"),
                alarm(2, inStunden = 36, stumm = true, anzeige = "21.08.2026 05:30")
            )
        )
        advanceUntilIdle()

        val stand = viewModel.uiState.value
        assertEquals("20.08.2026 05:00", stand.nextRingingAlarmTime)
        assertEquals(now + 12 * 60 * 60 * 1000L, stand.nextRingingAlarmTriggerTime)
    }

    @Test
    fun `steht nur eine stille Schicht an, verspricht die Karte keinen klingelnden Wecker`() = runTest {
        // Der schlimmste Fall: "1 aktive Alarme" in Gruen, obwohl kein einziger klingelt.
        val viewModel = buildViewModel(
            listOf(alarm(1, inStunden = 12, stumm = true, anzeige = "20.08.2026 05:30"))
        )
        advanceUntilIdle()

        val stand = viewModel.uiState.value
        assertNull(
            "Es gibt keinen klingelnden Wecker - die Karte darf auch keinen nennen",
            stand.nextRingingAlarmTime
        )
        assertNull(
            "...und der Knopf 'Nicht wecken' hat kein Ziel",
            stand.nextRingingAlarmTriggerTime
        )
    }

    // --- Die reine Rechenvorschrift, ohne ViewModel ---

    @Test
    fun `berechneWeckerAnzeige - der uebersprungene Eintrag faellt aus der Auswahl`() {
        val stumm = alarm(1, inStunden = 12, stumm = true, anzeige = "A")
        val laut = alarm(2, inStunden = 36, stumm = false, anzeige = "B")
        val spaeter = alarm(3, inStunden = 60, stumm = false, anzeige = "C")

        val ohneSkip = berechneWeckerAnzeige(listOf(stumm, laut, spaeter), skippedAlarmId = null, jetzt = now)
        assertEquals("Der stille Eintrag ist nie der naechste klingelnde", "B", ohneSkip.naechsterKlingelnder)

        val mitSkip = berechneWeckerAnzeige(listOf(stumm, laut, spaeter), skippedAlarmId = 2, jetzt = now)
        assertEquals("Faellt der laute weg, rueckt die Auswahl auf den naechsten lauten", "C", mitSkip.naechsterKlingelnder)
        assertEquals(spaeter.triggerTime, mitSkip.naechsterKlingelnderZeitpunkt)
    }

    @Test
    fun `berechneWeckerAnzeige - verstrichene Alarme zaehlen nicht als naechster klingelnder`() {
        val vergangen = AlarmInfo(
            id = 9,
            shiftId = "alt",
            shiftName = "Frueh",
            triggerTime = now - 1_000L,
            formattedTime = "gestern",
            isSilent = false
        )
        val kommend = alarm(2, inStunden = 5, stumm = true, anzeige = "morgen")

        val anzeige = berechneWeckerAnzeige(listOf(vergangen, kommend), skippedAlarmId = null, jetzt = now)

        // Der verstrichene ist laut, der kommende still: es klingelt nichts mehr.
        assertNull(anzeige.naechsterKlingelnder)
        assertNull(anzeige.naechsterKlingelnderZeitpunkt)
    }
}
