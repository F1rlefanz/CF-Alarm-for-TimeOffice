package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

/**
 * Reine Zeitmathematik fuer den "Waehrend der Dienstzeit"-Trigger - bewusst OHNE Android-
 * Abhaengigkeiten, damit sie unit-testbar ist (Vorbild: [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver]).
 *
 * Anders als beim Dimmer gibt es hier KEIN Anker-System (CLOCK/ALARM/SHIFT_END) - die Spanne ist
 * schlicht die rohe Kalender-Event-Spanne [shiftStartTime]..[shiftEndTime] der Schicht, ohne Offset.
 */
object DndShiftSpanResolver {

    /** Minimal-Info eines Alarms fuer die Spannen-Berechnung (entkoppelt von AlarmInfo/Android). */
    data class AlarmSlot(val shiftName: String, val shiftStartTime: Long, val shiftEndTime: Long)

    /**
     * Baut die Dienstzeit-Spannen aus [alarms]. Ein Alarm liefert nur dann eine Spanne, wenn BEIDE
     * Kalender-Zeiten bekannt sind ([AlarmSlot.shiftStartTime] > 0 UND [AlarmSlot.shiftEndTime] > 0 -
     * 0 bedeutet "unbekannt", z. B. bei manuell angelegten Alarmen ohne Schicht) UND die Endzeit
     * tatsaechlich nach der Startzeit liegt UND die Schicht nicht in [excludedShifts] steht (z. B.
     * Rufbereitschaft, wo Erreichbarkeit gerade der Zweck des Diensts ist - dieser reine Ausschluss
     * ist NICHT dasselbe wie [DndOnCallCutoffResolver]s On-Call-Cutoff: hier wird die Schicht komplett
     * von der "Waehrend der Dienstzeit"-Spanne ausgenommen, dort wird eine bestehende Spanne aus einer
     * ANDEREN Quelle nur auf eine feste Uhrzeit gekappt. Beide Konzepte koennen unabhaengig voneinander
     * fuer dieselbe Schicht aktiv sein).
     */
    fun buildShiftSpans(alarms: List<AlarmSlot>, excludedShifts: Set<String>): List<LongRange> =
        alarms.mapNotNull { alarm ->
            if (alarm.shiftName in excludedShifts) return@mapNotNull null
            if (alarm.shiftStartTime <= 0L || alarm.shiftEndTime <= 0L) return@mapNotNull null
            if (alarm.shiftEndTime <= alarm.shiftStartTime) return@mapNotNull null
            alarm.shiftStartTime..alarm.shiftEndTime
        }
}
