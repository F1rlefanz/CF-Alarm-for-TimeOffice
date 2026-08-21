package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
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
        val store: MutableStateFlow<ShiftConfig>,
        /** Die beiden DND-Schichtauswahlen (Rufbereitschaft + Dienstzeit-Ausnahmen). */
        val dnd: DndPrefs,
        /** Die Ausnahmenliste des Nacht-Standards im Dimmer. */
        val dimPrefs: DimOverlayPrefs,
        val dimSchedule: DimScheduleUseCase,
        val dndSchedule: DndScheduleUseCase,
        /** Der Alarm-Sync - er schreibt die Schichtspannen, auf die das Nacharmieren wartet. */
        val alarm: IAlarmUseCase
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
        dndPrefsErgebnis: Result<Int> = Result.success(1),
        dimPrefsErgebnis: Result<Int> = Result.success(1),
        dndEntfernErgebnis: Result<Int> = Result.success(1),
        dimEntfernErgebnis: Result<Int> = Result.success(1),
        mitTerminen: Boolean = true,
        /**
         * `true` = der Alarm-Sync laeuft wirklich (vollstaendige Eventliste). Nur dann werden die
         * Schichtspannen neu geschrieben - der Zustand, auf den das Nacharmieren warten muss.
         */
        vollstaendig: Boolean = false,
        alarmUseCase: IAlarmUseCase = mock<IAlarmUseCase>().apply {
            stub { onBlocking { syncAlarms(any(), any()) } doReturn Result.success(emptyList()) }
        }
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
        val dnd = mock<DndPrefs>()
        dnd.stub {
            onBlocking { renameShiftName(any(), any()) } doReturn dndPrefsErgebnis
            onBlocking { removeShiftName(any(), any()) } doReturn dndEntfernErgebnis
        }
        val dimPrefs = mock<DimOverlayPrefs>()
        dimPrefs.stub {
            onBlocking { renameShiftName(any(), any()) } doReturn dimPrefsErgebnis
            onBlocking { removeShiftName(any(), any()) } doReturn dimEntfernErgebnis
        }
        val dimSchedule = mock<DimScheduleUseCase>()
        val dndSchedule = mock<DndScheduleUseCase>()

        val holder = CalendarStateHolder()
        if (mitTerminen) {
            // `complete` = [vollstaendig], per Default FALSE: der Alarm-Sync ist in den meisten
            // dieser Tests nicht das Thema und darf mangels vollständiger Liste ausdrücklich NICHT
            // laufen (CLAUDE.md: eine unvollständige Eventliste ist keine Löschgrundlage). Für die
            // Schichterkennung - und damit für den `error = null`-Zweig - reicht "Liste nicht leer".
            // Nur die Tests zum Nacharmieren brauchen den echten Sync und setzen `true`.
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
                complete = vollstaendig
            )
        }

        val vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            calendarStateHolder = holder,
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { dim },
            hueRuleUseCase = dagger.Lazy { hue },
            dimScheduleUseCase = dagger.Lazy { dimSchedule },
            dndScheduleUseCase = dagger.Lazy { dndSchedule },
            dndPrefs = dagger.Lazy { dnd },
            dimOverlayPrefs = dagger.Lazy { dimPrefs }
        )
        return Umgebung(vm, dim, hue, store, dnd, dimPrefs, dimSchedule, dndSchedule, alarmUseCase)
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
            dndScheduleUseCase = dagger.Lazy { mock<DndScheduleUseCase>() },
            dndPrefs = dagger.Lazy { mock<DndPrefs>() },
            dimOverlayPrefs = dagger.Lazy { mock<DimOverlayPrefs>() }
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

    // ---------------------------------------------------------------------------------------
    // NACHTRAG 21.08.2026: die drei ÜBERSEHENEN Namenslisten.
    //
    // v1.30.0 zog nur Dimmer- und Hue-REGELN mit. Drei weitere Stellen binden ebenfalls über den
    // Namen: `dnd_oncall_shifts` (Rufbereitschaft), `dnd_shift_excluded_shifts` (Ausnahmen von
    // „Nicht stören während der Dienstzeit") und `dim_night_default_excluded_shifts` (Ausnahmen
    // vom Nacht-Standard). Am Gerät des Nutzers stand in der Rufbereitschaft-Auswahl noch
    // „Abrufdienst", während der Weckerbestand längst „Rufdienst" sagte - der On-Call-Cutoff griff
    // nicht mehr, und in der Nacht vor der Rufbereitschaft blieb das Telefon über 05:00 hinaus
    // stumm. Sichtbar war davon nichts: die Chips werden aus den AKTUELLEN Namen gebaut.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Umbenennen zieht auch die DND-Auswahlen und die Nacht-Ausnahmen nach`() =
        runTest(dispatcher) {
            val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
            advanceUntilIdle()

            u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
            advanceUntilIdle()

            verifyBlocking(u.dnd) { renameShiftName("AD1", "Abrufdienst") }
            verifyBlocking(u.dimPrefs) { renameShiftName("AD1", "Abrufdienst") }
            assertNull(u.vm.uiState.value.regelNachzugHinweis)
        }

    @Test
    fun `eine blockierte Umbenennung mit herrenlosem Altnamen laesst die Namenslisten unberuehrt`() =
        runTest(dispatcher) {
            // Zwei Definitionen tragen hinterher denselben Namen - die Zuordnung wäre geraten.
            // „AD1" gehört danach NIEMANDEM mehr: der gespeicherte Eintrag wirkt nirgends und wird
            // von selbst wieder richtig, wenn der Nutzer die Umbenennung zurücknimmt. Ihn zu
            // löschen wäre Datenverlust ohne Gegenwert.
            val u = umgebung(
                ShiftConfig(definitions = listOf(def("1", "AD1"), def("2", "Abrufdienst")))
            )
            advanceUntilIdle()

            u.vm.updateShiftConfig(
                ShiftConfig(definitions = listOf(def("1", "Abrufdienst"), def("2", "Abrufdienst")))
            )
            advanceUntilIdle()

            verifyBlocking(u.dnd, never()) { renameShiftName(any(), any()) }
            verifyBlocking(u.dimPrefs, never()) { renameShiftName(any(), any()) }
            verifyBlocking(u.dnd, never()) { removeShiftName(any(), any()) }
            verifyBlocking(u.dimPrefs, never()) { removeShiftName(any(), any()) }
            assertNotNull(u.vm.uiState.value.regelNachzugHinweis)
        }

    @Test
    fun `eine gescheiterte DND-Auswahl wird gemeldet und stoppt die uebrigen nicht`() =
        runTest(dispatcher) {
            val u = umgebung(
                ShiftConfig(definitions = listOf(def("1", "AD1"))),
                dndPrefsErgebnis = Result.failure(IllegalStateException("nicht schreibbar"))
            )
            advanceUntilIdle()

            u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
            advanceUntilIdle()

            // Die übrigen laufen trotzdem - ein Fehlschlag darf nicht die halbe Migration kosten.
            verifyBlocking(u.dim) { renameShiftPattern("AD1", "Abrufdienst") }
            verifyBlocking(u.hue) { renameShiftPattern("AD1", "Abrufdienst") }
            verifyBlocking(u.dimPrefs) { renameShiftName("AD1", "Abrufdienst") }

            val hinweis = u.vm.uiState.value.regelNachzugHinweis
            assertNotNull("Der gescheiterte Nachzug muss gemeldet werden", hinweis)
            // Der Nutzer liest den Namen des BILDSCHIRMS, nicht den des Speicherschlüssels.
            assertTrue(
                "Die Meldung muss den Bildschirm benennen, den der Nutzer sieht: $hinweis",
                hinweis!!.contains("Nicht stören")
            )
            assertTrue(hinweis.contains("neuen Namen"))
            assertNull(u.vm.uiState.value.error)
        }

    @Test
    fun `eine geaenderte DND-Auswahl armiert die DND-Kette neu, nicht die Dimmer-Kette`() =
        runTest(dispatcher) {
            // Nur die DND-Auswahl hat sich geändert: Dimm-Regeln und Nacht-Ausnahmen melden 0.
            // Die DND-Fenster (Dienstzeit, On-Call-Cutoff) hängen aber an genau dieser Liste - der
            // nächste Tick steht noch auf dem ALTEN Plan und muss neu gesetzt werden.
            val u = umgebung(
                ShiftConfig(definitions = listOf(def("1", "AD1"))),
                dimErgebnis = Result.success(0),
                hueErgebnis = Result.success(0),
                dndPrefsErgebnis = Result.success(1),
                dimPrefsErgebnis = Result.success(0)
            )
            advanceUntilIdle()

            u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
            advanceUntilIdle()

            verifyBlocking(u.dndSchedule) { enable() }
            // Der Dimmer kennt die DND-Auswahlen nicht - ein enable() dort wäre Arbeit ohne Wirkung.
            verifyBlocking(u.dimSchedule, never()) { enable() }
        }

    @Test
    fun `eine geaenderte Nacht-Ausnahme armiert beide Ketten neu`() = runTest(dispatcher) {
        // Die Ausnahmenliste ist ein Eingang von DimScheduleUseCase.computeWindows() - sie
        // verschiebt die Dimm-Fenster wie eine geänderte Regel, und über den DND-Modus
        // "folgt dem Dimmer" auch die DND-Fenster.
        val u = umgebung(
            ShiftConfig(definitions = listOf(def("1", "AD1"))),
            dimErgebnis = Result.success(0),
            hueErgebnis = Result.success(0),
            dndPrefsErgebnis = Result.success(0),
            dimPrefsErgebnis = Result.success(1)
        )
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()

        verifyBlocking(u.dimSchedule) { enable() }
        verifyBlocking(u.dndSchedule) { enable() }
    }

    // ---------------------------------------------------------------------------------------
    // Review über dem Nachtrag, 21.08.2026 - die vier Befunde A bis D.
    // ---------------------------------------------------------------------------------------

    /**
     * BEFUND A: Eine reine SCHREIBWEISEN-Änderung ist für die drei Namenslisten sehr wohl eine
     * Umbenennung.
     *
     * `planeSchichtUmbenennungen` stieg bei `equals(ignoreCase = true)` aus. Für die Dimm-/Hue-
     * REGELN ist das richtig (sie vergleichen selbst `ignoreCase`), für die Listen nicht: sie
     * prüfen exakte Mengen-Zugehörigkeit. Korrigiert der Nutzer „abrufdienst" zu „Abrufdienst",
     * bleibt dort der alte Kasus stehen, der Rufbereitschaft-Cutoff greift nicht mehr, und
     * „Nicht stören" bleibt in der Nacht vor der Rufbereitschaft über 05:00 hinaus an.
     *
     * OHNE DEN FIX fällt dieser Test: es wird nichts nachgezogen.
     */
    @Test
    fun `eine reine Schreibweisenaenderung zieht die Namenslisten nach`() = runTest(dispatcher) {
        val u = umgebung(ShiftConfig(definitions = listOf(def("1", "abrufdienst"))))
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()

        verifyBlocking(u.dnd) { renameShiftName("abrufdienst", "Abrufdienst") }
        verifyBlocking(u.dimPrefs) { renameShiftName("abrufdienst", "Abrufdienst") }
        // Für die Regeln ist derselbe Aufruf folgenlos - sie trafen vorher und treffen nachher.
        verifyBlocking(u.dim) { renameShiftPattern("abrufdienst", "Abrufdienst") }
        assertNull(u.vm.uiState.value.regelNachzugHinweis)
    }

    /**
     * BEFUND B: Das Nacharmieren muss die Schichtspannen mit dem NEUEN Namen sehen.
     *
     * Der `ShiftSpanStore` wird ausschließlich in `syncAlarms()` geschrieben, und `syncAlarms()`
     * armiert die Ketten nicht selbst. Lief `enable()` direkt nach dem Umschreiben der Listen,
     * mischte es die frisch nachgezogenen Listen (NEUER Name) mit Spannen, die noch den ALTEN
     * trugen: das Fenster entstand ohne Cutoff und ohne Ausnahme, und weil der nächste Tick genau
     * auf dieses zu späte Ende fällt, blieb der falsche Plan für die ganze Nacht stehen.
     *
     * Der Test bildet das direkt ab: Der Alarm-Sync schreibt den neuen Namen in die „Spannen",
     * beide `enable()` protokollieren, welchen Namen sie dort vorfinden.
     *
     * OHNE DEN FIX fällt er: die Ketten sehen „AD1".
     */
    @Test
    fun `das Nacharmieren sieht die Spannen mit dem NEUEN Namen`() = runTest(dispatcher) {
        val spanne = arrayOf("AD1")
        val alarm = mock<IAlarmUseCase>()
        alarm.stub {
            onBlocking { syncAlarms(any(), any()) } doAnswer {
                spanne[0] = "Abrufdienst"
                Result.success(emptyList())
            }
        }

        val u = umgebung(
            ShiftConfig(definitions = listOf(def("1", "AD1"))),
            vollstaendig = true,
            alarmUseCase = alarm
        )
        val gesehen = mutableListOf<String>()
        u.dimSchedule.stub {
            onBlocking { enable() } doAnswer {
                gesehen += "dim:" + spanne[0]
                Unit
            }
        }
        u.dndSchedule.stub {
            onBlocking { enable() } doAnswer {
                gesehen += "dnd:" + spanne[0]
                Unit
            }
        }
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()

        verifyBlocking(u.alarm) { syncAlarms(any(), any()) }
        assertEquals(listOf("dim:Abrufdienst", "dnd:Abrufdienst"), gesehen)
    }

    /**
     * BEFUND B, zweite Hälfte: Läuft der Sync in diesem Durchlauf gar nicht, darf das Nacharmieren
     * trotzdem NICHT ausfallen.
     *
     * Der Sync fällt regelmäßig aus - hier über das Vollständigkeits-Gate (die Eventliste ist nur
     * ein Ausschnitt). Die Namenslisten sind dann bereits umgeschrieben, während der armierte Tick
     * noch auf dem Stand von davor steht: nicht nachzuarmieren wäre der schlechtere von zwei alten
     * Ständen. Deshalb hängt der Aufruf im `finally`, nicht im Erfolgszweig des Syncs.
     */
    @Test
    fun `ohne Alarm-Sync wird trotzdem nacharmiert`() = runTest(dispatcher) {
        val u = umgebung(
            ShiftConfig(definitions = listOf(def("1", "AD1"))),
            vollstaendig = false
        )
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()

        verifyBlocking(u.alarm, never()) { syncAlarms(any(), any()) }
        verifyBlocking(u.dimSchedule) { enable() }
        verifyBlocking(u.dndSchedule) { enable() }
    }

    /**
     * BEFUND C: Ein blockierter Namenstausch darf keine scharfe Falschzuordnung hinterlassen.
     *
     * Nach dem Tausch gehört der gespeicherte Name einer ANDEREN Schicht. Für eine Regel ist
     * Nichtstun ehrlich - sie wird wirkungslos und steht sichtbar in ihrer Liste. Der
     * Listen-Eintrag dagegen ist nicht tot, sondern scharf für die falsche Schicht: „Nicht stören"
     * endet an deren Tagen früher, während die echte Rufbereitschaftsnacht durchgehend stumm
     * bleibt. Und sichtbar ist davon nichts, weil die Chips aus den AKTUELLEN Namen gebaut werden.
     *
     * OHNE DEN FIX fällt dieser Test: es wird nichts geräumt.
     */
    @Test
    fun `ein blockierter Namenstausch raeumt die Falscheintraege aus den Namenslisten`() =
        runTest(dispatcher) {
            val u = umgebung(
                ShiftConfig(definitions = listOf(def("1", "Frueh"), def("2", "Nacht")))
            )
            advanceUntilIdle()

            u.vm.updateShiftConfig(
                ShiftConfig(definitions = listOf(def("1", "Nacht"), def("2", "Frueh")))
            )
            advanceUntilIdle()

            // Nichts wird umgeschrieben - das wäre geraten.
            verifyBlocking(u.dnd, never()) { renameShiftName(any(), any()) }
            verifyBlocking(u.dim, never()) { renameShiftPattern(any(), any()) }
            // Aber beide Altnamen werden zum Raeumen angeboten - MIT ihrem Partnernamen. Der
            // entscheidet in den Prefs ueber den Tauschfall: stehen BEIDE Namen in derselben
            // Liste, ist ihr Inhalt nach dem Tausch weiterhin richtig (sie meint beide Schichten)
            // und bleibt unangetastet. Das ist die Zusicherung, die diese Signatur traegt -
            // ohne den Partnernamen koennte die Prefs-Schicht den Fall gar nicht erkennen.
            verifyBlocking(u.dnd) { removeShiftName("Frueh", "Nacht") }
            verifyBlocking(u.dnd) { removeShiftName("Nacht", "Frueh") }
            verifyBlocking(u.dimPrefs) { removeShiftName("Frueh", "Nacht") }
            verifyBlocking(u.dimPrefs) { removeShiftName("Nacht", "Frueh") }
            // Geänderte Listen heißen: der nächste Tick steht auf einem überholten Plan.
            verifyBlocking(u.dimSchedule) { enable() }
            verifyBlocking(u.dndSchedule) { enable() }

            val hinweis = u.vm.uiState.value.regelNachzugHinweis
            assertNotNull(hinweis)
            // BEFUND D: Der Text nennt die betroffene Schicht - ohne ihren Namen weiß der Nutzer
            // nicht, wo er nachbessern soll - und sagt, dass etwas entfernt wurde.
            assertTrue("Die Meldung muss die Schichten nennen: $hinweis", hinweis!!.contains("Frueh"))
            assertTrue(hinweis.contains("Nacht"))
            assertTrue("Die Meldung muss das Räumen benennen: $hinweis", hinweis.contains("entfernt"))
        }

    /**
     * BEFUND D: Der gemischte Fall - eine Schicht wandert sauber mit, eine zweite ist blockiert.
     *
     * Beide Listen des Plans können gleichzeitig gefüllt sein. Der alte Text war ein `when` und
     * behauptete dann pauschal, es sei NICHTS mitgezogen worden - der Nutzer hätte Einstellungen
     * neu gesetzt, die längst richtig waren.
     *
     * OHNE DEN FIX fällt dieser Test: die Meldung nennt weder die betroffene Schicht noch die
     * gelungene Migration.
     */
    @Test
    fun `der gemischte Fall meldet beides - migriert UND blockiert`() = runTest(dispatcher) {
        val u = umgebung(
            ShiftConfig(
                definitions = listOf(def("1", "AD1"), def("2", "Frueh"), def("3", "Nacht"))
            )
        )
        advanceUntilIdle()

        u.vm.updateShiftConfig(
            ShiftConfig(
                definitions = listOf(def("1", "Abrufdienst"), def("2", "Nacht"), def("3", "Frueh"))
            )
        )
        advanceUntilIdle()

        // Die saubere Umbenennung ist wirklich gelaufen.
        verifyBlocking(u.dnd) { renameShiftName("AD1", "Abrufdienst") }

        val hinweis = u.vm.uiState.value.regelNachzugHinweis
        assertNotNull(hinweis)
        assertTrue("Die betroffene Schicht muss im Text stehen: $hinweis", hinweis!!.contains("Frueh"))
        assertTrue(hinweis.contains("Nacht"))
        assertTrue(
            "Der Text darf nicht behaupten, es sei nichts mitgezogen worden: $hinweis",
            hinweis.contains("korrekt mitgezogen")
        )
    }
}
