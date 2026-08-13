package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Tests fuer [ShiftCodeSuggester].
 *
 * Die Testdaten sind bewusst die ECHTEN Titel aus dem Dienstplan-Kalender des Entwicklers, wie sie
 * am 10.08.2026 im Log standen: "F", "IMCF", "AD1", "FBE", "+". Genau daran ist sichtbar geworden,
 * dass geratene Vorgaben nicht skalieren - der Eintrag "F" wurde von einer "generischer" gemachten
 * Standardkonfiguration nicht mehr getroffen, und fuer eine echte Fruehschicht gab es keinen Wecker.
 */
class ShiftCodeSuggesterTest {

    private fun event(title: String, day: Int) = CalendarEvent(
        id = "e$day-$title",
        title = title,
        startTime = LocalDateTime.of(2026, 8, day, 6, 0),
        endTime = LocalDateTime.of(2026, 8, day, 14, 0),
        calendarId = "cal1"
    )

    private fun definition(name: String, keywords: List<String>, enabled: Boolean = true) =
        ShiftDefinition(
            id = name.lowercase(),
            name = name,
            keywords = keywords,
            alarmTime = LocalTime.of(5, 30),
            isEnabled = enabled
        )

    private val leereKonfiguration = ShiftConfig(autoAlarmEnabled = true, definitions = emptyList())

    @Test
    fun `haeufigstes Kuerzel steht vorn, mit Anzahl und erstem Datum`() {
        val events = listOf(
            event("F", 10), event("F", 11), event("F", 12),
            event("AD1", 13),
            event("FBE", 14), event("FBE", 15)
        )

        val result = ShiftCodeSuggester.suggest(events, leereKonfiguration)

        assertEquals(listOf("F", "FBE", "AD1"), result.suggestions.map { it.code })
        assertEquals(3, result.suggestions.first().occurrences)
        assertEquals(LocalDate.of(2026, 8, 10), result.suggestions.first().firstDate)
        assertEquals(0, result.droppedCount)
    }

    /**
     * Der Kern des Ganzen: Titel, die schon von einem Muster getroffen werden, sind KEIN Problem,
     * das der Nutzer loesen muesste - sie duerfen die Liste nicht zumuellen.
     */
    @Test
    fun `bereits getroffene Titel werden nicht vorgeschlagen`() {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("F", "IMCF")))
        )
        val events = listOf(event("F", 10), event("IMCF", 11), event("AD1", 12))

        val result = ShiftCodeSuggester.suggest(events, config)

        assertEquals(
            "Nur das noch unbekannte Kuerzel darf uebrig bleiben",
            listOf("AD1"),
            result.suggestions.map { it.code }
        )
    }

    /**
     * Eine DEAKTIVIERTE Definition erkennt nichts (`performRecognition` filtert sie heraus). Ihre
     * Muster duerfen deshalb auch keinen Kandidaten unterdruecken - sonst verschweigt die App ein
     * Kuerzel, fuer das gerade KEIN Wecker gestellt wird.
     */
    @Test
    fun `Muster einer deaktivierten Definition unterdruecken keinen Vorschlag`() {
        val config = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(definition("Fruehschicht", listOf("F"), enabled = false))
        )

        val result = ShiftCodeSuggester.suggest(listOf(event("F", 10)), config)

        assertEquals(listOf("F"), result.suggestions.map { it.code })
    }

    /**
     * "+" steht real im Dienstplan. Als Erkennungsmuster koennte es NIE treffen, weil
     * `matchesKeywords` mit Wortgrenzen ueber Buchstaben/Ziffern arbeitet. Es vorzuschlagen waere
     * ein Versprechen, das die Erkennung nicht halten kann.
     */
    @Test
    fun `Titel ohne Buchstaben oder Ziffern werden nicht vorgeschlagen`() {
        val result = ShiftCodeSuggester.suggest(
            listOf(event("+", 10), event("+", 11), event("---", 12), event("F", 13)),
            leereKonfiguration
        )

        assertEquals(listOf("F"), result.suggestions.map { it.code })
    }

    @Test
    fun `Freitext-Termine sind keine Kuerzel`() {
        val langerTitel = "Zahnarzttermin bei Dr. Mueller in der Innenstadt"
        assertTrue(langerTitel.length > ShiftCodeSuggester.MAX_CODE_LENGTH)

        val result = ShiftCodeSuggester.suggest(
            listOf(event(langerTitel, 10), event("ND", 11)),
            leereKonfiguration
        )

        assertEquals(listOf("ND"), result.suggestions.map { it.code })
    }

    @Test
    fun `gleiche Haeufigkeit wird stabil alphabetisch sortiert`() {
        val result = ShiftCodeSuggester.suggest(
            listOf(event("ND", 10), event("AD1", 11), event("ZD", 12)),
            leereKonfiguration
        )

        assertEquals(listOf("AD1", "ND", "ZD"), result.suggestions.map { it.code })
    }

    /**
     * Deckelung darf nicht wie Vollstaendigkeit aussehen - die Anzahl der weggelassenen Kandidaten
     * MUSS mitkommen, damit die UI sie nennen kann.
     */
    @Test
    fun `Deckelung meldet die Anzahl der weggelassenen Kandidaten`() {
        val events = (1..10).map { event("CODE$it", it) }

        val result = ShiftCodeSuggester.suggest(events, leereKonfiguration, maxSuggestions = 3)

        assertEquals(3, result.suggestions.size)
        assertEquals(7, result.droppedCount)
    }

    @Test
    fun `Leerzeichen am Rand werden getrimmt und zusammengefasst`() {
        val result = ShiftCodeSuggester.suggest(
            listOf(event("  ND  ", 10), event("ND", 11)),
            leereKonfiguration
        )

        assertEquals(listOf("ND"), result.suggestions.map { it.code })
        assertEquals("Beide Schreibweisen sind derselbe Code", 2, result.suggestions.first().occurrences)
    }

    @Test
    fun `keine Termine ergibt keine Vorschlaege`() {
        val result = ShiftCodeSuggester.suggest(emptyList(), leereKonfiguration)
        assertTrue(result.isEmpty)
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun `normalizeCode weist leere und reine Symboltitel ab`() {
        assertNull(ShiftCodeSuggester.normalizeCode(""))
        assertNull(ShiftCodeSuggester.normalizeCode("   "))
        assertNull(ShiftCodeSuggester.normalizeCode("+"))
        assertEquals("F", ShiftCodeSuggester.normalizeCode(" F "))
        assertEquals("S2", ShiftCodeSuggester.normalizeCode("S2"))
        // Umlaute muessen durchkommen - matchesKeywords kann sie seit dem Unicode-Fix treffen.
        assertEquals("Übergabe", ShiftCodeSuggester.normalizeCode("Übergabe"))
    }

    /**
     * Gegenprobe zur Standardkonfiguration: mit den echten Titeln des Dienstplans bleibt nur uebrig,
     * was wirklich unbekannt ist. "AD1" (Rufbereitschaft) ist bewusst nicht in den Vorgaben - genau
     * dieses Kuerzel soll der Nutzer hier angeboten bekommen.
     */
    @Test
    fun `mit der Standardkonfiguration bleibt genau das Unbekannte uebrig`() {
        val echteTitel = listOf(
            event("F", 10), event("IMCF", 11), event("AD1", 12), event("FBE", 13), event("+", 14)
        )

        val result = ShiftCodeSuggester.suggest(echteTitel, ShiftConfig.getDefaultConfig())

        assertEquals(listOf("AD1", "FBE"), result.suggestions.map { it.code })
    }
}
