package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests fuer [DimRuleRepository] - der Schreibpfad der Dimm-Regeln.
 *
 * Kern der Absicherung: ein Dekodier-Fehler ist ein ANZEIGE-Problem und darf niemals als leere
 * Liste in ein Read-Modify-Write laufen. Vorher las `upsert()`/`delete()` ueber den Flow (der bei
 * defektem JSON `emptyList()` liefert) und schrieb das Ergebnis unbedingt zurueck - eine einzige
 * nicht dekodierbare Regel loeschte damit beim naechsten Speichern den GANZEN Bestand, inklusive
 * der bedeutungstragenden Nachtdienst-Ausnahme (Regel mit leerer Fensterliste).
 */
class DimRuleRepositoryTest {

    private val rulesKey = stringPreferencesKey("dim_rules")
    private val seedJson = Json { encodeDefaults = true }

    /** Minimaler, echter In-Memory-DataStore<Preferences> - deckt data/updateData (und edit{}) ab. */
    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    private fun storeWithRaw(raw: String?): FakePreferencesDataStore =
        FakePreferencesDataStore(
            if (raw == null) emptyPreferences() else mutablePreferencesOf().apply { this[rulesKey] = raw }
        )

    private suspend fun FakePreferencesDataStore.raw(): String? = data.first()[rulesKey]

    private fun rule(id: String, name: String = id) = DimRule(
        id = id,
        name = name,
        shiftPattern = DimRule.SHIFT_UNIVERSAL,
        windows = listOf(DimWindow())
    )

    @Test
    fun `upsert fuegt eine Regel hinzu und behaelt die bestehenden`() = runTest {
        val store = storeWithRaw(seedJson.encodeToString(listOf(rule("a"), rule("b"))))
        val repo = DimRuleRepository(store)

        repo.upsert(rule("c"))

        assertEquals(listOf("a", "b", "c"), repo.getRules().map { it.id })
    }

    @Test
    fun `upsert ersetzt eine bestehende Regel anhand der id`() = runTest {
        val store = storeWithRaw(seedJson.encodeToString(listOf(rule("a", "alt"), rule("b"))))
        val repo = DimRuleRepository(store)

        repo.upsert(rule("a", "neu"))

        val rules = repo.getRules()
        assertEquals(2, rules.size)
        assertEquals("neu", rules.first { it.id == "a" }.name)
    }

    @Test
    fun `delete entfernt genau eine Regel`() = runTest {
        val store = storeWithRaw(seedJson.encodeToString(listOf(rule("a"), rule("b"))))
        val repo = DimRuleRepository(store)

        repo.delete("a")

        assertEquals(listOf("b"), repo.getRules().map { it.id })
    }

    @Test
    fun `upsert auf defektem JSON laesst die Rohdaten unveraendert statt alles zu loeschen`() = runTest {
        val broken = """[{"id":"a","name":"A"""" // abgeschnittenes JSON
        val store = storeWithRaw(broken)
        val repo = DimRuleRepository(store)

        // Der Anzeige-Flow degradiert (leere Liste) - das ist gewollt ...
        assertTrue(repo.getRules().isEmpty())

        // ... aber das Speichern darf darauf NICHT aufbauen.
        repo.upsert(rule("neu"))

        assertEquals("Defektes JSON muss unangetastet liegen bleiben", broken, store.raw())
    }

    @Test
    fun `delete auf defektem JSON laesst die Rohdaten unveraendert`() = runTest {
        val broken = """[{"id":"a","name":"A""""
        val store = storeWithRaw(broken)
        val repo = DimRuleRepository(store)

        repo.delete("a")

        assertEquals(broken, store.raw())
    }

    /** Simuliert ein Downgrade auf eine APK, die diesen Anker-Wert nicht kennt. */
    private val withUnknownAnchor = """
        [{"id":"a","name":"A","shiftPattern":"__UNIVERSAL__","enabled":true,
          "windows":[{"startAnchor":"MOND_AUFGANG","startClockMinutes":1200,
                      "startOffsetMinutes":-120,"endAnchor":"ALARM",
                      "endClockMinutes":360,"endOffsetMinutes":0}],
          "strength":55,"warmth":40}]
    """.trimIndent()

    @Test
    fun `ein unbekannter Anker-Wert kostet nur das Feld, nicht die ganze Regel-Liste`() = runTest {
        val store = storeWithRaw(withUnknownAnchor)
        val repo = DimRuleRepository(store)

        val rules = repo.getRules()

        assertEquals("Die Regel darf nicht verloren gehen", 1, rules.size)
        assertNotNull(rules.first().windows.firstOrNull())
        assertEquals(
            "Unbekannter Enum-Wert faellt auf den Feld-Default zurueck",
            DimAnchor.CLOCK,
            rules.first().windows.first().startAnchor
        )
    }

    /**
     * Die Kehrseite der Toleranz: `editRules()` serialisiert den DEKODIERTEN Bestand komplett neu.
     * Duerfte der auf den Feld-Default gefallene Anker mitgeschrieben werden, waere der
     * Originalwert nach dem Bearbeiten einer BELIEBIGEN anderen Regel dauerhaft weg - der
     * Rueckweg ueber ein Update/Downgrade-Fix, den der Kommentar an editRules() verspricht, ist
     * dann zu. Deshalb liest der Schreibpfad strikt und bricht ab wie bei defektem JSON.
     */
    @Test
    fun `upsert schreibt einen unbekannten Anker-Wert nicht still auf den Default um`() = runTest {
        val store = storeWithRaw(withUnknownAnchor)
        val repo = DimRuleRepository(store)

        repo.upsert(rule("neu"))

        assertEquals(
            "Rohdaten mit unbekanntem Anker muessen unangetastet liegen bleiben",
            withUnknownAnchor,
            store.raw()
        )
    }

    @Test
    fun `delete laesst einen unbekannten Anker-Wert unangetastet`() = runTest {
        val store = storeWithRaw(withUnknownAnchor)
        val repo = DimRuleRepository(store)

        repo.delete("a")

        assertEquals(withUnknownAnchor, store.raw())
    }
}
