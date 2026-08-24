package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
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
        val dnd: DndScheduleUseCase
    )

    private fun fixture(): Fixture {
        val dim = mock<DimScheduleUseCase>()
        val dnd = mock<DndScheduleUseCase>()
        return Fixture(ZeitkettenArmierer({ dim }, { dnd }), dim, dnd)
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
}
