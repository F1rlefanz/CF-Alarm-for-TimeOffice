package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.HellZeitpunkt
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TageslichtEnde
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Die Beispielzeile unter „Nachlauf nur, solange es dunkel ist" (#140): „Nächster Wecker … →
 * gedimmt bis …". Sie rechnet ueber DIESELBE Funktion wie die Fensteraufloesung
 * (`DimWindowResolver.nachlaufEnde`) - eine zweite Rechnung im Editor koennte etwas anderes
 * versprechen, als der Dimmer dann tut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DimmerRulesViewModelTageslichtTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val tag = LocalDate.parse("2026-10-15")
    private val jetzt = t(LocalDate.parse("2026-10-14"), "20:00")

    private fun t(datum: LocalDate, uhrzeit: String): Long =
        LocalDateTime.parse("${datum}T$uhrzeit").atZone(zone).toInstant().toEpochMilli()

    private fun fenster(hellAb: HellAb = HellAb.SONNENAUFGANG, nachlauf: Int = 60, schranke: Int = 8 * 60) = DimWindow(
        startAnchor = DimAnchor.CLOCK,
        startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.ALARM_SONST_CLOCK,
        endClockMinutes = schranke,
        weckzeitNachlaufMinuten = nachlauf,
        nachlaufBisHell = hellAb
    )

    private val hell0731: HellZeitpunkt = { d, wahl -> if (wahl == HellAb.IMMER) null else t(d, "07:31") }

    private fun beispiel(w: DimWindow, wecker: List<Long>, hell: HellZeitpunkt = hell0731) =
        DimmerRulesViewModel.nachlaufBeispiel(w, hell, wecker, jetzt, zone)

    @Test
    fun `voller Nachlauf - Sonnenaufgang ist erst danach`() {
        val b = beispiel(fenster(), listOf(t(tag, "05:30")))!!
        assertEquals(t(tag, "05:30"), b.weckzeit)
        assertEquals(t(tag, "06:30"), b.ende)
        assertNull(b.grund)
        assertEquals(t(tag, "07:31"), b.hellZeitpunkt)
        assertEquals(DimmerRulesViewModel.BeispielGrund.NACHLAUF_VOLL, b.grundArt)
        assertEquals(60, b.nachlaufMin)
    }

    @Test
    fun `Sonnenaufgang im Nachlauf - gedimmt bis Sonnenaufgang`() {
        val b = beispiel(fenster(), listOf(t(tag, "07:00")))!!
        assertEquals(t(tag, "07:31"), b.ende)
        assertEquals(TageslichtEnde(HellAb.SONNENAUFGANG, t(tag, "07:31"), vorWeckzeit = false), b.grund)
        assertEquals(DimmerRulesViewModel.BeispielGrund.HELL, b.grundArt)
    }

    @Test
    fun `schon hell beim Wecken - Ende an der Weckzeit`() {
        val b = beispiel(fenster(), listOf(t(tag, "07:40")))!!
        assertEquals(t(tag, "07:40"), b.ende)
        assertEquals(DimmerRulesViewModel.BeispielGrund.WECKZEIT, b.grundArt)
    }

    @Test
    fun `kappt die Uhrzeit-Schranke den Nachlauf, behauptet die Zeile keine vollen Minuten`() {
        val b = beispiel(fenster(schranke = 7 * 60 + 45), listOf(t(tag, "07:20")), { d, _ -> t(d, "07:50") })!!
        assertEquals(t(tag, "07:45"), b.ende)
        assertNull(b.grundArt)
    }

    @Test
    fun `der naechste Wecker, den das Fenster faengt - verstrichene und spaete zaehlen nicht`() {
        val vorbei = t(LocalDate.parse("2026-10-14"), "05:30") // vor jetzt
        val abends = t(LocalDate.parse("2026-10-14"), "21:00") // nach der Schranke, vor dem Start
        val morgen = t(tag, "06:00")
        val b = beispiel(fenster(), listOf(abends, vorbei, morgen))!!
        assertEquals(morgen, b.weckzeit)
    }

    @Test
    fun `kein Beispiel ohne passenden Wecker, ohne Nachlauf oder bei IMMER`() {
        assertNull(beispiel(fenster(), emptyList()))
        assertNull(beispiel(fenster(), listOf(t(tag, "12:30")))) // nach der Schranke
        assertNull(beispiel(fenster(nachlauf = 0), listOf(t(tag, "06:00"))))
        assertNull(beispiel(fenster(hellAb = HellAb.IMMER), listOf(t(tag, "06:00"))))
    }

    @Test
    fun `ohne Ereignis an dem Tag gilt der volle Nachlauf, ohne Begruendung mit einer Uhrzeit`() {
        val b = beispiel(fenster(), listOf(t(tag, "07:00")), { _, _ -> null })!!
        assertEquals(t(tag, "08:00"), b.ende)
        assertNull(b.hellZeitpunkt)
        assertNull(b.grundArt)
    }

    // ------------------------------------------------------------------ das ViewModel selbst

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(quelle: TageslichtQuelle, alarme: List<AlarmInfo>): DimmerRulesViewModel {
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
        val v = vm(TageslichtQuelle.OHNE, listOf(alarm(wecker)))
        v.ladeTageslicht()
        advanceUntilIdle()
        assertFalse(v.ortBekannt.value)
        assertNull(v.nachlaufBeispiel(fenster()))
    }

    @Test
    fun `mit Ort laedt das ViewModel Stand und Wecker und rechnet das Beispiel`() = runTest(dispatcher) {
        val sysZone = ZoneId.systemDefault()
        // Ein Wecker morgen 05:30 Systemzeit - liegt sicher im Fenster 22:00 -> 08:00.
        val wecker = LocalDate.now(sysZone).plusDays(1).atTime(5, 30).atZone(sysZone).toInstant().toEpochMilli()
        val muenchen = TageslichtStand(Ort(48.14, 11.58, OrtQuelle.PLZ, "München"), sysZone)
        val quelle = object : TageslichtQuelle {
            override suspend fun stand() = muenchen
        }
        val v = vm(quelle, listOf(alarm(wecker), alarm(wecker).copy(id = 2, isActive = false, triggerTime = wecker - 3_600_000L)))
        assertFalse("vor dem Laden nichts behaupten", v.ortBekannt.value)
        v.ladeTageslicht()
        advanceUntilIdle()
        assertTrue(v.ortBekannt.value)
        val b = v.nachlaufBeispiel(fenster())
        assertNotNull(b)
        assertEquals("nur aktive Wecker", wecker, b!!.weckzeit)
    }
}
