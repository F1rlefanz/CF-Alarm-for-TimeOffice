package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Spur
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.SpurStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.alsQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.fehlerAlsUnbekannt
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Die Quellen der Tagesleiste im `ShiftViewModel` (Umbau #196, SPEC 3.3 Punkt 3).
 *
 * Der Kern: ein LESEFEHLER einer Quelle wird [Quelle.Unbekannt], nie eine leere Liste - "kein Typ
 * hat ein Fenster" hiesse "kein Dimmen", "keine Hue-Regel" hiesse "kein Licht" (Pflichtregel "Kein Fehler
 * darf als leeres Erfolgsergebnis durchrutschen"). Und er trifft nur die Spuren, die an der Quelle
 * haengen. Seit #219 liegen Dimmen und "Nicht stoeren" am Typ: beide Spuren haengen an der Konfiguration.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle, runCurrent
class TagesleisteQuellenTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Bausteine -------------------------------------------------------------------------

    private val typF = ShiftDefinition(
        id = "id-F", name = "F", keywords = listOf("F"), alarmTime = LocalTime.of(4, 30),
        dimmen = TypDimmen().mitFenster(listOf(TypDimmFenster(DimWindow(), 55))),
        nichtStoeren = TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true)
    )
    private val hueRegel: HueSchedule =
        HueScheduleRule(id = "h1", name = "Hue h1", shiftPattern = "AD1", enabled = true, timeRanges = emptyList())
    private val spanne = ShiftSpan("AD1", startTime = 1_000L, endTime = 2_000L, alarmTriggerTime = 500L)

    /** Alles, was ein Lauf einstellen kann; der Rest sind gesunde Vorgaben. */
    private class Welt(
        var konfig: Result<ShiftConfig>,
        var hueRegeln: Result<List<HueSchedule>>,
        var spannen: Result<List<ShiftSpan>>
    ) {
        var angefasst = 0
        var hueGraphGebaut = 0
        var tageslichtGeholt = 0
        val holder = CalendarStateHolder()
        val alarmUseCase = mock<IAlarmUseCase>()
        lateinit var vm: ShiftViewModel
        lateinit var spanStore: ShiftSpanStore
        lateinit var hueUseCase: HueRuleUseCase
    }

    private fun welt(
        konfig: Result<ShiftConfig> = Result.success(ShiftConfig(definitions = listOf(typF))),
        hueRegeln: Result<List<HueSchedule>> = Result.success(listOf(hueRegel)),
        spannen: Result<List<ShiftSpan>> = Result.success(listOf(spanne)),
        tageslicht: TageslichtQuelle = TageslichtQuelle.OHNE
    ): Welt {
        val w = Welt(konfig, hueRegeln, spannen)
        val config = ShiftConfig(definitions = emptyList())
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(config))
        shiftUseCase.stub {
            // Der Wert der Tagesleiste kommt aus dem Result - nie aus dem Flow, der auf Standard degradiert.
            onBlocking { getCurrentShiftConfig() } doAnswer { w.konfig }
            on { saveShiftConfig(any()) } doReturn Result.success(Unit)
            on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val dimUseCase = DimRuleUseCase(mock<DimRuleRepository>())
        val overlay = mock<DimOverlayPrefs>()
        val dnd = mock<DndPrefs>()
        val hue = mock<IHueConfigRepository>()
        whenever(hue.getConfiguration()).thenReturn(
            flowOf(HueConfiguration(bridgeIp = "1.2.3.4", username = "u", isConfigured = true))
        )
        hue.stub { on { getScheduleRules() } doReturn hueRegeln }
        w.spanStore = mock<ShiftSpanStore>()
        w.spanStore.stub { onBlocking { spansNow() } doReturn spannen }
        w.hueUseCase = mock<HueRuleUseCase>()

        w.vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = w.alarmUseCase,
            calendarStateHolder = w.holder,
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { w.angefasst++; dimUseCase },
            hueRuleUseCase = dagger.Lazy { w.hueGraphGebaut++; w.hueUseCase },
            armierer = mock<ZeitkettenArmierer>(),
            dndPrefs = dagger.Lazy { w.angefasst++; dnd },
            shiftSpanStore = dagger.Lazy { w.spanStore },
            dimOverlayPrefs = dagger.Lazy { w.angefasst++; overlay },
            hueConfigRepository = dagger.Lazy { w.angefasst++; hue },
            tageslichtQuelle = dagger.Lazy { w.tageslichtGeholt++; tageslicht }
        )
        return w
    }

    /** Abonniert die Quellen (sie sind `WhileSubscribed`) und arbeitet das Lesen ab. */
    private fun TestScope.lese(w: Welt): TagesleisteQuellen {
        backgroundScope.launch { w.vm.tagesleisteQuellen.collect { } }
        runCurrent()
        advanceUntilIdle()
        val stand = w.vm.tagesleisteQuellen.value
        assertNotNull("Nach dem Lesen muss ein Stand vorliegen", stand)
        return stand!!
    }

    private fun TagesleisteQuellen.staende(): Map<Spur, SpurStand> = Spur.entries.associateWith { stand(it) }

    private fun alleBekannt() = Spur.entries.associateWith { SpurStand.BEKANNT }

    // --- Tageslicht: kein Bestand dieser Schicht, und ohne Ort heisst keine Einschraenkung ---

    @Test
    fun `der Tageslicht-Stand kommt aus der Quelle und wird erst beim Abo gelesen`() = runTest(dispatcher) {
        val berlin = TageslichtStand(Ort(52.52, 13.40, OrtQuelle.PLZ, null), ZoneId.of("Europe/Berlin"))
        var gelesen = 0
        val w = welt(
            tageslicht = object : TageslichtQuelle {
                override suspend fun stand(): TageslichtStand {
                    gelesen++
                    return berlin
                }
            }
        )

        runCurrent()
        assertEquals("Das Bauen des ViewModels fasst die Quelle nicht an", 0, w.tageslichtGeholt)
        assertEquals(0, gelesen)

        val q = lese(w)

        assertSame(berlin, q.tageslicht)
        assertEquals(1, gelesen)
    }

    @Test
    fun `wirft die Tageslicht-Quelle doch, gilt kein Ort - und keine andere Spur faellt aus`() = runTest(dispatcher) {
        val q = lese(
            welt(
                tageslicht = object : TageslichtQuelle {
                    override suspend fun stand(): TageslichtStand = throw IOException("tageslicht nicht lesbar")
                }
            )
        )

        assertNull(q.tageslicht.ort)
        assertEquals(alleBekannt(), q.staende())
    }

    // --- Der Kern: Lesefehler wird sichtbar, nicht leer -------------------------------------

    @Test
    fun `Lesefehler bei den Hue-Regeln macht NUR die Licht-Spur unbekannt - nicht leer`() = runTest(dispatcher) {
        val q = lese(welt(hueRegeln = Result.failure(IOException("hue_rules nicht lesbar"))))

        assertEquals("Hue-Regeln unlesbar heisst Licht unbekannt, nicht 'keine Regel'", Quelle.Unbekannt, q.licht)
        assertEquals(
            alleBekannt() + (Spur.LICHT to SpurStand.UNBEKANNT),
            q.staende()
        )
        // Die uebrigen Quellen sind unberuehrt - ein Fehler schaltet nicht alle Spuren ab.
        assertTrue(q.typen is Quelle.Bekannt)
        assertTrue(q.spannen is Quelle.Bekannt)
    }

    /**
     * #219 / #210: Dimmen und "Nicht stoeren" kommen aus der Konfiguration, und die wird ueber das `Result`
     * gelesen. Scheitert der Read, stehen BEIDE Spuren auf "unbekannt" - nie auf "kein Dimmen". Vor #219
     * lasen sie aus Stores, die still auf leer/aus degradierten (`DimRuleRepository.rules`, `safeData`):
     * ein Lesefehler kam als "kein Dimmen" an.
     */
    @Test
    fun `Lesefehler der Konfiguration macht Dimmen und Nicht stoeren unbekannt - nicht kein Dimmen`() = runTest(dispatcher) {
        val q = lese(welt(konfig = Result.failure(IOException("shift_config nicht lesbar"))))

        assertEquals(Quelle.Unbekannt, q.typen)
        assertEquals(
            alleBekannt() + (Spur.DIMMEN to SpurStand.UNBEKANNT) + (Spur.NICHT_STOEREN to SpurStand.UNBEKANNT),
            q.staende()
        )
        assertTrue("Hue und Spannen sind vom Konfigurations-Fehler unberuehrt", q.licht is Quelle.Bekannt && q.spannen is Quelle.Bekannt)
    }

    @Test
    fun `ein Speichern liest die Typ-Einstellungen neu - auch nach einem frueheren Lesefehler`() = runTest(dispatcher) {
        val w = welt(konfig = Result.failure(IOException("shift_config nicht lesbar")))
        assertEquals(Quelle.Unbekannt, lese(w).typen)

        // Der Bestand ist wieder lesbar; das Speichern ist der Ausloeser fuers Neu-Lesen.
        val neu = ShiftConfig(definitions = listOf(typF))
        w.konfig = Result.success(neu)
        w.vm.updateShiftConfig(neu)
        advanceUntilIdle()

        assertEquals(Quelle.Bekannt(neu), w.vm.tagesleisteQuellen.value!!.typen)
    }

    @Test
    fun `Lesefehler bei den Spannen macht jede Spur unbekannt - ihre Dienstzeit legt den Weckpunkt fest`() = runTest(dispatcher) {
        val q = lese(welt(spannen = Result.failure(IOException("shift_spans nicht lesbar"))))

        assertEquals(Quelle.Unbekannt, q.spannen)
        assertEquals(Spur.entries.associateWith { SpurStand.UNBEKANNT }, q.staende())
        // Die anderen Quellen wurden trotzdem gelesen und sind bekannt.
        assertTrue(q.typen is Quelle.Bekannt && q.licht is Quelle.Bekannt)
    }

    @Test
    fun `gesunde Quellen kommen mit ihren Werten an, auch die Typ-Einstellungen`() = runTest(dispatcher) {
        val w = welt()
        val q = lese(w)

        assertEquals(alleBekannt(), q.staende())
        assertEquals(listOf(typF), (q.typen as Quelle.Bekannt).wert.definitions)
        val licht = (q.licht as Quelle.Bekannt).wert
        assertEquals(true, licht.konfiguriert)
        assertEquals(listOf(hueRegel), licht.regeln)
        assertEquals(listOf(spanne), (q.spannen as Quelle.Bekannt).wert)
        assertEquals("Das Abo baut den Hue-Graphen nicht", 0, w.hueGraphGebaut)
    }

    @Test
    fun `die Vorlauf-Rechnung der Licht-Quelle greift erst beim Aufruf auf den Hue-Use-Case zu`() = runTest(dispatcher) {
        val w = welt()
        val licht = (lese(w).licht as Quelle.Bekannt).wert
        assertEquals(0, w.hueGraphGebaut)
        whenever(w.hueUseCase.getPreAlarmSunriseLeadMinutes(any(), any(), anyOrNullLong(), anyOrNullStand())).thenReturn(7)

        assertEquals(7, licht.vorlaufMinuten(listOf(hueRegel), "AD1", null, null))
        assertEquals(1, w.hueGraphGebaut)
    }

    private fun anyOrNullLong(): Long? = org.mockito.kotlin.anyOrNull()
    private fun anyOrNullStand(): com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand? =
        org.mockito.kotlin.anyOrNull()

    // --- Wann gelesen wird -----------------------------------------------------------------

    @Test
    fun `ohne Abo wird nichts gelesen, und vor dem ersten Lesen gibt es keinen Stand (null, nicht unbekannt)`() =
        runTest(dispatcher) {
            val w = welt()
            advanceUntilIdle()

            assertEquals("Ohne Abo darf keine Quelle angefasst werden (nicht beim App-Start)", 0, w.angefasst)
            // Der Span-Store selbst wird schon von `aktualisiereLetztenSchichtStand()` angefasst (andere
            // Methode, `letzterStand()`); die Tagesleiste liest `spansNow()` erst mit dem Abo.
            verifyBlocking(w.spanStore, never()) { spansNow() }
            assertNull("null heisst noch nicht gelesen - Unbekannt waere ein Lesefehler", w.vm.tagesleisteQuellen.value)

            lese(w)
            assertTrue("Mit dem Abo werden die Quellen gelesen", w.angefasst > 0)
            verifyBlocking(w.spanStore) { spansNow() }
        }

    @Test
    fun `eine neue Eventliste liest die Spannen neu`() = runTest(dispatcher) {
        val w = welt()
        val q1 = lese(w)
        assertEquals(listOf(spanne), (q1.spannen as Quelle.Bekannt).wert)

        val neu = ShiftSpan("AD1", startTime = 5_000L, endTime = 6_000L, alarmTriggerTime = 4_500L)
        w.spanStore.stub { onBlocking { spansNow() } doReturn Result.success(listOf(neu)) }
        w.holder.updateEvents(
            listOf(
                CalendarEvent(
                    id = "e1", title = "AD1",
                    startTime = LocalDateTime.of(2026, 8, 20, 6, 0), endTime = LocalDateTime.of(2026, 8, 20, 14, 0),
                    calendarId = "cal"
                )
            ),
            complete = true
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(neu), ((w.vm.tagesleisteQuellen.value)!!.spannen as Quelle.Bekannt).wert)
    }

    /**
     * #196, Befund des unabhaengigen Pruefers: Speichern eines Typs laeuft ueber `updateShiftConfig` ->
     * `syncAlarms`, und der Sync SCHREIBT die Spannen neu - waehrend die Eventliste gleich bleibt. Ohne
     * eigenen Ausloeser blieben die Spannen der Tagesleiste auf dem Stand von davor ("Im Kalender steht
     * noch kein Termin dieses Typs" nach dem Umbenennen).
     *
     * Der Ablauf geht ueber den ECHTEN Einstieg, keinen Test-Haken: die Spannen werden NACH dem ersten
     * Lesen umgestellt, die Eventliste bleibt dieselbe, und nur das Speichern darf sie neu lesen lassen.
     */
    private fun TestScope.spannenNachSpeichernNeuGelesen(syncErgebnis: Result<List<com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo>>) {
        val w = welt()
        w.holder.updateEvents(
            listOf(
                CalendarEvent(
                    id = "e1", title = "AD1",
                    startTime = LocalDateTime.of(2026, 8, 20, 6, 0), endTime = LocalDateTime.of(2026, 8, 20, 14, 0),
                    calendarId = "cal"
                )
            ),
            complete = true
        )
        w.alarmUseCase.stub { onBlocking { syncAlarms(any(), any(), anyOrNull()) } doReturn syncErgebnis }
        val vorher = lese(w)
        assertEquals(listOf(spanne), (vorher.spannen as Quelle.Bekannt).wert)

        // Der Sync schreibt die Spannen neu (hier: vor dem Speichern umgestellt - gelesen wird erst danach).
        val neu = ShiftSpan("AD2", startTime = 5_000L, endTime = 6_000L, alarmTriggerTime = 4_500L)
        w.spanStore.stub { onBlocking { spansNow() } doReturn Result.success(listOf(neu)) }

        w.vm.updateShiftConfig(ShiftConfig(autoAlarmEnabled = true, definitions = emptyList()))
        advanceUntilIdle()

        assertEquals(
            "Nach dem Speichern (Sync $syncErgebnis) muessen die neu geschriebenen Spannen ankommen",
            listOf(neu),
            ((w.vm.tagesleisteQuellen.value)!!.spannen as Quelle.Bekannt).wert
        )
    }

    @Test
    fun `Speichern eines Typs mit erfolgreichem Sync liest die Spannen neu - Eventliste unveraendert`() =
        runTest(dispatcher) { spannenNachSpeichernNeuGelesen(Result.success(emptyList())) }

    @Test
    fun `Speichern eines Typs liest die Spannen auch neu, wenn der Sync scheitert - er schreibt sie vor dem Scheitern`() =
        runTest(dispatcher) { spannenNachSpeichernNeuGelesen(Result.failure(IOException("Sync gescheitert"))) }

    // --- Die Abhaengigkeiten der Spuren (rein) ----------------------------------------------

    private val bekannteTypen = Quelle.Bekannt(ShiftConfig(definitions = listOf(typF)))
    private val bekanntLicht = Quelle.Bekannt(
        com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle(true, emptyList()) { _, _, _, _ -> null }
    )
    private val bekannteSpannen = Quelle.Bekannt(emptyList<ShiftSpan>())

    @Test
    fun `jede Spur ist genau von ihren Quellen abhaengig`() {
        fun quellen(
            typen: Quelle<ShiftConfig> = bekannteTypen,
            licht: Quelle<com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle> = bekanntLicht,
            spannen: Quelle<List<ShiftSpan>> = bekannteSpannen
        ) = TagesleisteQuellen(typen, licht, spannen)

        val unbekannt = SpurStand.UNBEKANNT

        assertEquals(alleBekannt(), quellen().staende())
        // Dimmen UND "Nicht stoeren" liegen am Typ und haengen deshalb an derselben Quelle.
        assertEquals(alleBekannt() + (Spur.DIMMEN to unbekannt) + (Spur.NICHT_STOEREN to unbekannt), quellen(typen = Quelle.Unbekannt).staende())
        assertEquals(alleBekannt() + (Spur.LICHT to unbekannt), quellen(licht = Quelle.Unbekannt).staende())
        assertEquals(Spur.entries.associateWith { unbekannt }, quellen(spannen = Quelle.Unbekannt).staende())
    }

    // --- Fangstellen ------------------------------------------------------------------------

    @Test
    fun `eine CancellationException ist kein Lesefehler und wird nicht zu unbekannt`() = runTest(dispatcher) {
        try {
            flow<Quelle<Int>> { throw CancellationException("abgebrochen") }.fehlerAlsUnbekannt("t").toList()
            fail("Der Flow-Fang darf eine CancellationException nicht schlucken")
        } catch (erwartet: CancellationException) {
            // richtig
        }
        try {
            Result.failure<Int>(CancellationException("abgebrochen")).alsQuelle("t") { it }
            fail("Der Result-Fang darf eine CancellationException nicht schlucken")
        } catch (erwartet: CancellationException) {
            // richtig
        }
        val geworfen = flow<Quelle<Int>> { throw IOException("kaputt") }.fehlerAlsUnbekannt("t").toList()
        assertSame(Quelle.Unbekannt, geworfen.single())
    }
}
