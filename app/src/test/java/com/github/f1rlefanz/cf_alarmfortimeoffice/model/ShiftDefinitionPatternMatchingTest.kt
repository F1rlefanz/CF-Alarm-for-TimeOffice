package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Sichert die Erkennungs-Muster von [ShiftDefinition.matchesKeywords] gegen die drei Fallen ab,
 * die bei der Ausweitung des Testkreises auf fremde Stationen gefunden wurden:
 *
 *  1. Einbuchstabige Standard-Muster ("F"/"S"/"N") erzeugten aus privaten Terminen echte Wecker
 *     ("Kino mit F" -> Fruehschicht 05:30). Die Erkennung laeuft ueber ALLE ausgewaehlten
 *     Kalender, nicht nur ueber den Dienstplan-Feed.
 *  2. Der Definitionsname nahm am Matching gar nicht teil - ein Termin, der wortwoertlich
 *     "Zwischendienst" hiess, wurde von der Definition "Zwischendienst" nicht erkannt.
 *  3. Muster wurden beim Speichern nicht getrimmt - " IMCF" ergab den Regex `\b\Q imcf\E\b` und
 *     traf einen Titel "IMCF" nicht mehr, waehrend die UI genau das erwartete Muster zeigte.
 *
 * Die Wortgrenzen-Logik selbst ist NICHT Gegenstand dieser Tests (die ist korrekt und in
 * `ShiftDefinitionTest` festgehalten) - hier geht es um die Muster, die hineingehen.
 */
class ShiftDefinitionPatternMatchingTest {

    private fun definition(
        keywords: List<String>,
        name: String = "Testschicht"
    ) = ShiftDefinition(
        id = "test",
        name = name,
        keywords = keywords,
        alarmTime = LocalTime.of(6, 0)
    )

    // ---- 1. Standardkonfiguration weckt nicht bei fremden Terminen ----

    @Test
    fun `Standardkonfiguration matcht keinen privaten Termin mit einzelnem Buchstaben`() {
        val config = ShiftConfig.getDefaultConfig()

        listOf("Kino mit F", "Abschluss S", "Termin N", "Geburtstag M").forEach { titel ->
            val treffer = config.definitions.filter { it.matchesKeywords(titel) }
            assertTrue(
                "'$titel' darf keine Schicht erkennen, traf aber: ${treffer.map { it.name }}",
                treffer.isEmpty()
            )
        }
    }

    @Test
    fun `Standardkonfiguration erkennt die eigenen Stationskuerzel weiterhin`() {
        val config = ShiftConfig.getDefaultConfig()

        assertEquals(
            listOf("Frühschicht"),
            config.definitions.filter { it.matchesKeywords("IMCF Dienst") }.map { it.name }
        )
        assertEquals(
            listOf("Zwischendienst"),
            config.definitions.filter { it.matchesKeywords("IMCZ") }.map { it.name }
        )
    }

    @Test
    fun `Standardkonfiguration erkennt Zwischendienst auch ohne Stationskuerzel`() {
        // Der Ausgangsfall: "Zwischendienst" hatte GENAU ein stationsspezifisches Muster
        // ("IMCZ") und war auf jeder anderen Station strukturell tot.
        val config = ShiftConfig.getDefaultConfig()

        assertEquals(
            listOf("Zwischendienst"),
            config.definitions.filter { it.matchesKeywords("Zwischendienst") }.map { it.name }
        )
        assertEquals(
            listOf("Zwischendienst"),
            config.definitions.filter { it.matchesKeywords("ZD Station 3") }.map { it.name }
        )
    }

    @Test
    fun `Standardkonfiguration erkennt ausgeschriebene Schichtnamen`() {
        val config = ShiftConfig.getDefaultConfig()

        mapOf(
            "Frühschicht" to "Frühschicht",
            "Frühdienst" to "Frühschicht",
            "Spätschicht" to "Spätschicht",
            "Spätdienst" to "Spätschicht",
            "Nachtschicht" to "Nachtschicht",
            "Nachtdienst" to "Nachtschicht",
            "S2" to "S2"
        ).forEach { (titel, erwartet) ->
            assertEquals(
                "Titel '$titel' muss genau '$erwartet' erkennen",
                listOf(erwartet),
                config.definitions.filter { it.matchesKeywords(titel) }.map { it.name }
            )
        }
    }

    // ---- 2. Der Definitionsname zaehlt als Muster ----

    @Test
    fun `Schichtname zaehlt als zusaetzliches Muster`() {
        val def = definition(keywords = listOf("IMCZ"), name = "Zwischendienst")

        assertTrue(def.matchesKeywords("Zwischendienst"))
        assertTrue(def.matchesKeywords("Station 3: Zwischendienst (8h)"))
    }

    @Test
    fun `Schichtname wird vor dem Matchen getrimmt`() {
        val def = definition(keywords = listOf("IMCZ"), name = " Zwischendienst ")

        assertTrue(def.matchesKeywords("Zwischendienst"))
    }

    @Test
    fun `Schichtname unter zwei Zeichen zaehlt NICHT als Muster`() {
        // Sonst holt der Name genau die einbuchstabige Falle zurueck, gegen die
        // MIN_FUZZY_KEYWORD_LENGTH existiert.
        val def = definition(keywords = listOf("IMCF"), name = "F")

        assertFalse(def.matchesKeywords("Kino mit F"))
        assertFalse(def.matchesKeywords("F"))
    }

    @Test
    fun `Schichtname matcht ebenfalls nur auf Wortgrenzen`() {
        val def = definition(keywords = listOf("XY"), name = "S2")

        assertTrue(def.matchesKeywords("S2 Dienst"))
        // "S2" steckt in "IMCS2", aber nicht als eigenes Wort - kein Treffer.
        assertFalse(def.matchesKeywords("IMCS2"))
    }

    // ---- 3. Muster werden getrimmt, leere Muster matchen nie ----

    @Test
    fun `Muster mit fuehrendem Leerzeichen trifft trotzdem`() {
        // Das Leerzeichen ist ein Nicht-Wortzeichen: `\b` vor " imcf" verlangt ein Wortzeichen
        // DAVOR, ein Titel "IMCF" traf also nicht mehr. Repariert bereits gespeicherte Altwerte.
        val def = definition(keywords = listOf(" IMCF"))

        assertTrue(def.matchesKeywords("IMCF"))
        assertTrue(def.matchesKeywords("IMCF Fruehdienst"))
    }

    @Test
    fun `Muster mit abschliessendem Leerzeichen trifft trotzdem`() {
        val def = definition(keywords = listOf("IMCF "))

        assertTrue(def.matchesKeywords("IMCF"))
    }

    @Test
    fun `leeres Muster matcht nie`() {
        // `Regex.escape("")` ergibt `\b\Q\E\b` - das trifft JEDEN Titel mit einem Wortzeichen.
        // Ein leeres Muster wuerde also jeden Termin zu dieser Schicht machen.
        val def = definition(keywords = listOf(""), name = "X")

        assertFalse(def.matchesKeywords("Kino"))
        assertFalse(def.matchesKeywords("Irgendein Termin"))
    }

    @Test
    fun `Muster nur aus Leerzeichen matcht nie`() {
        val def = definition(keywords = listOf("   "), name = "X")

        assertFalse(def.matchesKeywords("Kino"))
        assertFalse(def.matchesKeywords("Ein   Termin"))
    }
}
