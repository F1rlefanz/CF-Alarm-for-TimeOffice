package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bestand vor 1.52 kennt [DimWindow.nachlaufBisHell] nicht (#140). Er muss sich ueber BEIDE Pfade
 * des echten [DimRuleRepository] als [HellAb.IMMER] lesen - Anzeige (tolerant) und Schreibpfad
 * (strikt) -, damit ein Update kein bestehendes Fenster veraendert.
 */
class DimRuleAbwaertsTest {

    private val rulesKey = stringPreferencesKey("dim_rules")

    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    private fun store(raw: String) = FakePreferencesDataStore(mutablePreferencesOf().apply { this[rulesKey] = raw })

    /** Wortwoertlich so, wie 1.51.0 eine Regel mit ALARM_SONST_CLOCK-Ende und Nachlauf schreibt. */
    private val altJson = """
        [{"id":"r1","name":"Nacht","shiftPattern":"__UNIVERSAL__","enabled":true,
          "windows":[{"startAnchor":"CLOCK","startClockMinutes":1320,"startOffsetMinutes":-120,
                      "endAnchor":"ALARM_SONST_CLOCK","endClockMinutes":480,"endOffsetMinutes":0,
                      "weckzeitNachlaufMinuten":60,
                      "blockPositionen":["ERSTER","MITTLERER","LETZTER","EINZELNER"]}],
          "strength":55,"warmth":40}]
    """.trimIndent()

    @Test
    fun `Anzeige-Pfad liest altes Fenster als IMMER`() = runTest {
        val rules = DimRuleRepository(store(altJson)).rules.first()
        assertEquals(1, rules.size)
        assertEquals(HellAb.IMMER, rules.single().windows.single().nachlaufBisHell)
        assertEquals(60, rules.single().windows.single().weckzeitNachlaufMinuten)
    }

    @Test
    fun `Schreib-Pfad liest altes Fenster strikt als IMMER und schreibt den Default mit`() = runTest {
        val s = store(altJson)
        val repo = DimRuleRepository(s)
        repo.upsert(DimRule(id = "r2", name = "Zweite", shiftPattern = DimRule.SHIFT_FREE))

        val rules = repo.getRules()
        assertEquals(listOf("r1", "r2"), rules.map { it.id })
        assertEquals(HellAb.IMMER, rules.first { it.id == "r1" }.windows.single().nachlaufBisHell)
        assertTrue("encodeDefaults schreibt das Feld", s.data.first()[rulesKey]!!.contains("\"nachlaufBisHell\":\"IMMER\""))
    }

    @Test
    fun `gesetzte Wahl ueberlebt Speichern und Lesen`() = runTest {
        val s = store("[]")
        val repo = DimRuleRepository(s)
        val fenster = DimWindow(endAnchor = DimAnchor.ALARM_SONST_CLOCK, weckzeitNachlaufMinuten = 45, nachlaufBisHell = HellAb.SONNENAUFGANG)
        repo.upsert(DimRule(id = "r", name = "N", shiftPattern = DimRule.SHIFT_UNIVERSAL, windows = listOf(fenster)))

        assertEquals(HellAb.SONNENAUFGANG, repo.getRules().single().windows.single().nachlaufBisHell)
    }

    @Test
    fun `unbekannter HellAb-Wert - Anzeige faellt auf IMMER zurueck, Schreibpfad bricht ab statt festzuschreiben`() = runTest {
        val neu = altJson.replace("\"weckzeitNachlaufMinuten\":60,", "\"weckzeitNachlaufMinuten\":60,\"nachlaufBisHell\":\"MONDAUFGANG\",")
        val s = store(neu)
        val repo = DimRuleRepository(s)

        assertEquals(HellAb.IMMER, repo.rules.first().single().windows.single().nachlaufBisHell)

        repo.upsert(DimRule(id = "r2", name = "Zweite", shiftPattern = DimRule.SHIFT_FREE))
        assertEquals("Rohdaten bleiben unveraendert", neu, s.data.first()[rulesKey])
    }
}
