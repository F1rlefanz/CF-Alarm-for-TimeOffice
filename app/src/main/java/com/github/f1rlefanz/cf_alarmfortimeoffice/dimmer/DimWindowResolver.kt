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
