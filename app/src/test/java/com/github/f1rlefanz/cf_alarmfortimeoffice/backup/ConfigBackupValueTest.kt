package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests der typerhaltenden Umwandlung zwischen DataStore und Dateiformat.
 *
 * WARUM DER TYP MITMUSS: DataStore-Preferences sind typisiert. Ein `Int`-Schluessel, der als String
 * zurueckgeschrieben wird, wirft beim naechsten LESEN eine ClassCastException - und zwar in genau
 * den Einstellungen, die den Wecker steuern. Ein falsch typisierter Wert ist deshalb schlimmer als
 * ein fehlender.
 */
class ConfigBackupValueTest {

    @Test
    fun `alle in dieser App vorkommenden Typen ueberleben den Rundtrip`() {
        val prefs = mutablePreferencesOf()

        val cases = listOf<Pair<String, Any>>(
            "dnd_policy_block_calls" to true,
            "snooze_minutes" to 9,
            "dim_override_window_end" to 1786027254909L,
            "dim_rules" to """[{"id":"r1"}]""",
            "dnd_oncall_shifts" to setOf("AD1", "AD2")
        )

        cases.forEach { (name, original) ->
            val stored = ConfigBackupUseCase.toStoredValue(original)
            assertTrue("$name konnte nicht umgewandelt werden", stored != null)
            assertTrue("$name konnte nicht zurueckgeschrieben werden",
                ConfigBackupUseCase.applyValue(prefs, name, stored!!))
        }

        assertEquals(true, prefs[booleanPreferencesKey("dnd_policy_block_calls")])
        assertEquals(9, prefs[intPreferencesKey("snooze_minutes")])
        assertEquals("""[{"id":"r1"}]""", prefs[stringPreferencesKey("dim_rules")])
        assertEquals(setOf("AD1", "AD2"), prefs[stringSetPreferencesKey("dnd_oncall_shifts")])
    }

    /**
     * Zwei Exporte desselben Zustands muessen dieselbe Datei ergeben, sonst ist ein Diff zwischen
     * zwei Sicherungen nicht aussagekraeftig. Bei Mengen ist die Reihenfolge nicht garantiert -
     * deshalb wird sortiert.
     */
    @Test
    fun `Mengen werden stabil sortiert abgelegt`() {
        val a = ConfigBackupUseCase.toStoredValue(setOf("ZD", "AD1", "N"))
        val b = ConfigBackupUseCase.toStoredValue(setOf("N", "ZD", "AD1"))

        assertEquals(listOf("AD1", "N", "ZD"), a?.setValue)
        assertEquals(a, b)
    }

    /**
     * Ein unbekannter oder kaputter Wert darf NICHTS schreiben. Lieber fehlt eine Einstellung, als
     * dass ein falscher Typ im Store landet und den naechsten Lesezugriff sprengt.
     */
    @Test
    fun `kaputte Werte schreiben nichts`() {
        val prefs = mutablePreferencesOf()

        assertFalse(ConfigBackupUseCase.applyValue(prefs, "snooze_minutes", StoredValue("int", "keine Zahl")))
        assertFalse(ConfigBackupUseCase.applyValue(prefs, "x", StoredValue("boolean", "vielleicht")))
        assertFalse(ConfigBackupUseCase.applyValue(prefs, "x", StoredValue("int", null)))
        assertFalse(ConfigBackupUseCase.applyValue(prefs, "x", StoredValue("stringSet", value = "a,b")))
        assertFalse(ConfigBackupUseCase.applyValue(prefs, "x", StoredValue("unbekannt", "1")))

        assertNull(prefs[intPreferencesKey("snooze_minutes")])
        assertTrue("Nichts darf geschrieben worden sein", prefs.asMap().isEmpty())
    }

    @Test
    fun `nicht abbildbare Typen liefern null statt zu raten`() {
        assertNull(ConfigBackupUseCase.toStoredValue(null))
        assertNull(ConfigBackupUseCase.toStoredValue(listOf(1, 2, 3)))
    }

    /**
     * "true"/"false" streng, nicht tolerant: `toBooleanStrictOrNull` statt `toBoolean`. Letzteres
     * macht aus JEDEM Wert ein `false` - eine kaputte Datei haette damit stillschweigend
     * "Nicht stoeren blockiert Anrufe = false" gesetzt, statt den Wert zu ueberspringen.
     */
    @Test
    fun `Boolean wird streng gelesen`() {
        val prefs = mutablePreferencesOf()
        assertTrue(ConfigBackupUseCase.applyValue(prefs, "a", StoredValue("boolean", "true")))
        assertTrue(ConfigBackupUseCase.applyValue(prefs, "b", StoredValue("boolean", "false")))
        assertFalse(ConfigBackupUseCase.applyValue(prefs, "c", StoredValue("boolean", "TRUE")))
        assertFalse(ConfigBackupUseCase.applyValue(prefs, "d", StoredValue("boolean", "1")))
        assertEquals(true, prefs[booleanPreferencesKey("a")])
        assertEquals(false, prefs[booleanPreferencesKey("b")])
        assertNull(prefs[booleanPreferencesKey("c")])
    }
    /**
     * BESCHAEDIGTE REGELWERKE werden beim Import abgelehnt, statt als "keine Regeln" durchzugehen.
     *
     * Beide Leser fangen einen unlesbaren Wert bereits ab (`DimRuleRepository` per `runCatching`,
     * `HueConfigRepository` per `try/catch`) - und genau dieser Rueckfall auf eine leere Liste ist
     * das Problem: der Import haette Erfolg gemeldet, und der Nutzer sieht danach eine leere
     * Regelliste, ohne zu wissen warum. Der Import ist der letzte Ort, an dem das noch SAGBAR ist.
     */
    @Test
    fun `unlesbares Regelwerk wird beim Import abgelehnt`() {
        assertNotNull(ConfigBackupUseCase.structuralRejection("dim_rules", "{kein json"))
        assertNotNull(ConfigBackupUseCase.structuralRejection("hue_schedule_rules", "[[[" ))
        // Ein Objekt statt einer Liste ist strukturell falsch, auch wenn es gueltiges JSON ist.
        assertNotNull(ConfigBackupUseCase.structuralRejection("dim_rules", """{"id":"x"}"""))
    }

    /** Leer heisst "keine Regeln" - ein zulaessiger Zustand, kein Defekt. */
    @Test
    fun `leeres Regelwerk gilt nicht als beschaedigt`() {
        assertNull(ConfigBackupUseCase.structuralRejection("dim_rules", ""))
        assertNull(ConfigBackupUseCase.structuralRejection("dim_rules", "   "))
        assertNull(ConfigBackupUseCase.structuralRejection("hue_schedule_rules", "[]"))
    }

    /** Alle anderen Schluessel werden strukturell nicht beurteilt - kein blinder Zweitfilter. */
    @Test
    fun `andere Schluessel werden strukturell nicht beurteilt`() {
        assertNull(ConfigBackupUseCase.structuralRejection("dim_strength", "kein json"))
        assertNull(ConfigBackupUseCase.structuralRejection("irgendwas", null))
    }

}
