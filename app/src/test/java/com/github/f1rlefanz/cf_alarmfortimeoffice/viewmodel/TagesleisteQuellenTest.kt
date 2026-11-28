package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypLicht
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.TagesleisteKarteLogik
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.TypEntwurf
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import java.time.ZoneOffset

/**
 * Die Quellen der Tagesleiste im `ShiftViewModel` (Umbau #196, SPEC 3.3 Punkt 3).
 *
 * Der Kern: ein LESEFEHLER einer Quelle wird [Quelle.Unbekannt], nie eine leere Liste - "kein Typ
 * hat ein Fenster" hiesse "kein Dimmen", "keine Hue-Regel" hiesse "kein Licht" (Pflichtregel "Kein Fehler
 * darf als leeres Erfolgsergebnis durchrutschen"). Und er trifft nur die Spuren, die an der Quelle
 * haengen. Seit #219 liegen Dimmen und "Nicht stoeren" am Typ, seit #253 liest die Leiste auch die
 * Lichtregeln dort; aus dem Hue-Store kommt nur noch "Bridge eingerichtet" (strikt, #210).
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

    /** Eine Regel, die eine Gruppe [minuten] lang anschaltet. [muster] steht nur im Speicher und zaehlt nicht. */
    private fun lampeRegel(id: String, minuten: Int, muster: String = "egal") = HueScheduleRule(
        id = id, name = "Hue $id", shiftPattern = muster, enabled = true,
        timeRanges = listOf(
            HueTimeRange(
                listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP, targetId = "1", actionType = ActionType.TURN_ON,
                        on = true, duration = minuten, isGroup = true
                    )
                )
            )
        )
    )

    // "ALL" im Speicher: eine Kopie der frueheren allgemeinen Regel (#247) gehoert trotzdem nur diesem Typ.
    private val regelAmF = lampeRegel("licht-F", minuten = 20, muster = "ALL")

    private val typF = ShiftDefinition(
        id = "id-F", name = "F", keywords = listOf("F"), alarmTime = LocalTime.of(4, 30),
        dimmen = TypDimmen().mitFenster(listOf(TypDimmFenster(DimWindow(), 55))),
        nichtStoeren = TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true),
        licht = TypLicht().mitRegeln(listOf(regelAmF))
    )

    /** Altbestand im Hue-Store, bewusst auf "F" gemuenzt: er darf auf der Leiste nirgends auftauchen. */
    private val altbestandRegel: HueSchedule = lampeRegel("alt", minuten = 600, muster = "F")
    private val spanne = ShiftSpan("AD1", startTime = 1_000L, endTime = 2_000L, alarmTriggerTime = 500L)

    /** Alles, was ein Lauf einstellen kann; der Rest sind gesunde Vorgaben. */
    private class Welt(
        var konfig: Result<ShiftConfig>,
        var bridge: Result<Boolean>,
        var spannen: Result<List<ShiftSpan>>
    ) {
        var angefasst = 0
        var hueGraphGebaut = 0
        var tageslichtGeholt = 0
        val holder = CalendarStateHolder()
        val alarmUseCase = mock<IAlarmUseCase>()
        /** Der Flow `shiftConfig`: ein neuer Wert ist eine Aenderung von aussen (Regel-Editor, Import). */
        val konfigFluss = MutableStateFlow(ShiftConfig(definitions = emptyList()))
        lateinit var vm: ShiftViewModel
        lateinit var spanStore: ShiftSpanStore
        lateinit var hueUseCase: HueRuleUseCase
        lateinit var hueRepo: IHueConfigRepository
    }

    private fun welt(
        konfig: Result<ShiftConfig> = Result.success(ShiftConfig(definitions = listOf(typF))),
        bridge: Result<Boolean> = Result.success(true),
        spannen: Result<List<ShiftSpan>> = Result.success(listOf(spanne)),
        tageslicht: TageslichtQuelle = TageslichtQuelle.OHNE,
        echtesHueRepo: IHueConfigRepository? = null
    ): Welt {
        val w = Welt(konfig, bridge, spannen)
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(w.konfigFluss)
        shiftUseCase.stub {
            // Der Wert der Tagesleiste kommt aus dem Result - nie aus dem Flow, der auf Standard degradiert.
            onBlocking { getCurrentShiftConfig() } doAnswer { w.konfig }
            on { saveShiftConfig(any()) } doReturn Result.success(Unit)
            on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val dimUseCase = DimRuleUseCase(mock<DimRuleRepository>())
        val overlay = mock<DimOverlayPrefs>()
        val dnd = mock<DndPrefs>()
        val hue = echtesHueRepo ?: mock<IHueConfigRepository>().also { hue ->
            // Der Flow ist nur der Ausloeser; sein Wert sagt bewusst etwas anderes als die strikte Stelle.
            whenever(hue.getConfiguration()).thenReturn(flowOf(HueConfiguration()))
            hue.stub {
                onBlocking { bridgeEingerichtetOderFehler() } doAnswer { w.bridge }
                onBlocking { getScheduleRules() } doReturn Result.success(listOf(altbestandRegel))
            }
        }
        w.hueRepo = hue
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
    fun `Lesefehler der Bridge-Einrichtung macht NUR die Licht-Spur unbekannt - nicht keine Bridge`() = runTest(dispatcher) {
        val q = lese(welt(bridge = Result.failure(IOException("hue_settings nicht lesbar"))))

        assertEquals("Bridge-Einrichtung unlesbar heisst Licht unbekannt, nicht 'keine Bridge'", Quelle.Unbekannt, q.licht)
        assertEquals(
            alleBekannt() + (Spur.LICHT to SpurStand.UNBEKANNT),
            q.staende()
        )
        // Die uebrigen Quellen sind unberuehrt - ein Fehler schaltet nicht alle Spuren ab.
        assertTrue(q.typen is Quelle.Bekannt)
        assertTrue(q.spannen is Quelle.Bekannt)
    }

    @Test
    fun `ein unlesbarer Hue-Store - das echte Repository - macht die Licht-Spur unbekannt`() = runTest(dispatcher) {
        val q = lese(welt(echtesHueRepo = HueConfigRepository(WerfenderStore())))

        assertEquals(Quelle.Unbekannt, q.licht)
        assertEquals(alleBekannt() + (Spur.LICHT to SpurStand.UNBEKANNT), q.staende())
    }

    @Test
    fun `die strikte Lese-Stelle meldet den Lesefehler - der Flow der Laufzeit degradiert weiter`() = runTest(dispatcher) {
        val repo = HueConfigRepository(WerfenderStore())

        assertTrue(repo.bridgeEingerichtetOderFehler().isFailure)
        assertEquals(false, repo.getConfiguration().first().isConfigured)
    }

    @Test
    fun `eingerichtet heisst IP und Benutzer - dieselbe Antwort wie der Flow der Laufzeit`() = runTest(dispatcher) {
        assertEquals(false, HueConfigRepository(SpeicherStore()).bridgeEingerichtetOderFehler().getOrThrow())

        listOf(
            Triple("1.2.3.4", "u", true),
            Triple("1.2.3.4", "", false),
            Triple("", "u", false)
        ).forEach { (ip, benutzer, erwartet) ->
            val repo = HueConfigRepository(SpeicherStore())
            repo.saveBridgeConfig(ip, benutzer).getOrThrow()

            assertEquals("$ip/$benutzer", erwartet, repo.bridgeEingerichtetOderFehler().getOrThrow())
            assertEquals("$ip/$benutzer", erwartet, repo.getConfiguration().first().isConfigured)
        }
    }

    // --- #253: die Lichtregeln kommen vom Typ ------------------------------------------------

    private val zone = ZoneOffset.UTC
    private val jetzt = LocalDateTime.of(2026, 11, 9, 12, 0).toInstant(zone).toEpochMilli()

    private fun typ(name: String, vararg regeln: HueScheduleRule) = ShiftDefinition(
        id = "id-$name", name = name, keywords = listOf(name), alarmTime = LocalTime.of(4, 30),
        licht = TypLicht().mitRegeln(regeln.toList())
    )

    private fun fruehSpanne(name: String) = ShiftSpan(
        name,
        startTime = LocalDateTime.of(2026, 11, 10, 6, 0).toInstant(zone).toEpochMilli(),
        endTime = LocalDateTime.of(2026, 11, 10, 14, 15).toInstant(zone).toEpochMilli(),
        alarmTriggerTime = LocalDateTime.of(2026, 11, 10, 4, 30).toInstant(zone).toEpochMilli()
    )

    /** Die Licht-Balken der Karte eines gespeicherten Typs, gerechnet aus dem Stand des ViewModels. */
    private fun lichtBalken(q: TagesleisteQuellen, name: String): List<Pair<Int, Int>> = TagesleisteKarteLogik.rechne(
        TypEntwurf(
            name = name, muster = listOf(name), weckzeitText = "04:30", isEnabled = true, isSilent = false,
            weckerAn = true, kategorie = ShiftKategorie.DIENST
        ),
        name, q, Blockposition.EINZELNER, jetzt, zone
    ).leiste.balken.filter { it.spur == Spur.LICHT }.map { it.vonMin to it.bisMin }

    @Test
    fun `eine Regel am Typ erscheint in der Leiste dieses Typs, nicht in der eines anderen`() = runTest(dispatcher) {
        // F traegt eine Regel mit gespeichertem "ALL", S eine eigene, N keine. Der Hue-Store traegt eine
        // Regel auf "F" (600 min) - die alte Quelle zeigte sie.
        val config = ShiftConfig(definitions = listOf(typ("F", regelAmF), typ("S", lampeRegel("licht-S", 45)), typ("N")))
        val w = welt(
            konfig = Result.success(config),
            spannen = Result.success(listOf(fruehSpanne("F"), fruehSpanne("S"), fruehSpanne("N")))
        )
        val q = lese(w)

        // Wecker 04:30 = Minute 630; F schaltet 20 min, S 45 min.
        assertEquals(listOf(630 to 650), lichtBalken(q, "F"))
        assertEquals(listOf(630 to 675), lichtBalken(q, "S"))
        assertEquals(emptyList<Pair<Int, Int>>(), lichtBalken(q, "N"))
        verifyBlocking(w.hueRepo, never()) { getScheduleRules() }
    }

    @Test
    fun `eine von aussen gespeicherte Regel am Typ kommt an - ohne Aenderung des Hue-Stores`() = runTest(dispatcher) {
        val ohne = ShiftConfig(definitions = listOf(typ("F")))
        val w = welt(konfig = Result.success(ohne), spannen = Result.success(listOf(fruehSpanne("F"))))
        assertEquals(emptyList<Pair<Int, Int>>(), lichtBalken(lese(w), "F"))

        // Der Regel-Editor schreibt in die Schicht-Konfiguration: der Flow meldet sie, der Hue-Store nicht.
        val mit = ShiftConfig(definitions = listOf(typ("F", regelAmF)))
        w.konfig = Result.success(mit)
        w.konfigFluss.value = mit
        advanceUntilIdle()

        assertEquals(listOf(630 to 650), lichtBalken(w.vm.tagesleisteQuellen.value!!, "F"))
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
        // Seit #253 liegen auch die Lichtregeln am Typ: ohne Konfiguration weiss die Leiste nichts ueber sie.
        assertEquals(Quelle.Unbekannt, q.licht)
        assertEquals(
            alleBekannt() + (Spur.DIMMEN to SpurStand.UNBEKANNT) + (Spur.NICHT_STOEREN to SpurStand.UNBEKANNT) +
                (Spur.LICHT to SpurStand.UNBEKANNT),
            q.staende()
        )
        assertTrue("Die Spannen sind vom Konfigurations-Fehler unberuehrt", q.spannen is Quelle.Bekannt)
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
        // Die Regeln vom Typ, mit dem Typnamen als Muster (dieselbe Naht wie die Laufzeit, TypLichtQuelle).
        assertEquals(listOf(regelAmF.copy(shiftPattern = "F")), licht.regeln)
        assertEquals(listOf(spanne), (q.spannen as Quelle.Bekannt).wert)
        assertEquals("Das Abo baut den Hue-Graphen nicht", 0, w.hueGraphGebaut)
    }

    @Test
    fun `die Vorlauf-Rechnung der Licht-Quelle greift erst beim Aufruf auf den Hue-Use-Case zu`() = runTest(dispatcher) {
        val w = welt()
        val licht = (lese(w).licht as Quelle.Bekannt).wert
        assertEquals(0, w.hueGraphGebaut)
        whenever(w.hueUseCase.getPreAlarmSunriseLeadMinutes(any(), any(), anyOrNullLong(), anyOrNullStand())).thenReturn(7)

        assertEquals(7, licht.vorlaufMinuten(listOf(altbestandRegel), "AD1", null, null))
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
        try {
            HueConfigRepository(WerfenderStore(CancellationException("abgebrochen"))).bridgeEingerichtetOderFehler()
            fail("Die strikte Lese-Stelle darf eine CancellationException nicht in ein Result verpacken")
        } catch (erwartet: CancellationException) {
            // richtig
        }
        val geworfen = flow<Quelle<Int>> { throw IOException("kaputt") }.fehlerAlsUnbekannt("t").toList()
        assertSame(Quelle.Unbekannt, geworfen.single())
    }

    /** Ein Store, dessen Lesen scheitert - wie eine unlesbare `hue_settings`-Datei. */
    private class WerfenderStore(private val fehler: Throwable = IOException("hue_settings nicht lesbar")) :
        DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw fehler }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw fehler
    }

    /** Ein Store nur im Arbeitsspeicher - genug, um mit dem echten Repository zu schreiben und zu lesen. */
    private class SpeicherStore : DataStore<Preferences> {
        private val stand = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = stand
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(stand.value).also { stand.value = it }
    }
}
