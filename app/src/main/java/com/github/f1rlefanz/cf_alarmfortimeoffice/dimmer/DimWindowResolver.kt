package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reine Zeitmathematik der Dimm-Fenster – bewusst OHNE Android-Abhängigkeiten (Context/AlarmManager),
 * damit die kniffligen Teile (SHIFT_END-Auflösung, CLOCK „vor der Weckzeit", Roll-forward über
 * Mitternacht, Auswahl der aktiven Spanne) unit-testbar sind. [DimScheduleUseCase] delegiert hierher.
 *
 * Die [ZoneId] wird stets hereingereicht (statt intern `systemDefault()` zu ziehen), damit Tests
 * deterministisch gegen eine feste Zeitzone laufen.
 *
 * Einzige Ausnahme von "ohne Android-Abhaengigkeiten" ist [Logger] (Timber, reines JVM) fuer die
 * WARN-Zeile beim Regelkonflikt in [buildRuleSpans]: eine Entscheidung, die eine vom Nutzer als
 * aktiv gesehene Regel wirkungslos macht, darf nicht unsichtbar bleiben. Ohne geplanten Timber-Tree
 * ist der Aufruf ein No-op, Unit-Tests bleiben davon unberuehrt.
 */
object DimWindowResolver {
    private const val MIN_MS = 60_000L

    /**
     * Horizont der Konflikt-Auskunft für die Regelliste ([findRuleConflicts]) in Kalendertagen.
     *
     * Muss dem `HORIZON_DAYS` von `DimScheduleUseCase` entsprechen — die Oberfläche darf keinen
     * Zeitraum behaupten, den der Scheduler gar nicht plant. Die Konstante liegt hier statt dort,
     * weil sie der Aufrufer der Konflikt-Auskunft (das ViewModel der Regelliste) braucht und
     * `DimScheduleUseCase.HORIZON_DAYS` privat ist; der Zahlenwert steht deshalb an zwei Stellen.
     */
    const val KONFLIKT_HORIZONT_TAGE = 14

    /**
     * Wie viele Kalendertage VOR [today] die Fenster-Schleifen zusaetzlich mitrechnen. Ohne diesen
     * Rueckblick verschwindet ein am Vorabend gestartetes Fenster (Vorwaerts-Fenster des
     * Nacht-Standards, jedes CLOCK<->CLOCK-Regelfenster) nach dem Datumswechsel aus JEDER
     * Iteration: die Iteration fuer den neuen Tag erzeugt nur dessen EIGENEN Abend. Jede
     * Neuberechnung nach Mitternacht (App-Update via MY_PACKAGE_REPLACED, 6h-Wartung,
     * Master-Pause-Resume, jeder ViewModel-Setter, ein Tap auf die Korrektur-Notification) haette
     * die gerade laufende Nacht dadurch als "kein aktives Fenster" bewertet und Dimmen + DND
     * (Modus 1 rechnet ueber dieselben Fenster) mitten in der Nacht abgeschaltet. Vergangene
     * Spannen sind harmlos: `activeSpan` filtert per "now in range", der Scheduler per "> now".
     */
    private const val LOOKBACK_DAYS = 1L

    /** Ein aufgelöstes Dimm-Fenster samt Intensität seiner Quelle (Wellness = global, Regel = Regel-Wert). */
    data class DimSpan(val range: LongRange, val strength: Int, val warmth: Int)

    /**
     * Fenster eines Schicht-Tags. [alarmEpoch] = Weckzeit, [shiftEndEpoch] = Schichtende
     * (0 = unbekannt). Ein SHIFT_END-Anker an einem Alarm ohne bekanntes Schichtende liefert `null`.
     * Ergebnis `null` auch, wenn Ende ≤ Start (leeres/ungültiges Fenster).
     */
    fun resolveShiftWindow(
        w: DimWindow,
        alarmEpoch: Long,
        shiftEndEpoch: Long,
        zone: ZoneId,
        weckzeiten: List<Long> = emptyList()
    ): LongRange? {
        val start = when (w.startAnchor) {
            DimAnchor.ALARM -> alarmEpoch + w.startOffsetMinutes * MIN_MS
            // ALARM_SONST_CLOCK ist kein START-Anker (siehe DimAnchor-KDoc) - am Start verhaelt er
            // sich wie CLOCK, statt das Fenster wegen eines unmoeglichen Falls verschwinden zu
            // lassen. "Nicht dimmen" waere hier die schlechtere Degradation als "wie eingestellt".
            DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK ->
                clockAtOrBefore(alarmEpoch, w.startClockMinutes, zone)
            DimAnchor.SHIFT_END -> {
                if (shiftEndEpoch == 0L) return null
                shiftEndEpoch + w.startOffsetMinutes * MIN_MS
            }
        }
        val end = when (w.endAnchor) {
            DimAnchor.ALARM -> alarmEpoch + w.endOffsetMinutes * MIN_MS
            DimAnchor.SHIFT_END -> {
                if (shiftEndEpoch == 0L) return null
                shiftEndEpoch + w.endOffsetMinutes * MIN_MS
            }
            DimAnchor.ALARM_SONST_CLOCK ->
                endeAnWeckzeitSonstUhrzeit(start, w.endClockMinutes, zone, weckzeiten)
            DimAnchor.CLOCK -> {
                // Roll-forward ueber KALENDERTAGE, nicht ueber +24h-Millis: an einem
                // DST-Umstellungstag ist ein Tag 23 h bzw. 25 h lang, ein fixer Millis-Sprung
                // traefe dort die falsche Wanduhrzeit.
                var date = dateOf(alarmEpoch, zone)
                var e = wallClock(date, w.endClockMinutes, zone)
                while (e <= start) {
                    date = date.plusDays(1)
                    e = wallClock(date, w.endClockMinutes, zone)
                }
                e
            }
        }
        return if (end > start) start..end else null
    }

    /**
     * Fenster einer KALENDERNACHT – Start immer CLOCK, Ende CLOCK oder [DimAnchor.ALARM_SONST_CLOCK].
     * Braucht weder Wecker noch Schicht und gilt deshalb an freien wie an Schicht-Tagen gleich.
     *
     * [weckzeiten] ist die aufsteigend sortierte Zeitleiste ALLER Weckzeiten im Horizont (siehe
     * [buildRuleSpans]) und wird nur vom [DimAnchor.ALARM_SONST_CLOCK]-Ende gelesen. Leer heisst
     * „keine bekannt" – dann endet das Fenster an der Uhrzeit, also fail-safe hell statt endlos dunkel.
     */
    fun resolveFreeWindow(
        w: DimWindow,
        date: LocalDate,
        zone: ZoneId,
        weckzeiten: List<Long> = emptyList()
    ): LongRange? {
        if (w.startAnchor != DimAnchor.CLOCK) return null
        val start = wallClock(date, w.startClockMinutes, zone)
        return when (w.endAnchor) {
            DimAnchor.CLOCK -> {
                // Über Mitternacht = die Uhrzeit auf dem FOLGE-KALENDERTAG (nicht Start + 24h-Millis) –
                // sonst eine Stunde falsch an DST-Umstellungstagen.
                val end = wallClock(date, w.endClockMinutes, zone).let {
                    if (it <= start) wallClock(date.plusDays(1), w.endClockMinutes, zone) else it
                }
                start..end
            }

            DimAnchor.ALARM_SONST_CLOCK -> {
                val end = endeAnWeckzeitSonstUhrzeit(start, w.endClockMinutes, zone, weckzeiten)
                if (end > start) start..end else null
            }

            else -> null
        }
    }

    /**
     * Das Ende eines [DimAnchor.ALARM_SONST_CLOCK]-Fensters: die FRÜHESTE Weckzeit echt nach
     * [start] und echt vor der Uhrzeit-Schranke – sonst die Schranke selbst.
     *
     * Die Schranke wird über KALENDERTAGE nach vorn gerollt, bis sie hinter [start] liegt (nie über
     * fixe 24-h-Millis: ein DST-Tag hat 23 bzw. 25 Stunden, ein Millis-Sprung träfe dort die
     * falsche Wanduhrzeit – dieselbe Falle wie in [resolveShiftWindow]).
     *
     * DIE SCHRANKE IST EINE OBERGRENZE, KEIN SUCHFENSTER-ARTEFAKT: Eine Weckzeit GENAU auf der
     * Schranke ändert nichts (beides derselbe Zeitpunkt), eine danach gehört nicht mehr zu dieser
     * Nacht. Und eine Weckzeit genau auf [start] darf das Fenster nicht auf die Länge null
     * schrumpfen lassen – deshalb echt größer als [start]. Die Aufrufer verwerfen `end <= start`
     * ohnehin, aber ein Fenster, das wegen des eigenen Startzeitpunkts verschwindet, wäre eine
     * stille Dimm-Lücke statt eines erkennbaren Fehlers.
     */
    private fun endeAnWeckzeitSonstUhrzeit(
        start: Long,
        endClockMinutes: Int,
        zone: ZoneId,
        weckzeiten: List<Long>
    ): Long {
        var date = dateOf(start, zone)
        var schranke = wallClock(date, endClockMinutes, zone)
        while (schranke <= start) {
            date = date.plusDays(1)
            schranke = wallClock(date, endClockMinutes, zone)
        }
        val weckzeit = weckzeiten.firstOrNull { it > start && it < schranke }
        return weckzeit ?: schranke
    }

    /**
     * Die gerade aktive Spanne für [now]. Überlappen mehrere, gewinnt die DUNKELSTE
     * (max strength, bei Gleichstand max warmth) – so schlägt eine „hart dimmen"-Regel eine mildere.
     *
     * Die Zugehoerigkeit ist HALB OFFEN (`first <= now < last`), nicht inklusiv: der Scheduler plant
     * den naechsten Wechsel strikt auf `> now`. Traefe ein Tick exakt auf `range.last` (0 ms
     * Zustellungs-Latenz), waere das Fenster hier noch aktiv, waehrend als naechster Wechsel schon
     * die Grenze DANACH gesetzt wird - der Zustand "aus" fuer diesen Fensterrand wuerde nie
     * berechnet und das Overlay/DND blieb bis zum naechsten Fensterstart haengen.
     */
    fun activeSpan(spans: List<DimSpan>, now: Long): DimSpan? =
        spans.filter { now >= it.range.first && now < it.range.last }
            .maxWithOrNull(compareBy({ it.strength }, { it.warmth }))

    /**
     * Minimal-Info einer Schicht für die Fenster-Berechnung (entkoppelt von Android).
     *
     * Name historisch: `DimScheduleUseCase` füllt das seit v1.25.2 aus `ShiftSpan`, damit
     * SHIFT_END-verankerte Fenster nicht verschwinden, sobald der Wecker geklingelt hat. Die
     * **Wellness**-Quelle daneben liest weiterhin den echten Alarm-Bestand — sie dimmt vor der
     * Weckzeit, ihr Fenster ist danach ohnehin vorbei.
     *
     * **[triggerTime] ist der Tages-Anker**, nicht nur der ALARM-Anker: `buildRuleSpans` und
     * `buildDefaultNightSpans` leiten den Kalendertag daraus ab. Ein Platzhalter (0) datiert den
     * Slot auf 1970 und zerstört die Tagesverankerung aller Fenster.
     */
    data class AlarmSlot(val triggerTime: Long, val shiftName: String, val shiftEndTime: Long)

    /**
     * Baut die Regel-Spannen über [horizonDays] Kalendertage ab [today]. Pro Tag wird die passende
     * Regel gewählt (Schicht-Tag → [ruleForShift]; freier Tag → [ruleForFreeDay]); eine gefundene
     * Regel mit LEERER Fensterliste = Unterdrückung (kein Dimmen an diesem Tag → Nachtdienst-Ausnahme).
     *
     * Fenster-Auflösung hängt am Anker (siehe [resolveWindowForDate]):
     * - **CLOCK↔CLOCK** = fester Nacht-Zeitplan → „die Nacht DIESES Kalendertags" (lückenlos jede Nacht,
     *   unabhängig von Schicht/frei). Genau das ermöglicht „immer 22–7 dimmen, außer an Nachtdienst-
     *   Nächten": UNIVERSAL trägt das 22–7-Fenster jede Nacht, die leere Nachtdienst-Regel nimmt die
     *   Arbeitsnächte heraus.
     * - **ALARM/SHIFT_END** = schicht-relativ (Wind-down / ND-Tagschlaf) → braucht einen Alarm an
     *   diesem Datum, sonst übersprungen.
     *
     * **ALLE Schichten eines Kalendertags werden ausgewertet, nicht nur die frueheste** (Fix
     * Pruefrunde 8). Bis dahin faltete eine `HashMap<LocalDate, AlarmSlot>` mit "first wins" den
     * Tag auf einen einzigen Slot zusammen; weil die Eingabeliste nach Weckzeit sortiert ankommt,
     * gewann immer die frueheste Schicht. An einem Tag mit Fruehdienst UND anschliessender
     * Rufbereitschaft - im Pflege-/Klinikbetrieb der Regelfall - wurde die Rufbereitschaft-Regel
     * dadurch NIE gefragt: die Regelliste zeigte sie als aktiv, gewirkt hat sie nicht, und ueber
     * DND-Modus "folgt dem Dimmer" schaltete zusaetzlich "Nicht stoeren" in genau der Nacht ein,
     * in der Erreichbarkeit der Zweck des Dienstes ist. Auswahl unter mehreren Schichten:
     *
     * 1. **Unterdrueckung gewinnt.** Findet auch nur EINE Schicht des Tages eine Regel mit leerer
     *    Fensterliste, wird an diesem Tag nicht gedimmt (Nachtdienst-Ausnahme). Nicht zu dimmen
     *    ist die harmlose Richtung - "im Zweifel klingeln und hell".
     * 2. **Spezifisch schlaegt UNIVERSAL** - unveraendert die Invariante "eine spezifische Regel
     *    ueberschreibt UNIVERSAL komplett, nicht additiv", nur jetzt ueber alle Schichten des
     *    Tages gebildet statt ueber eine zufaellig ausgewaehlte.
     * 3. **Widersprechen sich zwei VERSCHIEDENE spezifische Regeln an einem Tag, gilt die Regel der
     *    Schicht, die als erste weckt** - der Fall wird als WARN protokolliert UND ueber
     *    [findRuleConflicts] in der Regelliste angezeigt (das Log allein waere wieder "angezeigt,
     *    wirkt nicht" - der Nutzer liest kein Logcat). Der erste Wurf
     *    dieses Fixes liess den Tag stattdessen kommentarlos ganz aus; das schaltete das Dimmen fuer
     *    diesen Kalendertag KOMPLETT ab (samt Nacht-Standard, den Punkt 3 in [DimScheduleUseCase]
     *    fuer regelbelegte Tage ohnehin ausschliesst), waehrend die Regelliste beide Regeln weiter
     *    als aktiv zeigte - "angezeigt, wirkt nicht", genau die Fehlerklasse, gegen die dieser Fix
     *    gebaut wurde. Die Fenster zu vereinigen ist keine Alternative: das waere additiv (Bruch von
     *    "pro Kalendertag GENAU eine Regel") und dimmte mehr als jede Regel fuer sich.
     * 4. Die gewaehlte Regel wird an der Schicht VERANKERT, zu der sie gehoert - ihre ALARM-/
     *    SHIFT_END-Anker meinen diese Schicht, nicht irgendeine andere des Tages. Treffen mehrere
     *    Schichten dieselbe Regel, gilt die mit der fruehesten Weckzeit: ausdruecklich sortiert
     *    (siehe [slotsByDate]), nicht mehr als stiller Nebeneffekt der Eingabereihenfolge.
     *
     * Die Fenster-IDENTITAET (`range.last` + `strength`, siehe [isOverrideStale]) bleibt damit
     * stabil: pro Kalendertag entsteht weiterhin genau EINE Regel-Quelle, und ihre Auswahl haengt
     * nur an Daten, nicht an der Listenreihenfolge - ein laufendes Fenster kippt also nicht mitten
     * in der Nacht auf einen anderen Anker oder wird doppelt erzeugt.
     */
    fun buildRuleSpans(
        alarms: List<AlarmSlot>,
        horizonDays: Int,
        today: LocalDate,
        zone: ZoneId,
        ruleForShift: (String) -> DimRule?,
        ruleForFreeDay: () -> DimRule?,
        weckzeiten: List<Long> = emptyList(),
    ): List<DimSpan> {
        val byDate = slotsByDate(alarms, zone)
        // Aufsteigend sortiert, weil [endeAnWeckzeitSonstUhrzeit] die FRUEHESTE passende Weckzeit
        // per firstOrNull sucht. Hier einmal statt in jeder Fenster-Aufloesung erneut.
        val sortierteWeckzeiten = weckzeiten.sorted()
        val out = mutableListOf<DimSpan>()
        val konflikte = mutableListOf<String>()
        // Beginnt bewusst EINEN Tag vor [today] (siehe [LOOKBACK_DAYS]): ein CLOCK<->CLOCK-Fenster
        // vom Vorabend gehoert nach dem Datumswechsel weiterhin zur laufenden Nacht.
        for (i in -LOOKBACK_DAYS until horizonDays.toLong()) {
            val date = today.plusDays(i)
            val shifts = byDate[date].orEmpty()

            if (shifts.isEmpty()) {
                // Freier Tag - unveraendert: FREI schlaegt UNIVERSAL, sonst nichts.
                val freeRule = ruleForFreeDay() ?: continue
                for (w in freeRule.windows) {
                    resolveWindowForDate(w, date, null, zone, sortierteWeckzeiten)
                        ?.let { out += DimSpan(it, freeRule.strength, freeRule.warmth) }
                }
                continue
            }

            // JEDE Schicht des Tages wird gefragt (siehe KDoc Punkt 1-4), nicht nur die frueheste.
            // Die Auswahl selbst steckt in [regelFuerTag] - dieselbe Funktion beantwortet auch
            // [findRuleConflicts] fuer die Oberflaeche, damit Anzeige und Wirkung nicht
            // auseinanderlaufen koennen.
            val wahl = regelFuerTag(shifts, ruleForShift) ?: continue

            // Gesammelt statt sofort geloggt: diese Schleife laeuft ueber den ganzen Horizont und
            // bei JEDEM Tick sowie nach jedem Setter erneut - eine Zeile je Konflikttag waere
            // Log-Spam. Eine Zeile je Berechnung genuegt, um den Fall im Release-Log zu sehen.
            if (wahl.verdraengteRegelIds.isNotEmpty()) {
                konflikte += "$date->${wahl.rule.id} statt ${wahl.verdraengteRegelIds}"
            }
            for (w in wahl.rule.windows) {
                resolveWindowForDate(w, date, wahl.anker, zone, sortierteWeckzeiten)
                    ?.let { out += DimSpan(it, wahl.rule.strength, wahl.rule.warmth) }
            }
        }
        if (konflikte.isNotEmpty()) {
            // WARN, damit es im Release-Log landet (dort steht nur WARN+). Das Log ist hier NUR
            // die Diagnose-Spur - die Auskunft an den Nutzer laeuft ueber [findRuleConflicts] und
            // die Regelliste; ein Konflikt, den ausschliesslich das Log kennt, waere wieder
            // "angezeigt, wirkt nicht". Bewusst nur Datum und Regel-IDs - Regel- und Schichtnamen
            // sind Nutzertexte und gehoeren nicht ins Log.
            Logger.w(
                LogTags.DIMMER,
                "Dimm-Regelkonflikt an ${konflikte.size} Tag(en): mehrere Schichten eines Tages " +
                    "treffen verschiedene Regeln. Es gilt jeweils die Regel der fruehesten " +
                    "Schicht, die uebrigen wirken an diesem Tag nicht. $konflikte"
            )
        }
        return out
    }

    /**
     * Ein Kalendertag, an dem mehrere Schichten VERSCHIEDENE Regeln treffen: [winningRuleId] wirkt,
     * [shadowedRuleIds] wirken an diesem Tag nicht.
     */
    data class RuleConflict(
        val date: LocalDate,
        val winningRuleId: String,
        val shadowedRuleIds: List<String>
    )

    /**
     * Auskunft fuer die Regelliste: an welchen Kalendertagen wird welche Regel von einer anderen
     * verdraengt?
     *
     * **Warum es das geben muss:** [buildRuleSpans] entscheidet den Konflikt zweier spezifischer
     * Regeln an einem Tag zugunsten der fruehesten Schicht. Die unterlegene Regel steht in der
     * Regelliste weiter als aktiv, wirkt an diesem Tag aber nicht - und ueber DND-Modus "folgt dem
     * Dimmer" haengt daran auch "Nicht stoeren". Solange das nur eine Logzeile war, war es genau
     * die Fehlerklasse "angezeigt, wirkt nicht", gegen die die Konfliktaufloesung gebaut wurde.
     * Deshalb dieselbe Auswahl ([regelFuerTag]) noch einmal als reine Auskunft - nicht als zweite
     * Kopie der Logik, sondern als zweiter Aufrufer derselben Funktion. Waeren es zwei
     * Implementierungen, koennte die Anzeige von der Wirkung abdriften, und das waere schlimmer
     * als gar keine Anzeige.
     *
     * **Warum die Fenster NICHT vereinigt werden** (die Alternative, die den Hinweis erspart
     * haette): eine Vereinigung waere additiv, braeche die Zusicherung "pro Kalendertag GENAU eine
     * Regel" und dimmte MEHR als jede der beiden Regeln fuer sich - "im Zweifel klingeln und hell"
     * zeigt in die andere Richtung. Bei widersprechenden Parametern (zwei Verdunkelungsstufen fuer
     * dieselbe Minute) gaebe es ohnehin keine saubere Antwort, sondern nur eine dritte, von
     * niemandem konfigurierte.
     *
     * Der Horizont beginnt bei [today] und NICHT - anders als in [buildRuleSpans] - einen Tag
     * davor: der Rueckblick dort haelt eine ueber Mitternacht laufende Nacht am Leben, fuer eine
     * Auskunft ueber "die naechsten Tage" waere der Vortag dagegen Rauschen. Reine Anzeige-Funktion
     * ohne Seiteneffekt; sie erzeugt insbesondere keine Logzeile (das tut [buildRuleSpans] bei
     * jeder echten Berechnung ohnehin).
     *
     * Unterdrueckungstage (eine getroffene Regel mit leerer Fensterliste) sind BEWUSST kein
     * Konflikt: "an diesem Tag nicht dimmen" ist die ausdrueckliche Nutzerentscheidung hinter der
     * leeren Fensterliste, und ihr Ergebnis - es bleibt hell - ist genau das, was der Nutzer dort
     * bestellt hat.
     */
    fun findRuleConflicts(
        alarms: List<AlarmSlot>,
        horizonDays: Int,
        today: LocalDate,
        zone: ZoneId,
        ruleForShift: (String) -> DimRule?,
    ): List<RuleConflict> {
        val byDate = slotsByDate(alarms, zone)
        val out = mutableListOf<RuleConflict>()
        for (i in 0 until horizonDays.toLong()) {
            val date = today.plusDays(i)
            val shifts = byDate[date].orEmpty()
            if (shifts.isEmpty()) continue
            val wahl = regelFuerTag(shifts, ruleForShift) ?: continue
            if (wahl.verdraengteRegelIds.isEmpty()) continue
            out += RuleConflict(date, wahl.rule.id, wahl.verdraengteRegelIds)
        }
        return out
    }

    /**
     * Die fuer einen Kalendertag massgebliche Regel samt ihrem Anker und den an diesem Tag
     * verdraengten Regeln. `null` = an diesem Tag greift keine Regel (keine gefunden, oder
     * Unterdrueckung durch eine leere Fensterliste).
     */
    private data class Tagesregel(
        val anker: AlarmSlot,
        val rule: DimRule,
        val verdraengteRegelIds: List<String>
    )

    /**
     * Die Auswahl "welche Regel gilt an diesem Kalendertag" - die EINE Wahrheit, die sowohl
     * [buildRuleSpans] (Wirkung) als auch [findRuleConflicts] (Anzeige) benutzen. Siehe die
     * Punkte 1-4 im KDoc von [buildRuleSpans] fuer die Begruendung jedes Schritts.
     */
    private fun regelFuerTag(shifts: List<AlarmSlot>, ruleForShift: (String) -> DimRule?): Tagesregel? {
        val treffer: List<Pair<AlarmSlot, DimRule>> =
            shifts.mapNotNull { slot -> ruleForShift(slot.shiftName)?.let { slot to it } }

        // (1) Eine gefundene Regel OHNE Fenster ist die Nachtdienst-Ausnahme und gilt fuer den
        // ganzen Tag. Sie muss auch dann greifen, wenn eine ANDERE Schicht desselben Tages eine
        // dimmende Regel traegt - sonst dimmt ausgerechnet die Nacht, in der der Nutzer
        // ausdruecklich wach sein will. Nicht dimmen ist die harmlose Richtung.
        if (treffer.any { (_, r) -> r.windows.isEmpty() }) return null

        // (2) Spezifische Regeln verdraengen UNIVERSAL komplett (bestehende Invariante).
        val spezifisch = treffer.filter { (_, r) -> r.shiftPattern != DimRule.SHIFT_UNIVERSAL }
        val massgeblich = spezifisch.ifEmpty { treffer }
        if (massgeblich.isEmpty()) return null

        // (3)+(4) Anker und Regel = die Schicht, die als ERSTE weckt (deterministisch vorsortiert,
        // siehe [slotsByDate]); jede andere getroffene Regel des Tages ist verdraengt.
        val (anker, rule) = massgeblich.first()
        val verdraengt = massgeblich.map { (_, r) -> r.id }.distinct().filter { it != rule.id }
        return Tagesregel(anker, rule, verdraengt)
    }

    /**
     * Eingebauter Nacht-Standard (seit v1.17.0): dimmt ab [startClockMinutes] bis zum naechsten
     * Wecker (ueber [DimAnchor.ALARM]) bzw. bis [freeDayEndClockMinutes] (ueber [DimAnchor.CLOCK],
     * wenn kein Wecker in Reichweite ist) - jeweils nur an Kalendertagen, fuer die [isExcluded]
     * false liefert. [isExcluded] buendelt ZWEI unabhaengige Ausschluss-Wege: eine explizit vom
     * Nutzer ausgeschlossene Schicht (Toggle direkt an der Nacht-Standard-Karte) ODER eine
     * vorhandene [DimRule] (spezifisch, UNIVERSAL oder FREI), die diesen Tag ohnehin schon
     * abdeckt - exakt dieselbe Ausschliesslichkeit wie in [buildRuleSpans].
     *
     * Pro Tag werden ZWEI voneinander unabhaengige Fenster geprueft, nicht nur eines:
     * 1. **Rueckwaerts** (nur wenn der Tag selbst einen Wecker hat): die Nacht VOR diesem Wecker,
     *    endend am Wecker selbst (CLOCK reicht ueber "vor der Weckzeit" zurueck).
     * 2. **Vorwaerts** (immer, AUSSER der Folgetag hat selbst einen NICHT ausgeschlossenen Wecker):
     *    der heutige ABEND braucht ein eigenes Fenster bis [freeDayEndClockMinutes], weil das
     *    Rueckwaerts-Fenster (Punkt 1) - falls es ueberhaupt existiert - nur bis zum EIGENEN Wecker
     *    des Tages reicht, nicht bis in den eigenen Abend hinein. Hat der Tag KEINEN eigenen Wecker
     *    (klassischer freier Tag), ist Punkt 2 das einzige Fenster. "Nicht ausgeschlossen" ist
     *    Teil der Bedingung, nicht nur "hat einen Wecker": ist der Folgetag selbst per [isExcluded]
     *    ausgeschlossen, ueberspringt SEINE Schleifen-Iteration ihr eigenes Rueckwaerts-Fenster
     *    komplett (Punkt 1 oben) - dann deckt niemand die gemeinsame Nacht ab, wenn Punkt 2 hier
     *    trotzdem uebersprungen wird.
     *
     * Diese Trennung ist bewusst: ein Wecker, der nicht der fruehe Morgen ist (z. B. eine
     * Nachmittagsschicht mit Wecker 14:30), "verbraucht" den Tag fuer Punkt 1 als Ende einer
     * Nacht VOR sich - deckt aber NIE den eigenen Abend ab. Waere Punkt 2 weiterhin exklusiv an
     * "kein eigener Wecker" gebunden (wie vor diesem Fix), faellt die Nacht NACH einem solchen
     * Nachmittagswecker komplett durch, sobald der Folgetag ebenfalls keinen Wecker hat (der
     * Skip fuer den Folgetag nimmt faelschlich an, ein Wecker am UEBERnaechsten Tag decke sie
     * schon ab - dessen Rueckwaerts-Fenster reicht aber nur einen Tag zurueck). Real reproduziert
     * am 03./04./05.08.2026 (S2-Wecker 14:30 -> Tag ohne Termin -> Fruehschicht 05:30): die Nacht
     * vom 3. auf den 4.8. blieb dadurch komplett ungedimmt/DND-los. Derselbe Fehlerklasse kehrte
     * spaeter ueber den Ausschluss-Pfad zurueck (freier Tag vor einer ausgeschlossenen Schicht),
     * seither deckt Punkt 2 auch diesen Fall ab. Siehe `DimWindowResolverTest` fuer beide
     * Regressionsfaelle.
     *
     * **[isExcluded] wird ueber ALLE Schichten des Kalendertags gefragt, nicht nur ueber die
     * fruehesten** (Fix Pruefrunde 8, dieselbe Ursache wie in [buildRuleSpans]): der Ausschluss
     * ist von jeher TAGES-granular - er nimmt sowohl das Rueckwaerts- als auch das
     * Vorwaerts-Fenster dieses Datums heraus. Solange nur die frueheste Schicht gefragt wurde, war
     * ein an der Nacht-Standard-Karte gesetzter Ausschluss fuer die ZWEITE Schicht des Tages
     * (typisch: Rufbereitschaft nach dem Fruehdienst) wirkungslos, obwohl die Oberflaeche ihn als
     * gesetzt anzeigte. Deshalb gilt jetzt: **ein Ausschluss irgendeiner Schicht des Tages
     * schliesst den ganzen Tag aus** - Ausschluss heisst "hier nicht dimmen", und nicht zu dimmen
     * ist die harmlose Richtung. Dieselbe Antwort gilt fuer [nextDayCoversTonight] weiter unten:
     * der Folgetag deckt den heutigen Abend nur ab, wenn er ueberhaupt eine Schicht hat UND als
     * Tag nicht ausgeschlossen ist - sonst faellt die gemeinsame Nacht durch beide Raster.
     * Das Rueckwaerts-Fenster verankert weiterhin an der FRUEHESTEN Weckzeit des Tages: die Nacht
     * davor endet am ersten Wecken, nicht am zweiten. Neu ist nur, dass diese Wahl ausdruecklich
     * sortiert getroffen wird ([slotsByDate]) statt als Nebeneffekt der Eingabereihenfolge.
     */
    fun buildDefaultNightSpans(
        alarms: List<AlarmSlot>,
        horizonDays: Int,
        today: LocalDate,
        zone: ZoneId,
        startClockMinutes: Int,
        freeDayEndClockMinutes: Int,
        strength: Int,
        warmth: Int,
        isExcluded: (shiftName: String?) -> Boolean,
    ): List<DimSpan> {
        val byDate = slotsByDate(alarms, zone)
        val out = mutableListOf<DimSpan>()
        // Beginnt bewusst EINEN Tag vor [today] (siehe [LOOKBACK_DAYS]): das Vorwaerts-Fenster des
        // Vorabends gehoert nach dem Datumswechsel weiterhin zur laufenden Nacht.
        for (i in -LOOKBACK_DAYS until horizonDays.toLong()) {
            val date = today.plusDays(i)
            val shifts = byDate[date].orEmpty()
            if (istTagAusgeschlossen(shifts, isExcluded)) continue

            // Rueckwaerts: Nacht VOR dem ERSTEN Wecker des Tages, falls einer existiert.
            val alarm = shifts.firstOrNull()
            if (alarm != null) {
                val window = DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = startClockMinutes, endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0)
                // Leere Zeitleiste: der eingebaute Nacht-Standard baut seine Fenster selbst und
                // ausschliesslich mit CLOCK-/ALARM-Ankern - er braucht ALARM_SONST_CLOCK nicht.
                resolveWindowForDate(window, date, alarm, zone, emptyList())
                    ?.let { out += DimSpan(it, strength, warmth) }
            }

            // Vorwaerts: heutiger Abend, AUSSER der Folgetag hat selbst einen NICHT ausgeschlossenen
            // Wecker (nur dann deckt dessen eigenes Rueckwaerts-Fenster den heutigen Abend automatisch
            // mit ab). Ist der Folgetag selbst ausgeschlossen (isExcluded), ueberspringt SEINE eigene
            // Schleifen-Iteration weiter oben ihr Rueckwaerts-Fenster komplett - dann muss dieser Tag
            // die gemeinsame Nacht selbst abdecken, sonst faellt sie ganz durch (Regression, real
            // reproduziert: freier Tag vor einer ausgeschlossenen Nachtdienst-Schicht).
            val nextDayShifts = byDate[date.plusDays(1)].orEmpty()
            val nextDayCoversTonight = nextDayShifts.isNotEmpty() && !istTagAusgeschlossen(nextDayShifts, isExcluded)
            if (!nextDayCoversTonight) {
                val window = DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = startClockMinutes, endAnchor = DimAnchor.CLOCK, endClockMinutes = freeDayEndClockMinutes)
                resolveWindowForDate(window, date, null, zone, emptyList())
                    ?.let { out += DimSpan(it, strength, warmth) }
            }
        }
        return out
    }

    /**
     * Gilt ein Dimmer-Korrektur-Override (Feature C) noch? Er ist an [DimOverlayPrefs.Override.windowEnd]
     * + [DimOverlayPrefs.Override.windowStrength] gebunden (= `range.last`/`strength` der aktiven
     * Spanne beim Setzen) - rein DATENBASIERTE Reset-Erkennung, KEIN eigener Timer/Alarm noetig: der
     * ohnehin rollende Tick ([DimScheduleUseCase] `REQ_TICK`) ruft `applyCurrentState()` an jeder
     * Fenstergrenze ohnehin neu auf, wodurch ein Override fuer ein inzwischen beendetes/gewechseltes
     * Fenster automatisch stale wird.
     *
     * `windowEnd` ALLEIN reicht nicht: [DimScheduleUseCase.windows] liefert DREI unabhaengige,
     * ueberlappende Quellen (Wellness/Regeln/Nacht-Standard), die sehr haeufig denselben Anker teilen
     * (typischerweise ALARM-Offset 0 = die Weckzeit). Wechselt "darkest wins" ([activeSpan]) wegen
     * einer neu ueberlappenden, staerkeren Quelle die aktive Spanne, bleibt `range.last` dabei oft
     * IDENTISCH - nur die Staerke aendert sich. Deshalb zaehlt zusaetzlich [activeStrength] zur
     * Identitaet des Fensters.
     *
     * Kein aktives Fenster ([activeWindowEnd] `null`, z. B. Dimmer gerade aus) ODER ein anderer
     * `windowEnd`/`strength` als beim Setzen (naechstes oder ein anderes Fenster ist aktiv) = stale.
     */
    fun isOverrideStale(activeWindowEnd: Long?, activeStrength: Int?, overrideWindowEnd: Long, overrideStrength: Int): Boolean =
        activeWindowEnd == null || activeWindowEnd != overrideWindowEnd || activeStrength != overrideStrength

    /** Wendet das Heller/Dunkler-Delta der Korrektur-Notification an - wirkt NUR auf strength, geklemmt auf [0, max]. */
    fun applyStrengthDelta(base: Int, delta: Int, max: Int): Int = (base + delta).coerceIn(0, max)

    /** Ein zusammenhaengender, nicht ueberlappender Abschnitt der resultierenden Dimm-Vorschau. */
    data class ResolvedInterval(val range: LongRange, val strength: Int, val warmth: Int)

    /**
     * Fasst (moeglicherweise ueberlappende) Spannen aus allen Quellen (Wellness/Regeln/Nacht-
     * Standard) zu einer chronologischen, nicht ueberlappenden Zeitleiste zusammen - bei
     * Ueberlappung gewinnt an jeder Stelle dieselbe "dunkelste zuerst"-Regel wie [activeSpan], nur
     * ueber die Zeit hinweg statt fuer einen einzelnen Zeitpunkt. Reine Vorschau-Funktion, ohne
     * Seiteneffekt auf den echten Scheduler.
     */
    fun mergeToTimeline(spans: List<DimSpan>): List<ResolvedInterval> {
        if (spans.isEmpty()) return emptyList()
        val boundaries = spans.flatMap { listOf(it.range.first, it.range.last) }.distinct().sorted()
        val out = mutableListOf<ResolvedInterval>()
        for (i in 0 until boundaries.size - 1) {
            val segStart = boundaries[i]
            val segEnd = boundaries[i + 1]
            if (segStart >= segEnd) continue
            val active = activeSpan(spans, segStart + (segEnd - segStart) / 2) ?: continue
            val last = out.lastOrNull()
            if (last != null && last.strength == active.strength && last.warmth == active.warmth && last.range.last == segStart) {
                out[out.lastIndex] = ResolvedInterval(last.range.first..segEnd, active.strength, active.warmth)
            } else {
                out += ResolvedInterval(segStart..segEnd, active.strength, active.warmth)
            }
        }
        return out
    }

    /**
     * Alle Schichten je Kalendertag, chronologisch sortiert - die gemeinsame Grundlage von
     * [buildRuleSpans] und [buildDefaultNightSpans].
     *
     * Ersetzt die fruehere `HashMap<LocalDate, AlarmSlot>` mit "first wins", die pro Tag alles
     * ausser der ersten Schicht verwarf (Pruefrunde 8). Die Sortierung nach `triggerTime` und -
     * bei gleicher Weckzeit - nach `shiftName` ist ausdruecklich Teil der Zusicherung: die Wahl
     * "frueheste Schicht des Tages" soll eine bewusste Entscheidung sein und nicht stillschweigend
     * daran haengen, in welcher Reihenfolge der ShiftSpanStore seine Spannen liefert. Die
     * Fenster-Berechnung wird dadurch reihenfolge-unabhaengig und damit ueber aufeinanderfolgende
     * Ticks stabil - ein laufendes Fenster darf seine Identitaet nicht wechseln.
     */
    private fun slotsByDate(alarms: List<AlarmSlot>, zone: ZoneId): Map<LocalDate, List<AlarmSlot>> =
        alarms.groupBy { Instant.ofEpochMilli(it.triggerTime).atZone(zone).toLocalDate() }
            .mapValues { (_, slots) -> slots.sortedWith(compareBy({ it.triggerTime }, { it.shiftName })) }

    /**
     * Ist dieser Kalendertag vom Nacht-Standard ausgenommen? Ein Ausschluss IRGENDEINER Schicht
     * des Tages schliesst den ganzen Tag aus - der Ausschluss ist tages-granular (er nimmt beide
     * Fenster dieses Datums heraus), und "nicht dimmen" ist die harmlose Richtung. Ein Tag ohne
     * Schicht fragt weiterhin mit `null` (freier Tag), damit die Aufrufer-Semantik in
     * [DimScheduleUseCase] unveraendert bleibt.
     */
    private fun istTagAusgeschlossen(shifts: List<AlarmSlot>, isExcluded: (String?) -> Boolean): Boolean =
        if (shifts.isEmpty()) isExcluded(null) else shifts.any { isExcluded(it.shiftName) }

    /**
     * CLOCK-Start = jede KALENDERNACHT des Datums; sonst schicht-relativ (nur mit Alarm auflösbar).
     *
     * Mit CLOCK-Start gehen BEIDE Kalendernacht-Enden hier durch — das feste [DimAnchor.CLOCK] und
     * das neue [DimAnchor.ALARM_SONST_CLOCK]. Letzteres braucht bewusst KEINEN Slot dieses Tages:
     * seine Weckzeit sucht es in der Zeitleiste [weckzeiten], nicht im Tages-Slot. Genau das macht
     * es an einem weckerfreien Tag ebenso auflösbar wie an einem Schicht-Tag und ersetzt damit das
     * Rückwärts-/Vorwärts-Fensterpaar des eingebauten Nacht-Standards samt seiner
     * Folgetag-Bedingung.
     */
    private fun resolveWindowForDate(
        w: DimWindow,
        date: LocalDate,
        alarm: AlarmSlot?,
        zone: ZoneId,
        weckzeiten: List<Long>
    ): LongRange? =
        if (w.startAnchor == DimAnchor.CLOCK &&
            (w.endAnchor == DimAnchor.CLOCK || w.endAnchor == DimAnchor.ALARM_SONST_CLOCK)
        ) {
            resolveFreeWindow(w, date, zone, weckzeiten)
        } else if (alarm != null) {
            resolveShiftWindow(w, alarm.triggerTime, alarm.shiftEndTime, zone, weckzeiten)
        } else {
            null
        }

    /** Die Uhrzeit [clockMinutes] auf dem Kalendertag von [referenceEpoch], aber nicht nach der Referenz. */
    private fun clockAtOrBefore(referenceEpoch: Long, clockMinutes: Int, zone: ZoneId): Long {
        val date = dateOf(referenceEpoch, zone)
        val t = wallClock(date, clockMinutes, zone)
        // Einen KALENDERTAG zurueck (nicht −24h-Millis), sonst an DST-Tagen eine Stunde falsch.
        return if (t > referenceEpoch) wallClock(date.minusDays(1), clockMinutes, zone) else t
    }

    private fun dateOf(referenceEpoch: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(referenceEpoch).atZone(zone).toLocalDate()

    /**
     * Loest [clockMinutes] als echte WANDUHRZEIT auf [date] auf - NICHT als „Mitternacht-Instant +
     * Minuten-Millis". An den beiden DST-Umstellungstagen ist ein Kalendertag 23 h bzw. 25 h lang;
     * der reine Millis-Offset traefe dort die falsche Uhrzeit (aus 22:00 wuerde am
     * Vorspringen-Tag 23:00, am Zurueckspringen-Tag 21:00) und verschob damit Dimmen UND DND
     * (Modus 1 rechnet ueber dieselben Fenster) um eine Stunde. Genau dieselbe Falle war fuer den
     * DND-Rufbereitschaft-Cutoff schon dokumentiert und dort behoben
     * ([com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndOnCallCutoffResolver]).
     *
     * `LocalDateTime.plusMinutes` rechnet bewusst auf der LOKALEN Zeitachse - `ZonedDateTime.
     * plusMinutes` wuerde wieder auf der Instant-Achse rechnen und den Fehler zurueckholen.
     * `atZone` loest anschliessend eine uebersprungene/doppelte Stunde regelkonform auf.
     */
    private fun wallClock(date: LocalDate, clockMinutes: Int, zone: ZoneId): Long =
        date.atStartOfDay().plusMinutes(clockMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
}
