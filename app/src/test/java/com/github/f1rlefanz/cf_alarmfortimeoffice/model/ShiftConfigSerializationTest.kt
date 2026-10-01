package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Echte Unit-Tests für die kotlinx.serialization-Persistenz von [ShiftConfig] /
 * [ShiftDefinition] inkl. [LocalTimeSerializer].
 *
 * Verwendet exakt die gleiche `Json { ignoreUnknownKeys = true; encodeDefaults = true }`
 * Konfiguration wie die Produktion (`ShiftConfigRepository.json`), damit das Verhalten
 * 1:1 dem echten Persistenz-Pfad entspricht.
 */
class ShiftConfigSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---- LocalTimeSerializer: Format "HH:mm" ----

    @Test
    fun `LocalTimeSerializer serialisiert als HH-mm String mit fuehrenden Nullen`() {
        val encoded = json.encodeToString(LocalTimeSerializer, LocalTime.of(5, 5))
        assertEquals("\"05:05\"", encoded)
    }

    @Test
    fun `LocalTimeSerializer serialisiert Mitternacht als 00-00`() {
        val encoded = json.encodeToString(LocalTimeSerializer, LocalTime.of(0, 0))
        assertEquals("\"00:00\"", encoded)
    }

    @Test
    fun `LocalTimeSerializer deserialisiert HH-mm String zurueck zu LocalTime`() {
        val decoded = json.decodeFromString(LocalTimeSerializer, "\"14:45\"")
        assertEquals(LocalTime.of(14, 45), decoded)
    }

    @Test
    fun `LocalTimeSerializer Rundtrip erhaelt den Wert (Sekunden werden nicht gespeichert)`() {
        val original = LocalTime.of(23, 59)
        val encoded = json.encodeToString(LocalTimeSerializer, original)
        val decoded = json.decodeFromString(LocalTimeSerializer, encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `LocalTimeSerializer wirft DateTimeParseException bei ungueltigem Format`() {
        // LocalTimeSerializer.deserialize() ruft LocalTime.parse() OHNE eigenes try-catch auf.
        // kotlinx.serialization wrapt das NICHT in SerializationException, die rohe
        // java.time.format.DateTimeParseException propagiert unveraendert nach oben.
        assertThrows(DateTimeParseException::class.java) {
            json.decodeFromString(LocalTimeSerializer, "\"not-a-time\"")
        }
    }

    // ---- ShiftDefinition Rundtrip ----

    @Test
    fun `ShiftDefinition Rundtrip erhaelt alle Felder`() {
        val original = ShiftDefinition(
            id = "early_shift",
            name = "Frühschicht",
            keywords = listOf("F", "IMCF"),
            alarmTime = LocalTime.of(5, 30),
            isEnabled = true
        )

        val encoded = json.encodeToString(ShiftDefinition.serializer(), original)
        val decoded = json.decodeFromString(ShiftDefinition.serializer(), encoded)

        assertEquals(original, decoded)
        // alarmTime wird intern als "HH:mm"-String kodiert
        assertTrue("JSON muss die Uhrzeit als String enthalten", encoded.contains("\"05:30\""))
    }

    @Test
    fun `ShiftDefinition Rundtrip mit isEnabled=false und leeren Keywords`() {
        val original = ShiftDefinition(
            id = "disabled_shift",
            name = "Deaktiviert",
            keywords = emptyList(),
            alarmTime = LocalTime.of(12, 0),
            isEnabled = false
        )

        val encoded = json.encodeToString(ShiftDefinition.serializer(), original)
        val decoded = json.decodeFromString(ShiftDefinition.serializer(), encoded)

        assertEquals(original, decoded)
        assertFalse(decoded.isEnabled)
        assertTrue(decoded.keywords.isEmpty())
    }

    // ---- ShiftConfig Rundtrip (das, was tatsaechlich in DataStore persistiert wird) ----

    @Test
    fun `ShiftConfig Rundtrip erhaelt autoAlarmEnabled und alle Definitionen`() {
        val original = ShiftConfig.getDefaultConfig()

        val encoded = json.encodeToString(ShiftConfig.serializer(), original)
        val decoded = json.decodeFromString(ShiftConfig.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(4, decoded.definitions.size)
        assertTrue(decoded.autoAlarmEnabled)
    }

    @Test
    fun `ShiftConfig Rundtrip mit leerer Definitionsliste`() {
        val original = ShiftConfig(autoAlarmEnabled = false, definitions = emptyList())

        val encoded = json.encodeToString(ShiftConfig.serializer(), original)
        val decoded = json.decodeFromString(ShiftConfig.serializer(), encoded)

        assertEquals(original, decoded)
        assertFalse(decoded.autoAlarmEnabled)
        assertTrue(decoded.definitions.isEmpty())
    }

    @Test
    fun `ShiftConfig ignoriert unbekannte JSON-Felder (Vorwaerts-Kompatibilitaet)`() {
        // Simuliert das Laden einer älteren/neueren Konfiguration mit einem Feld,
        // das im aktuellen Modell nicht mehr existiert (z.B. ehemaliges "daysAhead").
        val jsonWithExtraField = """
            {
                "autoAlarmEnabled": true,
                "definitions": [],
                "daysAhead": 14
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ShiftConfig.serializer(), jsonWithExtraField)

        assertTrue(decoded.autoAlarmEnabled)
        assertTrue(decoded.definitions.isEmpty())
    }

    @Test
    fun `getDefaultConfig liefert die erwarteten vier Schicht-Definitionen mit korrekten Weckzeiten`() {
        val config = ShiftConfig.getDefaultConfig()

        assertEquals(4, config.definitions.size)

        val early = config.definitions.first { it.id == "early_shift" }
        assertEquals(LocalTime.of(5, 30), early.alarmTime)
        assertEquals(listOf("F", "Frühdienst"), early.keywords)

        val late = config.definitions.first { it.id == "late_shift" }
        assertEquals(LocalTime.of(12, 30), late.alarmTime)

        val night = config.definitions.first { it.id == "night_shift" }
        assertEquals(LocalTime.of(20, 0), night.alarmTime)

        // "S2" war der Schichttyp EINER Station und ist seit #128 nicht mehr in den Vorgaben.
        assertTrue(config.definitions.none { it.id == "s2_shift" })

        val intermediate = config.definitions.first { it.id == "intermediate_shift" }
        assertEquals(LocalTime.of(7, 0), intermediate.alarmTime)

        assertTrue("Alle Standard-Definitionen sollten aktiviert sein", config.definitions.all { it.isEnabled })
    }

    /**
     * Einbuchstabige Standard-Muster ("F"/"S"/"N") sind ERLAUBT - aber sie duerfen ausschliesslich
     * ueber die Wortgrenzen-Erkennung greifen, niemals ueber die unscharfe Teiltreffer-Stufe.
     *
     * Die Unterscheidung ist der ganze Punkt, und sie wurde einmal verwechselt:
     *  - `matchesKeywords()` (Kalendertitel -> Definition) arbeitet mit Wortgrenzen. Dort ist "F"
     *    praezise: es trifft ein alleinstehendes F, nicht "Fruehschicht", nicht "Fortbildung".
     *    Genau diese kurzen Codes stehen real im Dienstplan - ohne sie bleibt eine echte Schicht
     *    unerkannt und der Wecker aus (am Geraet nachgewiesen, 10.08.2026).
     *  - `findDefinitionFor()` (Alarm -> Definition) vergleicht unscharf per `contains` OHNE
     *    Wortgrenzen. Dort waehlt ein einzelner Buchstabe die falsche Definition ("S" steckt in
     *    "Nacht**s**chicht") - deshalb filtert diese Stufe alles unter
     *    [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH] heraus.
     *
     * Dieser Test sichert die zweite Haelfte ab: kein einbuchstabiges Standard-Muster darf ueber
     * einen Teiltreffer eine Definition gewinnen.
     */
    @Test
    fun `einbuchstabige Standard-Muster gewinnen keinen unscharfen Teiltreffer`() {
        val config = ShiftConfig.getDefaultConfig()
        val einbuchstabige = config.definitions
            .flatMap { def -> def.keywords.map { def.name to it } }
            .filter { (_, keyword) -> keyword.length < ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH }

        assertTrue(
            "Erwartet werden die kurzen Dienstplan-Codes F/S/N in den Vorgaben",
            einbuchstabige.isNotEmpty()
        )

        // Jeder Schichtname, der einen dieser Buchstaben nur als Bestandteil enthaelt, muss
        // trotzdem seiner EIGENEN Definition zugeordnet werden.
        assertEquals("Nachtschicht", config.findDefinitionFor("Nachtschicht")?.name)
        assertEquals("Spätschicht", config.findDefinitionFor("Spätschicht")?.name)
        assertEquals("Zwischendienst", config.findDefinitionFor("Zwischendienst")?.name)
        assertNull(config.findDefinitionFor("S2"))

        // Und ein Name, der zu KEINER Definition gehoert, darf nicht ueber einen einzelnen
        // Buchstaben eingefangen werden.
        assertNull(
            "Ein einzelner Buchstabe darf keinen unscharfen Teiltreffer gewinnen",
            config.findDefinitionFor("Sonderdienst Nord")
        )
    }

    /**
     * Die Vorgaben tragen KEIN Kuerzel einer einzelnen Station (#128).
     *
     * Vorgeschichte: "Zwischendienst" hatte einmal genau ein Muster, "IMCZ" - auf jeder Station,
     * die nicht "IMC" codiert, war die Definition damit strukturell tot. Danach bekam jede
     * Definition zusaetzlich ein generisches Muster, die Stationskuerzel blieben aber als tote
     * Muster stehen. Seit 01.10.2026 sind sie ganz raus; eigene Kuerzel liefert der Vorschlag.
     */
    @Test
    fun `keine Standard-Definition traegt ein Stationskuerzel`() {
        val mitStationskuerzel = ShiftConfig.getDefaultConfig().definitions
            .filter { def -> def.keywords.any { it.startsWith("IMC", ignoreCase = true) } }
            .map { it.name }

        assertTrue("Stationskuerzel in den Vorgaben: $mitStationskuerzel", mitStationskuerzel.isEmpty())
        assertTrue(
            "Jede Standard-Definition braucht mindestens ein Muster",
            ShiftConfig.getDefaultConfig().definitions.all { it.keywords.isNotEmpty() }
        )
    }
}
