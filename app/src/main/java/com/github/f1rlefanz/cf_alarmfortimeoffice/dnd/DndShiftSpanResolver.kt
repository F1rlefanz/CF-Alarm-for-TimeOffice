package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

/**
 * Reine Zeitmathematik fuer „Waehrend des Kalendereintrags“ (frueher "Waehrend der Dienstzeit") - bewusst OHNE Android-
 * Abhaengigkeiten, damit sie unit-testbar ist (Vorbild: [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver]).
 *
 * Anders als beim Dimmer gibt es hier KEIN Anker-System (CLOCK/ALARM/SHIFT_END) - die Spanne ist
 * schlicht die rohe Kalender-Event-Spanne [shiftStartTime]..[shiftEndTime] der Schicht, ohne Offset.
 */
object DndShiftSpanResolver {

    /**
     * Minimal-Info einer Schicht fuer die Spannen-Berechnung (entkoppelt von Android).
     *
     * Name historisch: gefuellt aus `ShiftSpan`, nicht aus `AlarmInfo`. Wer hier wieder den
     * Alarm-Bestand anzapft, baut den Fehler zurueck (Dienstzeit-Fenster weg nach dem Wecken).
     */
    data class AlarmSlot(val shiftName: String, val shiftStartTime: Long, val shiftEndTime: Long)

    /**
     * Baut die Dienstzeit-Spannen aus [alarms] (#218: „Waehrend des Kalendereintrags“ am Typ). Ein
     * Eintrag liefert nur dann eine Spanne, wenn [schalterAn] fuer seinen Schichtnamen `true` sagt
     * (der Typ hat den Schalter an), BEIDE Kalender-Zeiten bekannt sind ([AlarmSlot.shiftStartTime]
     * > 0 UND [AlarmSlot.shiftEndTime] > 0 - 0 bedeutet "unbekannt") UND die Endzeit tatsaechlich
     * nach der Startzeit liegt.
     *
     * Der Schalter ist NICHT dasselbe wie [DndOnCallCutoffResolver]s Cutoff: hier entfaellt die Spanne
     * des Typs ganz, dort wird eine bestehende Spanne nur auf eine Uhrzeit gekappt. Beides kann fuer
     * denselben Typ gleichzeitig gelten.
     */
    fun buildShiftSpans(alarms: List<AlarmSlot>, schalterAn: (String) -> Boolean): List<LongRange> =
        alarms.mapNotNull { alarm ->
            if (!schalterAn(alarm.shiftName)) return@mapNotNull null
            if (alarm.shiftStartTime <= 0L || alarm.shiftEndTime <= 0L) return@mapNotNull null
            if (alarm.shiftEndTime <= alarm.shiftStartTime) return@mapNotNull null
            alarm.shiftStartTime..alarm.shiftEndTime
        }

    /**
     * Die fruehere Form mit globaler Ausnahmenliste (`dnd_shift_excluded_shifts`, Gross/Klein zaehlt).
     * Der Scheduler liest seit #218 den Schalter am Typ; diese Form rechnet nur noch die Tagesleiste,
     * bis sie ebenfalls am Typ liest (#219).
     */
    fun buildShiftSpans(alarms: List<AlarmSlot>, excludedShifts: Set<String>): List<LongRange> =
        buildShiftSpans(alarms) { it !in excludedShifts }
}
