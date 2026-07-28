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
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_MS = 60_000L

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
                var e = clockOnDateOf(alarmEpoch, w.endClockMinutes, zone)
                while (e <= start) e += DAY_MS
                e
            }
        }
        return if (end > start) start..end else null
    }

    /** Fenster eines freien Tags – nur CLOCK-Anker sinnvoll (kein Wecker/keine Schicht). */
    fun resolveFreeWindow(w: DimWindow, date: LocalDate, zone: ZoneId): LongRange? {
        if (w.startAnchor != DimAnchor.CLOCK || w.endAnchor != DimAnchor.CLOCK) return null
        val base = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = base + w.startClockMinutes * MIN_MS
        var end = base + w.endClockMinutes * MIN_MS
        if (end <= start) end += DAY_MS // über Mitternacht
        return start..end
    }

    /**
     * Die gerade aktive Spanne für [now]. Überlappen mehrere, gewinnt die DUNKELSTE
     * (max strength, bei Gleichstand max warmth) – so schlägt eine „hart dimmen"-Regel eine mildere.
     */
    fun activeSpan(spans: List<DimSpan>, now: Long): DimSpan? =
        spans.filter { now in it.range }.maxWithOrNull(compareBy({ it.strength }, { it.warmth }))

    /** Minimal-Info eines Alarms für die Fenster-Berechnung (entkoppelt von AlarmInfo/Android). */
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
        for (i in 0 until horizonDays) {
            val date = today.plusDays(i.toLong())
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
     * Wecker (Tage mit Alarm, ueber [DimAnchor.ALARM]) bzw. bis [freeDayEndClockMinutes] (Tage
     * ohne Alarm, ueber [DimAnchor.CLOCK]) - jeweils nur an Kalendertagen, fuer die [ruleForShift]/
     * [ruleForFreeDay] KEINE Regel liefern. Eine vorhandene Regel (spezifisch, UNIVERSAL oder FREI)
     * ersetzt diesen Standard fuer ihren Tag komplett, exakt dieselbe Ausschliesslichkeit wie in
     * [buildRuleSpans] - so bleibt z.B. die Nachtdienst-Ausnahme (leere Fensterliste) wirksam, auch
     * wenn der Nacht-Standard aktiv ist.
     *
     * Ein Tag OHNE eigenen Alarm erzeugt bewusst KEINEN Fallback-Abschnitt, wenn der FOLGETAG einen
     * Alarm hat - dessen eigene Iteration deckt die Nacht bereits an (CLOCK reicht ueber "vor der
     * Weckzeit" zurueck). Ohne diese Ausnahme wuerden zwei Spannen mit unterschiedlichem Ende
     * ueberlappen (heutiger fixer Fallback bis [freeDayEndClockMinutes] UND morgiges Fenster bis
     * zum echten Wecker) - der fixe Fallback wuerde die Nacht ueber den echten Wecker hinaus verlaengern
     * und genau den Zweck dieses Standards (dynamisch bis zum tatsaechlichen Wecker) aushebeln.
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
        ruleForShift: (String) -> DimRule?,
        ruleForFreeDay: () -> DimRule?,
    ): List<DimSpan> {
        val alarmByDate = HashMap<LocalDate, AlarmSlot>()
        for (a in alarms) {
            val d = Instant.ofEpochMilli(a.triggerTime).atZone(zone).toLocalDate()
            if (!alarmByDate.containsKey(d)) alarmByDate[d] = a
        }
        val out = mutableListOf<DimSpan>()
        for (i in 0 until horizonDays) {
            val date = today.plusDays(i.toLong())
            val alarm = alarmByDate[date]
            val overridden = if (alarm != null) ruleForShift(alarm.shiftName) != null else ruleForFreeDay() != null
            if (overridden) continue
            if (alarm == null && alarmByDate.containsKey(date.plusDays(1))) {
                // Morgen hat einen Wecker: dessen eigene Iteration deckt diese Nacht bereits ab
                // (CLOCK reicht ueber "vor der Weckzeit" zurueck bis in den heutigen Abend). Hier
                // KEINEN zusaetzlichen festen Fallback erzeugen, sonst ueberlappen sich zwei
                // Spannen mit unterschiedlichem Ende, und die fixe (spaetere) gewinnt die Nacht
                // ueber den echten Wecker hinaus - genau der Fehler, den dieser Standard vermeiden soll.
                continue
            }
            val window = if (alarm != null) {
                DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = startClockMinutes, endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0)
            } else {
                DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = startClockMinutes, endAnchor = DimAnchor.CLOCK, endClockMinutes = freeDayEndClockMinutes)
            }
            resolveWindowForDate(window, date, alarm, zone)?.let { out += DimSpan(it, strength, warmth) }
        }
        return out
    }

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
        var t = clockOnDateOf(referenceEpoch, clockMinutes, zone)
        if (t > referenceEpoch) t -= DAY_MS
        return t
    }

    private fun clockOnDateOf(referenceEpoch: Long, clockMinutes: Int, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(referenceEpoch).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() + clockMinutes * MIN_MS
    }
}
