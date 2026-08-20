package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Prüfrunde 8, Befund 2 - Dimmer-Seite: Beim Umbenennen einer Schichtdefinition muss das
 * Regelmuster mitgezogen werden.
 *
 * DER FEHLER: [DimRule.shiftPattern] bindet über den NAMEN der Definition, nicht über deren
 * stabile `id`. Nach einer reinen Beschriftungsänderung ("AD1" → "Abrufdienst") findet
 * [DimRuleUseCase.findRuleForShift] nichts mehr und fällt auf UNIVERSAL bzw. `null` zurück - das
 * Dimm-Fenster dieser Schicht verschwindet, und im DND-Modus „folgt dem Dimmer" fällt das
 * Nachtfenster gleich mit weg. Die Regelliste zeigt die Regel dabei unverändert als aktiv.
 *
 * Getestet wird gegen das ECHTE [DimRuleRepository] über einem In-Memory-DataStore (wie in
 * [DimRuleRepositoryTest]) - nur so laufen der transaktionale Schreibpfad und die Nachprüfung
 * wirklich mit.
 */
class Pruefrunde8DimRegelNachzugTest {

    private val rulesKey = stringPreferencesKey("dim_rules")
    private val seedJson = Json { encodeDefaults = true }

    private open class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        protected val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    /** Schreiben scheitert hart (voller Speicher, EACCES) - `upsert()` wirft durch. */
    private class WerfenderDataStore(initial: Preferences) : FakePreferencesDataStore(initial) {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("Schreiben nicht moeglich")
    }

    /**
     * Schreiben wird STILL verworfen - genau das Verhalten von `DimRuleRepository.editRules()`,
     * wenn es den Bestand nicht dekodieren kann: es loggt, aendert nichts und meldet nichts nach
     * oben. Ohne Nachpruefung meldete der Nachzug hier faelschlich Erfolg.
     */
    private class StillVerwerfenderDataStore(initial: Preferences) : FakePreferencesDataStore(initial) {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(flow.value)
    }

    private fun prefs(rules: List<DimRule>): Preferences =
        mutablePreferencesOf().apply { this[rulesKey] = seedJson.encodeToString(rules) }

    private fun rule(id: String, pattern: String) = DimRule(
        id = id,
        name = "Regel $id",
        shiftPattern = pattern,
        windows = listOf(DimWindow())
    )

    private fun useCase(store: DataStore<Preferences>) = DimRuleUseCase(DimRuleRepository(store))

    @Test
    fun `Regel der umbenannten Schicht wird auf den neuen Namen umgeschrieben`() = runTest {
        val store = FakePreferencesDataStore(prefs(listOf(rule("a", "AD1"), rule("b", "Frueh"))))
        val useCase = useCase(store)

        val ergebnis = useCase.renameShiftPattern("AD1", "Abrufdienst")

        assertEquals(1, ergebnis.getOrNull())
        val rules = useCase.getAllRules()
        assertEquals("Abrufdienst", rules.first { it.id == "a" }.shiftPattern)
        assertEquals("Frueh", rules.first { it.id == "b" }.shiftPattern)
        // Und die eigentliche Wirkung: die Regel ist unter dem neuen Namen wieder auffindbar.
        assertEquals("a", useCase.findRuleForShift("Abrufdienst", rules)?.id)
    }

    @Test
    fun `Universal- und Frei-Muster werden NICHT mitgezogen`() = runTest {
        // Sie meinen keinen Schichtnamen, sondern eine Tages-Kategorie. Mitziehen wuerde aus
        // "jede Nacht dimmen" ein "nur in dieser einen Schicht dimmen" machen.
        val store = FakePreferencesDataStore(
            prefs(
                listOf(
                    rule("uni", DimRule.SHIFT_UNIVERSAL),
                    rule("frei", DimRule.SHIFT_FREE),
                    rule("ad", "AD1")
                )
            )
        )
        val useCase = useCase(store)

        assertEquals(1, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())

        val rules = useCase.getAllRules()
        assertEquals(DimRule.SHIFT_UNIVERSAL, rules.first { it.id == "uni" }.shiftPattern)
        assertEquals(DimRule.SHIFT_FREE, rules.first { it.id == "frei" }.shiftPattern)
    }

    @Test
    fun `Nachzug ist gross-kleinschreibungsblind - wie die Regelsuche selbst`() = runTest {
        val store = FakePreferencesDataStore(prefs(listOf(rule("a", "ad1"))))
        val useCase = useCase(store)

        assertEquals(1, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())
        assertEquals("Abrufdienst", useCase.getAllRules().single().shiftPattern)
    }

    @Test
    fun `reine Schreibweisenaenderung schreibt nichts`() = runTest {
        val store = FakePreferencesDataStore(prefs(listOf(rule("a", "ad1"))))
        val useCase = useCase(store)

        assertEquals(0, useCase.renameShiftPattern("ad1", "AD1").getOrNull())
        assertEquals("ad1", useCase.getAllRules().single().shiftPattern)
    }

    @Test
    fun `zweiter Lauf findet nichts mehr vor - idempotent`() = runTest {
        val store = FakePreferencesDataStore(prefs(listOf(rule("a", "AD1"))))
        val useCase = useCase(store)

        assertEquals(1, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())
        assertEquals(0, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())
    }

    @Test
    fun `leerer Name wird abgelehnt statt blind geschrieben`() = runTest {
        val store = FakePreferencesDataStore(prefs(listOf(rule("a", "AD1"))))

        assertTrue(useCase(store).renameShiftPattern("AD1", "  ").isFailure)
        assertEquals("AD1", useCase(store).getAllRules().single().shiftPattern)
    }

    @Test
    fun `harter Schreibfehler wird gemeldet, nicht verschluckt`() = runTest {
        val store = WerfenderDataStore(prefs(listOf(rule("a", "AD1"))))

        assertTrue(useCase(store).renameShiftPattern("AD1", "Abrufdienst").isFailure)
    }

    @Test
    fun `still verworfener Schreibvorgang wird durch die Nachpruefung entlarvt`() = runTest {
        // Ohne die Nachpruefung in renameShiftPattern() meldete dieser Fall Erfolg, obwohl die
        // Regel weiter den Altnamen traegt - genau die stille Luege, gegen die der Fix ist.
        val store = StillVerwerfenderDataStore(prefs(listOf(rule("a", "AD1"))))
        val useCase = useCase(store)

        val ergebnis = useCase.renameShiftPattern("AD1", "Abrufdienst")

        assertTrue(ergebnis.isFailure)
        assertEquals("AD1", useCase.getAllRules().single().shiftPattern)
    }
}
