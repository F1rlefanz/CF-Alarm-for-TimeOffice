package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver.AlarmSlot
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Ein Repository, das [konfig] STRIKT liefert (wie `getCurrentShiftConfig`) - fuer die Scheduler-Tests (#217). */
internal suspend fun konfigRepo(konfig: Result<ShiftConfig>): IShiftConfigRepository {
    val repo = mock<IShiftConfigRepository>()
    whenever(repo.getCurrentShiftConfig()).thenReturn(konfig)
    return repo
}

/**
 * Die Regel-Fixtures der Scheduler-Tests, ueber die ECHTE Migration (#214) auf Typen mit den Namen
 * [schichtNamen] uebersetzt. So pruefen die alten Faelle unveraendert, was der Nutzer nach dem
 * Update am Typ vorfindet - und nicht eine zweite, im Test nachgebaute Uebersetzung.
 */
internal fun konfigAusRegeln(
    regeln: List<DimRule>,
    schichtNamen: Collection<String>,
    dimmenAn: Boolean = true
): ShiftConfig = TypEinstellungenUebersetzung.uebersetze(
    ShiftConfig(
        definitions = schichtNamen.distinct().mapIndexed { i, name ->
            ShiftDefinition(id = "typ$i", name = name, keywords = listOf(name), alarmTime = LocalTime.of(6, 0))
        }
    ),
    TypEinstellungenUebersetzung.Altbestand(
        regeln = regeln,
        dimmenAn = dimmenAn,
        dndSolangeGedimmt = false,
        dndWaehrendEintrag = false,
        dndAusnahmen = emptySet(),
        rufCutoffMinuten = 5 * 60
    ),
    DimRuleUseCase(mock())
)

/**
 * Die Fenster am Typ als EINE Dimm-Quelle (#217): [TypDimmQuelle] baut je Typ eine Regel und reicht
 * sie in dieselbe Auswahl ([DimWindowResolver.buildRuleSpans]), die vorher die Regelliste bediente.
 *
 * Feste Zone Europe/Berlin und ein fester Tag VOR der Zeitumstellung (25.10.2026), damit der
 * Gleichwertigkeits-Vergleich ueber 14 Tage auch den 25-Stunden-Tag enthaelt.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class TypDimmQuelleTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val heute = LocalDate.of(2026, 10, 19)

    private fun um(tag: LocalDate, h: Int, m: Int = 0): Long = tag.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun slot(name: String, tag: LocalDate, weckH: Int, weckM: Int, ende: Long) =
        AlarmSlot(um(tag, weckH, weckM), name, ende)

    private fun uhr(vonH: Int, bisH: Int) = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = vonH * 60,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = bisH * 60
    )

    private fun vorWecker(minuten: Int) = DimWindow(
        startAnchor = DimAnchor.ALARM, startOffsetMinutes = -minuten,
        endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
    )

    private fun dimmen(vararg fenster: Pair<DimWindow, Int>, an: Boolean = true, waerme: Int? = null) =
        TypDimmen(an = an, farbwaerme = waerme).mitFenster(fenster.map { (w, s) -> TypDimmFenster(fenster = w, staerke = s) })

    private fun typ(id: String, name: String, d: TypDimmen = TypDimmen()) =
        ShiftDefinition(id = id, name = name, keywords = listOf(name), alarmTime = LocalTime.of(6, 0), dimmen = d)

    private fun spannen(quelle: TypDimmQuelle, slots: List<AlarmSlot>): List<DimWindowResolver.DimSpan> =
        DimWindowResolver.buildRuleSpans(
            alarms = slots, horizonDays = 14, today = heute, zone = zone,
            ruleForShift = quelle::fuerSchicht, ruleForFreeDay = quelle::fuerFreienTag,
            weckzeiten = slots.map { it.triggerTime }
        )

    // --- Gleichwertigkeit mit der migrierten Regel -------------------------------------------

    @Test
    fun `Fenster am Typ ergeben dieselben Spannen wie die migrierte Regel`() {
        val universal = DimRule(
            id = "u", name = "Taeglich", shiftPattern = DimRule.SHIFT_UNIVERSAL,
            windows = listOf(
                DimWindow(
                    startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
                    endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60, weckzeitNachlaufMinuten = 20
                )
            ),
            strength = 67, warmth = 0
        )
        val nacht = DimRule(
            id = "n", name = "Nacht", shiftPattern = "Nachtschicht",
            windows = listOf(
                DimWindow(
                    startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 30,
                    endAnchor = DimAnchor.SHIFT_END, endOffsetMinutes = 7 * 60,
                    blockPositionen = setOf(Blockposition.ERSTER, Blockposition.MITTLERER)
                ),
                DimWindow(
                    startAnchor = DimAnchor.CLOCK, startClockMinutes = 16 * 60,
                    endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0, startNurAmWecktag = true,
                    blockPositionen = setOf(Blockposition.LETZTER, Blockposition.EINZELNER)
                )
            ),
            strength = 55, warmth = 40
        )
        val spaet = DimRule(id = "s", name = "Spaet frei", shiftPattern = "Spaetschicht", windows = emptyList())
        val frei = DimRule(id = "f", name = "Frei", shiftPattern = DimRule.SHIFT_FREE, windows = listOf(uhr(23, 8)), strength = 40, warmth = 20)
        val regeln = listOf(universal, nacht, spaet, frei)
        val auswahl = DimRuleUseCase(mock())

        val slots = (1..3).map { i -> slot("Nachtschicht", heute.plusDays(i.toLong()), 18, 0, um(heute.plusDays(i + 1L), 6)) } +
            slot("Fruehschicht", heute.plusDays(5), 5, 30, um(heute.plusDays(5), 14)) +
            slot("Spaetschicht", heute.plusDays(6), 11, 0, um(heute.plusDays(6), 22)) +
            // Gross/Klein wie findRuleForShift - und ein einzelner Nachtdienst ueber die Zeitumstellung.
            slot("nachtschicht", heute.plusDays(8), 18, 0, um(heute.plusDays(9), 6))

        val alt = DimWindowResolver.buildRuleSpans(
            alarms = slots, horizonDays = 14, today = heute, zone = zone,
            ruleForShift = { auswahl.findRuleForShift(it, regeln) },
            ruleForFreeDay = { auswahl.findRuleForFreeDay(regeln) },
            weckzeiten = slots.map { it.triggerTime }
        )
        val config = TypEinstellungenUebersetzung.uebersetze(
            ShiftConfig(definitions = listOf(typ("a", "Fruehschicht"), typ("b", "Nachtschicht"), typ("c", "Spaetschicht"))),
            TypEinstellungenUebersetzung.Altbestand(regeln, true, false, false, emptySet(), 300),
            auswahl
        )
        val neu = spannen(TypDimmQuelle(config, vorgabeWaerme = 99), slots)

        assertEquals(alt, neu)
        // Zwei leere Listen waeren auch gleich - jede Regel muss tatsaechlich gewirkt haben.
        assertTrue("Nachtregel fehlt", neu.any { it.strength == 55 && it.warmth == 40 })
        assertTrue("UNIVERSAL fehlt", neu.any { it.strength == 67 && it.warmth == 0 })
        assertTrue("FREI fehlt", neu.any { it.strength == 40 && it.warmth == 20 })
        assertTrue("Spaetschicht unterdrueckt ihren Tag", neu.none { it.range.first in um(heute.plusDays(6), 0)..um(heute.plusDays(6), 23, 59) })
    }

    // --- Staerke, Waerme, Unterdrueckung -------------------------------------------------------

    @Test
    fun `Staerke kommt je Fenster, Waerme vom Typ sonst aus der Vorgabe`() {
        val tag = heute.plusDays(2)
        val config = ShiftConfig(
            definitions = listOf(typ("a", "Frueh", dimmen(uhr(20, 21) to 30, uhr(21, 22) to 70, waerme = 15))),
            // Die feste Zeile ohne eigene Waerme: die Vorgabe `dim_warmth` gilt.
            ohneKalendereintrag = ShiftConfig().ohneKalendereintrag.copy(dimmen = dimmen(uhr(1, 2) to 50))
        )
        val s = spannen(TypDimmQuelle(config, vorgabeWaerme = 33), listOf(slot("Frueh", tag, 5, 0, um(tag, 13))))

        assertTrue(s.contains(DimWindowResolver.DimSpan(um(tag, 20)..um(tag, 21), 30, 15)))
        assertTrue(s.contains(DimWindowResolver.DimSpan(um(tag, 21)..um(tag, 22), 70, 15)))
        assertTrue(s.contains(DimWindowResolver.DimSpan(um(heute.plusDays(3), 1)..um(heute.plusDays(3), 2), 50, 33)))
    }

    @Test
    fun `an = false dimmt nicht und unterdrueckt auch einen zweiten Eintrag desselben Tages`() {
        val einzeln = heute.plusDays(2)
        val doppelt = heute.plusDays(4)
        val config = ShiftConfig(
            definitions = listOf(
                typ("a", "Frueh", dimmen(vorWecker(60) to 60)),
                typ("b", "Ruf", dimmen(vorWecker(90) to 60, an = false))
            )
        )
        val quelle = TypDimmQuelle(config, vorgabeWaerme = 40)
        val s = spannen(
            quelle,
            listOf(
                slot("Frueh", einzeln, 5, 0, um(einzeln, 13)),
                slot("Ruf", einzeln.plusDays(1), 5, 0, um(einzeln.plusDays(1), 13)),
                slot("Frueh", doppelt, 5, 0, um(doppelt, 13)),
                slot("Ruf", doppelt, 6, 0, um(doppelt, 14))
            )
        )

        assertEquals(listOf(um(einzeln, 4)..um(einzeln, 5)), s.map { it.range })
        // Nur ein Typ mit an = false und eine feste Zeile ohne Fenster: nichts wirkt, die Kette darf gehen.
        assertFalse(TypDimmQuelle(ShiftConfig(definitions = listOf(config.definitions[1])), 40).hatWirksamesFenster)
        assertTrue(quelle.hatWirksamesFenster)
    }

    @Test
    fun `feste Zeile wirkt an freien Tagen und wie Typ nur mit dessen Uhrzeit-Fenstern`() {
        val tag = heute.plusDays(3)
        val frueh = typ("f", "Frueh", dimmen(uhr(21, 23) to 45, vorWecker(60) to 80))
        val eigene = ShiftConfig(
            definitions = listOf(frueh),
            ohneKalendereintrag = ShiftConfig().ohneKalendereintrag.copy(dimmen = dimmen(uhr(19, 20) to 25))
        )
        val freierTag = spannen(TypDimmQuelle(eigene, 40), emptyList()).filter { it.range.first in um(tag, 0)..um(tag, 23, 59) }
        assertEquals(listOf(DimWindowResolver.DimSpan(um(tag, 19)..um(tag, 20), 25, 40)), freierTag)

        // „wie Frueh“: dessen Uhrzeit-Fenster; das Weckzeit-Fenster loest ohne Schichtspanne nicht auf (bewusste Grenze).
        val wie = spannen(TypDimmQuelle(eigene.copy(ohneKalendereintragWie = "f"), 40), emptyList())
            .filter { it.range.first in um(tag, 0)..um(tag, 23, 59) }
        assertEquals(listOf(DimWindowResolver.DimSpan(um(tag, 21)..um(tag, 23), 45, 40)), wie)

        // Ein Verweis auf einen Ignorieren-Typ steht in der Typ-Seite als „Eigene Einstellungen“ - und wirkt so.
        val ignoriert = eigene.copy(
            definitions = listOf(frueh.mitKategorie(ShiftKategorie.IGNORIEREN)),
            ohneKalendereintragWie = "f"
        )
        assertEquals(freierTag, spannen(TypDimmQuelle(ignoriert, 40), emptyList()).filter { it.range.first in um(tag, 0)..um(tag, 23, 59) })
    }

    @Test
    fun `die Staerke je Fenster reist nicht in den Regel-Speicher`() {
        val regel = TypDimmQuelle(ShiftConfig(definitions = listOf(typ("a", "Frueh", dimmen(uhr(20, 21) to 30, uhr(21, 22) to 70)))), 40)
            .fuerSchicht("FRUEH")!!

        assertEquals(listOf(30, 70), regel.fensterStaerken)
        assertFalse(Json.encodeToString(DimRule.serializer(), regel).contains("fensterStaerken"))
    }

    // --- Speichern armiert neu ---------------------------------------------------------------

    @Test
    fun `was als Aenderung an Dimmen und Nicht stoeren zaehlt`() {
        val basis = ShiftConfig(definitions = listOf(typ("a", "Frueh", dimmen(uhr(20, 21) to 30))))
        val geaendert = { vorher: ShiftConfig?, nachher: ShiftConfig -> TypDimmQuelle.dimmenOderNichtStoerenGeaendert(vorher, nachher) }

        assertTrue(geaendert(null, basis))
        assertTrue(geaendert(basis, basis.copy(definitions = listOf(typ("a", "Frueh", dimmen(uhr(20, 22) to 30))))))
        assertTrue(geaendert(basis, basis.copy(definitions = listOf(basis.definitions[0].copy(nichtStoeren = TypNichtStoeren(solangeGedimmt = true))))))
        assertTrue(geaendert(basis, basis.copy(ohneKalendereintragWie = "a")))
        assertTrue(geaendert(basis, basis.copy(ohneKalendereintrag = basis.ohneKalendereintrag.copy(nichtStoerenSolangeGedimmt = true))))
        assertTrue(geaendert(basis, basis.copy(definitions = basis.definitions + typ("b", "Spaet"))))
        // Umbenennen und Weckzeit verschieben keine Dimm-Fenster - die Umbenennung hat ihren eigenen Weg.
        assertFalse(geaendert(basis, basis.copy(definitions = listOf(basis.definitions[0].copy(name = "Frueh neu", alarmTime = LocalTime.of(4, 0))))))
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(bestand: ShiftConfig, armierer: ZeitkettenArmierer): ShiftViewModel {
        val store = MutableStateFlow(bestand)
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(store)
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doAnswer { Result.success(store.value) }
            on { saveShiftConfig(any()) } doAnswer { aufruf ->
                store.value = aufruf.getArgument(0)
                Result.success(Unit)
            }
            on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        return ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = mock<IAlarmUseCase>(),
            // Ohne Termine: der Alarm-Sync laeuft nicht, armiert wird trotzdem (im finally).
            calendarStateHolder = CalendarStateHolder(),
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { mock<DimRuleUseCase>() },
            hueRuleUseCase = dagger.Lazy { mock<HueRuleUseCase>() },
            armierer = armierer,
            dndPrefs = dagger.Lazy { mock<DndPrefs>() },
            shiftSpanStore = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore>() },
            dimOverlayPrefs = dagger.Lazy { mock<DimOverlayPrefs>() },
            hueConfigRepository = dagger.Lazy {
                mock<com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository>()
            }
        )
    }

    @Test
    fun `Speichern eines Typs mit geaendertem Fenster armiert beide Ketten genau einmal`() = runTest(dispatcher) {
        val bestand = ShiftConfig(definitions = listOf(typ("a", "Frueh", dimmen(uhr(20, 21) to 30))))
        val armierer = mock<ZeitkettenArmierer>()
        val vm = viewModel(bestand, armierer)
        advanceUntilIdle()

        vm.updateShiftConfig(bestand.copy(definitions = listOf(typ("a", "Frueh", dimmen(uhr(20, 22) to 30)))))
        advanceUntilIdle()

        verifyBlocking(armierer, times(1)) { armiere(eq("SCHICHTTYP"), eq(true), eq(true)) }
    }

    @Test
    fun `Speichern ohne Aenderung an Dimmen und Nicht stoeren armiert nicht`() = runTest(dispatcher) {
        val bestand = ShiftConfig(definitions = listOf(typ("a", "Frueh", dimmen(uhr(20, 21) to 30))))
        val armierer = mock<ZeitkettenArmierer>()
        val vm = viewModel(bestand, armierer)
        advanceUntilIdle()

        vm.updateShiftConfig(bestand.copy(definitions = listOf(bestand.definitions[0].copy(alarmTime = LocalTime.of(4, 30)))))
        advanceUntilIdle()

        verifyBlocking(armierer, never()) { armiere(any(), any(), any()) }
    }
}
