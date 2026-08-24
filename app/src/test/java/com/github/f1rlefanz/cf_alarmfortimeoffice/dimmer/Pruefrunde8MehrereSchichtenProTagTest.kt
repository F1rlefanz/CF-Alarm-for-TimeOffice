package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Prüfrunde 8: **Dimmer und DND kannten pro Kalendertag nur EINE Schicht.**
 *
 * [DimWindowResolver.buildRuleSpans] faltete die
 * Schichtliste zuvor mit einer `HashMap<LocalDate, AlarmSlot>` („first wins") auf höchstens einen
 * Eintrag je Kalendertag zusammen. Weil die Eingabe nach Weckzeit sortiert ankommt (der
 * `ShiftSpanStore` erbt die Reihenfolge aus `ShiftRecognitionEngine.performRecognition()`), gewann
 * immer die früheste Schicht — jede weitere existierte für Regelauswahl und Nacht-Ausnahme nicht.
 *
 * Praxisfall: Frühdienst plus anschließende Rufbereitschaft am selben Tag. Die für die
 * Rufbereitschaft angelegte Dimm-Regel wurde nie gefragt — die Oberfläche zeigte sie als aktiv,
 * gewirkt hat sie nicht. Über
 * DND-Modus „folgt dem Dimmer" schaltete zusätzlich „Nicht stören" in genau der Nacht ein, in der
 * Erreichbarkeit der Zweck des Dienstes ist.
 *
 * Deterministisch gegen UTC, wie [DimWindowResolverTest].
 */
class Pruefrunde8MehrereSchichtenProTagTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ep(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** Regelauswahl exakt wie `DimRuleUseCase.findRuleForShift` (erster Treffer, sonst UNIVERSAL). */
    private fun forShift(rules: List<DimRule>): (String) -> DimRule? = { name ->
        val en = rules.filter { it.enabled }
        en.firstOrNull { it.shiftPattern.equals(name, ignoreCase = true) }
            ?: en.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }

    /** Regelauswahl exakt wie `DimRuleUseCase.findRuleForFreeDay`. */
    private fun forFree(rules: List<DimRule>): () -> DimRule? = {
        val en = rules.filter { it.enabled }
        en.firstOrNull { it.shiftPattern == DimRule.SHIFT_FREE }
            ?: en.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }

    private fun nachtfenster(von: Int, bis: Int) = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = von * 60,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = bis * 60
    )

    // --- buildRuleSpans ---

    @Test
    fun `Regel der ZWEITEN Schicht des Tages wird gefunden und an IHRER Weckzeit verankert`() {
        // Nur die Rufbereitschaft hat eine Regel, kein UNIVERSAL. Vor dem Fix gewann der frühere
        // Frühdienst-Slot, `ruleForShift("Fruehdienst")` lieferte null - es entstand KEIN Fenster.
        val ruf = DimRule(
            id = "ruf", name = "Rufbereitschaft", shiftPattern = "Rufbereitschaft", enabled = true,
            windows = listOf(
                DimWindow(
                    startAnchor = DimAnchor.ALARM, startOffsetMinutes = -120,
                    endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
                )
            ),
            strength = 70, warmth = 30
        )
        val rules = listOf(ruf)
        val today = LocalDate.of(2026, 1, 13)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", ep(2026, 1, 13, 14, 0)),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", ep(2026, 1, 13, 22, 0))
        )

        val spans = DimWindowResolver.buildRuleSpans(
            alarms = alarms, horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        assertEquals(1, spans.size)
        // Verankert an der Rufbereitschaft (16:00), nicht am Frühdienst - die Anker einer Regel
        // meinen die Schicht, zu der die Regel gehört.
        assertEquals(ep(2026, 1, 13, 14, 0), spans[0].range.first)
        assertEquals(ep(2026, 1, 13, 16, 0), spans[0].range.last)
        assertEquals(70, spans[0].strength)
    }

    @Test
    fun `Unterdrueckung durch die zweite Schicht schlaegt die UNIVERSAL-Nacht`() {
        // "immer 22-7 dimmen, außer wenn ich erreichbar sein muss": UNIVERSAL trägt jede Nacht,
        // die leere Fensterliste der Rufbereitschaft nimmt diesen Tag heraus. Vor dem Fix gewann
        // der Frühdienst und damit UNIVERSAL - es wurde ausgerechnet in der Bereitschaftsnacht
        // gedimmt (und in DND-Modus 1 zusätzlich "Nicht stören" gesetzt).
        val universal = DimRule(
            id = "u", name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
            windows = listOf(nachtfenster(22, 7))
        )
        val ruf = DimRule(
            id = "r", name = "Rufbereitschaft frei", shiftPattern = "Rufbereitschaft",
            enabled = true, windows = emptyList()
        )
        val rules = listOf(universal, ruf)
        val today = LocalDate.of(2026, 1, 13)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0)
        )

        val spans = DimWindowResolver.buildRuleSpans(
            alarms = alarms, horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        // Die Nacht des 13.01. bleibt hell.
        assertTrue(spans.none { ep(2026, 1, 13, 23, 30) in it.range })
    }

    @Test
    fun `Zwei verschiedene spezifische Regeln an einem Tag - die frueheste Schicht entscheidet`() {
        // ALTE BEGRÜNDUNG dieses Tests (widerlegt, absichtlich stehengelassen):
        //   "Widerspruch: beide Fensterlisten zu vereinigen wäre additiv und bräche die Zusicherung
        //    'pro Kalendertag GENAU eine Regel'; eine davon still zu wählen ist genau der behobene
        //    Fehler. Also die harmlose Richtung - hell."
        //   Der Test verlangte deshalb, dass an so einem Tag GAR NICHT gedimmt wird.
        // WIDERLEGT (adversariale Review über Prüfrunde 8): "hell" ist hier nicht die harmlose
        // Richtung, sondern eine stille Abschaltung. Der Abbruch nahm dem Tag JEDE Dimm-Quelle -
        // auch den Nacht-Standard, weil `DimScheduleUseCase` jeden regelbelegten Tag von diesem
        // ausschließt -, während die Regelliste beide Regeln unverändert als aktiv anzeigte:
        // "angezeigt, wirkt nicht", genau die Fehlerklasse, gegen die Prüfrunde 8 gebaut wurde.
        // Richtig bleibt nur die Ablehnung der VEREINIGUNG (additiv, dimmt mehr als jede Regel für
        // sich). Der Konflikt wird deshalb entschieden statt vermieden: es gilt die Regel der
        // Schicht, die als erste weckt (datengetrieben, deterministisch vorsortiert, erklärbar) -
        // und der Fall geht als WARN ins Log.
        val frueh = DimRule(
            id = "f", name = "Frühdienst", shiftPattern = "Fruehdienst", enabled = true,
            windows = listOf(nachtfenster(22, 7)), strength = 65
        )
        val ruf = DimRule(
            id = "r", name = "Rufbereitschaft", shiftPattern = "Rufbereitschaft", enabled = true,
            windows = listOf(nachtfenster(20, 23)), strength = 80
        )
        val rules = listOf(frueh, ruf)
        val today = LocalDate.of(2026, 1, 13)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0)
        )

        val spans = DimWindowResolver.buildRuleSpans(
            alarms = alarms, horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        // Die Regel der frühesten Schicht (Frühdienst, 22-7) wirkt - der Tag steht nicht ohne
        // Dimmen da.
        val nacht = spans.filter { ep(2026, 1, 13, 23, 30) in it.range }
        assertEquals(1, nacht.size)
        assertEquals(65, nacht[0].strength)
        assertEquals(ep(2026, 1, 13, 22, 0), nacht[0].range.first)
        assertEquals(ep(2026, 1, 14, 7, 0), nacht[0].range.last)
        // Und NICHT additiv: das 20-23-Fenster der unterlegenen Regel entsteht nicht.
        assertTrue(spans.none { it.strength == 80 })
        assertTrue(spans.none { ep(2026, 1, 13, 21, 0) in it.range })
    }

    @Test
    fun `Unterdrueckung schlaegt auch die Regel der fruehesten Schicht`() {
        // Die Auswahl "früheste Schicht gewinnt" darf die Nachtdienst-Ausnahme nicht aushebeln:
        // eine leere Fensterliste ist eine ausdrückliche Nutzerentscheidung ("in dieser Nacht nicht
        // dimmen") und wird VOR der Konfliktauflösung geprüft. Ohne diese Reihenfolge gewänne hier
        // der Frühdienst und dimmte die Bereitschaftsnacht.
        val frueh = DimRule(
            id = "f", name = "Frühdienst", shiftPattern = "Fruehdienst", enabled = true,
            windows = listOf(nachtfenster(22, 7)), strength = 65
        )
        val ruf = DimRule(
            id = "r", name = "Rufbereitschaft frei", shiftPattern = "Rufbereitschaft",
            enabled = true, windows = emptyList()
        )
        val rules = listOf(frueh, ruf)
        val today = LocalDate.of(2026, 1, 13)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0)
        )

        val spans = DimWindowResolver.buildRuleSpans(
            alarms = alarms, horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        assertTrue(spans.none { ep(2026, 1, 13, 23, 30) in it.range })
    }

    @Test
    fun `Der Konflikt wird reihenfolge-unabhaengig gleich entschieden`() {
        // Die Fenster-Identität (range.last + strength) muss über aufeinanderfolgende Ticks stabil
        // bleiben - ein laufendes Fenster darf nicht auf einen anderen Anker kippen, nur weil die
        // Spannen in anderer Reihenfolge geliefert werden.
        val frueh = DimRule(
            id = "f", name = "Frühdienst", shiftPattern = "Fruehdienst", enabled = true,
            windows = listOf(nachtfenster(22, 7)), strength = 65
        )
        val ruf = DimRule(
            id = "r", name = "Rufbereitschaft", shiftPattern = "Rufbereitschaft", enabled = true,
            windows = listOf(nachtfenster(20, 23)), strength = 80
        )
        val rules = listOf(frueh, ruf)
        val today = LocalDate.of(2026, 1, 13)
        val a = DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0)
        val b = DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0)

        val vorwaerts = DimWindowResolver.buildRuleSpans(
            alarms = listOf(a, b), horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )
        val rueckwaerts = DimWindowResolver.buildRuleSpans(
            alarms = listOf(b, a), horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        assertEquals(vorwaerts, rueckwaerts)
        assertEquals(1, vorwaerts.size)
        assertEquals(65, vorwaerts[0].strength)
    }

    @Test
    fun `Die Eingabereihenfolge der Spannen aendert das Ergebnis nicht mehr`() {
        // Die Reihenfolge kam bisher aus der Sortierung nach Weckzeit und entschied still darüber,
        // welche Schicht überlebt. Sie darf keine Rolle mehr spielen: der Resolver sortiert selbst.
        val universal = DimRule(
            id = "u", name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
            windows = listOf(nachtfenster(22, 7))
        )
        val ruf = DimRule(
            id = "r", name = "Rufbereitschaft", shiftPattern = "Rufbereitschaft", enabled = true,
            windows = listOf(nachtfenster(20, 23)), strength = 80
        )
        val rules = listOf(universal, ruf)
        val today = LocalDate.of(2026, 1, 13)
        val frueh = DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0)
        val bereit = DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0)

        val vorwaerts = DimWindowResolver.buildRuleSpans(
            alarms = listOf(frueh, bereit), horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )
        val rueckwaerts = DimWindowResolver.buildRuleSpans(
            alarms = listOf(bereit, frueh), horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        assertEquals(vorwaerts, rueckwaerts)
        // Und zwar auf der spezifischen Regel: sie überschreibt UNIVERSAL komplett.
        assertTrue(vorwaerts.any { it.strength == 80 && ep(2026, 1, 13, 21, 0) in it.range })
    }

    @Test
    fun `Ein Tag mit nur EINER Schicht verhaelt sich unveraendert`() {
        // Regressionsschutz: der Fix darf den Normalfall nicht anfassen.
        val universal = DimRule(
            id = "u", name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
            windows = listOf(nachtfenster(22, 7))
        )
        val rules = listOf(universal)
        val today = LocalDate.of(2026, 1, 13)
        val alarms = listOf(DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0))

        val spans = DimWindowResolver.buildRuleSpans(
            alarms = alarms, horizonDays = 1, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        assertTrue(spans.any { ep(2026, 1, 13, 23, 30) in it.range })
    }

    // --- Die Nacht als gewoehnliche Regel (frueher: eingebauter Nacht-Standard) ---

    /** Die Nacht-Regel: jede Kalendernacht ab 22:00 bis zur Weckzeit, spaetestens 07:00. */
    private fun nachtRegel() = DimRule(
        id = "nacht", name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
        windows = listOf(
            DimWindow(
                startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
                endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60
            )
        ),
        strength = 60, warmth = 40
    )

    private fun nachtSpans(
        alarms: List<DimWindowResolver.AlarmSlot>,
        today: LocalDate,
        rules: List<DimRule>,
    ) = DimWindowResolver.buildRuleSpans(
        alarms = alarms, horizonDays = 2, today = today, zone = zone,
        ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules),
        weckzeiten = alarms.map { it.triggerTime }
    )

    @Test
    fun `Unterdrueckung an der ZWEITEN Schicht nimmt den ganzen Kalendertag heraus`() {
        // Uebersetzt aus "Ausschluss der zweiten Schicht nimmt den Tag vom Nacht-Standard aus":
        // die Ausnahme ist im Ein-Modell eine spezifische Regel mit leerer Fensterliste. Die
        // Regressionsabsicht ist unveraendert - vor dem Fix gewann der fruehere Frühdienst-Slot,
        // die Regel der Rufbereitschaft wurde nie gefragt, und der Abend wurde gedimmt, obwohl
        // die Oberflaeche die Ausnahme als gesetzt anzeigte.
        val today = LocalDate.of(2026, 1, 12)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0)
        )
        val ruf = DimRule(
            id = "ruf", name = "Rufbereitschaft", shiftPattern = "Rufbereitschaft",
            enabled = true, windows = emptyList()
        )

        val spans = nachtSpans(alarms, today, rules = listOf(nachtRegel(), ruf))

        // Der Abend des Bereitschaftstags bleibt hell - das ist der Kern der Ausnahme.
        assertTrue(spans.none { ep(2026, 1, 13, 23, 0) in it.range })
        // Die Nacht DAVOR deckt der Vorabend als freier Tag ab. VERHALTENSAENDERUNG gegenueber dem
        // Altmodell: sie endet jetzt an der echten Weckzeit 05:00 statt an der festen Morgenuhrzeit
        // 07:00 - der Ende-Anker sieht die Weckzeit des unterdrueckten Tages, weil er die gesamte
        // Zeitleiste durchsucht. Das ist die hellere Richtung und passt zum Alltag (wer um 05:00
        // geweckt wird, will keinen dunklen Bildschirm bis 07:00).
        val davor = spans.first { ep(2026, 1, 13, 3, 0) in it.range }
        assertEquals(ep(2026, 1, 12, 22, 0), davor.range.first)
        assertEquals(ep(2026, 1, 13, 5, 0), davor.range.last)
    }

    @Test
    fun `Die Nacht-Regel endet weiterhin an der FRUEHESTEN Weckzeit des Tages`() {
        // Die Nacht davor endet am ersten Wecken, nicht am zweiten - unverändert, jetzt aber als
        // ausdrücklich sortierte Wahl statt als Nebeneffekt der Eingabereihenfolge.
        val today = LocalDate.of(2026, 1, 12)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0)
        )

        val spans = nachtSpans(alarms, today, rules = listOf(nachtRegel()))

        val rueckwaerts = spans.filter { it.range.last == ep(2026, 1, 13, 5, 0) }
        assertEquals(1, rueckwaerts.size)
        assertEquals(ep(2026, 1, 12, 22, 0), rueckwaerts[0].range.first)
        // Kein zweites, an der späteren Schicht verankertes Fenster - nicht stapeln.
        assertTrue(spans.none { it.range.last == ep(2026, 1, 13, 16, 0) })
    }
}
