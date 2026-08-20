package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Prüfrunde 8, Befund 2 - die Verdrahtung: Speichert der Nutzer eine UMBENANNTE
 * Schichtdefinition, müssen die Dimmer- UND die Hue-Regeln mitgezogen werden.
 *
 * DER FEHLER: Beide Regelarten binden über den NAMEN der Definition, der Editor ändert den Namen
 * aber bei gleichbleibender `id`. Es gab keinen einzigen Codepfad, der beim Speichern einer
 * ShiftConfig ein Regelmuster umgeschrieben hätte - eine reine Beschriftungsänderung legte damit
 * beide bewusst eingerichteten Funktionen lautlos still, während die Regellisten sie weiter als
 * aktiv zeigten.
 *
 * WAS AN DIESEM TEST NACHGEBESSERT WURDE (die Regression über dem Fix): Er lief ursprünglich mit
 * einem LEEREN [CalendarStateHolder] - dem einzigen Zustand, in dem `updateShiftConfig()` die
 * anschließende Schichterkennung überspringt. Genau diese Erkennung setzt aber
 * `error = null` (nach `delay(200)`), und die Meldung über einen gescheiterten Nachzug landete
 * damals in `error`: im Normalbetrieb - also mit geladenen Terminen - war sie weg, bevor sie
 * irgendwo ankommen konnte. Der Test bestätigte eine Meldung, die es real nie gab.
 * Deshalb hat die Umgebung hier jetzt Termine (Normalfall), und die Meldung liegt in
 * [ShiftUiState.regelNachzugHinweis] - einem Kanal, den kein Ladevorgang leert.
 *
 * OHNE DEN FIX fällt der erste Test: `renameShiftPattern` wird nie gerufen.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde8SchichtUmbenennungNachzugTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun def(id: String, name: String, alarmTime: LocalTime = LocalTime.of(5, 30)) =
        ShiftDefinition(id = id, name = name, keywords = listOf(name), alarmTime = alarmTime)

    private class Umgebung(
        val vm: ShiftViewModel,
        val dim: DimRuleUseCase,
        val hue: HueRuleUseCase,
        /** Der Store, wie ihn ein FREMDER Schreiber (Import/Rücksicherung) sieht. */
        val store: MutableStateFlow<ShiftConfig>
    )

    /**
     * @param mitTerminen der NORMALFALL: es liegen Kalender-Termine vor, `updateShiftConfig()`
     *   lässt also nach dem Speichern die Schichterkennung laufen. Nur mit `false` (leerer Holder)
     *   überspringt sie den Zweig, der `error` zurücksetzt - der blinde Fleck des alten Tests.
     */
    private fun umgebung(
        bestand: ShiftConfig,
        dimErgebnis: Result<Int> = Result.success(1),
        hueErgebnis: Result<Int> = Result.success(1),
        mitTerminen: Boolean = true
    ): Umgebung {
        val store = MutableStateFlow(bestand)
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(store)
        shiftUseCase.stub {
            // Der maßgebliche Lesepfad liefert immer den AKTUELLEN Store-Inhalt - sonst könnte der
            // Beobachter für externe Änderungen gar nicht funktionieren.
            onBlocking { getCurrentShiftConfig() } doAnswer { Result.success(store.value) }
            // Wie DataStore: das Speichern veröffentlicht den neuen Wert auch im Flow. Ohne das
            // sähe dieser Test nie, ob der Beobachter beim eigenen Schreibvorgang ein zweites Mal
            // migriert (er darf nicht).
            onBlocking { saveShiftConfig(any()) } doAnswer { aufruf ->
                store.value = aufruf.getArgument(0)
                Result.success(Unit)
            }
            onBlocking { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val dim = mock<DimRuleUseCase>()
        dim.stub { onBlocking { renameShiftPattern(any(), any()) } doReturn dimErgebnis }
        val hue = mock<HueRuleUseCase>()
        hue.stub { onBlocking { renameShiftPattern(any(), any()) } doReturn hueErgebnis }

        val holder = CalendarStateHolder()
        if (mitTerminen) {
            // `complete = false`: der Alarm-Sync ist hier nicht das Thema und darf mangels
            // vollständiger Liste ausdrücklich NICHT laufen (CLAUDE.md: eine unvollständige
            // Eventliste ist keine Löschgrundlage). Für die Schichterkennung - und damit für den
            // `error = null`-Zweig, um den es hier geht - reicht "Liste nicht leer".
            holder.updateEvents(
                listOf(
                    CalendarEvent(
                        id = "e1",
                        title = "AD1 Dienst",
                        startTime = LocalDateTime.now().plusDays(1),
                        endTime = LocalDateTime.now().plusDays(1).plusHours(8),
                        calendarId = "cal1"
                    )
                ),
                complete = false
            )
        }

        val vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = mock<IAlarmUseCase>(),
            calendarStateHolder = holder,
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { dim },
            hueRuleUseCase = dagger.Lazy { hue },
            // Nur das Nacharmieren der Tick-Ketten - hier ohne Belang.
            dimScheduleUseCase = dagger.Lazy { mock<DimScheduleUseCase>() },
            dndScheduleUseCase = dagger.Lazy { mock<DndScheduleUseCase>() }
        )
        return Umgebung(vm, dim, hue, store)
    }

    @Test
    fun `Umbenennen zieht Dimmer- UND Hue-Regeln nach`() = runTest(dispatcher) {
        val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()

        verifyBlocking(u.dim) { renameShiftPattern("AD1", "Abrufdienst") }
        verifyBlocking(u.hue) { renameShiftPattern("AD1", "Abrufdienst") }
        assertNull(u.vm.uiState.value.error)
        assertNull(u.vm.uiState.value.regelNachzugHinweis)
    }

    @Test
    fun `der eigene Schreibvorgang migriert genau einmal, nicht noch einmal ueber den Beobachter`() =
        runTest(dispatcher) {
            val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
            advanceUntilIdle()

            u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
            advanceUntilIdle()

            // Der Beobachter für fremde Änderungen bekommt dieselbe Konfiguration aus dem Store -
            // er muss sie am Merker `selfWrittenConfig` als eigene erkennen. Ein zweiter Lauf wäre
            // ein Schreibvorgang ohne Nutzen auf einem Bestand, der bereits stimmt.
            verifyBlocking(u.dim, times(1)) { renameShiftPattern(any(), any()) }
            verifyBlocking(u.hue, times(1)) { renameShiftPattern(any(), any()) }
        }

    @Test
    fun `eine geaenderte Weckzeit ist keine Umbenennung - es wird nichts umgeschrieben`() =
        runTest(dispatcher) {
            val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
            advanceUntilIdle()

            u.vm.updateShiftConfig(
                ShiftConfig(definitions = listOf(def("1", "AD1", LocalTime.of(4, 15))))
            )
            advanceUntilIdle()

            verifyBlocking(u.dim, never()) { renameShiftPattern(any(), any()) }
            verifyBlocking(u.hue, never()) { renameShiftPattern(any(), any()) }
        }

    @Test
    fun `eine neu angelegte Schicht zieht nichts nach`() = runTest(dispatcher) {
        val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
        advanceUntilIdle()

        u.vm.updateShiftConfig(
            ShiftConfig(definitions = listOf(def("1", "AD1"), def("2", "Frueh")))
        )
        advanceUntilIdle()

        verifyBlocking(u.dim, never()) { renameShiftPattern(any(), any()) }
        verifyBlocking(u.hue, never()) { renameShiftPattern(any(), any()) }
    }

    @Test
    fun `gescheiterter Nachzug wird gemeldet und ueberlebt die folgende Schichterkennung`() =
        runTest(dispatcher) {
            // Die Umbenennung selbst ist gespeichert - aber die Regeln zeigen weiter ins Leere.
            // Genau hier darf die App nicht so tun, als sei alles in Ordnung.
            //
            // DER KERN DIESES TESTS ist das `advanceUntilIdle()` NACH dem Speichern: es lässt die
            // Schichterkennung samt `delay(200)` wirklich durchlaufen. Solange die Meldung in
            // `error` lag, war sie danach weg - sichtbar wurde sie nie. Der alte Test kam nur
            // deshalb durch, weil sein leerer CalendarStateHolder diesen Zweig übersprang.
            val u = umgebung(
                ShiftConfig(definitions = listOf(def("1", "AD1"))),
                dimErgebnis = Result.failure(IllegalStateException("Regeln nicht schreibbar"))
            )
            advanceUntilIdle()

            u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
            advanceUntilIdle()

            val zustand = u.vm.uiState.value
            assertNotNull(
                "Der gescheiterte Regel-Nachzug muss nach der Schichterkennung noch anliegen",
                zustand.regelNachzugHinweis
            )
            // Unterscheidbar von einem gewöhnlichen Lade-/Speicherfehler: das Speichern selbst hat
            // geklappt, es gibt keinen `error`. Beide in denselben Kanal zu werfen hieße, dass die
            // Oberfläche sie nicht verschieden behandeln kann.
            assertNull(zustand.error)
            assertTrue(
                "Die Meldung muss die Wirkung nennen und sagen, was zu tun ist",
                zustand.regelNachzugHinweis!!.contains("neuen Namen")
            )
        }

    @Test
    fun `der Hinweis verschwindet erst, wenn der Nutzer ihn bestaetigt`() = runTest(dispatcher) {
        val u = umgebung(
            ShiftConfig(definitions = listOf(def("1", "AD1"))),
            hueErgebnis = Result.failure(IllegalStateException("Bridge nicht erreichbar"))
        )
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()
        assertNotNull(u.vm.uiState.value.regelNachzugHinweis)

        // Auch ein weiterer Ladevorgang räumt ihn nicht weg - nur die Bestätigung.
        u.vm.processCalendarEvents(emptyList())
        advanceUntilIdle()
        assertNotNull(u.vm.uiState.value.regelNachzugHinweis)

        u.vm.clearRegelNachzugHinweis()
        assertNull(u.vm.uiState.value.regelNachzugHinweis)
    }

    @Test
    fun `mehrdeutiger Name zieht nichts nach und meldet es`() = runTest(dispatcher) {
        // Zwei Definitionen mit demselben Namen: eine Zuordnung waere geraten. Lieber nichts
        // umschreiben - aber es sagen.
        val u = umgebung(
            ShiftConfig(definitions = listOf(def("1", "AD1"), def("2", "Abrufdienst")))
        )
        advanceUntilIdle()

        u.vm.updateShiftConfig(
            ShiftConfig(definitions = listOf(def("1", "Abrufdienst"), def("2", "Abrufdienst")))
        )
        advanceUntilIdle()

        verifyBlocking(u.dim, never()) { renameShiftPattern(any(), any()) }
        verifyBlocking(u.hue, never()) { renameShiftPattern(any(), any()) }
        assertNotNull(u.vm.uiState.value.regelNachzugHinweis)
    }

    /**
     * Der zweite Teil der Regression: Der Nachzug hing am Aufruf-Gate in `updateShiftConfig()`.
     * Der Konfigurations-Import (Backup-Rücksicherung, Gerätewechsel) schreibt direkt über das
     * Repository - also an diesem Gate vorbei. Genau dort kommt eine Definition mit gleicher `id`
     * und anderem Namen zurück, und genau dort blieb die Migration aus.
     *
     * OHNE DEN FIX fällt dieser Test: `renameShiftPattern` wird nie gerufen.
     */
    @Test
    fun `auch eine FREMD geschriebene Umbenennung zieht die Regeln nach`() = runTest(dispatcher) {
        val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
        advanceUntilIdle()

        // Ein fremder Schreiber (Import) legt die neue Konfiguration direkt in den Store.
        u.store.value = ShiftConfig(definitions = listOf(def("1", "Abrufdienst")))
        advanceUntilIdle()

        verifyBlocking(u.dim) { renameShiftPattern("AD1", "Abrufdienst") }
        verifyBlocking(u.hue) { renameShiftPattern("AD1", "Abrufdienst") }
        assertEquals(
            "Abrufdienst",
            u.vm.uiState.value.currentShiftConfig?.definitions?.first()?.name
        )
    }

    /**
     * Die Gegenprobe zum Test darüber: Der Beobachter filtert die Notlage-Standardkonfiguration
     * ausdrücklich heraus ("DIE VIERTE TUER") - maßgeblich ist `getCurrentShiftConfig()`, das im
     * Defektfall SCHEITERT. Kippte dieses Gate, zöge eine Degradierung die Regelmuster auf die
     * Standardnamen um: Licht und Verdunkelung wanderten zu einer Schicht, die der Nutzer nie
     * angelegt hat.
     */
    @Test
    fun `eine degradierte Standardkonfiguration zieht NICHTS um`() = runTest(dispatcher) {
        val bestand = ShiftConfig(definitions = listOf(def("1", "AD1")))
        val store = MutableStateFlow(bestand)
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(store)
        var defekt = false
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doAnswer {
                if (defekt) Result.failure(IllegalStateException("Konfiguration nicht lesbar"))
                else Result.success(store.value)
            }
            onBlocking { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val dim = mock<DimRuleUseCase>()
        val hue = mock<HueRuleUseCase>()
        val vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = mock<IAlarmUseCase>(),
            calendarStateHolder = CalendarStateHolder(),
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { dim },
            hueRuleUseCase = dagger.Lazy { hue },
            dimScheduleUseCase = dagger.Lazy { mock<DimScheduleUseCase>() },
            dndScheduleUseCase = dagger.Lazy { mock<DndScheduleUseCase>() }
        )
        advanceUntilIdle()

        // Der Flow degradiert auf die Standardwerte, der maßgebliche Lesepfad scheitert.
        defekt = true
        store.value = ShiftConfig.getDefaultConfig()
        advanceUntilIdle()

        verifyBlocking(dim, never()) { renameShiftPattern(any(), any()) }
        verifyBlocking(hue, never()) { renameShiftPattern(any(), any()) }
        assertEquals("AD1", vm.uiState.value.currentShiftConfig?.definitions?.first()?.name)
    }
}
