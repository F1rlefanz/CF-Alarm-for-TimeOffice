package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Prüfrunde 8, Nachbesserung: **Der entschiedene Regelkonflikt war ausschließlich eine Logzeile.**
 *
 * [DimWindowResolver.buildRuleSpans] entscheidet seit dem Fix „mehrere Schichten pro Tag", welche
 * von zwei widersprechenden spezifischen Regeln an einem Tag gilt — die der Schicht, die als erste
 * weckt. Gemeldet wurde das nur per `Logger.w`. Die Regelliste zeigte die unterlegene Regel
 * unverändert als aktiv, obwohl sie an diesen Tagen nichts tut, und über DND-Modus „folgt dem
 * Dimmer" hängt daran zusätzlich „Nicht stören". Das ist dieselbe Fehlerklasse — „angezeigt, wirkt
 * nicht" —, gegen die die Konfliktauflösung überhaupt gebaut wurde.
 *
 * VERWORFENE ALTERNATIVE (die den Hinweis erspart hätte): die Fenster BEIDER Regeln vereinigen.
 * Das wäre additiv, bräche „pro Kalendertag GENAU eine Regel" und dimmte mehr als jede der beiden
 * Regeln für sich — die falsche Richtung („im Zweifel klingeln und hell"). Bei widersprechenden
 * Parametern (zwei Verdunkelungsstufen für dieselbe Minute) gäbe es ohnehin keine saubere Antwort.
 * Also bleibt „früheste gewinnt" — und [DimWindowResolver.findRuleConflicts] sagt der Oberfläche,
 * wo das greift.
 *
 * Deterministisch gegen UTC, wie [DimWindowResolverTest].
 */
class Pruefrunde8RegelkonfliktSichtbarTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ep(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** Regelauswahl exakt wie `DimRuleUseCase.findRuleForShift` (erster Treffer, sonst UNIVERSAL). */
    private fun forShift(rules: List<DimRule>): (String) -> DimRule? = { name ->
        val en = rules.filter { it.enabled }
        en.firstOrNull { it.shiftPattern.equals(name, ignoreCase = true) }
            ?: en.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }

    private fun forFree(rules: List<DimRule>): () -> DimRule? = {
        val en = rules.filter { it.enabled }
        en.firstOrNull { it.shiftPattern == DimRule.SHIFT_FREE }
            ?: en.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }

    private fun nachtfenster(von: Int, bis: Int) = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = von * 60,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = bis * 60
    )

    private val frueh = DimRule(
        id = "f", name = "Frühdienst", shiftPattern = "Fruehdienst", enabled = true,
        windows = listOf(nachtfenster(22, 7)), strength = 65
    )
    private val ruf = DimRule(
        id = "r", name = "Rufbereitschaft", shiftPattern = "Rufbereitschaft", enabled = true,
        windows = listOf(nachtfenster(20, 23)), strength = 80
    )

    private val today = LocalDate.of(2026, 1, 13)
    private val zweiDienste = listOf(
        DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0),
        DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 16, 0), "Rufbereitschaft", 0)
    )

    @Test
    fun `Der verdraengte Fall ist abfragbar - Tag, Sieger und Unterlegene`() {
        // Ohne den Fix gab es diese Auskunft gar nicht; der Konflikt endete im Logcat.
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = zweiDienste, horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(listOf(frueh, ruf))
        )

        assertEquals(1, konflikte.size)
        assertEquals(LocalDate.of(2026, 1, 13), konflikte[0].date)
        assertEquals("f", konflikte[0].winningRuleId)
        assertEquals(listOf("r"), konflikte[0].shadowedRuleIds)
    }

    @Test
    fun `Die Auskunft nennt genau die Regel, deren Fenster auch wirklich entstehen`() {
        // Der Kern der Zusicherung: Anzeige und Wirkung stammen aus derselben Auswahl
        // (DimWindowResolver.regelFuerTag). Zwei getrennte Implementierungen könnten
        // auseinanderdriften - dann wäre die Anzeige schlimmer als keine.
        val rules = listOf(frueh, ruf)
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = zweiDienste, horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(rules)
        )
        val spans = DimWindowResolver.buildRuleSpans(
            alarms = zweiDienste, horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(rules), ruleForFreeDay = forFree(rules)
        )

        // Sieger laut Auskunft = Frühdienst (strength 65); dessen Fenster ist da ...
        assertEquals("f", konflikte[0].winningRuleId)
        assertTrue(spans.any { it.strength == 65 && ep(2026, 1, 13, 23, 30) in it.range })
        // ... und das der als verdrängt gemeldeten Regel nicht (auch nicht additiv daneben).
        assertTrue(spans.none { it.strength == 80 })
    }

    @Test
    fun `Ein Tag mit nur EINER Regel meldet keinen Konflikt`() {
        // Kein falscher Alarm: der Normalfall darf die Regelliste nicht mit Hinweisen zupflastern.
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = listOf(zweiDienste[0]), horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(listOf(frueh, ruf))
        )
        assertTrue(konflikte.isEmpty())
    }

    @Test
    fun `UNIVERSAL hinter einer spezifischen Regel ist kein Konflikt`() {
        // "Spezifisch überschreibt UNIVERSAL komplett" ist die dokumentierte, gewollte Semantik -
        // und `findRuleForShift` liefert für JEDE Schicht dieselbe UNIVERSAL-Regel, es gibt also
        // gar keine zweite verdrängte Regel-ID. Ein Hinweis dazu wäre Rauschen.
        val universal = DimRule(
            id = "u", name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
            windows = listOf(nachtfenster(22, 7))
        )
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = zweiDienste, horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(listOf(universal, ruf))
        )
        assertTrue(konflikte.isEmpty())
    }

    @Test
    fun `Ein Unterdrueckungstag ist bewusst kein Konflikt`() {
        // Leere Fensterliste = "in dieser Nacht nicht dimmen", eine ausdrückliche
        // Nutzerentscheidung. Das Ergebnis (es bleibt hell) ist genau das Bestellte - hier zu
        // warnen würde die Nachtdienst-Ausnahme als Fehler darstellen.
        val rufFrei = ruf.copy(windows = emptyList())
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = zweiDienste, horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(listOf(frueh, rufFrei))
        )
        assertTrue(konflikte.isEmpty())
    }

    @Test
    fun `Die Auskunft blickt NICHT auf den Vortag zurueck`() {
        // buildRuleSpans rechnet bewusst einen Kalendertag zurück (LOOKBACK_DAYS), damit eine über
        // Mitternacht laufende Nacht nicht verschwindet. Für die Aussage "an N der nächsten Tage"
        // wäre der Vortag dagegen Rauschen - der Nutzer kann daran nichts mehr ändern.
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = zweiDienste, horizonDays = 3, today = LocalDate.of(2026, 1, 14), zone = zone,
            ruleForShift = forShift(listOf(frueh, ruf))
        )
        assertTrue(konflikte.isEmpty())
    }

    @Test
    fun `Die Auskunft ist reihenfolge-unabhaengig`() {
        // Dieselbe Stabilitätszusicherung wie für die Fenster selbst: die Anzeige darf nicht
        // davon abhängen, in welcher Reihenfolge der ShiftSpanStore seine Spannen liefert.
        val rules = listOf(frueh, ruf)
        val a = DimWindowResolver.findRuleConflicts(
            alarms = zweiDienste, horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(rules)
        )
        val b = DimWindowResolver.findRuleConflicts(
            alarms = zweiDienste.reversed(), horizonDays = 3, today = today, zone = zone,
            ruleForShift = forShift(rules)
        )
        assertEquals(a, b)
    }
}
