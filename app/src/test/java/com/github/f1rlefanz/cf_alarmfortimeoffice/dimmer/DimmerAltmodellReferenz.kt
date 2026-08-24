package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * EINGEFRORENE REFERENZ DES ALTMODELLS - NICHT MITPFLEGEN, NICHT VERWENDEN.
 *
 * Wortgetreue Kopie des ausgebauten eingebauten Nacht-Standards
 * (`DimWindowResolver.buildDefaultNightSpans` samt seiner privaten Hilfslogik `slotsByDate` und
 * `istTagAusgeschlossen`), Stand v1.33.0 - unmittelbar bevor die drei Fenster-Quellen
 * (Wellness / Regeln / Nacht-Standard) zu EINER Quelle (Regeln) zusammengefuehrt wurden.
 *
 * WOFUER SIE DA IST: Phase 2 dieses Umbaus migriert einen eingeschalteten Nacht-Standard in eine
 * gewoehnliche Regel (Fenster `CLOCK start` -> `ALARM_SONST_CLOCK ende`). Dass diese Migration
 * VERLUSTFREI ist, laesst sich nur beweisen, indem die Zeitleiste des ALTEN Modells gegen die des
 * neuen gestellt wird. Ohne diese Kopie ist der Beweis nach dem Ausbau nicht mehr fuehrbar - der
 * Code, gegen den zu vergleichen waere, existiert dann nirgends mehr.
 *
 * WAS SIE NICHT IST: kein Teil des Produktionsmodells, kein Vorbild fuer neuen Code, keine
 * lebende Zusicherung. Sie wird bei Aenderungen an `DimWindowResolver` ausdruecklich NICHT
 * nachgezogen - ihr Wert liegt genau darin, den Stand von damals zu zeigen. Ist die Migration in
 * Phase 2 bewiesen und ausgeliefert, darf diese Datei ersatzlos geloescht werden.
 *
 * Der Hergang des Altmodells (Fensterpaar rueckwaerts/vorwaerts, `nextDayCoversTonight`, der real
 * reproduzierte Regressionsfall 03.-05.08.2026, die tages-granularen Ausschluesse) steht im Skill
 * `cfalarm-dimmer-und-dnd`, `reference/dimmer.md`.
 */
internal object DimmerAltmodellReferenz {

    /** Wie im Original: die Fenster-Schleife beginnt einen Kalendertag VOR `today`. */
    private const val LOOKBACK_DAYS = 1L

    /**
     * Eingefroren: `DimWindowResolver.buildDefaultNightSpans` (v1.33.0).
     *
     * Kommentare bewusst gekuerzt - die Begruendungen stehen in `reference/dimmer.md`. Die LOGIK
     * ist unveraendert; wer sie hier "verbessert", macht die Referenz wertlos.
     */
    fun buildDefaultNightSpans(
        alarms: List<DimWindowResolver.AlarmSlot>,
        horizonDays: Int,
        today: LocalDate,
        zone: ZoneId,
        startClockMinutes: Int,
        freeDayEndClockMinutes: Int,
        strength: Int,
        warmth: Int,
        isExcluded: (shiftName: String?) -> Boolean,
    ): List<DimWindowResolver.DimSpan> {
        val byDate = slotsByDate(alarms, zone)
        val out = mutableListOf<DimWindowResolver.DimSpan>()
        for (i in -LOOKBACK_DAYS until horizonDays.toLong()) {
            val date = today.plusDays(i)
            val shifts = byDate[date].orEmpty()
            if (istTagAusgeschlossen(shifts, isExcluded)) continue

            // Rueckwaerts: Nacht VOR dem ERSTEN Wecker des Tages, falls einer existiert.
            val alarm = shifts.firstOrNull()
            if (alarm != null) {
                val window = DimWindow(
                    startAnchor = DimAnchor.CLOCK,
                    startClockMinutes = startClockMinutes,
                    endAnchor = DimAnchor.ALARM,
                    endOffsetMinutes = 0
                )
                resolveWindowForDate(window, date, alarm, zone)
                    ?.let { out += DimWindowResolver.DimSpan(it, strength, warmth) }
            }

            // Vorwaerts: heutiger Abend, AUSSER der Folgetag hat selbst einen NICHT
            // ausgeschlossenen Wecker.
            val nextDayShifts = byDate[date.plusDays(1)].orEmpty()
            val nextDayCoversTonight =
                nextDayShifts.isNotEmpty() && !istTagAusgeschlossen(nextDayShifts, isExcluded)
            if (!nextDayCoversTonight) {
                val window = DimWindow(
                    startAnchor = DimAnchor.CLOCK,
                    startClockMinutes = startClockMinutes,
                    endAnchor = DimAnchor.CLOCK,
                    endClockMinutes = freeDayEndClockMinutes
                )
                resolveWindowForDate(window, date, null, zone)
                    ?.let { out += DimWindowResolver.DimSpan(it, strength, warmth) }
            }
        }
        return out
    }

    /** Eingefroren: `DimWindowResolver.slotsByDate` (v1.33.0). */
    private fun slotsByDate(
        alarms: List<DimWindowResolver.AlarmSlot>,
        zone: ZoneId
    ): Map<LocalDate, List<DimWindowResolver.AlarmSlot>> =
        alarms.groupBy { Instant.ofEpochMilli(it.triggerTime).atZone(zone).toLocalDate() }
            .mapValues { (_, slots) -> slots.sortedWith(compareBy({ it.triggerTime }, { it.shiftName })) }

    /** Eingefroren: `DimWindowResolver.istTagAusgeschlossen` (v1.33.0). */
    private fun istTagAusgeschlossen(
        shifts: List<DimWindowResolver.AlarmSlot>,
        isExcluded: (String?) -> Boolean
    ): Boolean =
        if (shifts.isEmpty()) isExcluded(null) else shifts.any { isExcluded(it.shiftName) }

    /**
     * Eingefroren: der Zweig von `DimWindowResolver.resolveWindowForDate`, den der Nacht-Standard
     * benutzte. Er reichte stets eine LEERE Weckzeit-Zeitleiste weiter - der Nacht-Standard baute
     * seine Fenster ausschliesslich mit CLOCK-/ALARM-Ankern und kannte ALARM_SONST_CLOCK nicht.
     */
    private fun resolveWindowForDate(
        w: DimWindow,
        date: LocalDate,
        alarm: DimWindowResolver.AlarmSlot?,
        zone: ZoneId
    ): LongRange? =
        if (w.startAnchor == DimAnchor.CLOCK && w.endAnchor == DimAnchor.CLOCK) {
            DimWindowResolver.resolveFreeWindow(w, date, zone, emptyList())
        } else if (alarm != null) {
            DimWindowResolver.resolveShiftWindow(w, alarm.triggerTime, alarm.shiftEndTime, zone, emptyList())
        } else {
            null
        }
}
