package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.fensterListeAlsJson
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.fensterListeAusJson
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * „Nachlauf nur, solange es dunkel ist" (#140, [DimWindow.nachlaufBisHell]).
 *
 * DIE FORMEL IST VERBINDLICH (Plan §1.3, mit dem Eigentuemer abgestimmt):
 * - Das Ende liegt NIE vor der Weckzeit - gekuerzt wird nur der Nachlauf.
 * - Kein Ort, kein Ereignis an dem Tag, [HellAb.IMMER] oder Nachlauf 0 = unveraendert (fail-safe:
 *   voller Nachlauf).
 * - Massgeblich ist der Kalendertag der WECKZEIT, nicht der des Fensterstarts.
 * - „Nicht stoeren" bekommt den Nachlauf nicht und bleibt damit per Konstruktion unberuehrt.
 *
 * Fenster aller Faelle: CLOCK 22:00 -> „Weckzeit, spaetestens" 08:00, Nachlauf 60, Zone Berlin.
 */
class DimWindowTageslichtTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val nacht = LocalDate.parse("2026-10-14")
    private val wecktag = LocalDate.parse("2026-10-15")

    private fun t(datum: LocalDate, uhrzeit: String): Long =
        LocalDateTime.parse("${datum}T$uhrzeit").atZone(zone).toInstant().toEpochMilli()

    private fun fenster(
        hellAb: HellAb = HellAb.SONNENAUFGANG,
        nachlauf: Int = 60,
        schranke: Int = 8 * 60
    ) = DimWindow(
        startAnchor = DimAnchor.CLOCK,
        startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.ALARM_SONST_CLOCK,
        endClockMinutes = schranke,
        weckzeitNachlaufMinuten = nachlauf,
        nachlaufBisHell = hellAb
    )

    /** Fake: hell ab [uhrzeit] an JEDEM Tag, IMMER = keine Schwelle; merkt sich die gefragten Tage. */
    private class FakeHell(private val uhrzeit: String?, private val zone: ZoneId) : HellZeitpunkt {
        val gefragt = mutableListOf<Pair<LocalDate, HellAb>>()
        override fun invoke(datum: LocalDate, wahl: HellAb): Long? {
            gefragt += datum to wahl
            if (wahl == HellAb.IMMER || uhrzeit == null) return null
            return LocalDateTime.parse("${datum}T$uhrzeit").atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** Die eine Nacht-Spanne vom 14. auf den 15.10. - ueber [DimWindowResolver.buildRuleSpans], damit der Grund mitkommt. */
    private fun spanne(
        w: DimWindow,
        weckzeiten: List<Long>,
        hell: HellZeitpunkt,
        mitNachlauf: Boolean = true
    ): DimWindowResolver.DimSpan {
        val regel = DimRule(name = "Nacht", shiftPattern = DimRule.SHIFT_FREE, windows = listOf(w))
        val spans = DimWindowResolver.buildRuleSpans(
            alarms = emptyList(),
            horizonDays = 2,
            today = nacht,
            zone = zone,
            ruleForShift = { null },
            ruleForFreeDay = { regel },
            weckzeiten = weckzeiten,
            mitWeckzeitNachlauf = mitNachlauf,
            hellZeitpunkt = hell
        )
        return spans.single { it.range.first == t(nacht, "22:00") }
    }

    // ------------------------------------------------------------------ (a) - (h) die Formel

    @Test
    fun `a - Wecker 05 30, hell erst 07 31 - voller Nachlauf bis 06 30, kein Grund`() {
        val s = spanne(fenster(), listOf(t(wecktag, "05:30")), FakeHell("07:31", zone))
        assertEquals(t(wecktag, "06:30"), s.range.last)
        assertNull(s.tageslicht)
    }

    @Test
    fun `b - Wecker 07 00, hell 07 31 - der Nachlauf endet bei Sonnenaufgang`() {
        val s = spanne(fenster(), listOf(t(wecktag, "07:00")), FakeHell("07:31", zone))
        assertEquals(t(wecktag, "07:31"), s.range.last)
        assertEquals(TageslichtEnde(HellAb.SONNENAUFGANG, t(wecktag, "07:31"), vorWeckzeit = false), s.tageslicht)
    }

    @Test
    fun `c - Wecker 07 40, hell schon 07 31 - Ende an der Weckzeit, nie davor`() {
        val s = spanne(fenster(), listOf(t(wecktag, "07:40")), FakeHell("07:31", zone))
        assertEquals(t(wecktag, "07:40"), s.range.last)
        assertEquals(TageslichtEnde(HellAb.SONNENAUFGANG, t(wecktag, "07:31"), vorWeckzeit = true), s.tageslicht)
    }

    @Test
    fun `d - Wecker 07 40, Schranke 07 45, hell erst danach - die Schranke gewinnt`() {
        val s = spanne(fenster(schranke = 7 * 60 + 45), listOf(t(wecktag, "07:40")), FakeHell("07:50", zone))
        assertEquals(t(wecktag, "07:45"), s.range.last)
        assertNull(s.tageslicht)
    }

    @Test
    fun `e - kein Hell-Zeitpunkt (kein Ort, kein Ereignis) - voller Nachlauf`() {
        val frueh = spanne(fenster(), listOf(t(wecktag, "05:30")), FakeHell(null, zone))
        assertEquals(t(wecktag, "06:30"), frueh.range.last)
        assertNull(frueh.tageslicht)
        val spaet = spanne(fenster(), listOf(t(wecktag, "07:00")), FakeHell(null, zone))
        assertEquals(t(wecktag, "08:00"), spaet.range.last)
        assertNull(spaet.tageslicht)
    }

    @Test
    fun `f - IMMER laesst alles wie bisher und fragt gar nicht erst`() {
        val hell = FakeHell("07:31", zone)
        val s = spanne(fenster(hellAb = HellAb.IMMER), listOf(t(wecktag, "07:00")), hell)
        assertEquals(t(wecktag, "08:00"), s.range.last)
        assertNull(s.tageslicht)
        assertTrue("IMMER braucht keinen Sonnenstand", hell.gefragt.isEmpty())
    }

    @Test
    fun `g - Nachlauf 0 - Ende genau an der Weckzeit, ohne Grund`() {
        val hell = FakeHell("06:55", zone)
        val s = spanne(fenster(hellAb = HellAb.DAEMMERUNG, nachlauf = 0), listOf(t(wecktag, "07:00")), hell)
        assertEquals(t(wecktag, "07:00"), s.range.last)
        assertNull(s.tageslicht)
        assertTrue(hell.gefragt.isEmpty())
    }

    @Test
    fun `h - ohne Wecker in der Nacht endet das Fenster unveraendert an der Schranke`() {
        val s = spanne(fenster(), emptyList(), FakeHell("07:31", zone))
        assertEquals(t(wecktag, "08:00"), s.range.last)
        assertNull(s.tageslicht)
    }

    // ------------------------------------------------------------------ (i) DND unberuehrt

    @Test
    fun `i - die DND-Sicht ohne Nachlauf ist mit und ohne Tageslicht identisch`() {
        for (wecker in listOf("05:30", "07:00", "07:40")) {
            val weckzeiten = listOf(t(wecktag, wecker))
            val ohne = spanne(fenster(), weckzeiten, OHNE_TAGESLICHT, mitNachlauf = false)
            val mit = spanne(fenster(), weckzeiten, FakeHell("07:31", zone), mitNachlauf = false)
            assertEquals(ohne, mit)
            assertEquals("DND endet an der Weckzeit", t(wecktag, wecker), mit.range.last)
            assertNull(mit.tageslicht)
        }
    }

    // ------------------------------------------------------------------ (j) Zeitumstellung, echte Werte

    private val berlin = TageslichtStand(Ort(52.52, 13.40, OrtQuelle.PLZ, null), zone)

    private fun pruefeDst(nachtVor: LocalDate, tag: LocalDate, erwartetHell: String) {
        val regel = DimRule(name = "Nacht", shiftPattern = DimRule.SHIFT_FREE, windows = listOf(fenster()))
        val wecker = t(tag, "06:30")
        val spans = DimWindowResolver.buildRuleSpans(
            alarms = emptyList(),
            horizonDays = 2,
            today = nachtVor,
            zone = zone,
            ruleForShift = { null },
            ruleForFreeDay = { regel },
            weckzeiten = listOf(wecker),
            hellZeitpunkt = berlin::hellZeitpunkt
        )
        val s = spans.single { it.range.first == t(nachtVor, "22:00") }
        val erwartet = t(tag, erwartetHell)
        assertTrue(
            "Ende ${Instant.ofEpochMilli(s.range.last).atZone(zone)} statt ~$erwartetHell",
            kotlin.math.abs(s.range.last - erwartet) <= 2 * 60_000L
        )
        val grund = assertNotNullUnd(s.tageslicht)
        assertEquals(false, grund.vorWeckzeit)
        assertEquals(s.range.last, grund.hellZeitpunkt)
    }

    private fun <T> assertNotNullUnd(wert: T?): T {
        assertNotNull(wert)
        return wert!!
    }

    @Test
    fun `j - Sommerzeitbeginn 29 03 - Sonnenaufgang Berlin 06 48 Ortszeit`() =
        pruefeDst(LocalDate.parse("2026-03-28"), LocalDate.parse("2026-03-29"), "06:48")

    @Test
    fun `j - Sommerzeitende 25 10 - Sonnenaufgang Berlin 06 50 Ortszeit`() =
        pruefeDst(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25"), "06:50")

    // ------------------------------------------------------------------ (k) Grund in der Zeitleiste

    @Test
    fun `k - nur der Abschnitt, dessen Ende passt, traegt den Grund - auch unter staerkerer Ueberlappung`() {
        val grund = TageslichtEnde(HellAb.SONNENAUFGANG, t(wecktag, "07:31"), vorWeckzeit = false)
        val nachtSpanne = DimWindowResolver.DimSpan(t(nacht, "22:00")..t(wecktag, "07:31"), 30, 40, grund)
        val staerker = DimWindowResolver.DimSpan(t(nacht, "23:00")..t(wecktag, "05:00"), 70, 40)
        val zeitleiste = DimWindowResolver.mergeToTimeline(listOf(nachtSpanne, staerker))

        assertEquals(3, zeitleiste.size)
        assertNull(zeitleiste[0].tageslicht) // 22:00-23:00 endet nicht am Sonnenaufgang
        assertNull(zeitleiste[1].tageslicht) // 23:00-05:00 die staerkere, ohne Grund
        assertEquals(t(wecktag, "05:00")..t(wecktag, "07:31"), zeitleiste[2].range)
        assertEquals(grund, zeitleiste[2].tageslicht)
    }

    @Test
    fun `k - eine staerkere Spanne mit demselben Ende ohne Grund nimmt ihn nicht mit`() {
        val grund = TageslichtEnde(HellAb.SONNENAUFGANG, t(wecktag, "07:31"), vorWeckzeit = false)
        val nachtSpanne = DimWindowResolver.DimSpan(t(nacht, "22:00")..t(wecktag, "07:31"), 30, 40, grund)
        val staerker = DimWindowResolver.DimSpan(t(nacht, "23:00")..t(wecktag, "07:31"), 70, 40)
        val zeitleiste = DimWindowResolver.mergeToTimeline(listOf(nachtSpanne, staerker))
        assertEquals(2, zeitleiste.size)
        assertNull(zeitleiste.last().tageslicht)
    }

    @Test
    fun `k - verschmolzene Nachbarn behalten den Grund des spaeteren Abschnitts`() {
        val grund = TageslichtEnde(HellAb.DAEMMERUNG, t(wecktag, "07:00"), vorWeckzeit = false)
        val vorher = DimWindowResolver.DimSpan(t(nacht, "22:00")..t(nacht, "23:00"), 50, 40)
        val danach = DimWindowResolver.DimSpan(t(nacht, "23:00")..t(wecktag, "07:00"), 50, 40, grund)
        val zeitleiste = DimWindowResolver.mergeToTimeline(listOf(vorher, danach))
        assertEquals(1, zeitleiste.size)
        assertEquals(t(nacht, "22:00")..t(wecktag, "07:00"), zeitleiste.single().range)
        assertEquals(grund, zeitleiste.single().tageslicht)
    }

    // ------------------------------------------------------------------ (l) Datum = Wecktag

    @Test
    fun `l - gefragt wird der Kalendertag der WECKZEIT, nicht der des Fensterstarts`() {
        val hell = FakeHell("07:31", zone)
        // Fensterstart 22:00 am 14., Wecker 00:30 am 15. - der Wecktag ist der 15.
        spanne(fenster(), listOf(t(wecktag, "00:30")), hell)
        assertTrue(hell.gefragt.isNotEmpty())
        assertTrue("gefragt: ${hell.gefragt}", hell.gefragt.all { it.first == wecktag && it.second == HellAb.SONNENAUFGANG })
    }

    // ------------------------------------------------------------------ eine Wahrheit fuer den Editor

    @Test
    fun `nachlaufEnde rechnet dasselbe wie die Fensteraufloesung`() {
        val hell = FakeHell("07:31", zone)
        for (wecker in listOf("05:30", "07:00", "07:40")) {
            val w = t(wecktag, wecker)
            val ende = DimWindowResolver.nachlaufEnde(w, 8 * 60, 60, HellAb.SONNENAUFGANG, zone, hell)
            val s = spanne(fenster(), listOf(w), hell)
            assertEquals(s.range.last, ende.zeit)
            assertEquals(s.tageslicht, ende.tageslicht)
        }
    }

    @Test
    fun `die oeffentlichen Aufloeser liefern weiter nur die Spanne - mit Tageslicht gekuerzt`() {
        val r = DimWindowResolver.resolveFreeWindow(
            fenster(), nacht, zone, listOf(t(wecktag, "07:00")), FakeHell("07:31", zone)
        )
        assertEquals(t(nacht, "22:00")..t(wecktag, "07:31"), r)
        // Ohne Lambda (alle bisherigen Aufrufer): unveraendert.
        assertEquals(
            t(nacht, "22:00")..t(wecktag, "08:00"),
            DimWindowResolver.resolveFreeWindow(fenster(), nacht, zone, listOf(t(wecktag, "07:00")))
        )
    }

    // ------------------------------------------------------------------ Editor-Formular uebersteht Wegnavigieren

    @Test
    fun `die Fensterliste des Editors uebersteht den Speicher-Umweg samt nachlaufBisHell`() {
        val liste = listOf(fenster(hellAb = HellAb.DAEMMERUNG), DimWindow())
        assertEquals(liste, fensterListeAusJson(fensterListeAlsJson(liste)))
    }

    // ------------------------------------------------------------------ Scheduler: wann gelesen wird

    /** Zaehlt die Lesezugriffe - der Tick laeuft auch im Direct-Boot-Prozess, jeder unnoetige Read ist Flaeche. */
    private class ZaehlQuelle(private val antwort: () -> TageslichtStand) : TageslichtQuelle {
        var aufrufe = 0
        override suspend fun stand(): TageslichtStand {
            aufrufe++
            return antwort()
        }
    }

    private suspend fun scheduler(regel: DimRule, quelle: TageslichtQuelle): DimScheduleUseCase {
        val sysZone = ZoneId.systemDefault()
        val wecker = LocalDate.now(sysZone).plusDays(1).atTime(7, 0).atZone(sysZone).toInstant().toEpochMilli()
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.togglesNow()).thenReturn(DimOverlayPrefs.Toggles(dimEnabled = true))
        whenever(prefs.strengthNow()).thenReturn(55)
        whenever(prefs.warmthNow()).thenReturn(40)
        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.getAllAlarms()).thenReturn(
            Result.success(listOf(AlarmInfo(id = 1, shiftId = "", shiftName = "", triggerTime = wecker, formattedTime = "")))
        )
        val masterPausePrefs = mock<MasterPausePrefs>()
        whenever(masterPausePrefs.pausedNow()).thenReturn(false)
        val spanStore = mock<ShiftSpanStore>()
        whenever(spanStore.spansNow()).thenReturn(Result.success(emptyList()))
        val ruleRepo = mock<DimRuleRepository>()
        whenever(ruleRepo.getRules()).thenReturn(listOf(regel))
        return DimScheduleUseCase(
            mock<Context>(), alarmUseCase, spanStore, keineFreienTage(), DimRuleUseCase(ruleRepo),
            prefs, mock<DimCorrectionNotifier>(), masterPausePrefs, quelle
        )
    }

    private fun regel(w: DimWindow) = DimRule(name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, windows = listOf(w))

    @Test
    fun `der Scheduler liest den Ort NUR, wenn ein Fenster ihn braucht - und nie fuer DND`() = runTest {
        val immer = ZaehlQuelle { berlin }
        scheduler(regel(fenster(hellAb = HellAb.IMMER)), immer).previewTimeline()
        assertEquals("IMMER braucht keinen Ort", 0, immer.aufrufe)

        val ohneNachlauf = ZaehlQuelle { berlin }
        scheduler(regel(fenster(nachlauf = 0)), ohneNachlauf).previewTimeline()
        assertEquals("ohne Nachlauf gibt es nichts zu kuerzen", 0, ohneNachlauf.aufrufe)

        val gebraucht = ZaehlQuelle { berlin }
        val s = scheduler(regel(fenster()), gebraucht)
        s.previewTimeline()
        assertEquals(1, gebraucht.aufrufe)
        s.previewTimelineWithStatus(mitWeckzeitNachlauf = false)
        assertEquals("die DND-Sicht fragt nie nach dem Ort", 1, gebraucht.aufrufe)
    }

    @Test
    fun `kein Ort oder ein werfender Leser - der Scheduler rechnet den vollen Nachlauf`() = runTest {
        val erwartet = scheduler(regel(fenster(hellAb = HellAb.IMMER)), TageslichtQuelle.OHNE).previewTimeline()
        assertTrue(erwartet.isNotEmpty())

        val ohneOrt = scheduler(regel(fenster()), TageslichtQuelle.OHNE).previewTimeline()
        assertEquals(erwartet.map { it.range }, ohneOrt.map { it.range })
        assertTrue(ohneOrt.all { it.tageslicht == null })

        val werfend = scheduler(regel(fenster()), ZaehlQuelle { throw IllegalStateException("kaputt") }).previewTimeline()
        assertEquals(erwartet.map { it.range }, werfend.map { it.range })
    }
}
