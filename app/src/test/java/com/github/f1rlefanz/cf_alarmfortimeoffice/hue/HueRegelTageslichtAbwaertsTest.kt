package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import androidx.compose.runtime.saveable.SaverScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueRuleFormStateSaver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bestand vor 1.52 kennt [HueScheduleRule.hellAb] nicht (#140). Er muss sich ueber das echte
 * [HueConfigRepository] als [HellAb.IMMER] lesen - eine bestehende Regel laeuft nach dem Update
 * genau wie vorher.
 */
class HueRegelTageslichtAbwaertsTest {

    private val rulesKey = stringPreferencesKey("hue_schedule_rules")

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

    /** So schreibt 1.51.0 eine Sonnenaufgangs-Regel (encodeDefaults, ohne hellAb). */
    private val altJson = """
        [{"id":"rule_1","name":"Frühdienst hell","shiftPattern":"Frühdienst","enabled":true,
          "timeRanges":[{"actions":[{"targetType":"GROUP","targetId":"1","targetName":"Schlafzimmer",
            "actionType":"TURN_ON","on":true,"brightness":200,"hue":null,"saturation":null,
            "colorTemperature":null,"color":null,"transitionTime":10,"duration":30,"isGroup":true,
            "sceneId":null,"sceneName":null}]}],
          "priority":0,
          "sunrise":{"enabled":true,"durationMinutes":15,"startKelvin":2000,"endKelvin":4000,
                     "endBrightness":254,"startBeforeAlarm":true}}]
    """.trimIndent()

    @Test
    fun `alte Regel liest sich als IMMER`() = runTest {
        val rules = HueConfigRepository(store(altJson)).getScheduleRules().getOrThrow()
        assertEquals(1, rules.size)
        assertEquals(HellAb.IMMER, rules.single().hellAb)
        assertEquals(true, rules.single().sunrise?.enabled)
    }

    @Test
    fun `gesetzte Wahl ueberlebt Speichern und Lesen, alte Regel bekommt den Default mitgeschrieben`() = runTest {
        val s = store(altJson)
        val repo = HueConfigRepository(s)
        val neu = HueScheduleRule(
            id = "rule_2",
            name = "Nur dunkel",
            shiftPattern = "Frühdienst",
            timeRanges = listOf(HueTimeRange(emptyList())),
            hellAb = HellAb.DAEMMERUNG
        )
        repo.saveScheduleRule(neu).getOrThrow()

        val rules = repo.getScheduleRules().getOrThrow().associateBy { it.id }
        assertEquals(HellAb.IMMER, rules.getValue("rule_1").hellAb)
        assertEquals(HellAb.DAEMMERUNG, rules.getValue("rule_2").hellAb)
        assertTrue(s.data.first()[rulesKey]!!.contains("\"hellAb\":\"IMMER\""))
    }

    /**
     * Grundlinie fuer den Rotations-Saver: ein Formularzustand aus 1.51.0 (ohne jedes
     * Tageslicht-Feld) muss sich weiter wiederherstellen lassen. Das Feld selbst und seine
     * Rundreise gehoeren dem Hue-Editor-Paket (P5, `HueRuleFormStateTest`).
     */
    @Test
    fun `Saver stellt einen alten Formularzustand ohne Tageslicht-Feld wieder her`() {
        val alt = """{"name":"Alt","shiftPattern":"Frühdienst","enabled":true,"modus":"MANUELL"}"""
        val zurueck = HueRuleFormStateSaver.restore(alt)
        assertNotNull(zurueck)
        assertEquals("Alt", zurueck!!.name)

        val scope = SaverScope { true }
        val roh = with(HueRuleFormStateSaver) { scope.save(zurueck) }
        assertEquals(zurueck, HueRuleFormStateSaver.restore(roh!!))
    }
}
