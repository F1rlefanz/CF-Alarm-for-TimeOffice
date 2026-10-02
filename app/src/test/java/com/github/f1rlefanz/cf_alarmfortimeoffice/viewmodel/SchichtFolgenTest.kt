package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.time.LocalTime

/**
 * Die Statuszeile "Dimmen: … · Licht: … · DND: …" je Schicht (Issue #70).
 *
 * Geprueft wird jede Zeile der Falltabellen aus dem Umsetzungsplan - und zwar ueber DIESELBEN
 * Funktionen wie zur Laufzeit: die Dimm-Auswahl kommt aus einem echten
 * [DimRuleUseCase.findRuleForShift], die Hue-Auswahl aus `passtAufSchicht`, der DND-Abgleich ist
 * exakt wie im `DndShiftSpanResolver`. Eine Zeile, die anders zaehlt als die Wirkung, luegt genau
 * dann, wenn man sie braucht.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class SchichtFolgenTest {

    /** Geschuetztes Leerzeichen (U+00A0) - als Code, nicht als unsichtbares Zeichen im Quelltext. */
    private val NBSP: Char = Char(0x00A0)

    // --- Bausteine -------------------------------------------------------------------------

    private val auswahl = DimRuleUseCase(mock<DimRuleRepository>())

    private fun def(
        name: String = "AD1",
        enabled: Boolean = true,
        silent: Boolean = false,
        onCall: Boolean = false
    ) = ShiftDefinition(
        id = "id-$name",
        name = name,
        keywords = listOf(name),
        alarmTime = LocalTime.of(5, 30),
        isEnabled = enabled,
        isSilent = silent,
        isOnCall = onCall
    )

    private fun dimRegel(
        id: String,
        muster: String,
        aktiv: Boolean = true,
        fenster: List<DimWindow> = listOf(DimWindow())
    ) = DimRule(id = id, name = "Regel $id", shiftPattern = muster, enabled = aktiv, windows = fenster)

    private fun hueRegel(id: String, muster: String, aktiv: Boolean = true): HueSchedule =
        HueScheduleRule(id = id, name = "Hue $id", shiftPattern = muster, enabled = aktiv, timeRanges = emptyList())

    private val dndAus = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = false)

    /** Baut die Folgen mit neutralen Defaults fuer die jeweils nicht betrachteten Bereiche. */
    private fun folgen(
        definition: ShiftDefinition = def(),
        dimAn: Boolean = true,
        dimRegeln: List<DimRule> = emptyList(),
        hueRegeln: Result<List<HueSchedule>> = Result.success(emptyList()),
        hueKonfiguriert: Boolean = true,
        dnd: DndPrefs.Toggles = dndAus,
        ausgenommen: Set<String> = emptySet()
    ): SchichtFolgen = ermittleSchichtFolgen(
        definition = definition,
        dimAn = dimAn,
        dimRegeln = dimRegeln,
        ruleForShift = { name -> auswahl.findRuleForShift(name, dimRegeln) },
        hueRegeln = hueRegeln,
        hueKonfiguriert = hueKonfiguriert,
        dndToggles = dnd,
        dndAusgenommen = ausgenommen
    )

    /** Der Text mit normalen Leerzeichen - die geschuetzten prueft ein eigener Test. */
    private fun SchichtFolgen.lesbar(): String = alsText().replace(NBSP, ' ')

    private fun abschnitt(f: SchichtFolgen, index: Int): String = f.lesbar().split(" · ")[index]
    private fun dimmen(f: SchichtFolgen) = abschnitt(f, 0)
    private fun licht(f: SchichtFolgen) = abschnitt(f, 1)
    private fun dnd(f: SchichtFolgen) = abschnitt(f, 2)

    // --- Dimmen ----------------------------------------------------------------------------

    @Test
    fun `Dimmen - Hauptschalter aus heisst aus, auch mit eigener Regel`() {
        val f = folgen(dimAn = false, dimRegeln = listOf(dimRegel("r1", "AD1")))
        assertEquals("Dimmen: aus", dimmen(f))
    }

    @Test
    fun `Dimmen - keine passende Regel heisst keine`() {
        val f = folgen(dimRegeln = listOf(dimRegel("r1", "Frühdienst"), dimRegel("r2", DimRule.SHIFT_FREE)))
        assertEquals("Dimmen: keine", dimmen(f))
    }

    @Test
    fun `Dimmen - nur UNIVERSAL greift heisst allgemeine Regel`() {
        val f = folgen(dimRegeln = listOf(dimRegel("u", DimRule.SHIFT_UNIVERSAL)))
        assertEquals("Dimmen: allgemeine Regel", dimmen(f))
    }

    @Test
    fun `Dimmen - eigene Regel mit leerer Fensterliste heisst unterdrueckt`() {
        val f = folgen(
            dimRegeln = listOf(
                dimRegel("u", DimRule.SHIFT_UNIVERSAL),
                dimRegel("r1", "AD1", fenster = emptyList())
            )
        )
        assertEquals("Dimmen: unterdrückt", dimmen(f))
    }

    @Test
    fun `Dimmen - auch eine UNIVERSAL-Regel ohne Fenster unterdrueckt`() {
        // regelFuerTag Schritt 1 fragt nur nach der leeren Fensterliste, nicht nach dem Muster.
        val f = folgen(dimRegeln = listOf(dimRegel("u", DimRule.SHIFT_UNIVERSAL, fenster = emptyList())))
        assertEquals("Dimmen: unterdrückt", dimmen(f))
    }

    @Test
    fun `Dimmen - eigene Regel mit Fenstern verdraengt UNIVERSAL`() {
        val f = folgen(
            dimRegeln = listOf(dimRegel("u", DimRule.SHIFT_UNIVERSAL), dimRegel("r1", "AD1"))
        )
        assertEquals("Dimmen: eigene Regel", dimmen(f))
    }

    @Test
    fun `Dimmen - Name wird wie zur Laufzeit ohne Gross-Kleinschreibung verglichen`() {
        val f = folgen(dimRegeln = listOf(dimRegel("r1", "ad1")))
        assertEquals("Dimmen: eigene Regel", dimmen(f))
    }

    @Test
    fun `Dimmen - weitere aktive Regel auf denselben Namen ist ohne Wirkung`() {
        val f = folgen(
            dimRegeln = listOf(
                dimRegel("r1", "AD1"),
                dimRegel("r2", "ad1"),
                dimRegel("r3", "AD1", aktiv = false), // deaktiviert: zaehlt nicht
                dimRegel("u", DimRule.SHIFT_UNIVERSAL) // Sondermuster: zaehlt nie
            )
        )
        assertEquals("Dimmen: eigene Regel (+1 ohne Wirkung)", dimmen(f))
        assertEquals(DimmStatus.Wirksam(DimmArt.EIGENE, ohneWirkung = 1), f.dimmen)
    }

    @Test
    fun `Dimmen - es wirkt die ERSTE Regel, die zweite gilt als ohne Wirkung`() {
        // Die erste hat keine Fenster -> unterdrueckt; die zweite (mit Fenstern) waere tot.
        val f = folgen(
            dimRegeln = listOf(dimRegel("r1", "AD1", fenster = emptyList()), dimRegel("r2", "AD1"))
        )
        assertEquals("Dimmen: unterdrückt (+1 ohne Wirkung)", dimmen(f))
    }

    // --- Licht -----------------------------------------------------------------------------

    @Test
    fun `Licht - Lesefehler heisst nicht lesbar, nie keine`() {
        val f = folgen(hueRegeln = Result.failure(IllegalStateException("kaputt")))
        assertEquals("Licht: nicht lesbar", licht(f))
    }

    @Test
    fun `Licht - stille Schicht fuehrt keine Hue-Regel aus`() {
        val f = folgen(definition = def(silent = true), hueRegeln = Result.success(listOf(hueRegel("h1", "AD1"))))
        assertEquals("Licht: aus (stille Schicht)", licht(f))
    }

    @Test
    fun `Licht - ohne Bridge geht kein Licht an`() {
        val f = folgen(hueKonfiguriert = false, hueRegeln = Result.success(listOf(hueRegel("h1", "AD1"))))
        assertEquals("Licht: keine Bridge", licht(f))
    }

    @Test
    fun `Licht - nur eigene Regeln werden gezaehlt`() {
        assertEquals(
            "Licht: 1 Regel",
            licht(folgen(hueRegeln = Result.success(listOf(hueRegel("h1", "AD1")))))
        )
        assertEquals(
            "Licht: 2 Regeln",
            licht(folgen(hueRegeln = Result.success(listOf(hueRegel("h1", "AD1"), hueRegel("h2", "ad1")))))
        )
    }

    @Test
    fun `Licht - eigene und allgemeine Regeln feuern gemeinsam`() {
        val f = folgen(hueRegeln = Result.success(listOf(hueRegel("h1", "AD1"), hueRegel("h2", "ALL"))))
        assertEquals("Licht: 1 Regel + allgemeine", licht(f))
        assertEquals(LichtStatus.Regeln(eigene = 1, allgemeine = 1), f.licht)
    }

    @Test
    fun `Licht - nur allgemeine Regel, Muster wie zur Laufzeit ohne Gross-Kleinschreibung`() {
        val f = folgen(hueRegeln = Result.success(listOf(hueRegel("h1", "all"))))
        assertEquals("Licht: allgemeine Regel", licht(f))
        assertEquals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, "ALL") // Anker des Tests
    }

    @Test
    fun `Licht - sonst keine, deaktivierte und fremde Regeln zaehlen nicht`() {
        val f = folgen(
            hueRegeln = Result.success(listOf(hueRegel("h1", "AD1", aktiv = false), hueRegel("h2", "Frühdienst")))
        )
        assertEquals("Licht: keine", licht(f))
    }

    // --- DND -------------------------------------------------------------------------------

    @Test
    fun `DND - beide Quellen aus heisst aus, auch bei Rufbereitschaft`() {
        val f = folgen(definition = def(onCall = true), ausgenommen = setOf("AD1"))
        assertEquals("DND: aus", dnd(f))
        assertFalse(f.dnd.rufbereitschaft)
    }

    @Test
    fun `DND - nur Dienstzeit, nicht ausgenommen`() {
        val f = folgen(dnd = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true))
        assertEquals("DND: Dienstzeit", dnd(f))
    }

    @Test
    fun `DND - nur Dienstzeit, ausgenommen`() {
        val f = folgen(
            dnd = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true),
            ausgenommen = setOf("AD1")
        )
        assertEquals("DND: ausgenommen", dnd(f))
    }

    @Test
    fun `DND - Ausnahme wird EXAKT abgeglichen wie im Resolver`() {
        val f = folgen(
            dnd = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true),
            ausgenommen = setOf("ad1")
        )
        assertEquals("DND: Dienstzeit", dnd(f))
    }

    @Test
    fun `DND - nur folgt Dimmer, die Ausnahme wirkt darauf nicht`() {
        val toggles = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = false)
        assertEquals("DND: nach Dimmer", dnd(folgen(dnd = toggles)))
        assertEquals("DND: nach Dimmer", dnd(folgen(dnd = toggles, ausgenommen = setOf("AD1"))))
    }

    @Test
    fun `DND - beide an ergibt die Kombination`() {
        val f = folgen(dnd = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true))
        assertEquals("DND: Dienstzeit + nach Dimmer", dnd(f))
    }

    @Test
    fun `DND - beide an, Dienstzeit ausgenommen`() {
        val f = folgen(
            dnd = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true),
            ausgenommen = setOf("AD1")
        )
        assertEquals("DND: nach Dimmer (Dienstzeit ausgenommen)", dnd(f))
    }

    @Test
    fun `DND - Rufbereitschaft nur mit mindestens einer Quelle`() {
        val oc = def(onCall = true)
        assertEquals(
            "DND: Dienstzeit, Rufbereitschaft",
            dnd(folgen(definition = oc, dnd = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)))
        )
        assertEquals(
            "DND: nach Dimmer, Rufbereitschaft",
            dnd(folgen(definition = oc, dnd = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = false)))
        )
        assertEquals(
            "DND: nach Dimmer (Dienstzeit ausgenommen), Rufbereitschaft",
            dnd(
                folgen(
                    definition = oc,
                    dnd = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true),
                    ausgenommen = setOf("AD1")
                )
            )
        )
    }

    // --- Text und Umbruch ------------------------------------------------------------------

    @Test
    fun `ganze Zeile in der Reihenfolge Dimmen, Licht, DND`() {
        val f = folgen(
            dimRegeln = listOf(dimRegel("r1", "AD1")),
            hueRegeln = Result.success(listOf(hueRegel("h1", "ALL"))),
            dnd = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)
        )
        assertEquals("Dimmen: eigene Regel · Licht: allgemeine Regel · DND: Dienstzeit", f.lesbar())
    }

    @Test
    fun `umbrochen wird nur hinter dem Trenner und vor den Zusaetzen`() {
        val f = folgen(
            definition = def(onCall = true),
            dimRegeln = listOf(dimRegel("r1", "AD1"), dimRegel("r2", "AD1")),
            hueRegeln = Result.success(listOf(hueRegel("h1", "AD1"), hueRegel("h2", "ALL"))),
            dnd = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true)
        )
        val text = f.alsText()
        // Normale Leerzeichen gibt es genau an den erlaubten Bruchstellen.
        val erlaubt = listOf(
            "${NBSP}· Licht", "${NBSP}· DND", // hinter dem Trenner
            "Regel (+1", // vor dem Dimm-Zusatz
            "Dimmer, Rufbereitschaft" // hinter dem Komma
        )
        val normaleLeerzeichen = text.count { it == ' ' }
        assertEquals(erlaubt.size, normaleLeerzeichen)
        erlaubt.forEach { stelle ->
            assertTrue("Bruchstelle fehlt: $stelle in $text", text.contains(stelle))
        }
        // Der Punkt rutscht nie an den Zeilenanfang: davor steht immer ein geschuetztes Leerzeichen.
        assertFalse(text.contains(" ·"))
    }

    // --- ViewModel: deaktivierte Schicht, Lesen erst beim Abo --------------------------------

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ViewModel liest erst beim Abo und zeigt fuer eine deaktivierte Schicht keine Zeile`() =
        runTest(dispatcher) {
            val config = ShiftConfig(definitions = listOf(def("AD1"), def("Frei", enabled = false)))
            val shiftUseCase = mock<IShiftUseCase>()
            whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(config))
            shiftUseCase.stub {
                on { getCurrentShiftConfig() } doReturn Result.success(config)
                on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
            }

            val dimRepo = mock<DimRuleRepository>()
            whenever(dimRepo.rules).thenReturn(flowOf(listOf(dimRegel("r1", "AD1"))))
            val dimUseCase = DimRuleUseCase(dimRepo)
            val overlay = mock<DimOverlayPrefs>()
            whenever(overlay.toggles).thenReturn(flowOf(DimOverlayPrefs.Toggles(dimEnabled = true)))
            val dnd = mock<DndPrefs>()
            whenever(dnd.toggles).thenReturn(
                flowOf(DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true))
            )
            whenever(dnd.shiftExcludedShifts).thenReturn(flowOf(emptySet()))
            val hue = mock<IHueConfigRepository>()
            whenever(hue.getConfiguration()).thenReturn(
                flowOf(HueConfiguration(bridgeIp = "1.2.3.4", username = "u", isConfigured = true))
            )
            hue.stub { on { getScheduleRules() } doReturn Result.success(listOf(hueRegel("h1", "AD1"))) }

            var angefasst = 0
            val vm = ShiftViewModel(
                shiftUseCase = shiftUseCase,
                alarmUseCase = mock<IAlarmUseCase>(),
                calendarStateHolder = CalendarStateHolder(),
                errorHandler = mock<ErrorHandler>(),
                dimRuleUseCase = dagger.Lazy { angefasst++; dimUseCase },
                hueRuleUseCase = dagger.Lazy { error("Die Statuszeile darf den Hue-Graphen nicht bauen") },
                armierer = mock<ZeitkettenArmierer>(),
                dndPrefs = dagger.Lazy { angefasst++; dnd },
                shiftSpanStore = dagger.Lazy { mock<ShiftSpanStore>() },
                dimOverlayPrefs = dagger.Lazy { angefasst++; overlay },
                hueConfigRepository = dagger.Lazy { angefasst++; hue }
            )
            advanceUntilIdle()
            assertEquals("Ohne Abo darf keine Quelle der Statuszeile angefasst werden", 0, angefasst)

            backgroundScope.launch { vm.schichtFolgen.collect { } }
            // `advanceUntilIdle()` allein haelt an, sobald nur noch Hintergrund-Arbeit ansteht - der
            // Sammler im `backgroundScope` liefe dann nie an. `runCurrent()` startet ihn; das Abo
            // stoesst die Quellen im viewModelScope an, und die arbeitet `advanceUntilIdle()` ab.
            runCurrent()
            advanceUntilIdle()

            val zeilen = vm.schichtFolgen.value
            assertEquals(setOf("id-AD1"), zeilen.keys) // die deaktivierte Schicht fehlt
            assertEquals(
                "Dimmen: eigene Regel · Licht: 1 Regel · DND: Dienstzeit",
                zeilen.getValue("id-AD1").lesbar()
            )
        }
}
