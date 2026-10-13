package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.RufbereitschaftAbfrage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * Die Zusicherungen des gemeinsamen Nachzugs — sie lagen bis v1.34.3 an FÜNF Stellen von Hand und
 * werden deshalb jetzt genau einmal geprüft.
 *
 * WARUM DIE REIHENFOLGE TRÄGT: „Nicht stören" hat im Modus „folgt dem Dimmer" keine eigene
 * Fensterquelle — es liest die Dimm-Zeitleiste LIVE. `dataStore.edit {}` kehrt erst nach
 * persistiertem Write zurück, das DND-`enable()` sieht den neuen Stand also nur, wenn der Dimmer
 * vorher dran war. Am Fairphone gemessen (23.08.2026), als der Nachzug ganz fehlte: knapp drei
 * Stunden „Nicht stören" ohne Grund. In einer Rufbereitschaftsnacht sind das verlorene Anrufe.
 */
class ZeitkettenArmiererTest {

    private class Fixture(
        val armierer: ZeitkettenArmierer,
        val dim: DimScheduleUseCase,
        val dnd: DndScheduleUseCase,
        val abfrage: RufbereitschaftAbfrage,
        val preAlarm: CalendarPreAlarmRefreshScheduler,
        val spanStore: com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
    )

    private fun fixture(): Fixture {
        val dim = mock<DimScheduleUseCase>()
        val dnd = mock<DndScheduleUseCase>()
        val abfrage = mock<RufbereitschaftAbfrage>()
        val preAlarm = mock<CalendarPreAlarmRefreshScheduler>()
        val spanStore = mock<com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore>()
        return Fixture(
            ZeitkettenArmierer({ dim }, { dnd }, { abfrage }, { preAlarm }, { spanStore }),
            dim, dnd, abfrage, preAlarm, spanStore
        )
    }

    /**
     * Die stuendliche Rufbereitschafts-Abfrage liest dieselben Eingaenge wie der DND-Cutoff
     * (Spannen, Flag, freie Tage) - jeder Anlass, der DND neu armiert, verschiebt auch sie.
     */
    @Test
    fun `mit DND wird auch die Rufbereitschafts-Abfrage neu geplant`() = runTest {
        val f = fixture()

        f.armierer.armiere("TEST", dimmer = false, dnd = true)

        verify(f.abfrage).reschedule()
    }

    @Test
    fun `nur Dimmer laesst die Rufbereitschafts-Abfrage in Ruhe`() = runTest {
        val f = fixture()

        f.armierer.armiere("TEST", dimmer = true, dnd = false)

        verify(f.abfrage, never()).reschedule()
    }

    @Test
    fun `armiert erst den Dimmer, dann DND`() = runTest {
        val f = fixture()

        f.armierer.armiere("TEST")

        val reihenfolge = inOrder(f.dim, f.dnd)
        reihenfolge.verify(f.dim).enable()
        reihenfolge.verify(f.dnd).enable()
    }

    /**
     * Ändert sich ausschliesslich eine DND-eigene Namensliste (Rufbereitschaft,
     * Dienstzeit-Ausnahmen), wäre ein Dimmer-`enable()` eine vollständige Fensterberechnung ohne
     * jede Wirkung — der Dimmer kennt diese Listen nicht.
     */
    @Test
    fun `nur DND armieren laesst den Dimmer in Ruhe`() = runTest {
        val f = fixture()

        f.armierer.armiere("TEST", dimmer = false, dnd = true)

        verify(f.dim, never()).enable()
        verify(f.dnd).enable()
    }

    @Test
    fun `nur den Dimmer armieren laesst DND in Ruhe`() = runTest {
        val f = fixture()

        f.armierer.armiere("TEST", dimmer = true, dnd = false)

        verify(f.dim).enable()
        verify(f.dnd, never()).enable()
    }

    @Test
    fun `ohne Bedarf passiert gar nichts`() = runTest {
        val f = fixture()

        f.armierer.armiere("TEST", dimmer = false, dnd = false)

        verify(f.dim, never()).enable()
        verify(f.dnd, never()).enable()
    }

    /**
     * JEDER AUFRUF EINZELN GEFANGEN. Ein Fehlschlag der einen Kette darf die andere nicht
     * mitreissen — sonst nimmt ein kaputter Dimmer „Nicht stören" mit in den Abgrund, obwohl beide
     * völlig unabhängige Alarme planen.
     */
    @Test
    fun `ein Fehlschlag im Dimmer verhindert das DND-Armieren NICHT`() = runTest {
        val f = fixture()
        f.dim.stub { onBlocking { enable() } doAnswer { throw RuntimeException("DataStore kaputt") } }

        f.armierer.armiere("TEST")

        verify(f.dnd).enable()
    }

    /**
     * Und die Gegenrichtung: Ein Fehlschlag darf den AUSLÖSENDEN Vorgang nicht als gescheitert
     * melden. Import, Freigabe oder Umbenennung sind zu diesem Zeitpunkt längst geschrieben — eine
     * hier durchgereichte Exception würde dem Nutzer einen Fehlschlag anzeigen, den es nicht gab.
     */
    @Test
    fun `ein Fehlschlag beider Ketten wirft nicht nach oben`() = runTest {
        val f = fixture()
        f.dim.stub { onBlocking { enable() } doAnswer { throw RuntimeException("Dimmer kaputt") } }
        f.dnd.stub { onBlocking { enable() } doAnswer { throw RuntimeException("DND kaputt") } }

        f.armierer.armiere("TEST") // darf nicht werfen
    }

    /** #131 (G11-14): Boot und Wartung armieren ALLE vier Nebenketten - Dimmer vor DND. */
    @Test
    fun `armiereNebenketten plant alle vier Ketten, Dimmer vor DND`() = runTest {
        val f = fixture()

        f.armierer.armiereNebenketten("TEST", pausiert = false)

        val reihenfolge = inOrder(f.dim, f.dnd)
        reihenfolge.verify(f.dim).enable()
        reihenfolge.verify(f.dnd).enable()
        verify(f.preAlarm).reschedule()
        verify(f.abfrage).reschedule()
        verify(f.dim, never()).disable()
    }

    /** Master-Pause: raeumen statt planen - fuer alle vier. */
    @Test
    fun `armiereNebenketten raeumt bei Pause alle vier Ketten ab`() = runTest {
        val f = fixture()

        f.armierer.armiereNebenketten("TEST", pausiert = true)

        verify(f.dim).disable()
        verify(f.dnd).disable()
        verify(f.preAlarm).cancelAll()
        verify(f.abfrage).cancel()
        verify(f.dim, never()).enable()
        verify(f.abfrage, never()).reschedule()
    }

    /** Ein Fehlschlag der einen Kette darf die anderen nicht mitreissen. */
    @Test
    fun `armiereNebenketten - ein Fehlschlag blockiert die anderen Ketten nicht`() = runTest {
        val f = fixture()
        f.dim.stub { onBlocking { enable() } doAnswer { throw RuntimeException("boom") } }

        f.armierer.armiereNebenketten("TEST", pausiert = false)

        verify(f.dnd).enable()
        verify(f.preAlarm).reschedule()
        verify(f.abfrage).reschedule()
    }

    // --- planeNachVordergrundSync (Review 1.48.0) ---------------------------------------------

    /**
     * Nach einem Sync im Vordergrund ohne bekannten Vorher-Stand: nur die beiden Ketten, die aus dem Bestand rechnen. Dimmer
     * und DND wenden ihren Zustand in enable() sofort am Geraet an - ein Blick in die App soll
     * nichts umschalten.
     */
    @Test
    fun `planeNachVordergrundSync plant Pre-Alarm-Refresh und Rufbereitschaft, ohne Vorher-Stand nicht Dimmer und DND`() =
        runTest {
            val f = fixture()

            f.armierer.planeNachVordergrundSync()

            verify(f.preAlarm).reschedule()
            verify(f.abfrage).reschedule()
            verify(f.dim, never()).enable()
            verify(f.dnd, never()).enable()
            verify(f.dim, never()).disable()
            verify(f.dnd, never()).disable()
        }

    @Test
    fun `planeNachVordergrundSync - ein Fehlschlag reisst die andere Kette nicht mit und wirft nicht`() = runTest {
        val f = fixture()
        f.preAlarm.stub { onBlocking { reschedule() } doAnswer { throw RuntimeException("WorkManager weg") } }

        f.armierer.planeNachVordergrundSync()

        verify(f.abfrage).reschedule()
    }

    // --- Dimmer/DND nach einem Vordergrund-Sync nur bei geaendertem Schichtstand (1.49.1) -------

    private val fruehVorher = com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan(
        shiftName = "Frühschicht", startTime = 1_000_000L, endTime = 2_000_000L, alarmTriggerTime = 900_000L
    )

    private fun Fixture.standDanach(spannen: List<com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan>) {
        spanStore.stub { onBlocking { spansNow() } doAnswer { Result.success(spannen) } }
    }

    /**
     * Ein verschobener Dienst verschiebt Fenster, deren BEGINN an Weckzeit oder Dienst haengt -
     * ohne Nachzug hingen sie bis zur naechsten Wartung (bis zu 6 h) an der alten Zeit.
     */
    @Test
    fun `geaenderter Schichtstand - Dimmer und DND werden neu armiert, Dimmer zuerst`() = runTest {
        val f = fixture()
        f.standDanach(listOf(fruehVorher.copy(startTime = 500_000L, alarmTriggerTime = 400_000L)))

        f.armierer.planeNachVordergrundSync(standVorher = listOf(fruehVorher))

        inOrder(f.dim, f.dnd) {
            verify(f.dim).enable()
            verify(f.dnd).enable()
        }
    }

    @Test
    fun `unveraenderter Schichtstand - ein Blick in die App schaltet nichts um`() = runTest {
        val f = fixture()
        f.standDanach(listOf(fruehVorher))

        f.armierer.planeNachVordergrundSync(standVorher = listOf(fruehVorher))

        verify(f.dim, never()).enable()
        verify(f.dnd, never()).enable()
    }

    @Test
    fun `unbekannter Vorher-Stand - es bleibt beim Nachzug durch die Wartung`() = runTest {
        val f = fixture()
        f.standDanach(listOf(fruehVorher.copy(startTime = 1L)))

        f.armierer.planeNachVordergrundSync(standVorher = null)

        verify(f.dim, never()).enable()
        verify(f.dnd, never()).enable()
    }

    @Test
    fun `nicht lesbarer Stand danach - nichts umschalten`() = runTest {
        val f = fixture()
        f.spanStore.stub { onBlocking { spansNow() } doAnswer { Result.failure(RuntimeException("kaputt")) } }

        f.armierer.planeNachVordergrundSync(standVorher = listOf(fruehVorher))

        verify(f.dim, never()).enable()
        verify(f.dnd, never()).enable()
    }
}
