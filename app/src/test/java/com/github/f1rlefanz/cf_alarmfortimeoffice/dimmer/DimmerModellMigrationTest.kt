package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.LocalDate
import java.time.ZoneId

/**
 * DER VERLUSTFREIHEITS-BEWEIS der Dimmer-Modellmigration.
 *
 * Die Behauptung von [DimmerModellMigration] ist stark: was der Nutzer im ALTEN Modell (drei
 * Fenster-Quellen, drei Schalter) eingestellt hatte, dimmt nach dem Update zu denselben Zeiten wie
 * davor. Nachrechnen kann man das nur, indem man beide Modelle nebeneinander laufen lässt — das
 * alte über die eingefrorene [DimmerAltmodellReferenz] (dort steht, warum es sie gibt), das neue
 * über die migrierten Regeln — und die entstehenden ZEITLEISTEN vergleicht. Nicht die Regeln:
 * verlustfrei heißt nicht „dieselben Objekte", sondern „derselbe Bildschirm zur selben Minute".
 *
 * DIE ZEITLEISTE, NICHT DIE ROHEN SPANNEN: verglichen wird `mergeToTimeline(...)`, also genau das,
 * was `DimScheduleUseCase.previewTimelineWithStatus()` liefert — überlappende Spannen sind dort
 * schon nach „dunkelste gewinnt" aufgelöst. Zwei verschieden gebaute Spannen-Listen, die dieselbe
 * Zeitleiste ergeben, sind für Nutzer, Scheduler und DND-Modus 1 ununterscheidbar.
 *
 * DER FILTER `range.last > jetzt` ist derselbe wie dort und kein Weichzeichner: beide Modelle
 * rechnen einen Kalendertag vor `today` mit (`LOOKBACK_DAYS`), das Altmodell erzeugt an dieser
 * Kante zusätzlich das Rückwärts-Fenster der Nacht davor — ein Abschnitt, der vollständig in der
 * Vergangenheit liegt und den weder `activeSpan` („now in range") noch der Scheduler („> now") je
 * wieder anfasst.
 *
 * WO GLEICHHEIT NICHT HERSTELLBAR IST, steht das weiter unten als eigener Test mit konkreten
 * Zahlen — als Ergebnis, nicht als Ausnahme. Jede dieser Abweichungen ist entweder die AUSDRÜCKLICH
 * gewollte Wirkung des neuen Ende-Ankers oder eine Folge davon, dass eine Regel genau EINE
 * Verdunkelung trägt.
 */
class DimmerModellMigrationTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    /** Fester Montag ohne Zeitumstellung - die DST-Fälle prüfen `DimWindowResolverTimeArithmeticTest`. */
    private val heute: LocalDate = LocalDate.of(2026, 3, 2)
    private val horizont = 14
    private val jetzt = wanduhr(heute, 12, 0)

    private val FRUEH = "Fruehschicht"
    private val ZWISCHEN = "Zwischendienst"
    private val NACHT = "Nachtschicht"

    private fun wanduhr(d: LocalDate, h: Int, m: Int): Long =
        d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun tag(i: Long): LocalDate = heute.plusDays(i)

    private fun slot(i: Long, name: String, h: Int, m: Int) =
        DimWindowResolver.AlarmSlot(wanduhr(tag(i), h, m), name, 0L)

    /** Nur FRÜHE Weckzeiten (vor der Nacht-Schranke 07:00) - siehe die Abweichung „später Wecker". */
    private val dienstplanFrueh = listOf(
        slot(-1, FRUEH, 5, 30), slot(0, FRUEH, 5, 30), slot(1, FRUEH, 5, 30),
        slot(3, ZWISCHEN, 6, 15), slot(4, ZWISCHEN, 6, 15),
        slot(7, FRUEH, 5, 30), slot(8, FRUEH, 5, 30), slot(9, ZWISCHEN, 6, 15),
        slot(11, FRUEH, 5, 30)
    )

    /** Derselbe Plan mit zwei Nachtdiensten (Weckzeit 18:00) und freiem Tag danach - der Alltagsfall. */
    private val dienstplanMitNacht = listOf(
        slot(-1, FRUEH, 5, 30), slot(0, FRUEH, 5, 30), slot(1, FRUEH, 5, 30),
        slot(3, NACHT, 18, 0), slot(4, NACHT, 18, 0),
        slot(7, FRUEH, 5, 30), slot(8, FRUEH, 5, 30), slot(9, ZWISCHEN, 6, 15),
        slot(11, FRUEH, 5, 30)
    )

    private fun nachtRegelFenster(startMin: Int = 22 * 60, endeMin: Int = 6 * 60) = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = startMin,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = endeMin
    )

    private fun regel(
        id: String,
        pattern: String,
        fenster: List<DimWindow>,
        strength: Int = 55,
        warmth: Int = 40,
        enabled: Boolean = true
    ) = DimRule(
        id = id, name = "Regel $id", shiftPattern = pattern,
        enabled = enabled, windows = fenster, strength = strength, warmth = warmth
    )

    private fun alt(
        wellness: Boolean = false,
        regeln: Boolean = false,
        nacht: Boolean = false,
        windDown: Int = 120,
        gStrength: Int = 55,
        gWarmth: Int = 40,
        nachtStart: Int = 22 * 60,
        nachtEnde: Int = 7 * 60,
        nachtStrength: Int = 55,
        nachtWarmth: Int = 40,
        ausnahmen: Set<String> = emptySet()
    ) = DimmerModellMigration.AltZustand(
        wellnessAn = wellness, regelnAn = regeln, nachtStandardAn = nacht,
        windDownMinuten = windDown, globalStrength = gStrength, globalWarmth = gWarmth,
        nachtStartMinuten = nachtStart, nachtEndeMinuten = nachtEnde,
        nachtStrength = nachtStrength, nachtWarmth = nachtWarmth, nachtAusnahmen = ausnahmen
    )

    /** Die ECHTE Auswahl-Logik (exakter Name → UNIVERSAL / FREI → UNIVERSAL), nicht nachgebaut. */
    private val auswahl = DimRuleUseCase(mock())

    // --- Die beiden Modelle ------------------------------------------------------------------

    /**
     * Das ALTE `DimScheduleUseCase.computeWindows()` (Stand v1.33.0): drei Quellen, vereinigt.
     * Wortgleich zu dem, was dort stand - die Nacht-Standard-Quelle kommt aus der eingefrorenen
     * Referenz, die beiden anderen sind unverändert im Produktionscode.
     */
    private fun altModell(
        a: DimmerModellMigration.AltZustand,
        bestand: List<DimRule>,
        dienstplan: List<DimWindowResolver.AlarmSlot>,
        alarme: List<Long> = dienstplan.map { it.triggerTime }
    ): List<DimWindowResolver.DimSpan> {
        val out = mutableListOf<DimWindowResolver.DimSpan>()
        if (a.wellnessAn) {
            alarme.forEach {
                out += DimWindowResolver.DimSpan(
                    (it - a.windDownMinuten * 60_000L)..it, a.globalStrength, a.globalWarmth
                )
            }
        }
        val regelnAktiv = a.regelnAn && bestand.any { it.enabled }
        val weckzeiten = (dienstplan.map { it.triggerTime } + alarme).distinct().sorted()
        if (regelnAktiv) {
            out += DimWindowResolver.buildRuleSpans(
                alarms = dienstplan, horizonDays = horizont, today = heute, zone = zone,
                ruleForShift = { n -> auswahl.findRuleForShift(n, bestand) },
                ruleForFreeDay = { auswahl.findRuleForFreeDay(bestand) },
                weckzeiten = weckzeiten
            )
        }
        if (a.nachtStandardAn) {
            out += DimmerAltmodellReferenz.buildDefaultNightSpans(
                alarms = dienstplan, horizonDays = horizont, today = heute, zone = zone,
                startClockMinutes = a.nachtStartMinuten,
                freeDayEndClockMinutes = a.nachtEndeMinuten,
                strength = a.nachtStrength, warmth = a.nachtWarmth,
                isExcluded = { name ->
                    if (name != null) {
                        name in a.nachtAusnahmen ||
                            (regelnAktiv && auswahl.findRuleForShift(name, bestand) != null)
                    } else {
                        regelnAktiv && auswahl.findRuleForFreeDay(bestand) != null
                    }
                }
            )
        }
        return out
    }

    /** Das heutige `computeWindows()`: EIN Schalter, EINE Quelle - die migrierten Regeln. */
    private fun neuesModell(
        plan: DimmerModellMigration.Plan,
        bestand: List<DimRule>,
        dienstplan: List<DimWindowResolver.AlarmSlot>,
        alarme: List<Long> = dienstplan.map { it.triggerTime }
    ): List<DimWindowResolver.DimSpan> {
        if (!plan.dimEnabled) return emptyList()
        val regeln = anwenden(bestand, plan.regeln)
        if (regeln.none { it.enabled }) return emptyList()
        val weckzeiten = (dienstplan.map { it.triggerTime } + alarme).distinct().sorted()
        return DimWindowResolver.buildRuleSpans(
            alarms = dienstplan, horizonDays = horizont, today = heute, zone = zone,
            ruleForShift = { n -> auswahl.findRuleForShift(n, regeln) },
            ruleForFreeDay = { auswahl.findRuleForFreeDay(regeln) },
            weckzeiten = weckzeiten
        )
    }

    /** Was `DimRuleRepository.upsert` je Regel tut: nach `id` ersetzen, sonst anhängen. */
    private fun anwenden(bestand: List<DimRule>, upserts: List<DimRule>): List<DimRule> {
        val liste = bestand.toMutableList()
        upserts.forEach { neu ->
            val i = liste.indexOfFirst { it.id == neu.id }
            if (i >= 0) liste[i] = neu else liste += neu
        }
        return liste
    }

    private fun zeitleiste(spans: List<DimWindowResolver.DimSpan>): List<DimWindowResolver.ResolvedInterval> =
        DimWindowResolver.mergeToTimeline(spans).filter { it.range.last > jetzt }

    private fun zeige(l: List<DimWindowResolver.ResolvedInterval>): String =
        l.joinToString("\n") { i ->
            "  ${java.time.Instant.ofEpochMilli(i.range.first).atZone(zone).toLocalDateTime()} -> " +
                "${java.time.Instant.ofEpochMilli(i.range.last).atZone(zone).toLocalDateTime()} " +
                "(${i.strength}/${i.warmth})"
        }

    /** Der eigentliche Beweisschritt: 14 Tage alt gegen 14 Tage neu. */
    private fun beweise(
        fall: String,
        a: DimmerModellMigration.AltZustand,
        bestand: List<DimRule>,
        dienstplan: List<DimWindowResolver.AlarmSlot>
    ): DimmerModellMigration.Plan {
        val plan = DimmerModellMigration.plane(a, bestand)
        val vorher = zeitleiste(altModell(a, bestand, dienstplan))
        val nachher = zeitleiste(neuesModell(plan, bestand, dienstplan))
        assertEquals(
            "$fall: die Zeitleiste hat sich durch die Migration geaendert.\n" +
                "ALT:\n${zeige(vorher)}\nNEU:\n${zeige(nachher)}",
            vorher, nachher
        )
        return plan
    }

    // --- (a) Der Zustand des Eigentuemers ------------------------------------------------------

    /**
     * DIE GEFÄHRLICHSTE KONSTELLATION, und der Grund für [DimmerModellMigration] Punkt 2: Regeln AN
     * mit einer UNIVERSAL-Regel, Nacht-Standard AUS, aber mit einer gesetzten Ausnahme-Schicht.
     *
     * Würde aus dieser Ausnahme eine leere Regel („nicht dimmen") entstehen, wäre sie SOFORT scharf
     * — sie schlägt UNIVERSAL — und nähme dem Nutzer an jedem Nachtdienst-Tag das Dimmen weg, das
     * er dort heute hat. Die Ausnahme gehörte aber zu einer Quelle, die bei ihm nie lief.
     */
    @Test
    fun `a Eigentuemer - Regeln an, Nacht-Standard aus mit Ausnahme - kommt unveraendert durch`() {
        val universal = regel("u1", DimRule.SHIFT_UNIVERSAL, listOf(nachtRegelFenster()), 55, 40)
        val a = alt(regeln = true, ausnahmen = setOf(NACHT))

        val plan = beweise("Eigentuemer", a, listOf(universal), dienstplanMitNacht)

        assertTrue("Der Dimmer muss anbleiben", plan.dimEnabled)
        assertTrue(
            "Es darf NICHTS geschrieben werden - der Zustand ist im neuen Modell schon der richtige, " +
                "geplant war aber: ${plan.regeln}",
            plan.regeln.isEmpty()
        )
        assertNull(
            "Aus der Ausnahme einer AUSGESCHALTETEN Quelle darf keine Unterdrueckungs-Regel entstehen",
            anwenden(listOf(universal), plan.regeln).firstOrNull { it.shiftPattern == NACHT }
        )
    }

    // --- (b) Nur der Nacht-Standard ------------------------------------------------------------

    /**
     * Nacht-Standard AN, Regel-Quelle AUS — obwohl eine (aktivierte) Regel im Bestand liegt. Sie war
     * im Altmodell INERT; der eine neue Schalter darf sie nicht scharf machen. Deshalb wird sie
     * deaktiviert statt gelöscht: der Nutzer findet sie wieder und kann sie einschalten.
     */
    @Test
    fun `b nur Nacht-Standard - wird zur UNIVERSAL-Regel, inerter Regelbestand wird deaktiviert`() {
        val inert = regel("alt1", DimRule.SHIFT_UNIVERSAL, listOf(nachtRegelFenster()), 55, 40)
        val a = alt(nacht = true, nachtStrength = 45, nachtWarmth = 60)

        val plan = beweise("nur Nacht-Standard", a, listOf(inert), dienstplanFrueh)

        assertTrue(plan.dimEnabled)
        val ergebnis = anwenden(listOf(inert), plan.regeln)
        assertTrue(
            "Die inerte Regel muss deaktiviert werden",
            ergebnis.first { it.id == "alt1" }.enabled.not()
        )
        val neu = ergebnis.first { it.id == DimmerModellMigration.ID_UNIVERSAL }
        assertEquals(1, neu.windows.size)
        assertEquals(DimAnchor.CLOCK, neu.windows[0].startAnchor)
        assertEquals(DimAnchor.ALARM_SONST_CLOCK, neu.windows[0].endAnchor)
        assertEquals(22 * 60, neu.windows[0].startClockMinutes)
        assertEquals(7 * 60, neu.windows[0].endClockMinutes)
        assertEquals(45, neu.strength)
    }

    // --- (c) Nacht-Standard mit Ausnahmeschicht -------------------------------------------------

    /**
     * Jetzt WIRKT der Nacht-Standard — und erst jetzt wird aus seiner Ausnahme eine leere Regel.
     * Der Nachtdienst-Tag bleibt dadurch hell, und die Nacht davor endet weiterhin an der festen
     * Uhrzeit (die 18:00-Weckzeit liegt hinter der Schranke 07:00 und beendet sie nicht).
     */
    @Test
    fun `c Nacht-Standard mit Ausnahmeschicht - Ausnahme wird zur leeren Regel`() {
        val a = alt(nacht = true, ausnahmen = setOf(NACHT))

        val plan = beweise("Nacht-Standard + Ausnahme", a, emptyList(), dienstplanMitNacht)

        val ausnahme = plan.regeln.first { it.shiftPattern == NACHT }
        assertTrue(
            "Die Ausnahme ist eine UNTERDRUECKUNG - leere Fensterliste, nicht 'keine Regel'",
            ausnahme.windows.isEmpty() && ausnahme.enabled
        )
        assertEquals(DimmerModellMigration.ausnahmeId(NACHT), ausnahme.id)
    }

    // --- (d) Wellness + Regeln ------------------------------------------------------------------

    /**
     * Wellness lief im Altmodell NEBEN den Regeln — auch an einem Tag, den eine Regel mit leerer
     * Fensterliste unterdrückt hat („leere Liste" hieß dort nur „keine REGEL-Fenster"). Deshalb
     * bekommt jede wirksame Regel das Wind-down-Fenster angehängt, ausdrücklich auch die leere.
     */
    @Test
    fun `d Wellness und Regeln - das Wind-down-Fenster wandert in JEDE wirksame Regel`() {
        val universal = regel("u1", DimRule.SHIFT_UNIVERSAL, listOf(nachtRegelFenster()), 55, 40)
        val unterdrueckt = regel("z1", ZWISCHEN, emptyList(), 55, 40)
        val a = alt(wellness = true, regeln = true, windDown = 120, gStrength = 55, gWarmth = 40)

        val plan = beweise("Wellness + Regeln", a, listOf(universal, unterdrueckt), dienstplanFrueh)

        val ergebnis = anwenden(listOf(universal, unterdrueckt), plan.regeln)
        val wellnessFenster = DimWindow(
            startAnchor = DimAnchor.ALARM, startOffsetMinutes = -120,
            endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
        )
        assertTrue(ergebnis.first { it.id == "u1" }.windows.contains(wellnessFenster))
        assertEquals(
            "Die unterdrueckende Regel traegt danach GENAU das Wind-down-Fenster - genau das, was " +
                "das Altmodell an diesem Tag noch gedimmt hat",
            listOf(wellnessFenster), ergebnis.first { it.id == "z1" }.windows
        )
    }

    // --- (e) Alle drei Quellen an ---------------------------------------------------------------

    /**
     * Alle drei Schalter an — und der Nacht-Standard trotzdem wirkungslos, weil die aktive
     * UNIVERSAL-Regel JEDEN Tag abdeckt (Schicht-Tag wie freien Tag). Er darf deshalb weder ein
     * Fenster noch eine Ausnahme hinterlassen. Dieselbe Falle wie in (a), nur mit eingeschaltetem
     * Nacht-Standard-Schalter.
     */
    @Test
    fun `e alle drei an - der von UNIVERSAL verdeckte Nacht-Standard wird NICHT uebernommen`() {
        val universal = regel("u1", DimRule.SHIFT_UNIVERSAL, listOf(nachtRegelFenster()), 55, 40)
        val unterdrueckt = regel("z1", ZWISCHEN, emptyList(), 55, 40)
        val a = alt(
            wellness = true, regeln = true, nacht = true,
            gStrength = 55, gWarmth = 40, nachtStrength = 80, nachtWarmth = 90,
            ausnahmen = setOf(NACHT)
        )

        val plan = beweise("alle drei an", a, listOf(universal, unterdrueckt), dienstplanMitNacht)

        assertTrue(
            "Weder Nacht-Fenster noch Ausnahme duerfen entstehen - geplant war: ${plan.regeln}",
            plan.regeln.none { r ->
                r.shiftPattern == NACHT || r.windows.any { it.endAnchor == DimAnchor.ALARM_SONST_CLOCK }
            }
        )
    }

    // --- (f) Alles aus --------------------------------------------------------------------------

    /**
     * Alles aus: der Dimmer bleibt aus, und der Regelbestand wird NICHT angefasst. Ihn hier zu
     * deaktivieren wäre folgenlos (ohne Schalter entsteht kein Fenster), aber der Nutzer soll seine
     * Regeln beim ersten Einschalten vorfinden.
     */
    @Test
    fun `f alles aus - Dimmer bleibt aus, der Regelbestand bleibt unberuehrt`() {
        val vorhanden = regel("u1", DimRule.SHIFT_UNIVERSAL, listOf(nachtRegelFenster()))
        val a = alt()

        val plan = beweise("alles aus", a, listOf(vorhanden), dienstplanFrueh)

        assertTrue(plan.dimEnabled.not())
        assertTrue(plan.regeln.isEmpty())
    }

    // --- Idempotenz -----------------------------------------------------------------------------

    /**
     * Die Migration wird nur einmal angestoßen (Marker im DataStore) — aber nach einem Fehlschlag
     * läuft sie beim nächsten Start ERNEUT, dann über einen halb geschriebenen Bestand. Ein zweiter
     * Lauf darf deshalb nichts verdoppeln und nichts umdrehen: die deterministischen IDs ersetzen
     * dieselbe Regel, und die eigenen Erzeugnisse zählen nicht mehr zum „Altbestand" (sonst
     * deaktivierte Lauf 2 genau die Regel, die Lauf 1 angelegt hat).
     */
    @Test
    fun `ein zweiter Lauf aendert nichts mehr`() {
        val bestand = listOf(
            regel("u1", DimRule.SHIFT_UNIVERSAL, listOf(nachtRegelFenster())),
            regel("z1", ZWISCHEN, emptyList())
        )
        listOf(
            alt(wellness = true, regeln = true) to bestand,
            alt(nacht = true, ausnahmen = setOf(NACHT)) to bestand,
            alt(wellness = true, nacht = true) to bestand,
            alt(wellness = true, regeln = true, nacht = true, ausnahmen = setOf(NACHT)) to bestand
        ).forEachIndexed { i, (a, b) ->
            val ersterLauf = anwenden(b, DimmerModellMigration.plane(a, b).regeln)
            val zweiterLauf = anwenden(ersterLauf, DimmerModellMigration.plane(a, ersterLauf).regeln)
            assertEquals("Fall $i ist nicht idempotent", ersterLauf, zweiterLauf)
        }
    }

    // --- Die Abweichungen: Ergebnis, nicht Versagen ----------------------------------------------

    /**
     * ABWEICHUNG 1 (gewollt, sie ist der Anlass des ganzen Umbaus): Eine Weckzeit HINTER der
     * Nacht-Schranke beendet die Nacht nicht mehr.
     *
     * Am 23.08.2026 wachte der Eigentümer um 08:48 auf und fand den Bildschirm gedimmt — der alte
     * Nacht-Standard endete an Schicht-Tagen am ALARM-Anker, *egal wie spät der war*. Vor einem
     * Spätdienst mit Weckzeit 12:30 hieß „Nachtruhe bis 07:00" faktisch „bis mittags". Der neue
     * Ende-Anker ist das Minimum aus Weckzeit und Uhrzeit; die migrierte Regel endet deshalb um
     * 07:00. Richtung: heller.
     */
    @Test
    fun `Abweichung - ein spaeter Wecker verlaengert die Nacht nicht mehr bis mittags`() {
        val dienstplan = listOf(slot(1, "Spaetdienst", 12, 30))
        val a = alt(nacht = true)
        val plan = DimmerModellMigration.plane(a, emptyList())

        val vorher = zeitleiste(altModell(a, emptyList(), dienstplan))
        val nachher = zeitleiste(neuesModell(plan, emptyList(), dienstplan))

        val start = wanduhr(tag(0), 22, 0)
        assertEquals(
            "ALT endete am Wecker - also mittags",
            wanduhr(tag(1), 12, 30), vorher.first { it.range.first == start }.range.last
        )
        assertEquals(
            "NEU endet an der eingestellten Uhrzeit",
            wanduhr(tag(1), 7, 0), nachher.first { it.range.first == start }.range.last
        )
    }

    /**
     * ABWEICHUNG 2 (bewusst in Kauf genommen, steht auch im Produktionscode bei `computeWindows()`):
     * Ein MANUELLER Wecker kann kein Fenster mehr AUFSPANNEN.
     *
     * Die alte Wellness-Quelle legte ihr Wind-down um JEDE Weckzeit des Alarm-Bestands, also auch um
     * einen selbst gestellten Wecker. Als Regel (`ALARM −X` → `ALARM +0`) geht das nicht: ALARM-
     * verankerte Regelfenster lösen über die Schichtspannen auf, und ein manueller Wecker hat keine.
     * Er erreicht die Fensterlogik nur noch über die Weckzeit-Zeitleiste, also nur als ENDE-Anker —
     * er BEENDET eine Nacht, er beginnt keine. Der Ausweg für den Nutzer ist ein Nacht-Fenster mit
     * CLOCK-Start. Manuelle Wecker in die Schicht-Slots aufzunehmen ist ausgeschlossen: das machte
     * aus einem freien Tag still einen Schicht-Tag und hebelte die FREI-Regel aus.
     */
    @Test
    fun `Abweichung - ein manueller Wecker bekommt kein Wind-down-Fenster mehr`() {
        val manueller = wanduhr(tag(1), 9, 0)
        val a = alt(wellness = true, windDown = 120)
        val plan = DimmerModellMigration.plane(a, emptyList())

        val vorher = zeitleiste(altModell(a, emptyList(), emptyList(), listOf(manueller)))
        val nachher = zeitleiste(neuesModell(plan, emptyList(), emptyList(), listOf(manueller)))

        assertEquals(listOf(wanduhr(tag(1), 7, 0)..manueller), vorher.map { it.range })
        assertTrue("Ohne Schichtspanne gibt es keinen ALARM-Anker mehr", nachher.isEmpty())
    }

    /**
     * ABWEICHUNG 3: Nacht-Standard NEBEN spezifischen Regeln (ohne UNIVERSAL). Die Nacht vor einem
     * regelbelegten Schicht-Tag endete früher an der festen Uhrzeit, weil der Nacht-Standard diesen
     * Tag ausschloss und ihn deshalb gar nicht ansah. Der neue Ende-Anker sucht die früheste
     * Weckzeit in der GESAMTEN Zeitleiste und sieht auch die eines ausgeschlossenen Tages — die
     * Nacht endet jetzt an der echten Weckzeit. Richtung: heller.
     */
    @Test
    fun `Abweichung - die Nacht vor einem regelbelegten Tag endet an dessen Weckzeit`() {
        val dienstplan = listOf(slot(1, FRUEH, 5, 30))
        val fruehRegel = regel("f1", FRUEH, listOf(nachtRegelFenster()))
        val a = alt(regeln = true, nacht = true)
        val plan = DimmerModellMigration.plane(a, listOf(fruehRegel))

        val vorher = zeitleiste(altModell(a, listOf(fruehRegel), dienstplan))
        val nachher = zeitleiste(neuesModell(plan, listOf(fruehRegel), dienstplan))

        val start = wanduhr(tag(0), 22, 0)
        assertEquals(wanduhr(tag(1), 7, 0), vorher.first { it.range.first == start }.range.last)
        assertEquals(wanduhr(tag(1), 5, 30), nachher.first { it.range.first == start }.range.last)
        assertNotEquals(vorher, nachher)
    }

    /**
     * ABWEICHUNG 4, die einzige STRUKTURELLE: Eine [DimRule] trägt genau EINE Verdunkelung, das
     * Altmodell hatte je Quelle eine eigene.
     *
     * Waren Wellness und Nacht-Standard mit VERSCHIEDENEN Werten eingestellt, lässt sich das in
     * einer Regel nicht abbilden. Übernommen wird die dunklere — genau die, die im Altmodell in der
     * Überlappung ohnehin gewonnen hätte („dunkelste gewinnt"). Der Preis: der mildere Teil
     * AUSSERHALB der Überlappung wird dunkler als früher. Die Fenster-GRENZEN bleiben exakt gleich.
     */
    @Test
    fun `Abweichung - zwei Quellen mit verschiedener Verdunkelung werden zu einer`() {
        val a = alt(
            wellness = true, nacht = true, windDown = 120,
            gStrength = 70, gWarmth = 50, nachtStrength = 40, nachtWarmth = 30
        )
        val plan = DimmerModellMigration.plane(a, emptyList())

        val vorher = zeitleiste(altModell(a, emptyList(), dienstplanFrueh))
        val nachher = zeitleiste(neuesModell(plan, emptyList(), dienstplanFrueh))

        assertEquals(
            "Die Fenster-GRENZEN bleiben unveraendert - nur die Verdunkelung nicht",
            vorher.map { it.range.first }.min()..vorher.map { it.range.last }.max(),
            nachher.map { it.range.first }.min()..nachher.map { it.range.last }.max()
        )
        assertTrue("ALT kannte den milderen Abschnitt", vorher.any { it.strength == 40 })
        assertTrue("NEU hat nur noch die duenklere Stufe", nachher.all { it.strength == 70 })
    }
}
