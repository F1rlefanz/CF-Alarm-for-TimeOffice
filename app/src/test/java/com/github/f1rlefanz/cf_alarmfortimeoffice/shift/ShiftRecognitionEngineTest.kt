package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Tests fuer [ShiftRecognitionEngine] — bis v1.22.1 komplett ungetestet, obwohl sie der
 * Uebersetzer zwischen Kalender und Wecker ist: was sie nicht erkennt, weckt nicht, und was sie
 * faelschlich als leer meldet, laesst [com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase]
 * bestehende Alarme loeschen.
 *
 * Beide hier festgehaltenen Faelle sind echte, am Code belegte Fehler (05.08.2026), nicht
 * hypothetische:
 *  - der Schalter "Schichtdefinition aktiviert" war eine Attrappe
 *  - der Cache veroeffentlichte seinen Schluessel VOR dem Ergebnis
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftRecognitionEngineTest {

    /**
     * Faked Repository mit optionaler Schleuse: `gate` laesst [getCurrentShiftConfig] genau dort
     * haengen, wo die Engine mitten in `performRecognition()` steht — das ist das Zeitfenster, in
     * dem der Race sichtbar wird. Ohne `gate` verhaelt es sich wie ein normales Repository.
     */
    private class GatedShiftConfigRepository(
        private val config: ShiftConfig,
        private val gate: CompletableDeferred<Unit>? = null
    ) : IShiftConfigRepository {
        var loadCount = 0
            private set

        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)

        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> {
            gate?.await()
            loadCount++
            return Result.success(config)
        }

        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> = Result.success(true)
    }

    /**
     * Repository, dessen ERSTER Lesezugriff mit einer Exception scheitert und danach normal
     * liefert - fuer den Nachweis, dass ein gescheiterter Lauf nicht als leeres Ergebnis
     * gecacht wird.
     */
    private class FailingOnceShiftConfigRepository(
        private val config: ShiftConfig
    ) : IShiftConfigRepository {
        var loadCount = 0
            private set

        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)

        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> {
            loadCount++
            if (loadCount == 1) throw IllegalStateException("DataStore nicht lesbar")
            return Result.success(config)
        }

        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> = Result.success(true)
    }

    private fun event(title: String, day: Int = 10) = CalendarEvent(
        id = "e$day-$title",
        title = title,
        startTime = LocalDateTime.of(2026, 8, day, 6, 0),
        endTime = LocalDateTime.of(2026, 8, day, 14, 0),
        calendarId = "cal1"
    )

    private fun definition(
        name: String,
        keywords: List<String>,
        enabled: Boolean = true
    ) = ShiftDefinition(
        id = name.lowercase(),
        name = name,
        keywords = keywords,
        alarmTime = LocalTime.of(5, 30),
        isEnabled = enabled
    )

    /**
     * Der Schalter "Schichtdefinition aktiviert" (`ShiftDefinition.isEnabled`, im
     * ShiftEditDialog als Toggle sichtbar) muss die Erkennung wirklich abschalten.
     *
     * Vor dem Fix las ihn NIEMAND ausser der Dialog-UI selbst: `performRecognition` lief ueber
     * alle Definitionen und fragte nur `matchesKeywords()`. Ein Nutzer, der eine Schicht
     * deaktiviert (z.B. weil er sie gerade nicht faehrt), bekam trotzdem Wecker dafuer.
     */
    @Test
    fun `deaktivierte Schichtdefinition erzeugt keinen Treffer`() = runTest {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS"), enabled = false))
        )
        val engine = ShiftRecognitionEngine(GatedShiftConfigRepository(config))

        val matches = engine.getAllMatchingShifts(listOf(event("FS")))

        assertTrue(
            "Eine deaktivierte Definition darf keinen Wecker erzeugen, gefunden: " +
                matches.map { it.shiftDefinition.name },
            matches.isEmpty()
        )
    }

    /** Gegenprobe zum Test darueber: aktiviert muss dieselbe Definition treffen. */
    @Test
    fun `aktivierte Schichtdefinition erzeugt einen Treffer`() = runTest {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS"), enabled = true))
        )
        val engine = ShiftRecognitionEngine(GatedShiftConfigRepository(config))

        val matches = engine.getAllMatchingShifts(listOf(event("FS")))

        assertEquals(1, matches.size)
        assertEquals("Fruehschicht", matches.first().shiftDefinition.name)
    }

    /**
     * DER KERN-REGRESSIONSTEST. Zwei gleichzeitige Aufrufer auf derselben Engine-Instanz —
     * genau die Konstellation aus CLAUDE.md: `AlarmUseCase.syncAlarms()` (IO) und
     * `ShiftUseCase.recognizeShiftsInEvents()` (Main) teilen denselben Hilt-Singleton.
     *
     * Der Fehler: `lastRecognitionHash = eventsHash` wurde gesetzt, BEVOR `performRecognition()`
     * ueberhaupt lief. Der Cache-Treffer-Pfad prueft nur `lastRecognitionHash == eventsHash` und
     * das Cache-Alter — NICHT `recognitionInProgress`. Ein zweiter Aufrufer in diesem Fenster
     * bekam deshalb `cachedMatches` zurueck: beim allerersten Lauf eine LEERE Liste.
     *
     * Warum das teuer ist: `syncAlarms()` versteht eine leere Trefferliste als "keine Schichten"
     * und loescht daraufhin bestehende Alarme. Genau dieses Symptom ("0 Alarme trotz korrekt
     * erkannter Schichten") wurde in v1.21.0 am Fairphone beobachtet; der Fix damals entfernte
     * nur EINEN der beiden ueberlappenden Aufrufer, die Engine selbst blieb anfaellig.
     */
    @Test
    fun `gleichzeitiger Aufrufer bekommt keine leere Liste waehrend die Erkennung laeuft`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS")))
        )
        val engine = ShiftRecognitionEngine(GatedShiftConfigRepository(config, gate))
        val events = listOf(event("FS"))

        // Aufrufer A startet und haengt mitten in performRecognition() an der Schleuse.
        val first = async { engine.getAllMatchingShifts(events) }
        runCurrent()

        // Aufrufer B kommt genau jetzt — mit identischem Event-Hash.
        val second = async { engine.getAllMatchingShifts(events) }
        runCurrent()

        // A darf fertig werden.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("Aufrufer A muss die Schicht finden", 1, first.await().size)
        assertEquals(
            "Aufrufer B darf NICHT die leere Cache-Vorbelegung sehen, sonst loescht syncAlarms alle Alarme",
            1,
            second.await().size
        )
    }

    /**
     * Nach dem regulaeren Lauf darf ein zweiter Aufruf mit identischen Events den Cache nutzen
     * (das ist der eigentliche Zweck des Caches) — der Fix fuer den Race darf ihn nicht
     * abschalten. Belegt ueber `loadCount`: das Repository wird nur EINMAL gelesen.
     */
    @Test
    fun `identische Events werden aus dem Cache beantwortet`() = runTest {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS")))
        )
        val repo = GatedShiftConfigRepository(config)
        val engine = ShiftRecognitionEngine(repo)
        val events = listOf(event("FS"))

        val firstRun = engine.getAllMatchingShifts(events)
        val secondRun = engine.getAllMatchingShifts(events)

        assertEquals(1, firstRun.size)
        assertEquals(1, secondRun.size)
        assertEquals("Zweiter Aufruf muss aus dem Cache kommen", 1, repo.loadCount)
    }

    /**
     * Die Erkennung bricht pro Event beim ERSTEN passenden Treffer ab (`break`). Eine
     * deaktivierte Definition, die weiter vorn in der Liste steht, darf deshalb einer
     * aktivierten dahinter nicht den Treffer wegnehmen - sonst haette das Deaktivieren einer
     * Schicht die Nebenwirkung, eine andere passende Schicht stillzulegen.
     */
    @Test
    fun `deaktivierte Definition verdeckt keine aktivierte mit demselben Keyword`() = runTest {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(
                definition("Altlast", listOf("FS"), enabled = false),
                definition("Fruehschicht", listOf("FS"), enabled = true)
            )
        )
        val engine = ShiftRecognitionEngine(GatedShiftConfigRepository(config))

        val matches = engine.getAllMatchingShifts(listOf(event("FS")))

        assertEquals(1, matches.size)
        assertEquals("Fruehschicht", matches.first().shiftDefinition.name)
    }

    /**
     * Ein Lauf, der mit einer Exception scheitert (z.B. DataStore gerade nicht lesbar), darf
     * KEINEN Cache-Eintrag hinterlassen. Vor dem Fix wurde `lastRecognitionHash` vor der
     * Erkennung gesetzt und beim Fehlschlag nicht zurueckgenommen - der naechste Aufruf mit
     * denselben Events bekam deshalb einen "Cache-Treffer" auf die leere Vorbelegung, obwohl
     * nie eine Erkennung gelaufen war. Fuer eine Wecker-App ist "leer" die gefaehrlichste
     * Luege: `syncAlarms()` loescht darauf alle Alarme.
     */
    @Test
    fun `gescheiterter Lauf wird nicht als leeres Ergebnis gecacht`() = runTest {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS")))
        )
        val repo = FailingOnceShiftConfigRepository(config)
        val engine = ShiftRecognitionEngine(repo)
        val events = listOf(event("FS"))

        try {
            engine.getAllMatchingShifts(events)
            throw AssertionError("Der erste Lauf muss die Exception durchreichen")
        } catch (expected: IllegalStateException) {
            // so gewollt: der Aufrufer (SafeExecutor) sieht den Fehler, nicht "0 Schichten"
        }

        val secondRun = engine.getAllMatchingShifts(events)

        assertEquals(
            "Nach einem gescheiterten Lauf muss erneut wirklich erkannt werden",
            1,
            secondRun.size
        )
        assertEquals("Zweiter Aufruf muss das Repository erneut lesen", 2, repo.loadCount)
    }

    /**
     * Der Mutex darf die Deduplizierung nicht verlieren: zwei gleichzeitige Aufrufer mit
     * identischen Events duerfen die (teure) Erkennung nur EINMAL ausfuehren - der zweite
     * bekommt das fertige Ergebnis als Cache-Treffer.
     */
    @Test
    fun `gleichzeitige Aufrufer erkennen nur einmal`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS")))
        )
        val repo = GatedShiftConfigRepository(config, gate)
        val engine = ShiftRecognitionEngine(repo)
        val events = listOf(event("FS"))

        val first = async { engine.getAllMatchingShifts(events) }
        runCurrent()
        val second = async { engine.getAllMatchingShifts(events) }
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, first.await().size)
        assertEquals(1, second.await().size)
        assertEquals("Die Erkennung darf nur einmal gelaufen sein", 1, repo.loadCount)
    }

    /**
     * DIE ANDERE KANTE DES MUTEX: `clearRecognitionCache()` laeuft aus SYNCHRONEM Kontext
     * (`ShiftUseCase.invalidateAllCaches()`) und kann den Mutex gar nicht nehmen. Ein Lauf, der
     * gerade im kritischen Abschnitt steckt, darf seinen auf der ALTEN Konfiguration entstandenen
     * Stand danach nicht als frischen Cache veroeffentlichen.
     *
     * Reihenfolge im Test = die reale Reihenfolge am Geraet: (1) ein regulaerer Engine-Aufruf
     * (Vordergrund-Refresh, 400ms-Debounce des ShiftViewModel oder die 6h-Wartung) haengt mitten
     * in der Erkennung; (2) der Nutzer speichert eine geaenderte Schicht-Konfiguration ->
     * `clearRecognitionCache()`; (3) der haengende Lauf wird fertig und schreibt.
     *
     * Vor dem Fix nutzte der naechste Aufruf mit denselben Events diesen Stand fuer die volle
     * adaptive Cache-Dauer (2-30s) als Treffer - die gerade deaktivierte Schicht behielt ihren
     * Wecker, die geaenderte Weckzeit wurde nicht uebernommen. Belegt ueber `loadCount`: nach der
     * Invalidierung MUSS die Konfiguration erneut gelesen werden.
     */
    @Test
    fun `Invalidierung waehrend eines laufenden Aufrufs wird nicht ueberschrieben`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS")))
        )
        val repo = GatedShiftConfigRepository(config, gate)
        val engine = ShiftRecognitionEngine(repo)
        val events = listOf(event("FS"))

        // (1) Lauf A haengt mitten in performRecognition().
        val running = async { engine.getAllMatchingShifts(events) }
        runCurrent()

        // (2) Der Nutzer speichert -> Invalidierung, ohne Mutex, mitten in Lauf A.
        engine.clearRecognitionCache()

        // (3) Lauf A wird fertig und veroeffentlicht seinen Stand.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Lauf A liefert sein Ergebnis normal an seinen Aufrufer", 1, running.await().size)

        val afterInvalidation = engine.getAllMatchingShifts(events)

        assertEquals(1, afterInvalidation.size)
        assertEquals(
            "Nach einer Invalidierung mitten im Lauf muss die Konfiguration erneut gelesen werden",
            2,
            repo.loadCount
        )
    }

    /**
     * Gegenprobe: eine Invalidierung, die NICHT mit einem laufenden Aufruf kollidiert, muss den
     * Cache genauso wirksam leeren - der Epochen-Mechanismus darf den normalen Fall nicht
     * verschlucken.
     */
    @Test
    fun `Invalidierung nach einem abgeschlossenen Lauf erzwingt neues Lesen`() = runTest {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("FS")))
        )
        val repo = GatedShiftConfigRepository(config)
        val engine = ShiftRecognitionEngine(repo)
        val events = listOf(event("FS"))

        engine.getAllMatchingShifts(events)
        engine.clearRecognitionCache()
        engine.getAllMatchingShifts(events)

        assertEquals("Nach clearRecognitionCache() darf kein Cache-Treffer mehr entstehen", 2, repo.loadCount)
    }

    /**
     * Repository, das einen Lesefehler als `Result.failure` MELDET statt zu werfen — genau das
     * tut der echte [ShiftConfigRepository] bei einer vorhandenen, aber nicht dekodierbaren
     * Konfiguration ("Broken").
     */
    private class FailingResultShiftConfigRepository : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(ShiftConfig())
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> =
            Result.failure(IllegalStateException("Schicht-Konfiguration ist defekt"))
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> = Result.success(false)
    }

    /**
     * Ein gescheiterter Konfigurations-Read darf NICHT als "keine Schichtdefinitionen" und damit
     * als leere Trefferliste durchgehen.
     *
     * `performRecognition()` las die Definitionen als
     * `shiftConfigResult.getOrNull()?.definitions ?: emptyList()`. Der echte Repository-Pfad fuer
     * eine DEFEKTE Konfiguration liefert genau ein `Result.failure` — nicht eine Exception.
     * Damit wurden aus "ich kann die Konfiguration nicht lesen" lautlos "0 Definitionen", daraus
     * "0 erkannte Schichten", und `AlarmUseCase.syncAlarms()` versteht das als "keine Schichten"
     * und loescht ALLE Alarme. Dieselbe Fehlerklasse, die CLAUDE.md als "leer ist die
     * gefaehrlichste Luege" festhaelt.
     *
     * Erwartung: der Fehler kommt beim Aufrufer an, statt sich als leeres Ergebnis zu tarnen.
     */
    @Test
    fun `gescheiterter Konfigurations-Read wird nicht zu einer leeren Trefferliste`() = runTest {
        val engine = ShiftRecognitionEngine(FailingResultShiftConfigRepository())

        var thrown: Throwable? = null
        val matches = try {
            engine.getAllMatchingShifts(listOf(event("FS")))
        } catch (e: Throwable) {
            thrown = e
            null
        }

        assertTrue(
            "Ein Lesefehler muss beim Aufrufer ankommen, statt als leere Trefferliste getarnt zu " +
                "werden (bekommen: ${matches?.size ?: "Exception"})",
            thrown != null
        )
    }

    /**
     * GANZTAEGIGER TERMIN: Die Vortags-Heuristik fuer Nachtschichten darf nicht zuschlagen.
     *
     * Ein ganztaegiger Google-Kalendereintrag hat gar keinen Schichtbeginn — er liefert
     * `start.date` ohne Uhrzeit. Seit die Umrechnung ihn korrekt auf lokal 00:00 des Kalendertags
     * legt (vorher: UTC-Mitternacht, in Europe/Berlin also 01:00/02:00), ist JEDE Weckzeit ab
     * 12:00 "nach Schichtbeginn" mit einer Vorlaufzeit von unter 12h — die Heuristik rollt sie
     * also auf den Vortag. Die Standard-Spaetschicht (12:30) waere damit einen ganzen Tag zu
     * frueh geweckt worden, und am eigentlichen Schichttag haette es keinen Wecker gegeben.
     *
     * Bei einem ganztaegigen Eintrag ist 00:00 kein echter Schichtbeginn, sondern nur der Anker
     * des Kalendertags. Es gibt also nichts, gegen das "danach" sinnvoll pruefbar waere.
     */
    @Test
    fun `ganztaegiger Termin weckt am Tag des Termins, nicht am Vortag`() = runTest {
        val allDay = CalendarEvent(
            id = "allday-1",
            title = "S2",
            startTime = LocalDateTime.of(2026, 8, 10, 0, 0),
            endTime = LocalDateTime.of(2026, 8, 10, 23, 59),
            calendarId = "cal1",
            isAllDay = true
        )
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(
                ShiftDefinition(
                    id = "s2",
                    name = "Spaetschicht",
                    keywords = listOf("S2"),
                    alarmTime = LocalTime.of(12, 30)
                )
            )
        )
        val engine = ShiftRecognitionEngine(GatedShiftConfigRepository(config))

        val matches = engine.getAllMatchingShifts(listOf(allDay))

        assertEquals(1, matches.size)
        assertEquals(
            "Weckzeit muss am Kalendertag des ganztaegigen Termins liegen, nicht am Vortag",
            LocalDateTime.of(2026, 8, 10, 12, 30),
            matches.first().calculatedAlarmTime
        )
    }

    /**
     * Gegenprobe: bei einem ZEITGEBUNDENEN Termin muss die Vortags-Heuristik weiter greifen.
     * Echter Mitternachts-Fall: Schicht beginnt 00:30, Weckzeit 23:30 — der Wecker gehoert auf
     * den Vortag. Ohne diesen Test koennte ein Fix fuer den Ganztags-Fall die Heuristik
     * versehentlich komplett abschalten.
     */
    @Test
    fun `zeitgebundene Nachtschicht weckt weiterhin am Vortag`() = runTest {
        val nightShift = CalendarEvent(
            id = "night-1",
            title = "ND",
            startTime = LocalDateTime.of(2026, 8, 10, 0, 30),
            endTime = LocalDateTime.of(2026, 8, 10, 8, 30),
            calendarId = "cal1"
        )
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(
                ShiftDefinition(
                    id = "nd",
                    name = "Nachtschicht",
                    keywords = listOf("ND"),
                    alarmTime = LocalTime.of(23, 30)
                )
            )
        )
        val engine = ShiftRecognitionEngine(GatedShiftConfigRepository(config))

        val matches = engine.getAllMatchingShifts(listOf(nightShift))

        assertEquals(1, matches.size)
        assertEquals(
            "Echte Nachtschicht: Weckzeit 23:30 gehoert auf den Vortag",
            LocalDateTime.of(2026, 8, 9, 23, 30),
            matches.first().calculatedAlarmTime
        )
    }
}
