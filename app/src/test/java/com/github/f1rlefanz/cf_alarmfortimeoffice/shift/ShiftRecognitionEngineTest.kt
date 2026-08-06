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
}
