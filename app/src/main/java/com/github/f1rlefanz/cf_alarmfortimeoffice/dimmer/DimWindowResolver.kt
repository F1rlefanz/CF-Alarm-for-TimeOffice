package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

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
 */
object DimWindowResolver {
    private const val MIN_MS = 60_000L

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
    fun resolveShiftWindow(w: DimWindow, alarmEpoch: Long, shiftEndEpoch: Long, zone: ZoneId): LongRange? {
        val start = when (w.startAnchor) {
            DimAnchor.ALARM -> alarmEpoch + w.startOffsetMinutes * MIN_MS
            DimAnchor.CLOCK -> clockAtOrBefore(alarmEpoch, w.startClockMinutes, zone)
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

    /** Fenster eines freien Tags – nur CLOCK-Anker sinnvoll (kein Wecker/keine Schicht). */
    fun resolveFreeWindow(w: DimWindow, date: LocalDate, zone: ZoneId): LongRange? {
        if (w.startAnchor != DimAnchor.CLOCK || w.endAnchor != DimAnchor.CLOCK) return null
        val start = wallClock(date, w.startClockMinutes, zone)
        // Über Mitternacht = die Uhrzeit auf dem FOLGE-KALENDERTAG (nicht Start + 24h-Millis) –
        // sonst eine Stunde falsch an DST-Umstellungstagen.
        val end = wallClock(date, w.endClockMinutes, zone).let {
            if (it <= start) wallClock(date.plusDays(1), w.endClockMinutes, zone) else it
        }
        return start..end
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
     */
    fun buildRuleSpans(
        alarms: List<AlarmSlot>,
        horizonDays: Int,
        today: LocalDate,
        zone: ZoneId,
        ruleForShift: (String) -> DimRule?,
        ruleForFreeDay: () -> DimRule?,
    ): List<DimSpan> {
        val alarmByDate = HashMap<LocalDate, AlarmSlot>()
        for (a in alarms) {
            val d = Instant.ofEpochMilli(a.triggerTime).atZone(zone).toLocalDate()
            if (!alarmByDate.containsKey(d)) alarmByDate[d] = a
        }
        val out = mutableListOf<DimSpan>()
        // Beginnt bewusst EINEN Tag vor [today] (siehe [LOOKBACK_DAYS]): ein CLOCK<->CLOCK-Fenster
        // vom Vorabend gehoert nach dem Datumswechsel weiterhin zur laufenden Nacht.
        for (i in -LOOKBACK_DAYS until horizonDays.toLong()) {
            val date = today.plusDays(i)
            val alarm = alarmByDate[date]
            val rule = if (alarm != null) ruleForShift(alarm.shiftName) else ruleForFreeDay()
            rule ?: continue
            for (w in rule.windows) {
                resolveWindowForDate(w, date, alarm, zone)?.let { out += DimSpan(it, rule.strength, rule.warmth) }
            }
        }
        return out
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
        val alarmByDate = HashMap<LocalDate, AlarmSlot>()
        for (a in alarms) {
            val d = Instant.ofEpochMilli(a.triggerTime).atZone(zone).toLocalDate()
            if (!alarmByDate.containsKey(d)) alarmByDate[d] = a
        }
        val out = mutableListOf<DimSpan>()
        // Beginnt bewusst EINEN Tag vor [today] (siehe [LOOKBACK_DAYS]): das Vorwaerts-Fenster des
        // Vorabends gehoert nach dem Datumswechsel weiterhin zur laufenden Nacht.
        for (i in -LOOKBACK_DAYS until horizonDays.toLong()) {
            val date = today.plusDays(i)
            val alarm = alarmByDate[date]
            if (isExcluded(alarm?.shiftName)) continue

            // Rueckwaerts: Nacht VOR dem heutigen Wecker, falls einer existiert.
            if (alarm != null) {
                val window = DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = startClockMinutes, endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0)
                resolveWindowForDate(window, date, alarm, zone)?.let { out += DimSpan(it, strength, warmth) }
            }

            // Vorwaerts: heutiger Abend, AUSSER der Folgetag hat selbst einen NICHT ausgeschlossenen
            // Wecker (nur dann deckt dessen eigenes Rueckwaerts-Fenster den heutigen Abend automatisch
            // mit ab). Ist der Folgetag selbst ausgeschlossen (isExcluded), ueberspringt SEINE eigene
            // Schleifen-Iteration weiter oben ihr Rueckwaerts-Fenster komplett - dann muss dieser Tag
            // die gemeinsame Nacht selbst abdecken, sonst faellt sie ganz durch (Regression, real
            // reproduziert: freier Tag vor einer ausgeschlossenen Nachtdienst-Schicht).
            val nextDayAlarm = alarmByDate[date.plusDays(1)]
            val nextDayCoversTonight = nextDayAlarm != null && !isExcluded(nextDayAlarm.shiftName)
            if (!nextDayCoversTonight) {
                val window = DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = startClockMinutes, endAnchor = DimAnchor.CLOCK, endClockMinutes = freeDayEndClockMinutes)
                resolveWindowForDate(window, date, null, zone)?.let { out += DimSpan(it, strength, warmth) }
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

    /** CLOCK↔CLOCK = jede Nacht des Datums; sonst schicht-relativ (nur mit Alarm auflösbar). */
    private fun resolveWindowForDate(w: DimWindow, date: LocalDate, alarm: AlarmSlot?, zone: ZoneId): LongRange? =
        if (w.startAnchor == DimAnchor.CLOCK && w.endAnchor == DimAnchor.CLOCK) {
            resolveFreeWindow(w, date, zone)
        } else if (alarm != null) {
            resolveShiftWindow(w, alarm.triggerTime, alarm.shiftEndTime, zone)
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
