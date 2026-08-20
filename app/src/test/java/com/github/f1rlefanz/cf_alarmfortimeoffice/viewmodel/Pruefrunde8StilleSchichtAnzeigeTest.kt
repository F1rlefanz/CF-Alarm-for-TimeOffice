package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
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
import kotlinx.coroutines.flow.flowOf
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
 * WAS BEWUSST GLEICH BLEIBT: [AlarmUiState.nextAlarmTime] waehlt weiterhin den fruehesten
 * Eintrag, auch wenn er stumm ist. Der Knopf "Ueberspringen" haengt ueber
 * `enabled = nextAlarmTime != null` an genau dieser Auswahl, und `AlarmSkipUseCase.findNextAlarm()`
 * filtert ebenfalls nur nach Zeit - wuerde hier gefiltert, zeigte die Karte einen anderen Wecker
 * an, als der Knopf ueberspringt. Diese Tests halten beides zugleich fest.
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
            onBlocking { getAllAlarms() } doReturn Result.success(alarms)
            onBlocking { deleteAlarm(any()) } doReturn Result.success(Unit)
        }
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig())
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub { onBlocking { pausedNow() } doReturn false }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            alarmRepository = mock<IAlarmRepository>().apply {
                stub { onBlocking { isPersistenceBlocked() } doReturn false }
            }
        )
    }

    @Test
    fun `ein stiller naechster Eintrag wird als stumm ausgewiesen und verdeckt den echten Wecker nicht`() =
        runTest {
            // OHNE DEN FIX: nextAlarmIsSilent gaebe es nicht, die Karte behauptete schlicht
            // "Nächster Alarm: 20.08.2026 05:30" - ein Zeitpunkt, an dem nichts klingelt.
            val viewModel = buildViewModel(
                listOf(
                    alarm(1, inStunden = 12, stumm = true, anzeige = "20.08.2026 05:30"),
                    alarm(2, inStunden = 36, stumm = false, anzeige = "21.08.2026 05:00")
                )
            )
            advanceUntilIdle()

            val stand = viewModel.uiState.value
            assertEquals(
                "Die Auswahl selbst bleibt der fruehste Eintrag - sonst weicht sie von dem ab, " +
                    "was 'Ueberspringen' trifft",
                "20.08.2026 05:30",
                stand.nextAlarmTime
            )
            assertTrue("Der stille Eintrag muss als stumm gekennzeichnet sein", stand.nextAlarmIsSilent)
            assertEquals(
                "Der naechste wirklich klingelnde Wecker muss zusaetzlich ablesbar sein",
                "21.08.2026 05:00",
                stand.nextRingingAlarmTime
            )
            assertEquals("Der Zaehler muss die stummen mitzaehlen", 1, stand.silentAlarmCount)
        }

    @Test
    fun `ein klingelnder naechster Eintrag bleibt unmarkiert`() = runTest {
        val viewModel = buildViewModel(
            listOf(
                alarm(1, inStunden = 12, stumm = false, anzeige = "20.08.2026 05:00"),
                alarm(2, inStunden = 36, stumm = true, anzeige = "21.08.2026 05:30")
            )
        )
        advanceUntilIdle()

        val stand = viewModel.uiState.value
        assertEquals("20.08.2026 05:00", stand.nextAlarmTime)
        assertFalse(stand.nextAlarmIsSilent)
        assertEquals(1, stand.silentAlarmCount)
    }

    @Test
    fun `steht nur eine stille Schicht an, verspricht die Karte keinen klingelnden Wecker`() = runTest {
        // Der schlimmste Fall: "1 aktive Alarme" in Gruen, obwohl kein einziger klingelt.
        val viewModel = buildViewModel(
            listOf(alarm(1, inStunden = 12, stumm = true, anzeige = "20.08.2026 05:30"))
        )
        advanceUntilIdle()

        val stand = viewModel.uiState.value
        assertTrue(stand.nextAlarmIsSilent)
        assertNull(
            "Es gibt keinen klingelnden Wecker - die Karte darf auch keinen nennen",
            stand.nextRingingAlarmTime
        )
        assertEquals(1, stand.silentAlarmCount)
    }

    // --- Die reine Rechenvorschrift, ohne ViewModel ---

    @Test
    fun `berechneWeckerAnzeige - der uebersprungene Eintrag faellt aus beiden Auswahlen`() {
        val stumm = alarm(1, inStunden = 12, stumm = true, anzeige = "A")
        val laut = alarm(2, inStunden = 36, stumm = false, anzeige = "B")

        val ohneSkip = berechneWeckerAnzeige(listOf(stumm, laut), skippedAlarmId = null, jetzt = now)
        assertEquals("A", ohneSkip.naechsterEintrag)
        assertTrue(ohneSkip.naechsterEintragIstStumm)
        assertEquals("B", ohneSkip.naechsterKlingelnder)

        val mitSkip = berechneWeckerAnzeige(listOf(stumm, laut), skippedAlarmId = 1, jetzt = now)
        assertEquals(
            "Faellt der stille Eintrag weg, ruecken beide Angaben auf den lauten",
            "B",
            mitSkip.naechsterEintrag
        )
        assertFalse(mitSkip.naechsterEintragIstStumm)
        assertEquals("B", mitSkip.naechsterKlingelnder)
    }

    @Test
    fun `berechneWeckerAnzeige - verstrichene Alarme zaehlen nicht als naechster Eintrag`() {
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

        assertEquals("morgen", anzeige.naechsterEintrag)
        assertTrue(anzeige.naechsterEintragIstStumm)
        assertNull(anzeige.naechsterKlingelnder)
    }
}
