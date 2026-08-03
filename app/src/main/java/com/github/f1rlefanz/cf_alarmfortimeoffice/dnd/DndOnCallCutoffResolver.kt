package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import java.time.Instant
import java.time.ZoneId

/**
 * Reine Zeitmathematik fuer die Rufbereitschaft-DND-Schiene ("On-Call-Cutoff") - bewusst OHNE
 * Android-Abhaengigkeiten, damit sie unit-testbar ist (Vorbild:
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver], [DndShiftSpanResolver]).
 *
 * Keine eigene Fenster-Logik/Policy fuer On-Call-Naechte: dieser Resolver KAPPT lediglich die
 * bestehenden Fenster aus den zwei anderen Quellen ("Folgt dem Dimmer" / "Waehrend der Dienstzeit")
 * auf einen festen Cutoff-Zeitpunkt am Tag der On-Call-Schicht - dieselbe [android.app.AutomaticZenRule]
 * gilt bis dahin unveraendert weiter, es gibt keine separate Policy.
 *
 * Die [ZoneId] wird stets hereingereicht (statt intern `systemDefault()` zu ziehen), damit Tests
 * ohne Geraete-Zeitzonen-Abhaengigkeit reproduzierbar sind - gleiche Konvention wie bei
 * `DimWindowResolver`.
 */
object DndOnCallCutoffResolver {

    /** Minimal-Info eines Alarms fuer die Cutoff-Berechnung (entkoppelt von AlarmInfo/Android). */
    data class AlarmSlot(val shiftName: String, val shiftStartTime: Long)

    private const val MIN_MS = 60_000L

    /**
     * Ein Cutoff-Zeitpunkt (Epoch-Millis) pro erkanntem On-Call-Tag: fuer jeden Alarm, dessen
     * [AlarmSlot.shiftName] in [onCallShifts] steht UND dessen [AlarmSlot.shiftStartTime] bekannt
     * ist (> 0 - 0 bedeutet "unbekannt", z. B. manuell angelegte Alarme ohne Schicht), liegt der
     * Cutoff auf demselben Kalendertag wie [AlarmSlot.shiftStartTime], zur Uhrzeit [cutoffMinutes]
     * (Minuten seit Mitternacht).
     */
    fun cutoffInstants(
        alarms: List<AlarmSlot>,
        onCallShifts: Set<String>,
        cutoffMinutes: Int,
        zone: ZoneId
    ): List<Long> = alarms
        .asSequence()
        .filter { it.shiftName in onCallShifts && it.shiftStartTime > 0L }
        .map { alarm ->
            val date = Instant.ofEpochMilli(alarm.shiftStartTime).atZone(zone).toLocalDate()
            date.atStartOfDay(zone).toInstant().toEpochMilli() + cutoffMinutes * MIN_MS
        }
        .distinct()
        .toList()

    /**
     * Kappt jedes Fenster aus [windows], dessen Ende hinter einem der [cutoffInstants] liegt, auf
     * dessen Ende - unabhaengig pro Fenster, jedes wird hoechstens von dem Cutoff geklippt, der
     * tatsaechlich in seiner Spanne liegt (mehrere On-Call-Tage/Fenster beeinflussen sich nicht
     * gegenseitig). Ein Cutoff, der ausserhalb des Fensters liegt (vor dessen Start oder nach
     * dessen Ende), veraendert das Fenster nicht - insbesondere wird ein Fenster nie auf eine
     * leere/negative Spanne geklippt.
     */
    fun clip(windows: List<LongRange>, cutoffInstants: List<Long>): List<LongRange> {
        if (cutoffInstants.isEmpty()) return windows
        return windows.map { window ->
            val cutoff = cutoffInstants
                .filter { it > window.first && it < window.last }
                .minOrNull()
                ?: return@map window
            window.first..cutoff
        }
    }
}
