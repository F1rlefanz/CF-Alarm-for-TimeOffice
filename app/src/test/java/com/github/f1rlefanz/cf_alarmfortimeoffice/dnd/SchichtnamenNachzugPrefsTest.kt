package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Der Nachzug der drei SCHICHTNAMEN-LISTEN beim Umbenennen einer Schichtdefinition.
 *
 * DER BEFUND (Gerät des Nutzers, 21.08.2026): Seit v1.30.0 zieht das Umbenennen einer
 * Schichtdefinition die Dimmer- und Hue-REGELN mit. Drei weitere Stellen binden ebenfalls über den
 * NAMEN und wurden übersehen:
 *  - `dnd_oncall_shifts` - die Rufbereitschaft-Auswahl, Grundlage des On-Call-Cutoffs
 *    ([DndOnCallCutoffResolver]).
 *  - `dnd_shift_excluded_shifts` - die Ausnahmen von „Nicht stören während der Dienstzeit"
 *    ([DndShiftSpanResolver]).
 *  - `dim_night_default_excluded_shifts` - die Ausnahmen vom Nacht-Standard des Dimmers
 *    ([com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase]).
 *
 * Am Gerät stand in der Rufbereitschaft-Auswahl noch „Abrufdienst", während der Weckerbestand
 * längst „Rufdienst" sagte: Der Cutoff griff nicht mehr, „Nicht stören" blieb in der Nacht VOR der
 * Rufbereitschaft über 05:00 hinaus an - der Nutzer war nicht erreichbar. Sichtbar war davon
 * nichts, denn die Chips im DND-Bildschirm werden aus den AKTUELLEN Definitionsnamen gebaut; der
 * gespeicherte Alt-Name taucht dort gar nicht auf.
 *
 * OHNE DEN FIX fällt jeder dieser Tests: die Funktionen gab es nicht.
 */
class SchichtnamenNachzugPrefsTest {

    private val KEY_ONCALL = stringSetPreferencesKey("dnd_oncall_shifts")
    private val KEY_SHIFT_EXCLUDED = stringSetPreferencesKey("dnd_shift_excluded_shifts")
    private val KEY_NIGHT_EXCLUDED = stringSetPreferencesKey("dim_night_default_excluded_shifts")

    private class FakeDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)

        /**
         * Welche Schlüssel beim letzten Schreibvorgang tatsächlich einen anderen Wert bekommen
         * haben. `edit{}` selbst läuft immer - die interessante Frage ist, ob der Block einen
         * Schlüssel ANGEFASST hat („kein blindes Schreiben").
         */
        var zuletztGeaendert: Set<String> = emptySet()
            private set

        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val vorher = flow.value.asMap()
            val updated = transform(flow.value)
            zuletztGeaendert = updated.asMap()
                .filter { (k, v) -> vorher[k] != v }
                .keys.map { it.name }.toSet() +
                vorher.keys.filter { it !in updated.asMap() }.map { it.name }
            flow.value = updated
            return updated
        }

        fun lies(key: Preferences.Key<Set<String>>): Set<String>? = flow.value[key]
    }

    /** Ein Store, dessen Schreibvorgang scheitert - z. B. voller Speicher. */
    private class FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = MutableStateFlow(mutablePreferencesOf())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("simulierter Schreibfehler")
    }

    // `Preferences.Pair`, NICHT Kotlins `Pair`: `key to wert` loest bei einem
    // `Preferences.Key` auf DataStores eigenen `to`-Operator auf.
    private fun store(vararg eintraege: Preferences.Pair<*>) =
        FakeDataStore(mutablePreferencesOf(*eintraege))

    // ------------------------------------------------------------------ DND: beide Auswahlen

    @Test
    fun `der reale Fall des Nutzers - Abrufdienst wird zu Rufdienst`() = runTest {
        val s = store(KEY_ONCALL to setOf("Abrufdienst"))
        val prefs = DndPrefs(s)

        val ergebnis = prefs.renameShiftName("Abrufdienst", "Rufdienst")

        assertEquals(1, ergebnis.getOrNull())
        assertEquals(setOf("Rufdienst"), s.lies(KEY_ONCALL))
        // Und der Cutoff trifft wieder: der Resolver prüft exakte Mengen-Zugehörigkeit.
        assertTrue("Rufdienst" in prefs.onCallShiftsNow())
    }

    @Test
    fun `beide DND-Listen werden mitgezogen`() = runTest {
        val s = store(
            KEY_ONCALL to setOf("AD1", "Bereitschaft"),
            KEY_SHIFT_EXCLUDED to setOf("AD1")
        )

        val ergebnis = DndPrefs(s).renameShiftName("AD1", "Abrufdienst")

        assertEquals("Beide Listen zählen einzeln", 2, ergebnis.getOrNull())
        assertEquals(setOf("Abrufdienst", "Bereitschaft"), s.lies(KEY_ONCALL))
        assertEquals(setOf("Abrufdienst"), s.lies(KEY_SHIFT_EXCLUDED))
    }

    @Test
    fun `eine Liste ohne den Altnamen bleibt unveraendert - kein blindes Schreiben`() = runTest {
        val s = store(
            KEY_ONCALL to setOf("Bereitschaft"),
            KEY_SHIFT_EXCLUDED to setOf("Nachtschicht")
        )

        val ergebnis = DndPrefs(s).renameShiftName("AD1", "Abrufdienst")

        assertEquals(0, ergebnis.getOrNull())
        assertEquals("Kein Schlüssel darf angefasst worden sein", emptySet<String>(), s.zuletztGeaendert)
        assertEquals(setOf("Bereitschaft"), s.lies(KEY_ONCALL))
        assertEquals(setOf("Nachtschicht"), s.lies(KEY_SHIFT_EXCLUDED))
    }

    @Test
    fun `ein zweiter Lauf schreibt nichts mehr - idempotent`() = runTest {
        val s = store(KEY_ONCALL to setOf("AD1"))
        val prefs = DndPrefs(s)

        assertEquals(1, prefs.renameShiftName("AD1", "Abrufdienst").getOrNull())
        assertEquals(0, prefs.renameShiftName("AD1", "Abrufdienst").getOrNull())
        assertEquals(setOf("Abrufdienst"), s.lies(KEY_ONCALL))
    }

    @Test
    fun `ein Eintrag mit anderer Gross-Kleinschreibung bleibt liegen`() = runTest {
        // Beide Konsumenten prüfen `it.shiftName in ...Shifts` - exakt. Ein Eintrag "ad1" hat also
        // auch VORHER nie getroffen. Zöge man ihn mit, machte man aus einem toten Eintrag eine
        // scharfe Ausnahme, die der Nutzer nie eingerichtet hat.
        val s = store(KEY_ONCALL to setOf("ad1"))

        assertEquals(0, DndPrefs(s).renameShiftName("AD1", "Abrufdienst").getOrNull())
        assertEquals(setOf("ad1"), s.lies(KEY_ONCALL))
    }

    @Test
    fun `ein leerer Name wird abgelehnt statt geraten`() = runTest {
        val s = store(KEY_ONCALL to setOf("AD1"))

        assertTrue(DndPrefs(s).renameShiftName("AD1", "  ").isFailure)
        assertTrue(DndPrefs(s).renameShiftName("", "Abrufdienst").isFailure)
        assertEquals(setOf("AD1"), s.lies(KEY_ONCALL))
    }

    @Test
    fun `ein Schreibfehler wird als Fehlschlag gemeldet, nicht als Erfolg`() = runTest {
        val ergebnis = DndPrefs(FailingDataStore()).renameShiftName("AD1", "Abrufdienst")

        assertTrue(ergebnis.isFailure)
        assertNull(ergebnis.getOrNull())
    }

    // ------------------------------------------------ Dimmer: Ausnahmen des Nacht-Standards

    @Test
    fun `die Nacht-Ausnahme wird mitgezogen`() = runTest {
        val s = store(KEY_NIGHT_EXCLUDED to setOf("Nachtschicht", "Frueh"))
        val prefs = DimOverlayPrefs(s)

        assertEquals(1, prefs.renameShiftName("Nachtschicht", "Nachtdienst").getOrNull())
        assertEquals(setOf("Nachtdienst", "Frueh"), s.lies(KEY_NIGHT_EXCLUDED))
        assertTrue("Nachtdienst" in prefs.nightDefaultExcludedShiftsNow())
    }

    @Test
    fun `eine Nacht-Ausnahmenliste ohne den Altnamen bleibt unveraendert`() = runTest {
        val s = store(KEY_NIGHT_EXCLUDED to setOf("Frueh"))

        assertEquals(0, DimOverlayPrefs(s).renameShiftName("Nachtschicht", "Nachtdienst").getOrNull())
        assertEquals(emptySet<String>(), s.zuletztGeaendert)
        assertEquals(setOf("Frueh"), s.lies(KEY_NIGHT_EXCLUDED))
    }

    @Test
    fun `ein noch nie gesetzter Schluessel entsteht nicht durch den Nachzug`() = runTest {
        val s = store()

        assertEquals(0, DimOverlayPrefs(s).renameShiftName("Nachtschicht", "Nachtdienst").getOrNull())
        assertEquals(0, DndPrefs(s).renameShiftName("AD1", "Abrufdienst").getOrNull())
        assertNull(s.lies(KEY_NIGHT_EXCLUDED))
        assertNull(s.lies(KEY_ONCALL))
    }

    @Test
    fun `Nacht-Ausnahme ist idempotent und lehnt leere Namen ab`() = runTest {
        val s = store(KEY_NIGHT_EXCLUDED to setOf("Nachtschicht"))
        val prefs = DimOverlayPrefs(s)

        assertEquals(1, prefs.renameShiftName("Nachtschicht", "Nachtdienst").getOrNull())
        assertEquals(0, prefs.renameShiftName("Nachtschicht", "Nachtdienst").getOrNull())
        assertTrue(prefs.renameShiftName("Nachtdienst", " ").isFailure)
    }

    // ---------------------------------------------------- Nachtrag 21.08.2026, Befunde A und C

    /**
     * BEFUND A: Eine reine SCHREIBWEISEN-Änderung muss ankommen.
     *
     * `ShiftViewModel.planeSchichtUmbenennungen` stieg bei `equals(ignoreCase = true)` aus - richtig
     * für die Dimm-/Hue-Regeln, falsch für diese Listen: sie vergleichen exakt. Korrigiert der
     * Nutzer „abrufdienst" zu „Abrufdienst", trifft der gespeicherte Eintrag ab sofort nie wieder.
     * Hier der Beweis auf Speicherebene, dass der Fall überhaupt nachzuziehen IST.
     */
    @Test
    fun `eine reine Schreibweisenaenderung wird nachgezogen und trifft danach wieder`() = runTest {
        val s = store(KEY_ONCALL to setOf("abrufdienst"))
        val prefs = DndPrefs(s)

        assertEquals(1, prefs.renameShiftName("abrufdienst", "Abrufdienst").getOrNull())
        assertEquals(setOf("Abrufdienst"), s.lies(KEY_ONCALL))
        assertTrue("Abrufdienst" in prefs.onCallShiftsNow())
    }

    /**
     * BEFUND C: Der blockierte Namenstausch darf keine scharfe Falschzuordnung hinterlassen.
     *
     * Nach dem Tausch gehört der gespeicherte Name einer ANDEREN Schicht. Stehen gelassen wirkt er
     * dort still weiter (Cutoff an den falschen Tagen), und im Bildschirm sieht er aus wie eine
     * bewusste Auswahl - die Chips werden aus den aktuellen Definitionsnamen gebaut.
     */
    @Test
    fun `removeShiftName raeumt den Falscheintrag aus beiden DND-Listen`() = runTest {
        val s = store(
            KEY_ONCALL to setOf("Frueh", "Bereitschaft"),
            KEY_SHIFT_EXCLUDED to setOf("Frueh")
        )

        assertEquals(2, DndPrefs(s).removeShiftName("Frueh").getOrNull())
        assertEquals(setOf("Bereitschaft"), s.lies(KEY_ONCALL))
        assertEquals(emptySet<String>(), s.lies(KEY_SHIFT_EXCLUDED))
    }

    @Test
    fun `removeShiftName raeumt die Nacht-Ausnahme`() = runTest {
        val s = store(KEY_NIGHT_EXCLUDED to setOf("Frueh", "Nacht"))

        assertEquals(1, DimOverlayPrefs(s).removeShiftName("Frueh").getOrNull())
        assertEquals(setOf("Nacht"), s.lies(KEY_NIGHT_EXCLUDED))
    }

    @Test
    fun `removeShiftName fasst nichts an, was den Namen nicht enthaelt`() = runTest {
        val s = store(
            KEY_ONCALL to setOf("Bereitschaft"),
            KEY_NIGHT_EXCLUDED to setOf("Nacht")
        )

        assertEquals(0, DndPrefs(s).removeShiftName("Frueh").getOrNull())
        assertEquals(0, DimOverlayPrefs(s).removeShiftName("Frueh").getOrNull())
        assertEquals(emptySet<String>(), s.zuletztGeaendert)
    }

    @Test
    fun `removeShiftName vergleicht exakt - eine andere Schreibweise bleibt liegen`() = runTest {
        // Sonst löschte eine Blockade, die nur ueber `ignoreCase` zustande kam, einen Eintrag, der
        // der anderen Schicht gar nicht gehoert.
        val s = store(KEY_ONCALL to setOf("Frueh"))

        assertEquals(0, DndPrefs(s).removeShiftName("frueh").getOrNull())
        assertEquals(setOf("Frueh"), s.lies(KEY_ONCALL))
    }

    @Test
    fun `removeShiftName lehnt einen leeren Namen ab und meldet Schreibfehler`() = runTest {
        val s = store(KEY_ONCALL to setOf("Frueh"))

        assertTrue(DndPrefs(s).removeShiftName(" ").isFailure)
        assertTrue(DimOverlayPrefs(s).removeShiftName("").isFailure)
        assertEquals(setOf("Frueh"), s.lies(KEY_ONCALL))
        assertTrue(DndPrefs(FailingDataStore()).removeShiftName("Frueh").isFailure)
        assertTrue(DimOverlayPrefs(FailingDataStore()).removeShiftName("Frueh").isFailure)
    }

    @Test
    fun `ein Schreibfehler im Dimmer-Store wird als Fehlschlag gemeldet`() = runTest {
        assertTrue(DimOverlayPrefs(FailingDataStore()).renameShiftName("A", "B").isFailure)
    }

    // ------------------------------------------------------- Der Tauschfall (Befund 21.08.2026)

    /**
     * Zwei Schichten tauschen ihre Namen, und BEIDE stehen in derselben Liste.
     *
     * Dann ist ihr Inhalt nach dem Tausch weiterhin exakt richtig - die Liste meint beide
     * Schichten, und welcher Name zu welcher gehoert, ist ihr egal. Wer hier raeumt, zerstoert eine
     * korrekte Einstellung: der On-Call-Cutoff griffe danach fuer KEINE der beiden Schichten mehr,
     * und "Nicht stoeren" bliebe in der Nacht vor dem Dienst ueber 05:00 hinaus an - genau der
     * Schaden, gegen den der ganze Nachzug gebaut ist.
     *
     * OHNE DEN FIX faellt dieser Test: `removeShiftName` raeumte den Namen bedingungslos.
     */
    @Test
    fun `Namenstausch mit BEIDEN Namen in der Liste laesst sie unangetastet`() = runTest {
        val s = store(KEY_ONCALL to setOf("Frueh", "Nacht"))
        val prefs = DndPrefs(s)

        val ergebnis = prefs.removeShiftName("Frueh", partnerName = "Nacht")

        assertEquals("Nichts geraeumt", 0, ergebnis.getOrNull())
        assertEquals(setOf("Frueh", "Nacht"), s.lies(KEY_ONCALL))
    }

    /** Die Gegenprobe: steht nur EINER der beiden drin, zeigt er auf die falsche Schicht. */
    @Test
    fun `Namenstausch mit nur EINEM Namen in der Liste raeumt ihn weg`() = runTest {
        val s = store(KEY_ONCALL to setOf("Frueh"))
        val prefs = DndPrefs(s)

        val ergebnis = prefs.removeShiftName("Frueh", partnerName = "Nacht")

        assertEquals(1, ergebnis.getOrNull())
        assertEquals(emptySet<String>(), s.lies(KEY_ONCALL))
    }

    /** Dieselbe Regel fuer die Nacht-Ausnahmen des Dimmers. */
    @Test
    fun `Nacht-Ausnahmen bleiben beim Tausch mit beiden Namen erhalten`() = runTest {
        val s = store(KEY_NIGHT_EXCLUDED to setOf("Frueh", "Nacht"))
        val prefs = DimOverlayPrefs(s)

        val ergebnis = prefs.removeShiftName("Frueh", partnerName = "Nacht")

        assertEquals(0, ergebnis.getOrNull())
        assertEquals(setOf("Frueh", "Nacht"), s.lies(KEY_NIGHT_EXCLUDED))
    }
}
