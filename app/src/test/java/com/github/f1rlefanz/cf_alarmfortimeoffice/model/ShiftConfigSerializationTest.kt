package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(5, decoded.definitions.size)
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
    fun `getDefaultConfig liefert die erwarteten fuenf Schicht-Definitionen mit korrekten Weckzeiten`() {
        val config = ShiftConfig.getDefaultConfig()

        assertEquals(5, config.definitions.size)

        val early = config.definitions.first { it.id == "early_shift" }
        assertEquals(LocalTime.of(5, 30), early.alarmTime)
        assertEquals(listOf("IMCF", "Frühdienst"), early.keywords)

        val late = config.definitions.first { it.id == "late_shift" }
        assertEquals(LocalTime.of(12, 30), late.alarmTime)

        val night = config.definitions.first { it.id == "night_shift" }
        assertEquals(LocalTime.of(20, 0), night.alarmTime)

        val s2 = config.definitions.first { it.id == "s2_shift" }
        assertEquals(LocalTime.of(14, 30), s2.alarmTime)

        val intermediate = config.definitions.first { it.id == "intermediate_shift" }
        assertEquals(LocalTime.of(7, 0), intermediate.alarmTime)

        assertTrue("Alle Standard-Definitionen sollten aktiviert sein", config.definitions.all { it.isEnabled })
    }

    /**
     * Haelt die bewusste Entscheidung fest, dass in der Standardkonfiguration KEINE
     * einbuchstabigen Muster mehr stehen.
     *
     * `matchesKeywords()` trifft auf Wortgrenzen und laeuft ueber ALLE ausgewaehlten Kalender.
     * Mit den frueheren Mustern "F"/"S"/"N" erzeugte ein privater Termin "Kino mit F" einen
     * echten System-Wecker um 05:30 - dieselbe Begruendung, die
     * [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH] schon fuer `findDefinitionFor()` traegt.
     */
    @Test
    fun `kein Standard-Muster ist kuerzer als MIN_FUZZY_KEYWORD_LENGTH`() {
        val zuKurz = ShiftConfig.getDefaultConfig().definitions
            .flatMap { def -> def.keywords.map { def.name to it } }
            .filter { (_, keyword) -> keyword.length < ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH }

        assertTrue(
            "Einbuchstabige Standard-Muster wecken auch bei fremden Terminen: $zuKurz",
            zuKurz.isEmpty()
        )
    }

    /**
     * Jede Standard-Definition muss mindestens EIN Muster haben, das nicht das Kuerzel einer
     * einzelnen Station ist.
     *
     * Vorher hatte "Zwischendienst" genau ein Muster: "IMCZ". Auf jeder Station, die nicht "IMC"
     * codiert, war diese Definition damit strukturell tot - der Kollege haette dafuer NIE einen
     * Wecker bekommen und es erst nach dem Verschlafen gemerkt.
     */
    @Test
    fun `jede Standard-Definition hat ein Muster ohne Stationskuerzel`() {
        val nurStationsspezifisch = ShiftConfig.getDefaultConfig().definitions
            .filter { def ->
                def.keywords.none { !it.startsWith("IMC", ignoreCase = true) }
            }
            .map { it.name }

        assertTrue(
            "Nur stationsspezifische Muster = auf jeder anderen Station tot: $nurStationsspezifisch",
            nurStationsspezifisch.isEmpty()
        )
    }
}
