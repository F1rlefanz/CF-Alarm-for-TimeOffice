package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.GespeicherterStandort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzLand
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * Der Regel-Editor laedt Ort und Weckzeiten fuer die Beispielzeile unter „Nachlauf nur, solange es
 * dunkel ist" (#140). Die Rechnung selbst ist mit #224 nach `viewmodel.nachlaufBeispiel` umgezogen;
 * ihre Faelle stehen in `DimmenWeitereOptionenTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DimmerRulesViewModelTageslichtTest {

    private fun fenster(hellAb: HellAb = HellAb.SONNENAUFGANG, nachlauf: Int = 60, schranke: Int = 8 * 60) = DimWindow(
        startAnchor = DimAnchor.CLOCK,
        startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.ALARM_SONST_CLOCK,
        endClockMinutes = schranke,
        weckzeitNachlaufMinuten = nachlauf,
        nachlaufBisHell = hellAb
    )

    // ------------------------------------------------------------------ das ViewModel selbst

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Stand und Einstellungen getrennt steuerbar: der Test schreibt erst den Stand, dann das Signal. */
    private class FakeTageslicht(var aktuell: TageslichtStand = TageslichtStand.ohneOrt()) : ITageslichtRepository {
        val einstellungenFlow = MutableStateFlow(TageslichtEinstellungen.LEER)
        override val einstellungen: Flow<TageslichtEinstellungen> = einstellungenFlow
        override suspend fun stand() = aktuell
        override suspend fun setzePlz(land: PlzLand, plz: String) = Unit
        override suspend fun speichereStandort(breite: Double, laenge: Double, jetzt: Long) = Unit
        override suspend fun entferneStandort() = Unit
    }

    private fun vm(quelle: ITageslichtRepository, alarme: List<AlarmInfo>): DimmerRulesViewModel {
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        val ruleUseCase = mock<DimRuleUseCase>()
        whenever(ruleUseCase.rules).thenReturn(flowOf(emptyList()))
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.toggles).thenReturn(flowOf(DimOverlayPrefs.Toggles(dimEnabled = true)))
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub { on { getAllAlarms() } doReturn Result.success(alarme) }
        return DimmerRulesViewModel(
            ruleUseCase, shiftUseCase, mock<DimScheduleUseCase>(), mock<ZeitkettenArmierer>(),
            prefs, mock<ShiftSpanStore>(), alarmUseCase, quelle
        )
    }

    private fun alarm(trigger: Long) = AlarmInfo(id = 1, shiftId = "s", shiftName = "Frueh", triggerTime = trigger, formattedTime = "")

    @Test
    fun `ohne Ort ist ortBekannt false und es gibt kein Beispiel`() = runTest(dispatcher) {
        val wecker = System.currentTimeMillis() + 3_600_000L
        val v = vm(FakeTageslicht(), listOf(alarm(wecker)))
        assertNull("vor dem Laden weder Ort noch Kein-Ort behaupten", v.ortBekannt.value)
        val beobachtung = launch { v.verfolgeTageslicht() }
        advanceUntilIdle()
        assertEquals(false, v.ortBekannt.value)
        assertNull(v.nachlaufBeispiel(fenster()))
        beobachtung.cancel()
    }

    @Test
    fun `mit Ort laedt das ViewModel Stand und Wecker und rechnet das Beispiel`() = runTest(dispatcher) {
        val sysZone = ZoneId.systemDefault()
        // Ein Wecker morgen 05:30 Systemzeit - liegt sicher im Fenster 22:00 -> 08:00.
        val wecker = LocalDate.now(sysZone).plusDays(1).atTime(5, 30).atZone(sysZone).toInstant().toEpochMilli()
        val muenchen = TageslichtStand(Ort(48.14, 11.58, OrtQuelle.PLZ, "München"), sysZone)
        val quelle = FakeTageslicht(muenchen)
        val v = vm(quelle, listOf(alarm(wecker), alarm(wecker).copy(id = 2, isActive = false, triggerTime = wecker - 3_600_000L)))
        // null statt false: false liesse den Editor bis zum ersten Laden "Kein Ort eingestellt"
        // zeigen, obwohl hier ein Ort gesetzt ist.
        assertNull("vor dem Laden nichts behaupten", v.ortBekannt.value)
        val beobachtung = launch { v.verfolgeTageslicht() }
        advanceUntilIdle()
        assertEquals(true, v.ortBekannt.value)
        val b = v.nachlaufBeispiel(fenster())
        assertNotNull(b)
        assertEquals("nur aktive Wecker", wecker, b!!.weckzeit)
        beobachtung.cancel()
    }

    /**
     * Review #140: Die Standortabfrage laeuft im Activity-ViewModel bis zu 30 s weiter und kann
     * erst schreiben, wenn der Nutzer schon zurueck im Editor ist. Ein Einmal-Schnappschuss beim
     * Eintritt behauptete dann bis zum naechsten Oeffnen "Kein Ort eingestellt".
     */
    @Test
    fun `ein Ort, der erst nach dem Oeffnen des Editors geschrieben wird, erscheint ohne erneutes Oeffnen`() = runTest(dispatcher) {
        val sysZone = ZoneId.systemDefault()
        val wecker = LocalDate.now(sysZone).plusDays(1).atTime(5, 30).atZone(sysZone).toInstant().toEpochMilli()
        val repo = FakeTageslicht(TageslichtStand.ohneOrt(sysZone))
        val v = vm(repo, listOf(alarm(wecker)))
        val beobachtung = launch { v.verfolgeTageslicht() }
        advanceUntilIdle()
        assertEquals(false, v.ortBekannt.value)
        val geladenVorher = v.tageslichtGeladen.value

        repo.aktuell = TageslichtStand(Ort(48.1, 11.6, OrtQuelle.STANDORT, null), sysZone)
        repo.einstellungenFlow.value = TageslichtEinstellungen(GespeicherterStandort(481, 116, 1L), null, null)
        advanceUntilIdle()

        assertEquals(true, v.ortBekannt.value)
        assertTrue("Beispielzeile wird neu gerechnet", v.tageslichtGeladen.value > geladenVorher)
        assertNotNull(v.nachlaufBeispiel(fenster()))
        beobachtung.cancel()
    }
}
